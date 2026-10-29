package com.coara.browser.plugin;

import android.util.Base64;
import android.webkit.CookieManager;
import com.coara.browser.util.CookieSitePolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.util.Map;

final class PluginHttp {
    static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int MAX_REQUEST_BODY = 2 * 1024 * 1024;
    private static final int MAX_RESPONSE_HEADERS = 64 * 1024;
    private static final int MAX_REDIRECTS = 8;
    private static final int DEFAULT_TIMEOUT = 30000;
    private static final int MAX_TIMEOUT = 120000;

    static final class Call {
        volatile boolean aborted;
        volatile HttpURLConnection conn;

        void abort() {
            aborted = true;
            HttpURLConnection c = conn;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final class ResponseBuffer extends ByteArrayOutputStream {
        ResponseBuffer(int size) {
            super(size);
        }

        byte[] buffer() {
            return buf;
        }
    }

    private PluginHttp() {
    }

    private static long parseLooseIpv4(String h) {
        if (h.isEmpty()) {
            return -1L;
        }
        String[] parts = h.split("\\.", -1);
        if (parts.length > 4) {
            return -1L;
        }
        long[] v = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i];
            if (t.isEmpty()) {
                return -1L;
            }
            try {
                if (t.startsWith("0x") || t.startsWith("0X")) {
                    if (t.length() == 2) {
                        return -1L;
                    }
                    v[i] = Long.parseLong(t.substring(2), 16);
                } else if (t.length() > 1 && t.charAt(0) == '0') {
                    v[i] = Long.parseLong(t.substring(1), 8);
                } else {
                    v[i] = Long.parseLong(t, 10);
                }
            } catch (NumberFormatException e) {
                return -1L;
            }
            if (v[i] < 0) {
                return -1L;
            }
        }
        long result;
        switch (parts.length) {
            case 1:
                if (v[0] > 0xFFFFFFFFL) {
                    return -1L;
                }
                result = v[0];
                break;
            case 2:
                if (v[0] > 0xFF || v[1] > 0xFFFFFFL) {
                    return -1L;
                }
                result = (v[0] << 24) | v[1];
                break;
            case 3:
                if (v[0] > 0xFF || v[1] > 0xFF || v[2] > 0xFFFFL) {
                    return -1L;
                }
                result = (v[0] << 24) | (v[1] << 16) | v[2];
                break;
            default:
                if (v[0] > 0xFF || v[1] > 0xFF || v[2] > 0xFF || v[3] > 0xFF) {
                    return -1L;
                }
                result = (v[0] << 24) | (v[1] << 16) | (v[2] << 8) | v[3];
                break;
        }
        return result;
    }

