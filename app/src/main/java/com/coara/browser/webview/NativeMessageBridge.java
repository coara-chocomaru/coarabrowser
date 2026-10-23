package com.coara.browser.webview;

import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

public final class NativeMessageBridge {
    public static final String BRIDGE_NAME = "CoaraNativeBridge";
    private static final int MAX_MESSAGE_LENGTH = 8192;
    private static final int MAX_URL_LENGTH = 4096;
    private static final int MAX_MESSAGES_PER_SECOND = 240;

    public interface Handler {
        void onMessage(WebView view, JSONObject message);
    }

    private NativeMessageBridge() {
    }

    public static boolean attach(WebView view, Handler handler) {
        if (view == null || handler == null) {
            return false;
        }
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                return false;
            }
            RateLimit rateLimit = new RateLimit();
            WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, Collections.singleton("*"),
                    new WebViewCompat.WebMessageListener() {
                        @Override
                        public void onPostMessage(@NonNull WebView sourceView,
                                                  @NonNull WebMessageCompat message,
                                                  @NonNull Uri sourceOrigin,
                                                  boolean isMainFrame,
                                                  @NonNull androidx.webkit.JavaScriptReplyProxy replyProxy) {
                            if (sourceView != view || !isMainFrame || !rateLimit.allow()) {
                                return;
                            }
                            String pageUrl = sourceView.getUrl();
                            if (!BridgeGuard.isWebPage(pageUrl)
                                    || !BridgeGuard.sameOrigin(sourceOrigin.toString(), pageUrl)) {
                                return;
                            }
                            String data = message.getData();
                            if (data == null || data.isEmpty() || data.length() > MAX_MESSAGE_LENGTH
                                    || !hasFlatJsonStructure(data)) {
                                return;
                            }
                            try {
                                JSONObject payload = new JSONObject(data);
                                if (!isValidMessage(payload)) {
                                    return;
                                }
                                handler.onMessage(sourceView, payload);
                            } catch (JSONException | RuntimeException ignored) {
                            }
                        }
                    });
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean isValidTouchpadRequest(int kind, float a, float b) {
        if (kind < 1 || kind > 6 || Float.isNaN(a) || Float.isInfinite(a)
                || Float.isNaN(b) || Float.isInfinite(b)) {
            return false;
        }
        if (kind <= 4) {
            return a >= 0f && a <= 1f && b >= 0f && b <= 1f;
        }
        return a >= 0f && a <= 65535f && a == (int) a && b == 0f;
    }

    private static final class RateLimit {
        private long windowStart = SystemClock.uptimeMillis();
        private int count;

        boolean allow() {
            long now = SystemClock.uptimeMillis();
            if (now - windowStart >= 1000L || now < windowStart) {
                windowStart = now;
                count = 0;
            }
            if (count >= MAX_MESSAGES_PER_SECOND) {
                return false;
            }
            count++;
            return true;
        }
    }

    private static boolean hasFlatJsonStructure(String data) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                } else if (c < 0x20) {
                    return false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth != 0) {
                    return false;
                }
                depth = 1;
            } else if (c == '}') {
                if (depth != 1) {
                    return false;
                }
                depth = 0;
            } else if (c == '[' || c == ']') {
                return false;
            } else if (c < 0x20 && c != '\t' && c != '\r' && c != '\n') {
                return false;
            }
        }
        return !inString && !escaped && depth == 0;
    }

    private static boolean isValidMessage(JSONObject payload) {
        Object typeValue = payload.opt("type");
        if (!(typeValue instanceof String)) {
            return false;
        }
        String type = (String) typeValue;
        switch (type) {
            case "touchpad": {
                Object kindValue = payload.opt("kind");
                Object aValue = payload.opt("a");
                Object bValue = payload.opt("b");
                if (!(kindValue instanceof Number) || !(aValue instanceof Number) || !(bValue instanceof Number)) {
                    return false;
                }
                double kindNumber = ((Number) kindValue).doubleValue();
                double a = ((Number) aValue).doubleValue();
                double b = ((Number) bValue).doubleValue();
                if (!finite(kindNumber) || kindNumber != Math.rint(kindNumber)
                        || kindNumber < 1 || kindNumber > 6 || !finite(a) || !finite(b)) {
                    return false;
                }
                return isValidTouchpadRequest((int) kindNumber, (float) a, (float) b);
            }
            case "pointerlock":
                return payload.opt("locked") instanceof Boolean;
            case "url": {
                Object url = payload.opt("url");
                return url instanceof String && !((String) url).isEmpty()
                        && ((String) url).length() <= MAX_URL_LENGTH;
            }
            case "spa":
                return payload.opt("detected") instanceof Boolean;
            default:
                return false;
        }
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
