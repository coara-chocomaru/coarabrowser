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
            String scheme = uri.getScheme();
            return scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean sameOrigin(String firstUrl, String secondUrl) {
        if (!isWebPage(firstUrl) || !isWebPage(secondUrl)) {
            return false;
        }
        try {
            Uri first = Uri.parse(firstUrl);
            Uri second = Uri.parse(secondUrl);
            String firstScheme = first.getScheme().toLowerCase(Locale.US);
            String secondScheme = second.getScheme().toLowerCase(Locale.US);
            int firstPort = first.getPort() >= 0 ? first.getPort() : ("https".equals(firstScheme) ? 443 : 80);
            int secondPort = second.getPort() >= 0 ? second.getPort() : ("https".equals(secondScheme) ? 443 : 80);
            return firstScheme.equals(secondScheme)
                    && first.getHost().equalsIgnoreCase(second.getHost())
                    && firstPort == secondPort;
        } catch (Exception e) {
            return false;
        }
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
