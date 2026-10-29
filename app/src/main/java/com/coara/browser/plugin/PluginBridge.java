package com.coara.browser.plugin;

import android.webkit.JavascriptInterface;

public final class PluginBridge {
    private final PluginManager manager;
    private final PluginViewOps ops;

    PluginBridge(PluginManager manager, PluginViewOps ops) {
        this.manager = manager;
        this.ops = ops;
    }

    @JavascriptInterface
    public String invoke(String token, String id, String op, String a, String b, String c) {
        try {
            if (token == null || token.length() != 32 || id == null || id.isEmpty() || id.length() > 128
                    || op == null || op.isEmpty() || op.length() > 32 || !validArguments(op, a, b, c)
                    || !manager.checkToken(token, id)) {
                return null;
            }
            return manager.handleBridge(ops, id, op, a, b, c);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean validArguments(String op, String a, String b, String c) {
        int al = a == null ? 0 : a.length();
        int bl = b == null ? 0 : b.length();
        int cl = c == null ? 0 : c.length();
        if ("xhr".equals(op)) return al <= 40 && bl <= 3_000_000 && cl <= 8192;
        if ("clip".equals(op)) return al <= 200_000 && bl <= 8192 && cl <= 8192;
        if ("sset".equals(op)) return al <= 128 && bl <= 65_536 && cl <= 8192;
        if ("open".equals(op)) return al <= 8192 && bl <= 8192 && cl <= 8192;
        if ("notify".equals(op)) return al <= 512 && bl <= 2048 && cl <= 8192;
        if ("report".equals(op)) return al <= 32 && bl <= 2048 && cl <= 16_384;
        if ("log".equals(op)) return al <= 16 && bl <= 8192 && cl <= 8192;
        if ("cmd".equals(op)) return al <= 64 && bl <= 256 && cl <= 8192;
        if ("i18n".equals(op)) return al <= 128 && bl <= 8192 && cl <= 8192;
        if ("res".equals(op) || "resurl".equals(op)) return al <= 512 && bl <= 8192 && cl <= 8192;
        return al <= 8192 && bl <= 8192 && cl <= 16_384;
    }
}
