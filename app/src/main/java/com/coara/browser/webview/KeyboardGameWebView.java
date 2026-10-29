package com.coara.browser.webview;

import android.content.Context;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.coara.browser.util.CacheModePolicy;
import com.coara.browser.util.TouchpadController;

import java.util.Map;

import androidx.annotation.Nullable;

import org.json.JSONObject;

public class KeyboardGameWebView extends WebView {
    public static final int PAGE_MODE_NORMAL = 0;
    public static final int PAGE_MODE_GAME = 1;
    public static final int PAGE_MODE_TYPING = 2;

    private static final int CHAR_MODE_NORMAL = 0;
    private static final int CHAR_MODE_NUMBER = 1;
    private static final int CHAR_MODE_ENGLISH = 2;

    private volatile int inputBehaviorMode = PAGE_MODE_NORMAL;
    private volatile int keyboardMode = CHAR_MODE_NORMAL;
    private volatile boolean alwaysVisibleKeyboard;
    private volatile String composingText = "";
    private volatile boolean urlAwareCachePolicyEnabled;
    private volatile boolean forceNoCacheOnNavigation;
    private boolean touchpadMouseInitialized;
    private boolean touchpadPointerLocked;
    private float touchpadMouseX;
    private float touchpadMouseY;
    private int touchpadMouseButtons;
    private long touchpadMouseDownTime;

    public KeyboardGameWebView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setInputBehaviorMode(int mode) {
        inputBehaviorMode = mode >= PAGE_MODE_NORMAL && mode <= PAGE_MODE_TYPING ? mode : PAGE_MODE_NORMAL;
        composingText = "";
    }

    public void setUrlAwareCachePolicyEnabled(boolean enabled) {
        urlAwareCachePolicyEnabled = enabled;
        if (enabled) {
            forceNoCacheOnNavigation = false;
        }
    }

    public void setForceNoCacheOnNavigation(boolean enabled) {
        forceNoCacheOnNavigation = enabled;
        if (enabled) {
            urlAwareCachePolicyEnabled = false;
            getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        }
    }

    private void applyNavigationCachePolicy(String url) {
        if (forceNoCacheOnNavigation) {
            getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        } else if (urlAwareCachePolicyEnabled) {
            getSettings().setCacheMode(CacheModePolicy.determineForUrl(url));
        }
    }

    @Override
    public void loadUrl(String url) {
        applyNavigationCachePolicy(url);
        super.loadUrl(url);
    }

