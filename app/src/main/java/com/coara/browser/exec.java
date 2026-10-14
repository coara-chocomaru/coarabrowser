package com.coara.browser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.coara.browser.util.LogFileSaver;

import org.json.JSONArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class exec extends Activity {

    private static final int MAX_LOG_CHARS = 4_000_000;
    private static final int MAX_VIEW_CHARS = 300_000;
    private static final int PENDING_LIMIT = 512 * 1024;
    private static final long FLUSH_MS = 50L;
    private static final String PREFS = "coara_exec";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 30;
    private static final String[] TIMEOUT_LABELS = {"30秒", "2分", "10分", "無制限"};
    private static final int[] TIMEOUT_SECONDS = {30, 120, 600, 0};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder log = new StringBuilder();
    private final List<String> history = new ArrayList<>();

    private AutoCompleteTextView input;
    private Button runButton;
    private Button stopButton;
    private TextView output;
    private TextView status;
    private ScrollView scroll;
    private Spinner timeoutSpinner;
    private ArrayAdapter<String> historyAdapter;

    private volatile Process process;
    private volatile int runId;
    private volatile boolean running;
    private boolean flushPosted;
    private boolean userStopped;
    private boolean timedOut;
    private long startedAt;
    private String lastCommand = "";

    private final Runnable flushRunnable = this::flushNow;
    private final Runnable timeoutRunnable = () -> {
        if (running) {
            timedOut = true;
            killProcess();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.exec);
        input = findViewById(R.id.execCommandInput);
        runButton = findViewById(R.id.execRunButton);
        stopButton = findViewById(R.id.execStopButton);
        Button copyButton = findViewById(R.id.execCopyButton);
        Button saveButton = findViewById(R.id.execSaveButton);
        Button clearButton = findViewById(R.id.execClearButton);
        output = findViewById(R.id.execOutput);
        status = findViewById(R.id.execStatus);
        scroll = findViewById(R.id.execScroll);
        timeoutSpinner = findViewById(R.id.execTimeoutSpinner);

        ArrayAdapter<String> timeoutAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, TIMEOUT_LABELS);
        timeoutAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        timeoutSpinner.setAdapter(timeoutAdapter);

        loadHistory();
        historyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(history));
        input.setAdapter(historyAdapter);
        input.setThreshold(1);
        input.setOnClickListener(v -> {
            if (historyAdapter.getCount() > 0 && input.getText().length() == 0) {
                input.showDropDown();
            }
        });
        input.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            input.setSelection(input.length());
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                runCommand();
                return true;
            }
            return false;
        });

        runButton.setOnClickListener(v -> runCommand());
        stopButton.setOnClickListener(v -> {
            if (running) {
                userStopped = true;
                killProcess();
            } else {
                Toast.makeText(this, "実行中のプロセスはありません。", Toast.LENGTH_SHORT).show();
            }
        });
        copyButton.setOnClickListener(v -> copyOutput());
        saveButton.setOnClickListener(v -> saveLog());
        clearButton.setOnClickListener(v -> {
            if (!running) {
                clearOutput();
                status.setText("待機中");
            } else {
                Toast.makeText(this, "実行中は消去できません。", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadHistory() {
        history.clear();
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String raw = sp.getString(KEY_HISTORY, null);
            if (raw != null) {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length() && history.size() < MAX_HISTORY; i++) {
                    String item = array.optString(i, "");
                    if (!item.isEmpty()) {
                        history.add(item);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void rememberCommand(String command) {
        history.remove(command);
        history.add(0, command);
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
        try {
            JSONArray array = new JSONArray();
            for (String item : history) {
                array.put(item);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HISTORY, array.toString()).apply();
        } catch (Exception ignored) {
        }
        historyAdapter.clear();
        historyAdapter.addAll(history);
        historyAdapter.notifyDataSetChanged();
    }

    private void runCommand() {
        if (running) {
            Toast.makeText(this, "実行中です。停止してから再実行してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        String command = input.getText().toString().trim();
        if (command.isEmpty()) {
            Toast.makeText(this, "コマンドを入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        input.dismissDropDown();
        rememberCommand(command);
        lastCommand = command;
        clearOutput();
        final int myRun = ++runId;
        userStopped = false;
        timedOut = false;
        startedAt = System.currentTimeMillis();
        appendDirect("$ " + command + "\n");

        final Process p;
        try {
            Process started;
            try {
                started = new ProcessBuilder("/system/bin/sh", "-c", command).start();
            } catch (IOException shellMissing) {
                started = Runtime.getRuntime().exec(command);
            }
            p = started;
        } catch (IOException e) {
            appendDirect("ERROR: " + e.getMessage() + "\n");
            status.setText("起動に失敗しました");
            return;
        }
        process = p;
        running = true;
        updateButtons();
        status.setText("実行中…");

        int seconds = TIMEOUT_SECONDS[Math.max(0, Math.min(TIMEOUT_SECONDS.length - 1, timeoutSpinner.getSelectedItemPosition()))];
        if (seconds > 0) {
            handler.postDelayed(timeoutRunnable, seconds * 1000L);
        }

        final Thread outThread = new Thread(() -> pumpStdout(myRun, p.getInputStream()), "ExecOut");
        final Thread errThread = new Thread(() -> pumpStderr(myRun, p.getErrorStream()), "ExecErr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();
        Thread waiter = new Thread(() -> {
            int code = -1;
            try {
                outThread.join();
                errThread.join();
                code = p.waitFor();
            } catch (InterruptedException ignored) {
            }
            final int exit = code;
            handler.post(() -> finishRun(myRun, exit));
        }, "ExecWait");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void pumpStdout(int myRun, InputStream stream) {
        char[] buffer = new char[4096];
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            int n;
            while ((n = reader.read(buffer)) != -1) {
                if (!enqueue(myRun, new String(buffer, 0, n))) {
                    return;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void pumpStderr(int myRun, InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!enqueue(myRun, "ERROR: " + line + "\n")) {
                    return;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private boolean enqueue(int myRun, String text) {
        synchronized (lock) {
            while (myRun == runId && running && pending.length() > PENDING_LIMIT) {
                try {
                    lock.wait(30);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            if (myRun != runId) {
                return false;
            }
            pending.append(text);
            if (!flushPosted) {
                flushPosted = true;
                handler.postDelayed(flushRunnable, FLUSH_MS);
            }
        }
        return true;
    }

    private void flushNow() {
        String chunk;
        synchronized (lock) {
            chunk = pending.toString();
            pending.setLength(0);
            flushPosted = false;
            lock.notifyAll();
        }
        if (!chunk.isEmpty()) {
            appendDirect(chunk);
        }
    }

    private void appendDirect(String chunk) {
        log.append(chunk);
        if (log.length() > MAX_LOG_CHARS) {
            log.delete(0, log.length() - MAX_LOG_CHARS / 2);
        }
        boolean atBottom = isAtBottom();
        output.append(chunk);
        CharSequence text = output.getText();
        if (text.length() > MAX_VIEW_CHARS && text instanceof Editable) {
            ((Editable) text).delete(0, text.length() - (MAX_VIEW_CHARS * 2 / 3));
        }
        if (atBottom) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private boolean isAtBottom() {
        View child = scroll.getChildAt(0);
        if (child == null) {
            return true;
        }
        return scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - 80;
    }

    private void finishRun(int myRun, int code) {
        if (myRun != runId) {
            return;
        }
        handler.removeCallbacks(timeoutRunnable);
        flushNow();
        if (timedOut) {
            appendDirect("INFO: タイムアウトにより強制終了されました\n");
        } else if (userStopped) {
            appendDirect("INFO: コマンドが強制終了されました\n");
        }
        double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
        appendDirect(String.format(Locale.US, "[終了コード: %d / %.1f秒]\n", code, seconds));
        status.setText(String.format(Locale.US, "終了コード %d (%.1f秒)", code, seconds));
        running = false;
        process = null;
        synchronized (lock) {
            lock.notifyAll();
        }
        updateButtons();
    }

    private void updateButtons() {
        runButton.setEnabled(!running);
        stopButton.setEnabled(running);
    }

    private void killProcess() {
        Process p = process;
        if (p == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.destroyForcibly();
            } else {
                p.destroy();
            }
        } catch (Exception ignored) {
        }
    }

    private void clearOutput() {
        synchronized (lock) {
            pending.setLength(0);
        }
        log.setLength(0);
        output.setText("");
    }

    private void copyOutput() {
        String text = null;
        int start = output.getSelectionStart();
        int end = output.getSelectionEnd();
        if (start >= 0 && end >= 0 && start != end) {
            int a = Math.min(start, end);
            int b = Math.max(start, end);
            text = output.getText().subSequence(a, b).toString();
        }
        if (text == null) {
            text = log.toString();
        }
        if (text.isEmpty()) {
            Toast.makeText(this, "コピーする出力がありません。", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("exec", text));
            Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveLog() {
        if (log.length() == 0) {
            Toast.makeText(this, "保存する出力がありません。", Toast.LENGTH_SHORT).show();
            return;
        }
        final String stamp = LogFileSaver.timestamp();
        final String header = "# exec log " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n";
        final String body = header + log.toString();
        final Context appContext = getApplicationContext();
        Thread t = new Thread(() -> {
            final String path = LogFileSaver.save(appContext, "exec_log_" + stamp + ".txt", body);
            handler.post(() -> Toast.makeText(appContext,
                    path != null ? "保存しました: " + path : "保存に失敗しました",
                    Toast.LENGTH_LONG).show());
        }, "ExecSave");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void onDestroy() {
        runId++;
        running = false;
        handler.removeCallbacksAndMessages(null);
        killProcess();
        process = null;
        synchronized (lock) {
            pending.setLength(0);
            lock.notifyAll();
        }
        super.onDestroy();
    }
}
