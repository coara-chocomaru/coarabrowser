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

    public interface TouchpadHandler {
        void onTouchpad(WebView view, int kind, float a, float b);
    }

    private NativeMessageBridge() {
    }

    public static boolean attach(WebView view, Handler handler) {
        return attach(view, handler, null);
    }

    public static boolean attach(WebView view, Handler handler, TouchpadHandler touchpadHandler) {
        if (view == null || handler == null) {
            return false;
        }
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                return false;
            }
            RateLimit rateLimit = new RateLimit();
            TouchpadRequest touchpadRequest = new TouchpadRequest();
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
                            if (!BridgeGuard.sameOrigin(sourceOrigin, pageUrl)) {
                                return;
                            }
                            String data = message.getData();
                            if (data == null || data.isEmpty() || data.length() > MAX_MESSAGE_LENGTH) {
                                return;
                            }
                            if (data.charAt(0) == 't' && data.length() >= 2 && data.charAt(1) == ',') {
                                if (touchpadHandler != null && parseTouchpadRequest(data, touchpadRequest)) {
                                    touchpadHandler.onTouchpad(sourceView, touchpadRequest.kind,
                                            touchpadRequest.a, touchpadRequest.b);
                                }
                                return;
                            }
                            if (!hasFlatJsonStructure(data)) {
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

    private static final class TouchpadRequest {
        int kind;
        float a;
        float b;
    }

    private static boolean parseTouchpadRequest(String data, TouchpadRequest out) {
        if (data.length() < 7 || data.length() > 96 || data.charAt(0) != 't' || data.charAt(1) != ',') {
            return false;
        }
        char kindChar = data.charAt(2);
        if (kindChar < '1' || kindChar > '6' || data.charAt(3) != ',') {
            return false;
        }
        int secondComma = data.indexOf(',', 4);
        if (secondComma <= 4 || secondComma >= data.length() - 1
                || data.indexOf(',', secondComma + 1) >= 0) {
            return false;
        }
        if (!isNumericToken(data, 4, secondComma)
                || !isNumericToken(data, secondComma + 1, data.length())) {
            return false;
        }
        float a = parseNumericToken(data, 4, secondComma);
        float b = parseNumericToken(data, secondComma + 1, data.length());
        int kind = kindChar - '0';
        if (!isFinite(a) || !isFinite(b) || !isValidTouchpadRequest(kind, a, b)) {
            return false;
        }
        out.kind = kind;
        out.a = a;
        out.b = b;
        return true;
    }

    private static float parseNumericToken(String value, int start, int end) {
        if (start >= end) {
            return Float.NaN;
        }
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (c == 'e' || c == 'E') {
                try {
                    return Float.parseFloat(value.substring(start, end));
                } catch (NumberFormatException ignored) {
                    return Float.NaN;
                }
            }
        }
        int index = start;
        boolean negative = false;
        char first = value.charAt(index);
        if (first == '-' || first == '+') {
            negative = first == '-';
            index++;
        }
        double number = 0d;
        double fraction = 0.1d;
        boolean decimal = false;
        boolean digitFound = false;
        for (; index < end; index++) {
            char c = value.charAt(index);
            if (c >= '0' && c <= '9') {
                digitFound = true;
                int digit = c - '0';
                if (decimal) {
                    number += digit * fraction;
                    fraction *= 0.1d;
                } else {
                    number = number * 10d + digit;
                    if (number > 1000000d) {
                        return Float.NaN;
                    }
                }
            } else if (c == '.' && !decimal) {
                decimal = true;
            } else {
                return Float.NaN;
            }
        }
        if (!digitFound) {
            return Float.NaN;
        }
        if (negative) {
            number = -number;
        }
        float result = (float) number;
        return isFinite(result) ? result : Float.NaN;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean isNumericToken(String value, int start, int end) {
        boolean digit = false;
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c != '.' && c != '-' && c != '+' && c != 'e' && c != 'E') {
                return false;
            }
        }
        return digit;
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
