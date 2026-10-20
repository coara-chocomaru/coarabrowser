package com.coara.browser.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.coara.browser.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class FindInPageController {

    public interface Provider {
        WebView current();
    }

    private static final long DEBOUNCE_MS = 160L;
    private static final long OVERVIEW_POLL_MS = 70L;

    private final Activity activity;
    private final SharedPreferences pref;
    private final Provider provider;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View bar;
    private EditText input;
    private TextView count;
    private int defaultCountColor;
    private WebView active;
    private String lastQuery = "";
    private int total;
    private int activeOrdinal = -1;
    private int selectedMarkerOrdinal = -1;
    private int markerMatchCount;
    private boolean suppressWatcher;
    private boolean overviewReady;
    private boolean manualSelection;
    private String overviewScript;
    private int overviewGeneration;
    private Runnable markerPollTask;
    private FindMarkerOverlay markerOverlay;

    private final Runnable searchTask = new Runnable() {
        @Override
        public void run() {
            search();
        }
    };

    private final WebView.FindListener listener = new WebView.FindListener() {
        @Override
        public void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches, boolean isDoneCounting) {
            if (!isShowing() || active == null || active != provider.current()) {
                return;
            }
            total = numberOfMatches;
            if (activeMatchOrdinal >= 0) {
                FindInPageController.this.activeOrdinal = activeMatchOrdinal;
            }
            if (count == null) {
                return;
            }
            if (numberOfMatches > 0) {
                count.setTextColor(defaultCountColor);
                int displayOrdinal = activeMatchOrdinal;
                if (manualSelection && overviewReady && markerMatchCount > 0 && selectedMarkerOrdinal >= 0) {
                    displayOrdinal = mapMarkerToNativeOrdinal(selectedMarkerOrdinal);
                }
                if (displayOrdinal < 0) {
                    displayOrdinal = 0;
                }
                count.setText((Math.min(displayOrdinal, numberOfMatches - 1) + 1) + "/" + numberOfMatches);
            } else if (isDoneCounting) {
                count.setTextColor(lastQuery.isEmpty() ? defaultCountColor : Color.parseColor("#D93025"));
                count.setText("0/0");
            }
            if (markerOverlay != null) {
                markerOverlay.setActiveOrdinal(manualSelection ? selectedMarkerOrdinal : mapNativeToMarkerOrdinal(FindInPageController.this.activeOrdinal));
            }
        }
    };

    public FindInPageController(Activity activity, SharedPreferences pref, Provider provider) {
        this.activity = activity;
        this.pref = pref;
        this.provider = provider;
    }

    public boolean isShowing() {
        return bar != null && bar.getVisibility() == View.VISIBLE;
    }

    public void show() {
        if (bar == null) {
            build();
        }
        bar.setVisibility(View.VISIBLE);
        ensureMarkerOverlayAttached();
        input.requestFocus();
        input.selectAll();
        if (input.getText().length() > 0) {
            scheduleSearch(0L);
        }
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void hide() {
        if (bar == null) {
            return;
        }
        handler.removeCallbacks(searchTask);
        clearActive();
        bar.setVisibility(View.GONE);
        resetCount();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    public void onTabSwitched(WebView previous) {
        if (!isShowing()) {
            return;
        }
        handler.removeCallbacks(searchTask);
        clearOverview(previous);
        if (previous != null) {
            try {
                previous.clearMatches();
                previous.setFindListener(null);
            } catch (Exception ignored) {
            }
        }
        active = null;
        activeOrdinal = -1;
        manualSelection = false;
        selectedMarkerOrdinal = -1;
        resetCount();
        ensureMarkerOverlayAttached();
        if (input.getText().length() > 0) {
            scheduleSearch(0L);
        }
    }

    public void onPageStarted(WebView view) {
        if (!isShowing() || view == null || view != provider.current()) {
            return;
        }
        handler.removeCallbacks(searchTask);
        clearOverview(view);
        activeOrdinal = -1;
        manualSelection = false;
        selectedMarkerOrdinal = -1;
        try {
            view.clearMatches();
        } catch (Exception ignored) {
        }
        resetCount();
    }

    public void onPageFinished(WebView view) {
        if (!isShowing() || view == null || view != provider.current()) {
            return;
        }
        if (input.getText().toString().trim().length() > 0) {
            scheduleSearch(250L);
        }
    }

    public void release() {
        handler.removeCallbacks(searchTask);
        clearActive();
        if (markerOverlay != null && markerOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) markerOverlay.getParent()).removeView(markerOverlay);
        }
        markerOverlay = null;
    }

    private void build() {
        bar = LayoutInflater.from(activity).inflate(R.layout.find_in_page_bar, null);
        input = bar.findViewById(R.id.etFindQuery);
        count = bar.findViewById(R.id.tvFindCount);
        defaultCountColor = count.getCurrentTextColor();
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        KeyboardSettings.applyToEditor(input, pref);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int c, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressWatcher) {
                    scheduleSearch(DEBOUNCE_MS);
                }
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit();
                return true;
            }
            return false;
        });
        input.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                submit();
                return true;
            }
            return false;
        });
        bar.findViewById(R.id.btnFindNext).setOnClickListener(v -> step(true));
        bar.findViewById(R.id.btnFindPrev).setOnClickListener(v -> step(false));
        bar.findViewById(R.id.btnFindClose).setOnClickListener(v -> {
            suppressWatcher = true;
            input.setText("");
            suppressWatcher = false;
            hide();
        });
        View toolbar = activity.findViewById(R.id.topAppBar);
        if (toolbar != null && toolbar.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) toolbar.getParent();
            int index = parent.indexOfChild(toolbar);
            ViewGroup.LayoutParams params;
            if (parent instanceof LinearLayout) {
                params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            } else {
                params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            parent.addView(bar, index >= 0 ? index + 1 : parent.getChildCount(), params);
        } else {
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content != null) {
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
                content.addView(bar, params);
            }
        }
    }

    private void scheduleSearch(long delay) {
        handler.removeCallbacks(searchTask);
        handler.postDelayed(searchTask, delay);
    }

    private void submit() {
        String q = input.getText().toString().trim();
        if (!q.equals(lastQuery)) {
            handler.removeCallbacks(searchTask);
            search();
        } else {
            step(true);
        }
    }

    private void step(boolean forward) {
        WebView w = provider.current();
        if (w == null || w != active) {
            return;
        }
        if (overviewReady && markerMatchCount > 0 && total > 0) {
            int current = manualSelection && selectedMarkerOrdinal >= 0
                ? selectedMarkerOrdinal
                : mapNativeToMarkerOrdinal(activeOrdinal);
            if (current < 0) {
                current = forward ? -1 : 0;
            }
            int next = forward
                ? (current + 1) % markerMatchCount
                : (current - 1 + markerMatchCount) % markerMatchCount;
            selectMarker(next, true);
            return;
        }
        if (total <= 0) {
            return;
        }
        try {
            w.findNext(forward);
        } catch (Exception ignored) {
        }
    }

    private void search() {
        WebView w = provider.current();
        if (w == null) {
            return;
        }
        String q = input.getText().toString().trim();
        if (active == w && q.equals(lastQuery)) {
            return;
        }
        if (active != null && active != w) {
            try {
                active.clearMatches();
                active.setFindListener(null);
            } catch (Exception ignored) {
            }
            clearOverview(active);
        } else {
            clearOverview(w);
        }
        active = w;
        activeOrdinal = -1;
        selectedMarkerOrdinal = -1;
        manualSelection = false;
        if (q.isEmpty()) {
            lastQuery = "";
            try {
                w.clearMatches();
            } catch (Exception ignored) {
            }
            resetCount();
            hideMarkerOverlay();
            return;
        }
        lastQuery = q;
        try {
            w.setFindListener(listener);
            w.findAllAsync(q);
            startOverviewSearch(w, q);
        } catch (Exception ignored) {
            total = 0;
            hideMarkerOverlay();
            if (count != null) {
                count.setTextColor(defaultCountColor);
                count.setText("0/0");
            }
        }
    }

    private void startOverviewSearch(WebView webView, String query) {
        cancelOverviewPoll();
        overviewReady = false;
        markerMatchCount = 0;
        selectedMarkerOrdinal = -1;
        manualSelection = false;
        if (markerOverlay != null) {
            markerOverlay.setMarkers(new ArrayList<>(), 0, -1);
            markerOverlay.setVisibility(View.GONE);
        }
        String script = loadOverviewScript();
        if (script == null || script.isEmpty()) {
            return;
        }
        final int generation = overviewGeneration;
        final String quotedQuery = JSONObject.quote(query);
        try {
            webView.evaluateJavascript("if(!window.__CoaraFindOverview){" + script + "}window.__CoaraFindOverview.start(" + quotedQuery + ");", null);
        } catch (Exception ignored) {
            return;
        }
        markerPollTask = new Runnable() {
            @Override
            public void run() {
                if (generation != overviewGeneration || !isShowing() || webView != provider.current() || webView != active || !query.equals(lastQuery)) {
                    return;
                }
                try {
                    webView.evaluateJavascript("window.__CoaraFindOverview?window.__CoaraFindOverview.result():null", value -> {
                        if (generation != overviewGeneration || !isShowing() || webView != provider.current() || webView != active || !query.equals(lastQuery)) {
                            return;
                        }
                        try {
                            if (value == null || "null".equals(value)) {
                                scheduleOverviewPoll(this);
                                return;
                            }
                            JSONObject result = new JSONObject(value);
                            if (!result.optBoolean("ready", false)) {
                                scheduleOverviewPoll(this);
                                return;
                            }
                            markerMatchCount = Math.max(0, result.optInt("count", 0));
                            JSONArray points = result.optJSONArray("points");
                            ArrayList<Marker> markers = new ArrayList<>();
                            if (points != null) {
                                for (int i = 0; i < points.length(); i++) {
                                    JSONObject item = points.optJSONObject(i);
                                    if (item == null) {
                                        continue;
                                    }
                                    markers.add(new Marker(
                                        Math.max(0, item.optInt("ordinal", 0)),
                                        clamp(item.optDouble("fraction", 0.0))));
                                }
                            }
                            overviewReady = true;
                            if (selectedMarkerOrdinal < 0 && markerMatchCount > 0) {
                                selectedMarkerOrdinal = mapNativeToMarkerOrdinal(activeOrdinal);
                            }
                            if (markerOverlay != null) {
                                markerOverlay.setMarkers(markers, markerMatchCount, manualSelection ? selectedMarkerOrdinal : mapNativeToMarkerOrdinal(activeOrdinal));
                                if (!markers.isEmpty()) {
                                    ensureMarkerOverlayAttached();
                                    markerOverlay.setVisibility(View.VISIBLE);
                                    markerOverlay.bringToFront();
                                } else {
                                    markerOverlay.setVisibility(View.GONE);
                                }
                            }
                        } catch (Exception ignored) {
                            scheduleOverviewPoll(this);
                        }
                    });
                } catch (Exception ignored) {
                    scheduleOverviewPoll(this);
                }
            }
        };
        handler.postDelayed(markerPollTask, OVERVIEW_POLL_MS);
    }

    private void scheduleOverviewPoll(Runnable task) {
        if (task != null && task == markerPollTask) {
            handler.postDelayed(task, OVERVIEW_POLL_MS);
        }
    }

    private void selectMarker(int ordinal, boolean updateCount) {
        if (!overviewReady || markerMatchCount <= 0) {
            return;
        }
        selectedMarkerOrdinal = Math.max(0, Math.min(ordinal, markerMatchCount - 1));
        manualSelection = true;
        WebView webView = provider.current();
        if (webView != null && webView == active) {
            try {
                webView.evaluateJavascript("window.__CoaraFindOverview&&window.__CoaraFindOverview.go(" + selectedMarkerOrdinal + ")", null);
            } catch (Exception ignored) {
            }
        }
        if (markerOverlay != null) {
            markerOverlay.setActiveOrdinal(selectedMarkerOrdinal);
            markerOverlay.invalidate();
        }
        if (updateCount && count != null && total > 0) {
            count.setTextColor(defaultCountColor);
            count.setText((mapMarkerToNativeOrdinal(selectedMarkerOrdinal) + 1) + "/" + total);
        }
    }

    private int mapMarkerToNativeOrdinal(int markerOrdinal) {
        if (total <= 1 || markerMatchCount <= 1) {
            return 0;
        }
        double mapped = markerOrdinal * (double) (total - 1) / (markerMatchCount - 1);
        return Math.max(0, Math.min(total - 1, (int) Math.round(mapped)));
    }

    private int mapNativeToMarkerOrdinal(int nativeOrdinal) {
        if (markerMatchCount <= 1 || total <= 1) {
            return 0;
        }
        int value = Math.max(0, Math.min(total - 1, nativeOrdinal));
        double mapped = value * (double) (markerMatchCount - 1) / (total - 1);
        return Math.max(0, Math.min(markerMatchCount - 1, (int) Math.round(mapped)));
    }

    private void ensureMarkerOverlayAttached() {
        FrameLayout container = activity.findViewById(R.id.webViewContainer);
        if (container == null) {
            return;
        }
        if (markerOverlay == null) {
            markerOverlay = new FindMarkerOverlay(activity);
        }
        if (markerOverlay.getParent() instanceof ViewGroup && markerOverlay.getParent() != container) {
            ((ViewGroup) markerOverlay.getParent()).removeView(markerOverlay);
        }
        if (markerOverlay.getParent() == null) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.TOP);
            container.addView(markerOverlay, params);
        }
        markerOverlay.bringToFront();
    }

    private void hideMarkerOverlay() {
        if (markerOverlay != null) {
            markerOverlay.setMarkers(new ArrayList<>(), 0, -1);
            markerOverlay.setVisibility(View.GONE);
        }
    }

    private void cancelOverviewPoll() {
        overviewGeneration++;
        if (markerPollTask != null) {
            handler.removeCallbacks(markerPollTask);
            markerPollTask = null;
        }
    }

    private void clearOverview(WebView webView) {
        cancelOverviewPoll();
        overviewReady = false;
        markerMatchCount = 0;
        selectedMarkerOrdinal = -1;
        manualSelection = false;
        hideMarkerOverlay();
        if (webView != null) {
            try {
                webView.evaluateJavascript("window.__CoaraFindOverview&&window.__CoaraFindOverview.clear()", null);
            } catch (Exception ignored) {
            }
        }
    }

    private String loadOverviewScript() {
        if (overviewScript != null) {
            return overviewScript;
        }
        try (InputStream inputStream = activity.getAssets().open("find_in_page_overview.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            overviewScript = new String(output.toByteArray(), StandardCharsets.UTF_8);
            return overviewScript;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void clearActive() {
        WebView w = active != null ? active : provider.current();
        clearOverview(w);
        if (w != null) {
            try {
                w.clearMatches();
                w.setFindListener(null);
            } catch (Exception ignored) {
            }
        }
        active = null;
        lastQuery = "";
        activeOrdinal = -1;
        selectedMarkerOrdinal = -1;
        manualSelection = false;
    }

    private void resetCount() {
        total = 0;
        lastQuery = "";
        activeOrdinal = -1;
        if (count != null) {
            count.setTextColor(defaultCountColor);
            count.setText("0/0");
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final class Marker {
        final int ordinal;
        final float fraction;

        Marker(int ordinal, double fraction) {
            this.ordinal = ordinal;
            this.fraction = (float) clamp(fraction);
        }
    }

    private final class FindMarkerOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final ArrayList<Marker> markers = new ArrayList<>();
        private int matchCount;
        private int activeMarkerOrdinal = -1;

        FindMarkerOverlay(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            setContentDescription("ページ内検索の一致位置");
            setClickable(true);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            setVisibility(View.GONE);
        }

        void setMarkers(ArrayList<Marker> values, int count, int activeOrdinal) {
            markers.clear();
            if (values != null) {
                markers.addAll(values);
            }
            matchCount = Math.max(0, count);
            activeMarkerOrdinal = activeOrdinal;
            invalidate();
        }

        void setActiveOrdinal(int ordinal) {
            activeMarkerOrdinal = ordinal;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (markers.isEmpty() || matchCount <= 0 || getHeight() <= dp(16)) {
                return;
            }
            float inset = 8f * density;
            float usableHeight = Math.max(1f, getHeight() - inset * 2f);
            float trackWidth = 2f * density;
            float trackX = getWidth() - 4f * density;
            paint.setColor(0x44000000);
            canvas.drawRoundRect(new RectF(trackX, inset, trackX + trackWidth, getHeight() - inset), trackWidth, trackWidth, paint);
            int selectedIndex = -1;
            int closestOrdinalDistance = Integer.MAX_VALUE;
            if (activeMarkerOrdinal >= 0) {
                for (int i = 0; i < markers.size(); i++) {
                    int distance = Math.abs(markers.get(i).ordinal - activeMarkerOrdinal);
                    if (distance < closestOrdinalDistance) {
                        closestOrdinalDistance = distance;
                        selectedIndex = i;
                    }
                }
            }
            for (int i = 0; i < markers.size(); i++) {
                Marker marker = markers.get(i);
                float y = inset + marker.fraction * usableHeight;
                boolean selected = i == selectedIndex;
                float width = (selected ? 8f : 5f) * density;
                float height = (selected ? 4f : 2.5f) * density;
                paint.setColor(selected ? Color.rgb(217, 48, 37) : Color.rgb(255, 183, 77));
                canvas.drawRoundRect(new RectF(getWidth() - width, y - height / 2f, getWidth() - 1f * density, y + height / 2f), height, height, paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (markers.isEmpty() || matchCount <= 0) {
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float y = Math.max(0f, Math.min(getHeight(), event.getY()));
                float inset = 8f * density;
                float usableHeight = Math.max(1f, getHeight() - inset * 2f);
                float fraction = Math.max(0f, Math.min(1f, (y - inset) / usableHeight));
                Marker nearest = null;
                float nearestDistance = Float.MAX_VALUE;
                for (Marker marker : markers) {
                    float distance = Math.abs(marker.fraction - fraction);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = marker;
                    }
                }
                if (nearest != null) {
                    selectMarker(nearest.ordinal, true);
                }
                performClick();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                return true;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
