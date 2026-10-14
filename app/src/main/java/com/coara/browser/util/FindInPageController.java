package com.coara.browser.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.coara.browser.R;

public final class FindInPageController {

    public interface Provider {
        WebView current();
    }

    private static final long DEBOUNCE_MS = 160L;

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
    private boolean suppressWatcher;

    private final Runnable searchTask = new Runnable() {
        @Override
        public void run() {
            search();
        }
    };

    private final WebView.FindListener listener = new WebView.FindListener() {
        @Override
        public void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches, boolean isDoneCounting) {
            total = numberOfMatches;
            if (count == null) {
                return;
            }
            if (numberOfMatches > 0) {
                count.setTextColor(defaultCountColor);
                count.setText((activeMatchOrdinal + 1) + "/" + numberOfMatches);
            } else if (isDoneCounting) {
                count.setTextColor(lastQuery.isEmpty() ? defaultCountColor : Color.parseColor("#D93025"));
                count.setText("0/0");
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
        if (previous != null) {
            try {
                previous.clearMatches();
                previous.setFindListener(null);
            } catch (Exception ignored) {
            }
        }
        active = null;
        resetCount();
        if (input.getText().length() > 0) {
            scheduleSearch(0L);
        }
    }

    public void onPageStarted(WebView view) {
        if (!isShowing() || view == null || view != provider.current()) {
            return;
        }
        handler.removeCallbacks(searchTask);
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
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        activity.addContentView(bar, params);
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
        if (w == null || total <= 0) {
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
        if (active != null && active != w) {
            try {
                active.clearMatches();
                active.setFindListener(null);
            } catch (Exception ignored) {
            }
        }
        active = w;
        lastQuery = q;
        if (q.isEmpty()) {
            try {
                w.clearMatches();
            } catch (Exception ignored) {
            }
            resetCount();
            return;
        }
        w.setFindListener(listener);
        w.findAllAsync(q);
    }

    private void clearActive() {
        WebView w = active != null ? active : provider.current();
        if (w != null) {
            try {
                w.clearMatches();
                w.setFindListener(null);
            } catch (Exception ignored) {
            }
        }
        active = null;
        lastQuery = "";
    }

    private void resetCount() {
        total = 0;
        lastQuery = "";
        if (count != null) {
            count.setTextColor(defaultCountColor);
            count.setText("0/0");
        }
    }
}