    @Override
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        applyNavigationCachePolicy(url);
        super.loadUrl(url, additionalHttpHeaders);
    }

    public int getInputBehaviorMode() {
        return inputBehaviorMode;
    }

    public void setKeyboardMode(int mode) {
        keyboardMode = mode >= CHAR_MODE_NORMAL && mode <= CHAR_MODE_ENGLISH ? mode : CHAR_MODE_NORMAL;
        composingText = "";
    }

    public void setAlwaysVisibleKeyboard(boolean enabled) {
        alwaysVisibleKeyboard = enabled;
        if (!enabled) {
            inputBehaviorMode = PAGE_MODE_NORMAL;
            keyboardMode = CHAR_MODE_NORMAL;
            composingText = "";
        }
    }

    public boolean isAlwaysVisibleKeyboard() {
        return alwaysVisibleKeyboard;
    }

    public void setGameKeyboardEnabled(boolean enabled) {
        setInputBehaviorMode(enabled ? PAGE_MODE_GAME : PAGE_MODE_NORMAL);
    }

    public boolean isGameKeyboardEnabled() {
        return inputBehaviorMode != PAGE_MODE_NORMAL;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return alwaysVisibleKeyboard || super.onCheckIsTextEditor();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection base = super.onCreateInputConnection(outAttrs);
        boolean bridgeEnabled = isBridgeEnabled();
        boolean filterEnabled = alwaysVisibleKeyboard && keyboardMode != CHAR_MODE_NORMAL;
        boolean baseWasMissing = base == null;
        if (!alwaysVisibleKeyboard) return base;
        if (baseWasMissing) {
            base = new BaseInputConnection(this, true);
        }
        if (!bridgeEnabled && !filterEnabled && !baseWasMissing) return base;
        configureEditorInfo(outAttrs);
        if (!bridgeEnabled && !filterEnabled) return base;
        return new GameInputConnection(this, base);
    }

    private void configureEditorInfo(EditorInfo outAttrs) {
        if (outAttrs == null) return;
        if (keyboardMode == CHAR_MODE_NUMBER) {
            outAttrs.inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_NORMAL;
        } else if (keyboardMode == CHAR_MODE_ENGLISH) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        } else {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (keyboardMode == CHAR_MODE_ENGLISH) {
            outAttrs.imeOptions |= EditorInfo.IME_FLAG_FORCE_ASCII;
        }
    }

    private boolean fingerGestureActive;

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event == null) {
            return false;
        }
        if (TouchpadController.shouldDropScreenTouch()) {
            if (fingerGestureActive) {
                fingerGestureActive = false;
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                try {
                    super.dispatchTouchEvent(cancel);
                } finally {
                    cancel.recycle();
                }
            }
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            fingerGestureActive = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            fingerGestureActive = false;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        if (alwaysVisibleKeyboard && event != null && event.getActionMasked() == MotionEvent.ACTION_UP) {
            postDelayed(() -> {
                if (!alwaysVisibleKeyboard || !hasFocus() || getWindowToken() == null || !isAttachedToWindow()) return;
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }, 120L);
        }
        return handled;
    }

    public void resetTouchpadMouse() {
        long now = SystemClock.uptimeMillis();
        if (touchpadMouseButtons != 0) {
            int oldButtons = touchpadMouseButtons;
            touchpadMouseButtons = 0;
            if ((oldButtons & MotionEvent.BUTTON_SECONDARY) != 0) {
                dispatchTouchpadMouseButtonEvent(2, false, now, oldButtons);
            }
            if ((oldButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                dispatchTouchpadMouseButtonEvent(0, false, now, oldButtons & ~MotionEvent.BUTTON_SECONDARY);
            }
        }
        touchpadMouseButtons = 0;
        touchpadMouseInitialized = false;
        touchpadMouseDownTime = 0L;
    }

    public boolean setTouchpadPointerLocked(boolean locked) {
        if (touchpadPointerLocked == locked) {
            return false;
        }
        touchpadPointerLocked = locked;
        if (!locked && touchpadMouseInitialized) {
            touchpadMouseX = clampTouchpadCoordinate(touchpadMouseX, getWidth());
            touchpadMouseY = clampTouchpadCoordinate(touchpadMouseY, getHeight());
        }
        return true;
    }

    public void installTouchpadPointerLockTracking() {
        evaluateJavascript("(function(){var d=document,w=window;function n(){try{var v=!!d.pointerLockElement;if(w.CoaraNativeBridge&&typeof w.CoaraNativeBridge.postMessage==='function'){w.CoaraNativeBridge.postMessage(JSON.stringify({type:'pointerlock',locked:v}));}else{AndroidBridge.onPointerLockChanged(v);}}catch(e){}}function i(doc){try{if(!doc||doc.__coaraTouchpadPointerLockInstalled)return;doc.__coaraTouchpadPointerLockInstalled=true;doc.addEventListener('pointerlockchange',n,true);doc.addEventListener('pointerlockerror',n,true);var fs=doc.querySelectorAll('iframe,frame');for(var j=0;j<fs.length;j++){try{i(fs[j].contentDocument);}catch(e){}}}catch(e){}}i(d);n();})();", null);
    }

    public boolean dispatchTouchpadMouseMove(float dx, float dy) {
        if (!isFiniteTouchpadDelta(dx) || !isFiniteTouchpadDelta(dy) || (dx == 0f && dy == 0f) || getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        if (!touchpadMouseInitialized) {
            touchpadMouseX = getWidth() * 0.5f;
            touchpadMouseY = getHeight() * 0.5f;
            touchpadMouseInitialized = true;
        }
        if (!touchpadPointerLocked) {
            touchpadMouseX = clampTouchpadCoordinate(touchpadMouseX + dx, getWidth());
            touchpadMouseY = clampTouchpadCoordinate(touchpadMouseY + dy, getHeight());
        } else {
            touchpadMouseX += dx;
            touchpadMouseY += dy;
        }
        long now = SystemClock.uptimeMillis();
        int action = touchpadMouseButtons == 0 ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_MOVE;
        MotionEvent event = obtainTouchpadMouseEvent(now, now, action, touchpadMouseX, touchpadMouseY, touchpadMouseButtons);
        try {
            if (action == MotionEvent.ACTION_HOVER_MOVE) {
                return super.onHoverEvent(event);
            }
            return super.dispatchGenericMotionEvent(event);
        } finally {
            event.recycle();
        }
    }

    public boolean dispatchTouchpadMouseScroll(float dx, float dy) {
        if (!isFiniteTouchpadDelta(dx) || !isFiniteTouchpadDelta(dy) || (dx == 0f && dy == 0f) || getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        if (!touchpadMouseInitialized) {
            touchpadMouseX = getWidth() * 0.5f;
            touchpadMouseY = getHeight() * 0.5f;
            touchpadMouseInitialized = true;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = obtainTouchpadMouseScrollEvent(now, touchpadMouseX, touchpadMouseY, dx, dy);
        try {
            return super.dispatchGenericMotionEvent(event);
        } finally {
            event.recycle();
        }
    }

    public boolean dispatchTouchpadMouseButton(int button, boolean pressed) {
        long now = SystemClock.uptimeMillis();
        int mask = button == 2 ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
        if (!touchpadMouseInitialized) {
            touchpadMouseX = getWidth() * 0.5f;
            touchpadMouseY = getHeight() * 0.5f;
            touchpadMouseInitialized = true;
        }
        if (pressed) {
            if ((touchpadMouseButtons & mask) != 0) return true;
            int next = touchpadMouseButtons | mask;
            boolean result = dispatchTouchpadMouseButtonEvent(button, true, now, next);
            if (result) {
                touchpadMouseButtons = next;
                if (touchpadMouseDownTime == 0L) touchpadMouseDownTime = now;
            }
            return result;
        }
        if ((touchpadMouseButtons & mask) == 0) return true;
        int next = touchpadMouseButtons & ~mask;
        boolean result = dispatchTouchpadMouseButtonEvent(button, false, now, next);
        if (result) {
            touchpadMouseButtons = next;
            if (next == 0) touchpadMouseDownTime = 0L;
        }
        return result;
    }

    private boolean dispatchTouchpadMouseButtonEvent(int button, boolean pressed, long eventTime, int buttonState) {
        if (getWidth() <= 0 || getHeight() <= 0) return false;
        int changedButton = button == 2 ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
        int actionBase = pressed ? MotionEvent.ACTION_BUTTON_PRESS : MotionEvent.ACTION_BUTTON_RELEASE;
        int action = actionBase | (changedButton << 8);
        long downTime = touchpadMouseDownTime != 0L ? touchpadMouseDownTime : eventTime;
        MotionEvent event = obtainTouchpadMouseEvent(downTime, eventTime, action, touchpadMouseX, touchpadMouseY, buttonState);
        try {
            return super.dispatchGenericMotionEvent(event);
        } finally {
            event.recycle();
        }
    }

    private MotionEvent obtainTouchpadMouseEvent(long downTime, long eventTime, int action, float x, float y, int buttonState) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = buttonState == 0 ? 0f : 0.5f;
        coords.size = 1f;
        return MotionEvent.obtain(downTime, eventTime, action, 1, new MotionEvent.PointerProperties[] {properties}, new MotionEvent.PointerCoords[] {coords}, 0, buttonState, 1f, 1f, -1, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    private MotionEvent obtainTouchpadMouseScrollEvent(long eventTime, float x, float y, float dx, float dy) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, dx);
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, dy);
        return MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_SCROLL, 1, new MotionEvent.PointerProperties[] {properties}, new MotionEvent.PointerCoords[] {coords}, 0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    private static float clampTouchpadCoordinate(float value, int size) {
        if (size <= 1) return 0f;
        if (value < 0f) return 0f;
        float max = size - 1f;
        return value > max ? max : value;
    }

    private static boolean isFiniteTouchpadDelta(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && Math.abs(value) < 10000f;
    }

    public void installKeyboardEventTracking() {
        if (!alwaysVisibleKeyboard || inputBehaviorMode == PAGE_MODE_NORMAL) {
            setKeyboardEventTrackingEnabled(false);
            return;
        }
        evaluateJavascript("(function(){var w=window,d=document;w.__coaraKeyboardTrackerEnabled=true;if(w.__coaraKeyboardTrackerInstalled)return;w.__coaraKeyboardTrackerInstalled=true;var active=function(){return w.__coaraKeyboardTrackerEnabled===true;};var remember=function(e){if(!active())return;var t=e&&e.target;if(!t)return;if(t.nodeType===3)t=t.parentElement;if(t&&t!==d&&t!==d.documentElement&&t!==d.body)w.__coaraKeyboardTarget=t;};d.addEventListener('pointerdown',remember,true);d.addEventListener('touchstart',remember,true);d.addEventListener('mousedown',remember,true);d.addEventListener('click',remember,true);d.addEventListener('focusin',function(e){if(active())w.__coaraKeyboardFocusedTarget=e&&e.target;},true);d.addEventListener('focusout',function(e){if(active()&&w.__coaraKeyboardFocusedTarget===e.target)w.__coaraKeyboardFocusedTarget=null;},true);})();", null);
    }

    public void setKeyboardEventTrackingEnabled(boolean enabled) {
        evaluateJavascript("(function(){window.__coaraKeyboardTrackerEnabled=" + enabled + ";})();", null);
    }

    private boolean isBridgeEnabled() {
        return alwaysVisibleKeyboard && inputBehaviorMode != PAGE_MODE_NORMAL;
    }

    private boolean isFilteredCharacter(char c) {
        if (keyboardMode == CHAR_MODE_NUMBER) return c >= '0' && c <= '9';
        if (keyboardMode == CHAR_MODE_ENGLISH) return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        if (inputBehaviorMode == PAGE_MODE_TYPING) return c >= 32 && c <= 126;
        return (c >= 32 && c <= 126) || c == '\n' || c == '\r' || c == '\t';
    }

    private String filterText(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isFilteredCharacter(c)) result.append(c);
        }
        return result.toString();
    }

    private boolean isPrintableAsciiText(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 32 || c > 126) && c != '\n' && c != '\r' && c != '\t') return false;
        }
        return true;
    }

    private boolean isAllowedKeyEvent(KeyEvent event) {
        if (!alwaysVisibleKeyboard || event == null || keyboardMode == CHAR_MODE_NORMAL) return true;
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DEL || key == KeyEvent.KEYCODE_FORWARD_DEL || key == KeyEvent.KEYCODE_TAB || key == KeyEvent.KEYCODE_ESCAPE || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_SHIFT_LEFT || key == KeyEvent.KEYCODE_SHIFT_RIGHT || key == KeyEvent.KEYCODE_CTRL_LEFT || key == KeyEvent.KEYCODE_CTRL_RIGHT || key == KeyEvent.KEYCODE_ALT_LEFT || key == KeyEvent.KEYCODE_ALT_RIGHT || key == KeyEvent.KEYCODE_MOVE_HOME || key == KeyEvent.KEYCODE_MOVE_END || key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_PAGE_DOWN) return true;
        if (keyboardMode == CHAR_MODE_NUMBER) {
            if (key >= KeyEvent.KEYCODE_NUMPAD_0 && key <= KeyEvent.KEYCODE_NUMPAD_9) return true;
            int unicode = event.getUnicodeChar(event.getMetaState());
            return key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9 && unicode >= '0' && unicode <= '9';
        }
        return key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z;
    }

    private boolean shouldHandleCommittedText(String value) {
        if (!isBridgeEnabled()) return false;
        if (value == null || value.isEmpty()) return true;
        if (keyboardMode == CHAR_MODE_NUMBER || keyboardMode == CHAR_MODE_ENGLISH || inputBehaviorMode == PAGE_MODE_TYPING) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 32 && c <= 126) || c == '\n' || c == '\r' || c == '\t') continue;
            return false;
        }
        return true;
    }

    private void dispatchCommittedText(String value) {
        if (!isBridgeEnabled() || value == null || value.isEmpty()) return;
        String filtered = filterText(value);
        if (filtered.isEmpty()) return;
        final String text = filtered.replace("\r\n", "\n");
        post(() -> {
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r' || c == '\t') {
                    if (i > start) dispatchCharacterRun(text.substring(start, i));
                    dispatchSpecialKey(c == '\t' ? KeyEvent.KEYCODE_TAB : KeyEvent.KEYCODE_ENTER);
                    start = i + 1;
                }
            }
            if (start < text.length()) dispatchCharacterRun(text.substring(start));
        });
    }

    private void dispatchCharacterRun(String value) {
        if (value == null || value.isEmpty()) return;
        KeyEvent[] events = null;
        try {
            events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray());
        } catch (RuntimeException ignored) {
        }
        if (events != null && events.length > 0) {
            for (KeyEvent event : events) {
                if (event != null) dispatchKeyEvent(event);
            }
        } else {
            dispatchFallbackText(value);
        }
    }

    private void dispatchSpecialKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0);
        KeyEvent up = new KeyEvent(now, now + 1, KeyEvent.ACTION_UP, keyCode, 0, 0);
        dispatchKeyEvent(down);
        dispatchKeyEvent(up);
    }

    private void dispatchFallbackText(String value) {
        String quoted = JSONObject.quote(value);
        String script = "(function(){var w=window,d=document,s=" + quoted + ";var a=d.activeElement;var target=a&&a!==d.body&&a!==d.documentElement?a:(w.__coaraKeyboardTarget&&d.documentElement.contains(w.__coaraKeyboardTarget)?w.__coaraKeyboardTarget:d);if(!target)target=d;function kc(c){var n=c.charCodeAt(0);if(n>=65&&n<=90)return n;if(n>=97&&n<=122)return n-32;if(n>=48&&n<=57)return n;var m={' ':'32','-':'189','_':'189','=':'187','+':'187','[':'219','{':'219',']':'221','}':'221','\\\\':'220','|':'220',';':'186',':':'186',\"'\":'222',\"\\\"\":'222',',':'188','<':'188','.':'190','>':'190','/':'191','?':'191','`':'192','~':'192','!':'49','@':'50','#':'51','$':'52','%':'53','^':'54','&':'55','*':'56','(':'57',')':'48'};return m[c]===undefined?0:Number(m[c]);}var chars=Array.from(s);for(var i=0;i<chars.length;i++){var c=chars[i],n=c.codePointAt(0),k=kc(c),code='';if(/[a-z]/i.test(c))code='Key'+c.toUpperCase();else if(/[0-9]/.test(c))code='Digit'+c;else if(c===' ')code='Space';var o={key:c,code:code,bubbles:true,cancelable:true,composed:true,view:w};function send(type,keyCode,charCode){var e=new KeyboardEvent(type,Object.assign({},o,{keyCode:keyCode,which:type==='keypress'?charCode:keyCode,charCode:charCode}));try{Object.defineProperty(e,'keyCode',{get:function(){return keyCode;}});Object.defineProperty(e,'which',{get:function(){return type==='keypress'?charCode:keyCode;}});Object.defineProperty(e,'charCode',{get:function(){return charCode;}});}catch(_){}target.dispatchEvent(e);return e;}var down=send('keydown',k,0);if(!down.defaultPrevented&&n>=32&&!/[\\r\\n\\t]/.test(c))send('keypress',k,n<=255?n:0);send('keyup',k,0);}})();";
        evaluateJavascript(script, null);
    }

    private void dispatchFallbackKey(int action, int keyCode, int repeat, int metaState, long downTime, long eventTime) {
        post(() -> {
            KeyEvent event = new KeyEvent(downTime, eventTime, action, keyCode, repeat, metaState);
            dispatchKeyEvent(event);
        });
    }

    private final class GameInputConnection extends InputConnectionWrapper {
        private final KeyboardGameWebView owner;

        GameInputConnection(KeyboardGameWebView owner, InputConnection target) {
            super(target, true);
            this.owner = owner;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event != null && !owner.isAllowedKeyEvent(event)) return true;
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DEL) owner.composingText = "";
            boolean result = super.sendKeyEvent(event);
            if (!result && owner.isBridgeEnabled() && event != null) {
                owner.dispatchFallbackKey(event.getAction(), event.getKeyCode(), event.getRepeatCount(), event.getMetaState(), event.getDownTime(), event.getEventTime());
                return true;
            }
            return result;
        }

        @Override
        public boolean commitText(@Nullable CharSequence text, int newCursorPosition) {
            String committed = text == null ? "" : text.toString();
            String composing = owner.composingText;
            owner.composingText = "";
            if (owner.inputBehaviorMode == PAGE_MODE_TYPING
                    && !committed.isEmpty()
                    && !owner.isPrintableAsciiText(committed)
                    && owner.isPrintableAsciiText(composing)) {
                owner.dispatchCommittedText(composing);
                return true;
            }
            if (owner.shouldHandleCommittedText(committed)) {
                owner.dispatchCommittedText(committed);
                return true;
            }
            if (!owner.isBridgeEnabled() && owner.keyboardMode != CHAR_MODE_NORMAL) {
                return super.commitText(owner.filterText(committed), newCursorPosition);
            }
            if (owner.isBridgeEnabled() && !committed.isEmpty()) {
                boolean result = super.commitText(text, newCursorPosition);
                owner.post(() -> owner.dispatchFallbackText(committed));
                return result;
            }
            return super.commitText(text, newCursorPosition);
        }

        @Override
        public boolean setComposingText(@Nullable CharSequence text, int newCursorPosition) {
            if (owner.isBridgeEnabled()) {
                owner.composingText = text == null ? "" : text.toString();
                return true;
            }
            return super.setComposingText(text, newCursorPosition);
        }

        @Override
        public boolean finishComposingText() {
            if (owner.isBridgeEnabled()) {
                String pending = owner.composingText;
                owner.composingText = "";
                if (pending != null && !pending.isEmpty()) {
                    if (owner.shouldHandleCommittedText(pending)) {
                        owner.dispatchCommittedText(pending);
                    } else if (owner.isBridgeEnabled()) {
                        super.commitText(pending, 1);
                        owner.post(() -> owner.dispatchFallbackText(pending));
                    } else if (owner.keyboardMode != CHAR_MODE_NORMAL) {
                        super.commitText(owner.filterText(pending), 1);
                    } else {
                        super.commitText(pending, 1);
                    }
                }
                return true;
            }
            return super.finishComposingText();
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            return owner.isBridgeEnabled() || super.setComposingRegion(start, end);
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (owner.isBridgeEnabled() && (beforeLength > 0 || afterLength > 0)) {
                owner.composingText = "";
                owner.post(() -> owner.dispatchSpecialKey(KeyEvent.KEYCODE_DEL));
                return true;
            }
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            if (owner.isBridgeEnabled() && (beforeLength > 0 || afterLength > 0)) {
                owner.composingText = "";
                owner.post(() -> owner.dispatchSpecialKey(KeyEvent.KEYCODE_DEL));
                return true;
            }
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            if (owner.isBridgeEnabled() && (actionCode == EditorInfo.IME_ACTION_DONE || actionCode == EditorInfo.IME_ACTION_GO || actionCode == EditorInfo.IME_ACTION_NEXT || actionCode == EditorInfo.IME_ACTION_UNSPECIFIED || actionCode == EditorInfo.IME_ACTION_SEND || actionCode == EditorInfo.IME_ACTION_SEARCH || actionCode == EditorInfo.IME_ACTION_PREVIOUS)) {
                owner.composingText = "";
                owner.post(() -> owner.dispatchSpecialKey(KeyEvent.KEYCODE_ENTER));
                return true;
            }
            return super.performEditorAction(actionCode);
        }
    }
}
