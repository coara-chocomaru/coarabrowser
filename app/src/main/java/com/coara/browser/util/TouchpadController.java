package com.coara.browser.util;

import android.os.SystemClock;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

public final class TouchpadController {
    private static final String CALL_HEAD = "(function(){try{var n=window.__coaraTouchpadNative;if(n&&typeof n.";
    private static final String CALL_MID = "==='function'){n.";
    private static final String CALL_TAIL = ");return 1;}return 0;}catch(e){return 0;}})();";
    private static final long REINSTALL_INTERVAL_MS = 700L;

    private static WeakReference<WebView> currentWebView = new WeakReference<>(null);
    private static volatile boolean active;
    private static volatile boolean blockScreenTouch;
    private static float pendingMoveX;
    private static float pendingMoveY;
    private static float pendingStickX;
    private static float pendingStickY;
    private static boolean pendingStick;
    private static float stickX;
    private static float stickY;
    private static int stickMode;
    private static boolean flushScheduled;
    private static WebView flushWebView;
    private static boolean bridgeInstalled;
    private static long lastInstallTime;
    private static long lastBridgeCheckTime;
    private static String bridgeSource;

    private static final Runnable FLUSH_SCHEDULED_MOVE = new Runnable() {
        @Override
        public void run() {
            WebView target = flushWebView;
            flushWebView = null;
            flushScheduled = false;
            if (target != null) {
                flush(target);
            }
        }
    };

    private TouchpadController() {
    }

    private static void clearPending() {
        if (flushScheduled && flushWebView != null) {
            flushWebView.removeCallbacks(FLUSH_SCHEDULED_MOVE);
        }
        flushWebView = null;
        pendingMoveX = 0f;
        pendingMoveY = 0f;
        pendingStickX = 0f;
        pendingStickY = 0f;
        pendingStick = false;
        flushScheduled = false;
    }

    public static void setCurrentWebView(WebView webView) {
        WebView current = currentWebView.get();
        if (current == webView) {
            if (active) {
                installAndStart(webView);
            }
            return;
        }
        if (current != null) {
            call(current, "stop", null);
        }
        currentWebView = new WeakReference<>(webView);
        clearPending();
        stickX = 0f;
        stickY = 0f;
        bridgeInstalled = false;
        if (active) {
            installAndStart(webView);
        }
    }

    public static void clearCurrentWebView(WebView webView) {
        if (currentWebView.get() == webView) {
            call(webView, "stop", null);
            currentWebView = new WeakReference<>(null);
            clearPending();
            stickX = 0f;
            stickY = 0f;
            bridgeInstalled = false;
        }
    }

    public static void onPageStarted(WebView webView) {
        if (currentWebView.get() == webView) {
            pendingMoveX = 0f;
            pendingMoveY = 0f;
            pendingStick = false;
            bridgeInstalled = false;
        }
    }

    public static void onPageFinished(WebView webView) {
        if (currentWebView.get() == webView) {
            if (active) {
                installAndStart(webView);
            }
        }
    }

    public static void setPointerLocked(WebView webView, boolean locked) {
        if (currentWebView.get() != webView || !bridgeInstalled) {
            return;
        }
        call(webView, "setPointerLocked", locked ? "true" : "false");
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean enabled) {
        if (active == enabled) {
            WebView webView = currentWebView.get();
            if (enabled) {
                installAndStart(webView);
            }
            return;
        }
        active = enabled;
        WebView webView = currentWebView.get();
        clearPending();
        stickX = 0f;
        stickY = 0f;
        if (webView == null) {
            return;
        }
        if (enabled) {
            installAndStart(webView);
        } else {
            call(webView, "stop", null);
        }
    }

    public static void setScreenTouchBlocked(boolean blocked) {
        blockScreenTouch = blocked;
    }

    public static boolean isScreenTouchBlocked() {
        return active && blockScreenTouch;
    }

    public static boolean shouldDropScreenTouch() {
        return active && blockScreenTouch;
    }

    public static void setStickMode(int mode) {
        int safe = mode >= 0 && mode < TouchpadSettings.STICK_MODE_COUNT ? mode : 0;
        if (stickMode == safe) {
            return;
        }
        stickMode = safe;
        WebView webView = currentWebView.get();
        if (webView != null && bridgeInstalled) {
            call(webView, "stickMode", Integer.toString(safe));
        }
    }

    public static void move(float dx, float dy) {
        if (!active || !isFinite(dx) || !isFinite(dy) || (dx == 0f && dy == 0f)) {
            return;
        }
        WebView webView = currentWebView.get();
        if (webView == null) {
            return;
        }
        pendingMoveX += dx;
        pendingMoveY += dy;
        scheduleFlush(webView);
    }

    public static void stickTap() {
        if (!active) {
            return;
        }
        WebView webView = currentWebView.get();
        if (webView == null) {
            return;
        }
        flush(webView);
        ensureStarted(webView);
        call(webView, "stickTap", null);
    }

    public static void stick(float x, float y) {
        if (!active || !isFinite(x) || !isFinite(y)) {
            return;
        }
        WebView webView = currentWebView.get();
        if (webView == null) {
            return;
        }
        stickX = x;
        stickY = y;
        pendingStickX = x;
        pendingStickY = y;
        pendingStick = true;
        if (x == 0f && y == 0f) {
            flush(webView);
        } else {
            scheduleFlush(webView);
        }
    }

