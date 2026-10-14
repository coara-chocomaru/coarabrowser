package com.coara.browser;

import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Base64InputStream;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.SafeBrowsingResponseCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewFeature;

import com.coara.browser.util.BasicAuthManager;
import com.coara.browser.util.SafeBrowsingSupport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class pagedl extends AppCompatActivity {
    private static final String TAG = "pagedl";
    private static final long SAVE_TIMEOUT_MS = 90000L;
    private static final long READINESS_TIMEOUT_MS = 6000L;
    private static final int MAX_SCROLL_STEPS = 30;
    private static final int SCROLL_DELAY_MS = 140;
    private static final int READINESS_INTERVAL_MS = 400;
    private static final int REQUESTED_HTML_WAIT_MS = 1200;
    private static final String PREPARE_ARCHIVE_SCRIPT = "(function(){try{var a=document.images;for(var i=0;i<a.length;i++){try{a[i].loading='eager';}catch(e){}}var d=document.documentElement,b=document.body;return Math.max(d?d.scrollHeight:0,b?b.scrollHeight:0);}catch(e){return 0;}})()";
    private static final String READINESS_SCRIPT = "(function(){try{var p=0,a=document.images;for(var i=0;i<a.length;i++){if(!a[i].complete)p++;}var r=window.performance&&performance.getEntriesByType?performance.getEntriesByType('resource').length:0;return (document.readyState==='complete'?1000000000:0)+(Math.min(p,99)*1000000)+Math.min(r,999999);}catch(e){return 0;}})()";

    private EditText urlInput;
    private Switch jsSwitch;
    private Button saveButton;
    private WebView webView;
    private volatile boolean isSaving;
    private boolean basicAuthEnabled;
    private static boolean sWebViewDataDirectoryConfigured;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int saveGeneration;
    private int pageGeneration;
    private int preparedPageGeneration = -1;
    private Runnable saveTimeoutRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !sWebViewDataDirectoryConfigured) {
            try {
                WebView.setDataDirectorySuffix("MainActivity");
            } catch (IllegalStateException ignored) {
            }
            sWebViewDataDirectoryConfigured = true;
        }
        super.onCreate(savedInstanceState);
        SafeBrowsingSupport.initialize(this);
        setContentView(R.layout.activity_pgdl);
        basicAuthEnabled = getIntent().getBooleanExtra(BasicAuthManager.EXTRA_BASIC_AUTH_ENABLED, false);

        urlInput = findViewById(R.id.urlInput);
        jsSwitch = findViewById(R.id.jsSwitch);
        saveButton = findViewById(R.id.saveButton);
        webView = findViewById(R.id.webView);

        configureWebView();
        saveButton.setOnClickListener(v -> handleSaveButtonClick());
    }

    private void configureWebView() {
        SafeBrowsingSupport.recordSafePage(webView, "about:blank");
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(jsSwitch.isChecked());
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        jsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> settings.setJavaScriptEnabled(isChecked));
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                invalidateCurrentCapture();
                SafeBrowsingSupport.clear(view);
                ViewGroup parent = view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
                if (parent != null) {
                    parent.removeView(view);
                }
                try {
                    view.stopLoading();
                    view.destroy();
                } catch (Exception ignored) {
                }
                if (view == webView) {
                    webView = null;
                }
                saveButton.setEnabled(false);
                Toast.makeText(pagedl.this, "ページ表示プロセスが終了しました。この画面を開き直してください。", Toast.LENGTH_LONG).show();
                return true;
            }

            @Override
            public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponseCompat callback) {
                SafeBrowsingSupport.handle(pagedl.this, view, request, threatType, callback);
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler sslHandler, android.net.http.SslError error) {
                SafeBrowsingSupport.handleSslError(pagedl.this, view, sslHandler, error);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                SafeBrowsingSupport.recordNavigation(view, url);
                if (isSaving) {
                    pageGeneration++;
                    preparedPageGeneration = -1;
                }
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, android.webkit.HttpAuthHandler authHandler, String host, String realm) {
                BasicAuthManager.handleHttpAuthRequest(pagedl.this, view, authHandler, basicAuthEnabled, host, realm);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceErrorCompat error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame() && isSaving) {
                    String description = error == null ? "ページを読み込めませんでした" : String.valueOf(error.getDescription());
                    failSaving("ページ読み込みエラー: " + description);
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && isSaving && sameUrl(view.getUrl(), failingUrl)) {
                    failSaving("ページ読み込みエラー: " + description);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!isSaving || view != webView || !sameUrl(view.getUrl(), url)) {
                    return;
                }
                final int token = saveGeneration;
                final int pageToken = pageGeneration;
                if (preparedPageGeneration == pageToken) {
                    return;
                }
                preparedPageGeneration = pageToken;
                prepareArchiveCapture(token, pageToken);
            }
        });
    }

    private void handleSaveButtonClick() {
        if (isSaving) {
            Toast.makeText(this, "保存処理中です", Toast.LENGTH_SHORT).show();
            return;
        }
        if (webView == null) {
            Toast.makeText(this, "WebViewを再初期化できません。画面を開き直してください。", Toast.LENGTH_LONG).show();
            return;
        }
        String input = urlInput.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show();
            return;
        }
        final String url = normalizeInputUrl(input);
        if (url == null) {
            Toast.makeText(this, "http://、https://、または data: のURLを入力してください", Toast.LENGTH_LONG).show();
            return;
        }
        if (url.regionMatches(true, 0, "data:", 0, 5) && !isHtmlDataUrl(url)) {
            final int token = beginSavingState();
            executor.execute(() -> saveDataUrl(url, token));
            return;
        }
        beginSavingState();
        webView.setVisibility(View.VISIBLE);
        webView.getSettings().setJavaScriptEnabled(jsSwitch.isChecked());
        try {
            webView.loadUrl(url);
        } catch (Exception e) {
            Log.e(TAG, "ページ読み込み開始エラー", e);
            failSaving("ページを開けません: " + e.getMessage());
        }
    }

    private String normalizeInputUrl(String input) {
        String value = input.trim();
        if (value.regionMatches(true, 0, "data:", 0, 5)) {
            return value.indexOf(',') > 4 ? value : null;
        }
        if (!value.matches("(?i)^https?://.*")) {
            if (value.matches("(?i)^[a-z][a-z0-9+.-]*:.*")
                    && !value.matches("(?i)^[^/:\\s]+:\\d+(?:/.*)?$")) {
                return null;
            }
            value = "https://" + value;
        }
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
            return null;
        }
        return value;
    }

    private boolean isHtmlDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            return false;
        }
        String header = dataUrl.substring(0, comma).toLowerCase(Locale.ROOT);
        return header.startsWith("data:text/html") || header.startsWith("data:application/xhtml+xml");
    }

    private int beginSavingState() {
        isSaving = true;
        saveGeneration++;
        pageGeneration = 0;
        preparedPageGeneration = -1;
        handler.removeCallbacksAndMessages(null);
        saveButton.setEnabled(false);
        saveButton.setText("保存中...");
        jsSwitch.setEnabled(false);
        saveTimeoutRunnable = () -> {
            if (isSaving) {
                failSaving("保存処理がタイムアウトしました。ページが重い場合は時間をおいて再試行してください。");
            }
        };
        handler.postDelayed(saveTimeoutRunnable, SAVE_TIMEOUT_MS);
        return saveGeneration;
    }

    private void prepareArchiveCapture(int token, int pageToken) {
        if (!isCurrentCapture(token, pageToken)) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT || !jsSwitch.isChecked()) {
            handler.postDelayed(() -> saveWebArchive(token, pageToken), REQUESTED_HTML_WAIT_MS);
            return;
        }
        webView.evaluateJavascript(PREPARE_ARCHIVE_SCRIPT, value -> {
            if (!isCurrentCapture(token, pageToken)) {
                return;
            }
            int height = parseJsInteger(value, webView.getHeight());
            int viewport = Math.max(1, webView.getHeight());
            if (height > viewport + 100) {
                scrollPageForArchive(token, pageToken, 0, 0, 0);
            } else {
                handler.postDelayed(() -> beginReadinessChecks(token, pageToken), 350L);
            }
        });
    }

    private void scrollPageForArchive(int token, int pageToken, int y, int steps, int bottomStreak) {
        if (!isCurrentCapture(token, pageToken)) {
            return;
        }
        if (steps >= MAX_SCROLL_STEPS) {
            finishLazyLoadPass(token, pageToken);
            return;
        }
        int viewport = Math.max(1, webView.getHeight());
        String script = "(function(){window.scrollTo(0," + y + ");var d=document.documentElement,b=document.body;return Math.max(d?d.scrollHeight:0,b?b.scrollHeight:0);})()";
        webView.evaluateJavascript(script, value -> {
            if (!isCurrentCapture(token, pageToken)) {
                return;
            }
            int height = parseJsInteger(value, y + viewport);
            int maxY = Math.max(0, height - viewport);
            int nextBottomStreak = y >= maxY ? bottomStreak + 1 : 0;
            if (steps + 1 >= MAX_SCROLL_STEPS || nextBottomStreak >= 2) {
                finishLazyLoadPass(token, pageToken);
                return;
            }
            int nextY = Math.min(maxY, y + Math.max(1, (int) (viewport * 0.8f)));
            if (nextY == y && nextBottomStreak > 0) {
                finishLazyLoadPass(token, pageToken);
                return;
            }
            handler.postDelayed(() -> scrollPageForArchive(token, pageToken, nextY, steps + 1, nextBottomStreak), SCROLL_DELAY_MS);
        });
    }

    private void finishLazyLoadPass(int token, int pageToken) {
        if (!isCurrentCapture(token, pageToken)) {
            return;
        }
        webView.evaluateJavascript("window.scrollTo(0,0);void(0)", null);
        handler.postDelayed(() -> beginReadinessChecks(token, pageToken), 350L);
    }

    private void beginReadinessChecks(int token, int pageToken) {
        checkArchiveReadiness(token, pageToken, SystemClock.elapsedRealtime(), -1, 0);
    }

    private void checkArchiveReadiness(int token, int pageToken, long startedAt, int previousResources, int stableCount) {
        if (!isCurrentCapture(token, pageToken)) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT || !jsSwitch.isChecked()) {
            saveWebArchive(token, pageToken);
            return;
        }
        webView.evaluateJavascript(READINESS_SCRIPT, value -> {
            if (!isCurrentCapture(token, pageToken)) {
                return;
            }
            long metric = parseJsLong(value, -1L);
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            if (metric < 0) {
                if (elapsed >= READINESS_TIMEOUT_MS) {
                    saveWebArchive(token, pageToken);
                } else {
                    handler.postDelayed(() -> checkArchiveReadiness(token, pageToken, startedAt, previousResources, stableCount), READINESS_INTERVAL_MS);
                }
                return;
            }
            boolean ready = metric >= 1000000000L;
            long rest = metric % 1000000000L;
            long pendingImages = rest / 1000000L;
            int resources = (int) (rest % 1000000L);
            int nextStableCount = resources == previousResources ? stableCount + 1 : 0;
            if ((ready && pendingImages == 0 && nextStableCount >= 2) || elapsed >= READINESS_TIMEOUT_MS) {
                saveWebArchive(token, pageToken);
            } else {
                handler.postDelayed(() -> checkArchiveReadiness(token, pageToken, startedAt, resources, nextStableCount), READINESS_INTERVAL_MS);
            }
        });
    }

    private void saveWebArchive(int token, int pageToken) {
        if (!isCurrentCapture(token, pageToken) || webView == null) {
            return;
        }
        String finalUrl = webView.getUrl() == null ? urlInput.getText().toString().trim() : webView.getUrl();
        String siteName = siteNameForUrl(finalUrl);
        String title = webView.getTitle();
        if (title == null || title.trim().isEmpty()) {
            title = "untitled";
        }
        File outputDir = getOutputDirectory(siteName, title);
        if (outputDir == null) {
            failSaving("保存先フォルダーを作成できませんでした");
            return;
        }
        File archiveFile = new File(outputDir, "page_archive.mht");
        try {
            if (archiveFile.exists() && !archiveFile.delete()) {
                failSaving("既存のMHTファイルを置き換えられませんでした");
                return;
            }
            webView.saveWebArchive(archiveFile.getAbsolutePath(), false, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (!isCurrentCapture(token, pageToken)) {
                        return;
                    }
                    File savedFile = value == null ? null : new File(value);
                    if (savedFile == null || !savedFile.isFile() || savedFile.length() <= 0L) {
                        savedFile = archiveFile.isFile() && archiveFile.length() > 0L ? archiveFile : null;
                    }
                    if (savedFile == null) {
                        failSaving("MHT保存に失敗しました。ページのリソース取得状況を確認してください。");
                        return;
                    }
                    finishSaving("MHT保存完了:\n" + savedFile.getAbsolutePath());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Web Archive 保存エラー", e);
            failSaving("MHT保存エラー: " + e.getMessage());
        }
    }

    private boolean isCurrentSave(int token) {
        return isSaving && token == saveGeneration;
    }

    private boolean isCurrentCapture(int token, int pageToken) {
        return isSaving && token == saveGeneration && pageToken == pageGeneration && webView != null;
    }

    private int parseJsInteger(String value, int fallback) {
        long parsed = parseJsLong(value, fallback);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            return fallback;
        }
        return (int) parsed;
    }

    private long parseJsLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            return (long) Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String siteNameForUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                return safeFileName(host, "page");
            }
        } catch (Exception ignored) {
        }
        return "page";
    }

    private String safeFileName(String input, String fallback) {
        String value = input == null ? "" : input.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", "_");
        value = value.replaceAll("^[. _]+|[. _]+$", "");
        if (value.isEmpty()) {
            value = fallback;
        }
        if (value.length() > 80) {
            value = value.substring(0, 80);
        }
        return value;
    }

    private File getOutputDirectory(String siteName, String pageTitle) {
        File base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) {
            base = new File(getFilesDir(), "documents");
        }
        File root = new File(base, "page");
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date());
        String folderName = safeFileName(siteName, "page") + "(" + safeFileName(pageTitle, "untitled") + ")_" + timestamp;
        File output = new File(root, folderName);
        if (!output.exists() && !output.mkdirs()) {
            Log.e(TAG, "フォルダー作成失敗: " + output.getAbsolutePath());
            return null;
        }
        if (!output.isDirectory()) {
            return null;
        }
        return output;
    }

    private void saveDataUrl(String dataUrl, int token) {
        File output = null;
        try {
            if (!isCurrentSave(token)) {
                return;
            }
            int comma = dataUrl.indexOf(',');
            if (comma < 0) {
                throw new IOException("data URLの形式が不正です");
            }
            String metadata = dataUrl.substring(5, comma);
            String payload = dataUrl.substring(comma + 1);
            String[] metadataParts = metadata.split(";");
            String mimeType = metadataParts.length > 0 && metadataParts[0].contains("/")
                    ? metadataParts[0].toLowerCase(Locale.ROOT)
                    : "text/plain";
            boolean base64 = false;
            for (String part : metadataParts) {
                if ("base64".equalsIgnoreCase(part.trim())) {
                    base64 = true;
                    break;
                }
            }
            File outputDir = getOutputDirectory("data_url", mimeType);
            if (outputDir == null) {
                throw new IOException("保存先フォルダーを作成できませんでした");
            }
            output = new File(outputDir, "data_content" + extensionForMimeType(mimeType));
            if (base64) {
                try (InputStream input = new Base64InputStream(new StringAsciiInputStream(payload), Base64.DEFAULT);
                     FileOutputStream stream = new FileOutputStream(output)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (!isCurrentSave(token)) {
                            throw new IOException("保存処理は終了しました");
                        }
                        stream.write(buffer, 0, count);
                    }
                    stream.flush();
                }
            } else {
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    writePercentDecodedPayload(payload, stream, token);
                    stream.flush();
                }
            }
            if (isCurrentSave(token)) {
                finishSaving("Data URL保存完了:\n" + output.getAbsolutePath());
            }
        } catch (Exception e) {
            if (!isCurrentSave(token)) {
                if (output != null && output.isFile()) {
                    output.delete();
                }
                return;
            }
            Log.e(TAG, "Data URL保存エラー", e);
            failSaving("Data URL保存エラー: " + e.getMessage());
        }
    }

    private void writePercentDecodedPayload(String payload, FileOutputStream output, int token) throws IOException {
        int index = 0;
        while (index < payload.length()) {
            if ((index & 0x3fff) == 0 && !isCurrentSave(token)) {
                throw new IOException("保存処理は終了しました");
            }
            char current = payload.charAt(index);
            if (current == '%' && index + 2 < payload.length()) {
                int high = Character.digit(payload.charAt(index + 1), 16);
                int low = Character.digit(payload.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    output.write((high << 4) | low);
                    index += 3;
                    continue;
                }
            }
            int codePoint = payload.codePointAt(index);
            if (codePoint <= 0x7f) {
                output.write(codePoint);
            } else if (codePoint <= 0x7ff) {
                output.write(0xc0 | (codePoint >> 6));
                output.write(0x80 | (codePoint & 0x3f));
            } else if (codePoint <= 0xffff) {
                output.write(0xe0 | (codePoint >> 12));
                output.write(0x80 | ((codePoint >> 6) & 0x3f));
                output.write(0x80 | (codePoint & 0x3f));
            } else {
                output.write(0xf0 | (codePoint >> 18));
                output.write(0x80 | ((codePoint >> 12) & 0x3f));
                output.write(0x80 | ((codePoint >> 6) & 0x3f));
                output.write(0x80 | (codePoint & 0x3f));
            }
            index += Character.charCount(codePoint);
        }
    }

    private static final class StringAsciiInputStream extends InputStream {
        private final String source;
        private int position;

        StringAsciiInputStream(String source) {
            this.source = source;
        }

        @Override
        public int read() {
            if (position >= source.length()) {
                return -1;
            }
            return source.charAt(position++) & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            if (position >= source.length()) {
                return -1;
            }
            int count = Math.min(length, source.length() - position);
            for (int i = 0; i < count; i++) {
                buffer[offset + i] = (byte) source.charAt(position++);
            }
            return count;
        }
    }

    private String extensionForMimeType(String mimeType) {
        if (mimeType == null) {
            return ".bin";
        }
        String type = mimeType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        switch (type) {
            case "text/html": return ".html";
            case "application/xhtml+xml": return ".xhtml";
            case "text/plain": return ".txt";
            case "text/css": return ".css";
            case "text/javascript":
            case "application/javascript": return ".js";
            case "application/json": return ".json";
            case "application/xml":
            case "text/xml": return ".xml";
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "image/gif": return ".gif";
            case "image/webp": return ".webp";
            case "image/svg+xml": return ".svg";
            case "application/pdf": return ".pdf";
            case "application/zip": return ".zip";
            case "audio/mpeg": return ".mp3";
            case "video/mp4": return ".mp4";
            default:
                int slash = type.indexOf('/');
                if (slash >= 0 && slash + 1 < type.length()) {
                    String subtype = type.substring(slash + 1).replaceAll("[^a-z0-9.+-]", "");
                    if (!subtype.isEmpty() && subtype.length() <= 12) {
                        return "." + subtype;
                    }
                }
                return ".bin";
        }
    }

    private boolean sameUrl(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return first.equals(second);
    }

    private void failSaving(String message) {
        finishSaving(message);
    }

    private void finishSaving(String message) {
        runOnUiThread(() -> {
            if (saveTimeoutRunnable != null) {
                handler.removeCallbacks(saveTimeoutRunnable);
                saveTimeoutRunnable = null;
            }
            isSaving = false;
            saveButton.setEnabled(webView != null);
            saveButton.setText("MHT保存開始");
            jsSwitch.setEnabled(true);
            Toast.makeText(pagedl.this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void invalidateCurrentCapture() {
        if (saveTimeoutRunnable != null) {
            handler.removeCallbacks(saveTimeoutRunnable);
            saveTimeoutRunnable = null;
        }
        isSaving = false;
        saveGeneration++;
    }

    @Override
    public void onBackPressed() {
        if (isSaving) {
            Toast.makeText(this, "保存中です。完了またはタイムアウト後に戻ってください。", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        invalidateCurrentCapture();
        BasicAuthManager.cancelPendingForActivity(pagedl.this);
        handler.removeCallbacksAndMessages(null);
        WebView current = webView;
        if (current != null) {
            SafeBrowsingSupport.clear(current);
            View parentView = current.getParent() instanceof View ? (View) current.getParent() : null;
            if (parentView instanceof ViewGroup) {
                ((ViewGroup) parentView).removeView(current);
            }
            try {
                current.stopLoading();
                current.destroy();
            } catch (Exception ignored) {
            }
            webView = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}
