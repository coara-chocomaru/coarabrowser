package com.coara.browser.webview;

import android.net.Uri;

import java.util.Locale;

public final class BridgeGuard {
    private static final int MAX_URL_LENGTH = 4096;

    private BridgeGuard() {
    }

    public static boolean isWebPage(String url) {
        if (url == null || url.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            return isWebUri(url, uri);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean sameOrigin(String firstUrl, String secondUrl) {
        if (firstUrl == null || secondUrl == null
                || firstUrl.length() > MAX_URL_LENGTH || secondUrl.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            Uri first = Uri.parse(firstUrl);
            Uri second = Uri.parse(secondUrl);
            if (!isWebUri(firstUrl, first) || !isWebUri(secondUrl, second)) {
                return false;
            }
            return sameOriginUris(first, second);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean sameOrigin(Uri first, String secondUrl) {
        if (!isWebUri(first) || secondUrl == null || secondUrl.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            Uri second = Uri.parse(secondUrl);
            return isWebUri(secondUrl, second) && sameOriginUris(first, second);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean sameOriginUris(Uri first, Uri second) {
        String firstScheme = first.getScheme().toLowerCase(Locale.US);
        String secondScheme = second.getScheme().toLowerCase(Locale.US);
        int firstPort = first.getPort() >= 0 ? first.getPort() : ("https".equals(firstScheme) ? 443 : 80);
        int secondPort = second.getPort() >= 0 ? second.getPort() : ("https".equals(secondScheme) ? 443 : 80);
        return firstScheme.equals(secondScheme)
                && first.getHost().equalsIgnoreCase(second.getHost())
                && firstPort == secondPort;
    }

    private static boolean isWebUri(String url, Uri uri) {
        return url != null && url.length() <= MAX_URL_LENGTH && isWebUri(uri);
    }

    private static boolean isWebUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && host != null
                && !host.isEmpty()
                && uri.getUserInfo() == null;
    }

    public static boolean isSecureCaptureOrigin(String origin) {
        if (!isWebPage(origin)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(origin);
            String scheme = uri.getScheme().toLowerCase(Locale.US);
            if ("https".equals(scheme)) {
                return true;
            }
            String host = uri.getHost().toLowerCase(Locale.US);
            return "localhost".equals(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || host.endsWith(".localhost");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean acceptReportedUrl(String pageUrl, String reportedUrl) {
        return sameOrigin(pageUrl, reportedUrl);
    }
}
