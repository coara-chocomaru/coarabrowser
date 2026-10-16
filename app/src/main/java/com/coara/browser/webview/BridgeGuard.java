package com.coara.browser.webview;

import android.net.Uri;

import java.util.Locale;

public final class BridgeGuard {
    private static final int MAX_URL_LENGTH = 4096;

    private BridgeGuard() {
    }

    public static boolean acceptReportedUrl(String pageUrl, String reportedUrl) {
        if (pageUrl == null || reportedUrl == null || reportedUrl.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            Uri page = Uri.parse(pageUrl);
            Uri reported = Uri.parse(reportedUrl);
            String pageScheme = page.getScheme();
            String reportedScheme = reported.getScheme();
            if (pageScheme == null || reportedScheme == null) {
                return false;
            }
            pageScheme = pageScheme.toLowerCase(Locale.US);
            reportedScheme = reportedScheme.toLowerCase(Locale.US);
            if (!pageScheme.equals(reportedScheme)) {
                return false;
            }
            if (!pageScheme.equals("http") && !pageScheme.equals("https")) {
                return false;
            }
            String pageHost = page.getHost();
            String reportedHost = reported.getHost();
            if (pageHost == null || reportedHost == null) {
                return false;
            }
            return pageHost.equalsIgnoreCase(reportedHost) && page.getPort() == reported.getPort();
        } catch (Exception e) {
            return false;
        }
    }
}