    private static boolean isPrivateIpv4(long ip) {
        int a = (int) ((ip >> 24) & 0xFF);
        int b = (int) ((ip >> 16) & 0xFF);
        int c = (int) ((ip >> 8) & 0xFF);
        return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                || (a == 100 && b >= 64 && b <= 127) || a >= 224
                || (a == 192 && b == 0 && c == 0) || (a == 192 && b == 0 && c == 2)
                || (a == 192 && b == 88 && c == 99) || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113);
    }

    private static boolean isPrivateHost(String host) {
        if (host == null) {
            return true;
        }
        String h = host.toLowerCase(Locale.US);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty() || h.equals("localhost") || h.endsWith(".localhost")
                || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan")
                || h.endsWith(".home.arpa") || (h.indexOf('.') < 0 && h.indexOf(':') < 0 && parseLooseIpv4(h) < 0)) {
            return true;
        }
        long ip = parseLooseIpv4(h);
        if (ip >= 0) {
            return isPrivateIpv4(ip);
        }
        return false;
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.startsWith("[") || parseLooseIpv4(host) >= 0;
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            long ip = ((raw[0] & 0xFFL) << 24) | ((raw[1] & 0xFFL) << 16)
                    | ((raw[2] & 0xFFL) << 8) | (raw[3] & 0xFFL);
            return isPrivateIpv4(ip);
        }
        if (raw.length != 16) {
            return true;
        }
        boolean mappedV4 = true;
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                mappedV4 = false;
                break;
            }
        }
        mappedV4 = mappedV4 && raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
        if (mappedV4) {
            long ip = ((raw[12] & 0xFFL) << 24) | ((raw[13] & 0xFFL) << 16)
                    | ((raw[14] & 0xFFL) << 8) | (raw[15] & 0xFFL);
            return isPrivateIpv4(ip);
        }
        if ((raw[0] & 0xFE) == 0xFC || (raw[0] & 0xE0) != 0x20) {
            return true;
        }
        return (raw[0] & 0xFF) == 0x20 && (raw[1] & 0xFF) == 0x01
                && (raw[2] & 0xFF) == 0x0D && (raw[3] & 0xFF) == 0xB8;
    }

    private static InetAddress resolveAllowedAddress(List<String> connects, String url, String originUrl) {
        final URL parsed;
        final String host;
        final String scheme;
        try {
            parsed = new URL(url);
            host = parsed.getHost();
            scheme = parsed.getProtocol();
            if (parsed.getUserInfo() != null || host == null || host.isEmpty()
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        String normalizedHost = host.toLowerCase(Locale.US);
        while (normalizedHost.endsWith(".")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }
        String lookupHost = canonicalHost(normalizedHost);
        if (lookupHost.indexOf('%') >= 0 || isPrivateHost(lookupHost)) {
            return null;
        }
        boolean secure = "https".equalsIgnoreCase(scheme);
        boolean permitted = sameOrigin(originUrl == null ? "" : originUrl, url);
        String policyHost = lookupHost;
        if (!permitted && connects != null) {
            for (String item : connects) {
                if (item == null) {
                    continue;
                }
                String entry = canonicalHost(item.trim().toLowerCase(Locale.US));
                if (entry.isEmpty()) {
                    continue;
                }
                if (entry.equals("*") && secure) {
                    permitted = true;
                    break;
                }
                if (policyHost.equals(entry)) {
                    permitted = true;
                    break;
                }
                if (!isIpLiteral(policyHost) && policyHost.endsWith("." + entry)) {
                    permitted = true;
                    break;
                }
            }
        }
        if (!permitted) {
            return null;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(lookupHost);
            if (addresses == null || addresses.length == 0) {
                return null;
            }
            InetAddress selected = null;
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    return null;
                }
                if (selected == null) {
                    selected = address;
                }
            }
            return selected;
        } catch (Exception e) {
            return null;
        }
    }


    private static String pinnedUrl(URL original, InetAddress address) throws Exception {
        String host = address.getHostAddress();
        if (address instanceof Inet6Address) {
            host = "[" + host + "]";
        }
        return new URL(original.getProtocol(), host, original.getPort(), original.getFile()).toString();
    }

    private static String canonicalHost(String host) {
        if (host == null) {
            return "";
        }
        String value = host;
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String hostHeader(URL original) {
        String host = original.getHost();
        int port = original.getPort();
        if (port >= 0 && port != original.getDefaultPort()) {
            return host + ":" + port;
        }
        return host;
    }

    private static final class PinnedSSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String host;
        private final InetAddress address;
        private final int port;
        private final int connectTimeout;
        private final int readTimeout;

        PinnedSSLSocketFactory(String host, InetAddress address, int port, int connectTimeout, int readTimeout) {
            this.delegate = (SSLSocketFactory) HttpsURLConnection.getDefaultSSLSocketFactory();
            this.host = host;
            this.address = address;
            this.port = port;
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
        }

        private SSLSocket connectPinned(InetAddress localAddress, int localPort) throws java.io.IOException {
            Socket raw = new Socket();
            try {
                if (localAddress != null) {
                    raw.bind(new InetSocketAddress(localAddress, Math.max(0, localPort)));
                }
                raw.connect(new InetSocketAddress(address, port), connectTimeout);
                raw.setSoTimeout(readTimeout);
                return wrap(raw, true);
            } catch (java.io.IOException e) {
                try {
                    raw.close();
                } catch (Exception ignored) {
                }
                throw e;
            }
        }

        private SSLSocket wrap(Socket socket, boolean autoClose) throws java.io.IOException {
            if (socket == null || socket.getInetAddress() == null || !address.equals(socket.getInetAddress())
                    || socket.getPort() != port) {
                if (autoClose && socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
                throw new java.io.IOException("pinned destination mismatch");
            }
            SSLSocket ssl = (SSLSocket) delegate.createSocket(socket, host, port, autoClose);
            SSLParameters parameters = ssl.getSSLParameters();
            try {
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
            } catch (Throwable ignored) {
            }
            if (!isIpLiteral(host)) {
                try {
                    parameters.setServerNames(Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
                } catch (Throwable ignored) {
                }
            }
            ssl.setSSLParameters(parameters);
            ssl.setSoTimeout(readTimeout);
            return ssl;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws java.io.IOException {
            throw new java.io.IOException("pinned destination required");
        }

        @Override
        public Socket createSocket(String ignored, int requestedPort) throws java.io.IOException {
            if (requestedPort != port) {
                throw new java.io.IOException("pinned port mismatch");
            }
            return connectPinned(null, 0);
        }

        @Override
        public Socket createSocket(String ignored, int requestedPort, InetAddress local, int localPort) throws java.io.IOException {
            if (requestedPort != port) {
                throw new java.io.IOException("pinned port mismatch");
            }
            return connectPinned(local, localPort);
        }

        @Override
        public Socket createSocket(InetAddress ignored, int requestedPort) throws java.io.IOException {
            if (requestedPort != port) {
                throw new java.io.IOException("pinned port mismatch");
            }
            return connectPinned(null, 0);
        }

        @Override
        public Socket createSocket(InetAddress ignored, int requestedPort, InetAddress local, int localPort) throws java.io.IOException {
            if (requestedPort != port) {
                throw new java.io.IOException("pinned port mismatch");
            }
            return connectPinned(local, localPort);
        }

        @Override
        public Socket createSocket(Socket socket, String ignored, int requestedPort, boolean autoClose) throws java.io.IOException {
            if (requestedPort != port) {
                throw new java.io.IOException("pinned port mismatch");
            }
            return wrap(socket, autoClose);
        }
    }

    static JSONObject errorPayload(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("status", 0);
            o.put("error", message == null ? "error" : message);
        } catch (JSONException ignored) {
        }
        return o;
    }

    static JSONObject execute(JSONObject opts, List<String> connects, Call call, String trustedOrigin) {
        try {
            String url = opts.optString("url", "");
            if (url.length() > 8192) {
                return errorPayload("url too long");
            }
            String origin = trustedOrigin == null || trustedOrigin.length() > 8192 ? "" : trustedOrigin;
            String method = opts.optString("method", "GET").toUpperCase(Locale.US);
            if (!method.matches("[A-Z]{3,10}")) {
                return errorPayload("invalid method");
            }
            String lower = url.toLowerCase(Locale.US);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return errorPayload("unsupported scheme");
            }
            boolean anonymous = opts.optBoolean("anonymous", false);
            int timeout = Math.max(1000, Math.min(MAX_TIMEOUT, opts.optInt("timeout", DEFAULT_TIMEOUT)));
            if (opts.optInt("timeout", 0) <= 0) {
                timeout = DEFAULT_TIMEOUT;
            }
            JSONObject headers = opts.optJSONObject("headers");
            byte[] body = null;
            if (opts.has("dataB64")) {
                String encoded = opts.optString("dataB64", "");
                if (encoded.length() > ((MAX_REQUEST_BODY + 2L) / 3L) * 4L + 16L) {
                    return errorPayload("request body too large");
                }
                body = Base64.decode(encoded, Base64.DEFAULT);
            } else if (opts.has("data") && !opts.isNull("data")) {
                String text = opts.optString("data", "");
                if (text.length() > MAX_REQUEST_BODY) {
                    return errorPayload("request body too large");
                }
                body = text.getBytes(StandardCharsets.UTF_8);
            }
            if (body != null && body.length > MAX_REQUEST_BODY) {
                return errorPayload("request body too large");
            }
            if (headers != null && !validHeaders(headers)) {
                return errorPayload("invalid headers");
            }
            String responseType = opts.optString("responseType", "");
            String user = opts.optString("user", "");
            String password = opts.optString("password", "");
            String userAgent = opts.optString("ua", "Mozilla/5.0 (Linux; Android 10) CoaraBrowser");
            String charsetOverride = opts.optString("charset", "");
            if (responseType.length() > 64 || user.length() > 4096 || password.length() > 4096
                    || userAgent.length() > 8192 || charsetOverride.length() > 128) {
                return errorPayload("request metadata too large");
            }

            String current = url;
            int redirects = 0;
            while (true) {
                if (call.aborted) {
                    return abortedPayload();
                }
                URL original = new URL(current);
                InetAddress pinnedAddress = resolveAllowedAddress(connects, current, origin);
                if (pinnedAddress == null) {
                    return errorPayload("blocked host (not in @connect or non-public address): " + hostOf(current));
                }
                URLConnection opened = new URL(pinnedUrl(original, pinnedAddress)).openConnection(Proxy.NO_PROXY);
                if (!(opened instanceof HttpURLConnection)) {
                    return errorPayload("unsupported connection");
                }
                HttpURLConnection c = (HttpURLConnection) opened;
                if (c instanceof HttpsURLConnection) {
                    int effectivePort = original.getPort() < 0 ? original.getDefaultPort() : original.getPort();
                    HttpsURLConnection https = (HttpsURLConnection) c;
                    HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
                    String tlsHost = canonicalHost(original.getHost());
                    https.setSSLSocketFactory(new PinnedSSLSocketFactory(tlsHost, pinnedAddress,
                            effectivePort, Math.min(timeout, 20000), timeout));
                    https.setHostnameVerifier((ignored, session) -> verifier != null && verifier.verify(tlsHost, session));
                }
                call.conn = c;
                try {
                    c.setConnectTimeout(Math.min(timeout, 20000));
                    c.setReadTimeout(timeout);
                    c.setInstanceFollowRedirects(false);
                    c.setRequestMethod(method);
                    c.setRequestProperty("Host", hostHeader(original));
                    boolean hasCookie = false;
                    boolean hasUa = false;
                    if (headers != null) {
                        Iterator<String> it = headers.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            String kl = k.toLowerCase(Locale.US);
                            boolean sameOriginalOrigin = sameOrigin(url, current);
                            if (kl.equals("host") || kl.equals("content-length") || kl.equals("connection")
                                    || kl.equals("transfer-encoding") || kl.equals("proxy-authorization")
                                    || (!sameOriginalOrigin && (kl.equals("authorization") || kl.equals("cookie")))) {
                                continue;
                            }
                            if (kl.equals("cookie")) {
                                hasCookie = true;
                            }
                            if (kl.equals("user-agent")) {
                                hasUa = true;
                            }
                            c.setRequestProperty(k, headers.optString(k, ""));
                        }
                    }
                    if (!hasUa) {
                        c.setRequestProperty("User-Agent", userAgent);
                    }
                    if (!anonymous && !hasCookie) {
                        String cookie = CookieSitePolicy.cookieFor(current);
                        if (cookie != null && !cookie.isEmpty() && cookie.length() <= 65536) {
                            c.setRequestProperty("Cookie", cookie);
                        }
                    }
                    if (!user.isEmpty() && sameOrigin(url, current)) {
                        String cred = Base64.encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                        c.setRequestProperty("Authorization", "Basic " + cred);
                    }
                    if (body != null && body.length > 0 && !method.equals("GET") && !method.equals("HEAD")) {
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(body.length);
                        try (java.io.OutputStream output = c.getOutputStream()) {
                            output.write(body);
                            output.flush();
                        }
                    }
                    int code = c.getResponseCode();
                    if (!anonymous) {
                        Map<String, List<String>> hf = c.getHeaderFields();
                        List<String> sc = hf == null ? null : hf.get("Set-Cookie");
                        if (sc != null) {
                            for (String s : sc) {
                                if (s != null && s.length() <= 8192) {
                                    CookieManager.getInstance().setCookie(current, s);
                                }
                            }
                        }
                    }
                    if (code >= 300 && code < 400 && code != 304 && code != 300) {
                        String loc = c.getHeaderField("Location");
                        if (loc != null && !loc.isEmpty() && redirects < MAX_REDIRECTS && !opts.optBoolean("noRedirect", false)) {
                            redirects++;
                            String next = new URL(new URL(current), loc).toString();
                            if (current.regionMatches(true, 0, "https://", 0, 8) && !next.regionMatches(true, 0, "https://", 0, 8)) {
                                return errorPayload("blocked https to http redirect: " + hostOf(next));
                            }
                            current = next;
                            if (code == 301 || code == 302 || code == 303) {
                                if (!method.equals("HEAD")) {
                                    method = "GET";
                                }
                                body = null;
                            }
                            continue;
                        }
                    }
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    byte[] data = new byte[0];
                    int dataLength = 0;
                    if (in != null && !method.equals("HEAD")) {
                        try (InputStream is = in) {
                            int declaredLength = c.getContentLength();
                            int initialCapacity = declaredLength > 0 ? Math.min(declaredLength, 64 * 1024) : 32;
                            ResponseBuffer bos = new ResponseBuffer(initialCapacity);
                            byte[] buf = new byte[8192];
                            int n;
                            long total = 0L;
                            while ((n = is.read(buf)) != -1) {
                                if (n == 0) {
                                    continue;
                                }
                                if (call.aborted) {
                                    return abortedPayload();
                                }
                                total += n;
                                if (total > MAX_BODY) {
                                    return errorPayload("response too large");
                                }
                                bos.write(buf, 0, n);
                            }
                            data = bos.buffer();
                            dataLength = bos.size();
                        }
                    }
                    JSONObject out = new JSONObject();
                    out.put("status", code);
                    out.put("statusText", c.getResponseMessage() == null ? "" : c.getResponseMessage());
                    out.put("finalUrl", current);
                    out.put("headers", headerString(c));
                    String ctype = c.getContentType();
                    if (responseType.equals("arraybuffer") || responseType.equals("blob") || opts.optBoolean("binary", false)) {
                        out.put("b64", Base64.encodeToString(data, 0, dataLength, Base64.NO_WRAP));
                        out.put("mime", ctype == null ? "" : ctype);
                    } else {
                        out.put("text", new String(data, 0, dataLength, charsetOf(ctype, charsetOverride)));
                    }
                    return out;
                } finally {
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            if (call.aborted) {
                return abortedPayload();
            }
            return errorPayload(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }


    private static boolean validHeaders(JSONObject headers) {
        if (headers.length() > 64) {
            return false;
        }
        Iterator<String> it = headers.keys();
        int total = 0;
        while (it.hasNext()) {
            String key = it.next();
            String value = headers.optString(key, "");
            if (key == null || key.length() == 0 || key.length() > 256 || value.length() > 8192
                    || key.matches(".*[\\r\\n].*") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                    || !key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                return false;
            }
            total += key.length() + value.length();
            if (total > 32768) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOrigin(String first, String second) {
        try {
            URL a = new URL(first);
            URL b = new URL(second);
            int ap = a.getPort() < 0 ? a.getDefaultPort() : a.getPort();
            int bp = b.getPort() < 0 ? b.getDefaultPort() : b.getPort();
            return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                    && a.getHost().equalsIgnoreCase(b.getHost()) && ap == bp
                    && a.getUserInfo() == null && b.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject abortedPayload() {
        JSONObject o = errorPayload("aborted");
        try {
            o.put("aborted", true);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static String headerString(HttpURLConnection c) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<String>> hf = c.getHeaderFields();
        if (hf == null) {
            return "";
        }
        for (Map.Entry<String, List<String>> e : hf.entrySet()) {
            if (e.getKey() == null || e.getKey().equalsIgnoreCase("Set-Cookie")
                    || e.getKey().equalsIgnoreCase("Set-Cookie2")) {
                continue;
            }
            for (String v : e.getValue()) {
                if (sb.length() >= MAX_RESPONSE_HEADERS) {
                    return sb.toString();
                }
                int remaining = MAX_RESPONSE_HEADERS - sb.length();
                String prefix = e.getKey() + ": ";
                int prefixLength = Math.min(prefix.length(), remaining);
                sb.append(prefix, 0, prefixLength);
                remaining -= prefixLength;
                String value = v == null ? "" : v;
                int valueLength = Math.min(value.length(), remaining);
                sb.append(value, 0, valueLength);
                remaining -= valueLength;
                if (remaining > 0) sb.append('\r');
                if (remaining > 1) sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static Charset charsetOf(String contentType, String override) {
        String name = override;
        if ((name == null || name.isEmpty()) && contentType != null) {
            int i = contentType.toLowerCase(Locale.US).indexOf("charset=");
            if (i >= 0) {
                name = contentType.substring(i + 8).split(";")[0].trim().replace("\"", "");
            }
        }
        if (name != null && !name.isEmpty()) {
            try {
                return Charset.forName(name);
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }
}
