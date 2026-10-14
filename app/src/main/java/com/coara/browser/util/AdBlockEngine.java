package com.coara.browser.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AdBlockEngine {

    private static final String PREFS = "coara_adblock";
    private static final String KEY_ENABLED = "enabled";
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] GIF = {
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00,
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x21, (byte) 0xF9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C,
        0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
    };
    private static final String ALLOWED_METHODS = "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS";

    private static final Map<WebView, String> PAGE_HOSTS = Collections.synchronizedMap(new WeakHashMap<WebView, String>());
    private static final Map<WebView, ScriptHandler> HANDLERS = new WeakHashMap<>();
    private static final AtomicInteger BLOCKED = new AtomicInteger();
    private static final Object LOCK = new Object();

    private static volatile boolean enabled = false;
    private static volatile boolean initialized;
    private static volatile String script;
    private static Boolean docStartSupported;

    private AdBlockEngine() {
    }

    public static void init(Context context) {
        if (initialized || context == null) {
            return;
        }
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            try {
                SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                enabled = sp.getBoolean(KEY_ENABLED, false);
            } catch (Throwable ignored) {
                enabled = false;
            }
            script = AdBlockRules.buildScript();
            initialized = true;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int blockedCount() {
        return BLOCKED.get();
    }

    public static String menuTitle() {
        int n = BLOCKED.get();
        return n > 0 ? "AdBlock (" + n + ")" : "AdBlock";
    }

    private static boolean docStartSupported() {
        if (docStartSupported == null) {
            boolean ok;
            try {
                ok = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
            } catch (Throwable t) {
                ok = false;
            }
            docStartSupported = ok;
        }
        return docStartSupported;
    }

    public static void attach(WebView view) {
        if (view == null) {
            return;
        }
        init(view.getContext());
        synchronized (HANDLERS) {
            HANDLERS.put(view, null);
        }
        if (enabled) {
            install(view);
        }
    }

    private static void install(WebView view) {
        removeHandler(view);
        if (!docStartSupported()) {
            return;
        }
        try {
            ScriptHandler handler = WebViewCompat.addDocumentStartJavaScript(view, script, Collections.singleton("*"));
            synchronized (HANDLERS) {
                HANDLERS.put(view, handler);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void removeHandler(WebView view) {
        ScriptHandler handler;
        synchronized (HANDLERS) {
            handler = HANDLERS.get(view);
            HANDLERS.put(view, null);
        }
        if (handler != null) {
            try {
                handler.remove();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void setEnabled(Context context, boolean value) {
        init(context);
        enabled = value;
        try {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, value).apply();
        } catch (Throwable ignored) {
        }
        List<WebView> views;
        synchronized (HANDLERS) {
            views = new ArrayList<>(HANDLERS.keySet());
        }
        for (WebView view : views) {
            if (view == null) {
                continue;
            }
            try {
                if (value) {
                    install(view);
                    view.evaluateJavascript(script, null);
                } else {
                    removeHandler(view);
                    view.evaluateJavascript(AdBlockRules.SCRIPT_OFF, null);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static void onPageStarted(WebView view) {
        if (!enabled || view == null || docStartSupported()) {
            return;
        }
        try {
            view.evaluateJavascript(script, null);
        } catch (Throwable ignored) {
        }
    }

    public static boolean allowNewWindow(boolean isUserGesture) {
        return !enabled || isUserGesture;
    }

    public static WebResourceResponse intercept(WebView view, WebResourceRequest request) {
        if (!enabled || request == null) {
            return null;
        }
        Uri uri = request.getUrl();
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        String host = AdBlockRules.lower(uri.getHost());
        if (host == null) {
            return null;
        }
        if (request.isForMainFrame()) {
            if (view != null) {
                PAGE_HOSTS.put(view, host);
            }
            return null;
        }
        String pageHost = view != null ? PAGE_HOSTS.get(view) : null;
        String path = uri.getEncodedPath();
        if (!AdBlockRules.shouldBlock(host, path, pageHost)) {
            return null;
        }
        BLOCKED.incrementAndGet();
        String accept = null;
        String origin = null;
        String preflightHeaders = null;
        try {
            Map<String, String> headers = request.getRequestHeaders();
            if (headers != null) {
                accept = headers.get("Accept");
                origin = headers.get("Origin");
                preflightHeaders = headers.get("Access-Control-Request-Headers");
            }
        } catch (Throwable ignored) {
        }
        return blocked(path, accept, corsHeaders(origin, preflightHeaders));
    }

    private static Map<String, String> corsHeaders(String origin, String requestedHeaders) {
        Map<String, String> h = new HashMap<>(8);
        h.put("Cache-Control", "no-store");
        if (origin != null && !origin.isEmpty() && !origin.equals("null")) {
            h.put("Access-Control-Allow-Origin", origin);
            h.put("Access-Control-Allow-Credentials", "true");
            h.put("Vary", "Origin");
        } else {
            h.put("Access-Control-Allow-Origin", "*");
        }
        h.put("Access-Control-Allow-Methods", ALLOWED_METHODS);
        if (requestedHeaders != null && !requestedHeaders.isEmpty()) {
            h.put("Access-Control-Allow-Headers", requestedHeaders);
        }
        h.put("Access-Control-Max-Age", "600");
        return h;
    }

    private static WebResourceResponse blocked(String path, String accept, Map<String, String> headers) {
        String p = path == null ? "" : AdBlockRules.lower(path);
        String mime;
        byte[] data = EMPTY;
        if (accept != null && accept.contains("text/html")) {
            mime = "text/html";
        } else if (endsWith(p, ".gif", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".ico", ".avif", ".bmp")
                || (accept != null && accept.startsWith("image/"))) {
            mime = "image/gif";
            data = GIF;
        } else if (endsWith(p, ".js", ".mjs")) {
            mime = "application/javascript";
        } else if (endsWith(p, ".css")) {
            mime = "text/css";
        } else if (endsWith(p, ".json")) {
            mime = "application/json";
        } else {
            mime = "text/plain";
        }
        return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, new ByteArrayInputStream(data));
    }

    private static boolean endsWith(String s, String... suffixes) {
        for (String suffix : suffixes) {
            if (s.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