    public static void click(int button) {
        if (!active) {
            return;
        }
        WebView webView = currentWebView.get();
        if (webView == null) {
            return;
        }
        flush(webView);
        ensureStarted(webView);
        call(webView, "click", Integer.toString(button));
    }

    public static void buttonDown(int button) {
        if (!active) {
            return;
        }
        WebView webView = currentWebView.get();
        if (webView == null) {
            return;
        }
        flush(webView);
        ensureStarted(webView);
        call(webView, "down", Integer.toString(button));
    }

    public static void buttonUp(int button) {
        if (!active) {
            return;
        }
        WebView webView = currentWebView.get();
        if (webView == null) {
            return;
        }
        flush(webView);
        ensureStarted(webView);
        call(webView, "up", Integer.toString(button));
    }

    public static void cancelButtons() {
        if (!active) {
            return;
        }
        WebView webView = currentWebView.get();
        if (webView == null) {
            return;
        }
        flush(webView);
        ensureStarted(webView);
        call(webView, "cancel", null);
    }

    private static void scheduleFlush(WebView webView) {
        if (flushScheduled && flushWebView == webView) {
            return;
        }
        if (flushScheduled && flushWebView != null) {
            flushWebView.removeCallbacks(FLUSH_SCHEDULED_MOVE);
        }
        flushScheduled = true;
        flushWebView = webView;
        webView.postOnAnimation(FLUSH_SCHEDULED_MOVE);
    }

    private static void flush(WebView webView) {
        if (webView == null || currentWebView.get() != webView) {
            return;
        }
        float dx = pendingMoveX;
        float dy = pendingMoveY;
        boolean hasStick = pendingStick;
        float tx = pendingStickX;
        float ty = pendingStickY;
        pendingMoveX = 0f;
        pendingMoveY = 0f;
        pendingStickX = 0f;
        pendingStickY = 0f;
        pendingStick = false;
        boolean hasMove = dx != 0f || dy != 0f;
        if (!hasMove && !hasStick) {
            return;
        }
        ensureStarted(webView);
        StringBuilder args = new StringBuilder(80);
        args.append(dx).append(',').append(dy).append(',')
                .append(hasStick ? 1 : 0).append(',')
                .append(tx).append(',').append(ty);
        call(webView, "batch", args.toString());
    }

    private static void ensureStarted(WebView webView) {
        if (!bridgeInstalled) {
            installAndStart(webView);
        }
    }

    private static void installAndStart(WebView webView) {
        if (webView == null) {
            return;
        }
        boolean freshlyInstalled = false;
        if (!bridgeInstalled) {
            installBridge(webView);
            freshlyInstalled = bridgeInstalled;
        }
        if (bridgeInstalled) {
            call(webView, "start", null);
            if (freshlyInstalled) {
                call(webView, "stickMode", Integer.toString(stickMode));
                if (stickX != 0f || stickY != 0f) {
                    call(webView, "stick", Float.toString(stickX) + "," + Float.toString(stickY));
                }
            }
        }
    }

    private static void installBridge(WebView webView) {
        if (webView == null || currentWebView.get() != webView) {
            return;
        }
        try {
            String script = readBridge(webView);
            if (script == null || script.isEmpty()) {
                return;
            }
            webView.evaluateJavascript(script, null);
            bridgeInstalled = true;
            lastInstallTime = SystemClock.uptimeMillis();
            lastBridgeCheckTime = 0L;
        } catch (Throwable ignored) {
            bridgeInstalled = false;
        }
    }

    private static String readBridge(WebView webView) {
        if (bridgeSource != null) {
            return bridgeSource;
        }
        StringBuilder out = new StringBuilder(32000);
        try (InputStream in = webView.getContext().getAssets().open("touchpad_bridge.js");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            bridgeSource = out.toString();
            return bridgeSource;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void call(WebView webView, String method, String args) {
        if (webView == null || method == null || method.isEmpty()) {
            return;
        }
        StringBuilder js = new StringBuilder(160);
        js.append(CALL_HEAD).append(method).append(CALL_MID).append(method).append('(');
        if (args != null) {
            js.append(args);
        }
        js.append(CALL_TAIL);
        long now = SystemClock.uptimeMillis();
        boolean verify = !"stop".equals(method)
                && (lastBridgeCheckTime == 0L || now - lastBridgeCheckTime >= REINSTALL_INTERVAL_MS);
        ValueCallback<String> callback = null;
        if (verify) {
            lastBridgeCheckTime = now;
            callback = new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (!"1".equals(value)) {
                        onBridgeMissing(webView);
                    }
                }
            };
        }
        try {
            webView.evaluateJavascript(js.toString(), callback);
        } catch (Throwable ignored) {
        }
    }

    private static void onBridgeMissing(WebView webView) {
        if (!active || currentWebView.get() != webView) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastInstallTime < REINSTALL_INTERVAL_MS) {
            return;
        }
        bridgeInstalled = false;
        lastBridgeCheckTime = 0L;
        installAndStart(webView);
    }


    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
