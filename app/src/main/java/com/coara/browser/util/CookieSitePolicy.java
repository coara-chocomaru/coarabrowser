package com.coara.browser.util;

import android.content.SharedPreferences;
import android.net.Uri;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class CookieSitePolicy {
    public static final String KEY_ALLOW_SITES = "third_party_cookie_allow_sites";
    public static final String KEY_BLOCK_SITES = "third_party_cookie_block_sites";
    private static final int MAX_SITES = 500;

    private CookieSitePolicy() {
    }

    public static String hostOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase(Locale.US);
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            return host;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean matches(Set<String> sites, String host) {
        if (sites == null || sites.isEmpty() || host.isEmpty()) {
            return false;
        }
        if (sites.contains(host)) {
            return true;
        }
        int dot = host.indexOf('.');
        while (dot >= 0 && dot < host.length() - 1) {
            host = host.substring(dot + 1);
            if (sites.contains(host)) {
                return true;
            }
            dot = host.indexOf('.');
        }
        return false;
    }

    public static boolean globalAllowed(SharedPreferences pref) {
        return pref != null && pref.getBoolean(BrowserConstants.KEY_THIRD_PARTY_COOKIES, false);
    }

    public static boolean isAllowed(SharedPreferences pref, String url) {
        boolean global = globalAllowed(pref);
        if (pref == null) {
            return global;
        }
        String host = hostOf(url);
        if (host.isEmpty()) {
            return global;
        }
        if (matches(pref.getStringSet(KEY_BLOCK_SITES, null), host)) {
            return false;
        }
        if (matches(pref.getStringSet(KEY_ALLOW_SITES, null), host)) {
            return true;
        }
        return global;
    }

    public static int override(SharedPreferences pref, String url) {
        if (pref == null) {
            return 0;
        }
        String host = hostOf(url);
        if (host.isEmpty()) {
            return 0;
        }
        Set<String> block = pref.getStringSet(KEY_BLOCK_SITES, null);
        if (block != null && block.contains(host)) {
            return -1;
        }
        Set<String> allow = pref.getStringSet(KEY_ALLOW_SITES, null);
        if (allow != null && allow.contains(host)) {
            return 1;
        }
        return 0;
    }

    public static boolean setOverride(SharedPreferences pref, String url, int mode) {
        if (pref == null) {
            return false;
        }
        String host = hostOf(url);
        if (host.isEmpty()) {
            return false;
        }
        Set<String> allow = copy(pref.getStringSet(KEY_ALLOW_SITES, null));
        Set<String> block = copy(pref.getStringSet(KEY_BLOCK_SITES, null));
        allow.remove(host);
        block.remove(host);
        if (mode > 0) {
            if (allow.size() >= MAX_SITES) {
                return false;
            }
            allow.add(host);
        } else if (mode < 0) {
            if (block.size() >= MAX_SITES) {
                return false;
            }
            block.add(host);
        }
        pref.edit().putStringSet(KEY_ALLOW_SITES, allow).putStringSet(KEY_BLOCK_SITES, block).apply();
        return true;
    }

    private static Set<String> copy(Set<String> source) {
        return source == null ? new HashSet<String>() : new HashSet<String>(source);
    }
}
