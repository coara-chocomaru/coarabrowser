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

    private static int matchingLength(Set<String> sites, String host) {
        if (sites == null || sites.isEmpty() || host.isEmpty()) {
            return -1;
        }
        int best = -1;
        for (String site : sites) {
            if (site == null || site.isEmpty()) {
                continue;
            }
            if (host.equals(site) || host.endsWith("." + site)) {
                best = Math.max(best, site.length());
            }
        }
        return best;
    }

    public static boolean globalAllowed(SharedPreferences pref) {
        return pref != null && pref.getBoolean(BrowserConstants.KEY_THIRD_PARTY_COOKIES, false);
    }

    public static boolean isAllowed(SharedPreferences pref, String url) {
        int siteMode = override(pref, url);
        if (siteMode != 0) {
            return siteMode > 0;
        }
        return globalAllowed(pref);
    }

    public static int override(SharedPreferences pref, String url) {
        if (pref == null) {
            return 0;
        }
        String host = hostOf(url);
        if (host.isEmpty()) {
            return 0;
        }
        int allowLength = matchingLength(pref.getStringSet(KEY_ALLOW_SITES, null), host);
        int blockLength = matchingLength(pref.getStringSet(KEY_BLOCK_SITES, null), host);
        if (allowLength < 0 && blockLength < 0) {
            return 0;
        }
        if (allowLength > blockLength) {
            return 1;
        }
        return -1;
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
