package com.coara.browser;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.InputType;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebViewDatabase;
import android.webkit.HttpAuthHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebBackForwardList;
import android.webkit.WebResourceRequest;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.SafeBrowsingResponseCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.coara.browser.util.BasicAuthManager;
import com.coara.browser.util.CookieSitePolicy;
import com.coara.browser.util.ExternalDownloadTabTracker;
import com.coara.browser.util.DownloadSupport;
import com.coara.browser.util.DownloadFallbackManager;
import com.coara.browser.util.BrowserConstants;
import com.coara.browser.util.AdBlockEngine;
import com.coara.browser.util.PageTranslator;
import com.coara.browser.util.TranslateSettingsDialog;
import com.coara.browser.util.BrowserUrlRouter;
import com.coara.browser.util.BrowserAssetLoader;
import com.coara.browser.util.BookmarkStore;
import com.coara.browser.webview.WebViewOptimizationUtils;
import com.coara.browser.webview.KeyboardGameWebView;
import com.coara.browser.webview.BlobDownloadBridge;
import com.coara.browser.webview.BridgeGuard;
import com.coara.browser.webview.NativeMessageBridge;
import com.coara.browser.util.UiThread;
import com.coara.browser.util.HistoryDialog;
import com.coara.browser.model.HistoryItem;
import com.coara.browser.plugin.PluginHost;
import com.coara.browser.plugin.PluginManagerActivity;
import com.coara.browser.plugin.PluginManager;
import com.coara.browser.plugin.PluginRuntime;
import com.coara.browser.util.SafeBrowsingSupport;
import com.coara.browser.util.FindInPageController;
import com.coara.browser.util.KeyboardSettings;
import com.coara.browser.util.NetworkStateGuard;
import com.coara.browser.util.TouchpadController;
import com.coara.browser.util.TouchpadOverlayController;
import com.coara.browser.util.TouchpadSettings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@SuppressWarnings("deprecation")
public class SecretActivity extends AppCompatActivity implements PluginHost, PluginManager.Listener {
    private static final String PREF_NAME = "SecretBrowserPrefs";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_BASIC_AUTH = "basic_auth";
    private static final String KEY_ZOOM_ENABLED = "zoom_enabled";
    private static final String KEY_JS_ENABLED = "js_enabled";
    private static final String KEY_IMG_BLOCK_ENABLED = "img_block_enabled";
    private static final String KEY_UA_ENABLED = "ua_enabled";
    private static final String KEY_DESKUA_ENABLED = "deskua_enabled";
    private static final String KEY_CT3UA_ENABLED = "ct3ua_enabled";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String START_PAGE = BrowserConstants.APP_ASSET_SECRET_PAGE;
    private static final int FILE_SELECT_CODE = 1001;
    private static final int MAX_HISTORY_SIZE = 100;
    private static Method sSetSaveFormDataMethod;
    private static Method sSetDatabaseEnabledMethod;
    private static Method sSetAppCacheEnabledMethod;
    private static Method sSetAppCachePathMethod;
    private static boolean sWebViewDataDirectoryConfigured = false;
    private PermissionRequest pendingPermissionRequest = null;
    private WebView pendingPermissionWebView = null;
    private String pendingPermissionOrigin = "";
    private final java.util.Set<String> approvedCaptureOrigins = new java.util.HashSet<>();
    private ActivityResultLauncher<String[]> permissionRequestLauncher;

    private WebView webView;
    private BrowserAssetLoader browserAssetLoader;
    private TextInputEditText urlEditText;
    private ImageView faviconImageView;
    private MaterialCardView urlContainer;
    private Drawable urlEditSavedIcon;
    private boolean urlEditMode;
    private int urlEditOriginalStrokeColor;
    private int urlEditOriginalStrokeWidth;
    private MaterialButton btnGo;
    private MaterialToolbar toolbar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private FrameLayout webViewContainer;
    private ImageView copyButton;
    private ImageView clearButton;
    private ImageView urlSecurityIcon;
    private android.widget.ProgressBar pageProgressBar;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<String> permissionLauncher;
    private SharedPreferences pref;
    private final ExecutorService backgroundExecutor = UiThread.newPool(2);
    private final List<Bookmark> bookmarks = new ArrayList<>();
    private final List<HistoryItem> historyItems = new ArrayList<>();

    private boolean darkModeEnabled = false;
    private boolean basicAuthEnabled = false;
    private boolean zoomEnabled = true;
    private boolean jsEnabled = true;
    private boolean imgBlockEnabled = false;
    private boolean uaEnabled = false;
    private boolean deskuaEnabled = false;
    private boolean ct3uaEnabled = false;

    private LruCache<String, Bitmap> faviconCache;
    private final java.util.Set<WebView> loadedPages = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<WebView, Boolean>());
    private final Map<WebView, String> originalUserAgents = new HashMap<>();
    private final Map<WebView, BlobDownloadBridge> blobDownloadBridges = new HashMap<>();
    private final Map<WebView, Runnable> pendingSpaHistoryTasks = new HashMap<>();
    private boolean defaultLoadsImagesAutomatically;
    private boolean defaultLoadsImagesAutomaticallyInitialized = false;
    private View customView = null;
    private WebChromeClient.CustomViewCallback customViewCallback = null;
    private boolean pluginReloadPending = false;
    private boolean pluginReloadRunning = false;
    private int lastPluginRuntimeVersion = -1;
    private PluginManager pluginManager;
    static {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        try {
            sSetSaveFormDataMethod = WebSettings.class.getMethod("setSaveFormData", boolean.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            sSetDatabaseEnabledMethod = WebSettings.class.getMethod("setDatabaseEnabled", boolean.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            sSetAppCacheEnabledMethod = WebSettings.class.getMethod("setAppCacheEnabled", boolean.class);
            sSetAppCachePathMethod = WebSettings.class.getMethod("setAppCachePath", String.class);
        } catch (Exception e) {
            e.printStackTrace();
           }
        }
     }
    public static class Bookmark {
        private final String title;
        private final String url;
        public Bookmark(String title, String url) {
            this.title = title;
            this.url = url;
        }
        public String getTitle() { return title; }
        public String getUrl() { return url; }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        browserAssetLoader = new BrowserAssetLoader(this);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !sWebViewDataDirectoryConfigured) {
        try {
            WebView.setDataDirectorySuffix("SecretActivity");
        } catch (IllegalStateException ignored) {
        }
        sWebViewDataDirectoryConfigured = true;
    }
    super.onCreate(savedInstanceState);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    SafeBrowsingSupport.initialize(this);
    setContentView(R.layout.secret_main);

    toolbar = findViewById(R.id.topAppBar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
        getSupportActionBar().setTitle("");
    }

        pref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        pluginManager = PluginManager.get(this);
        pluginManager.addListener(this);
        lastPluginRuntimeVersion = pluginManager.runtimeVersion();
        darkModeEnabled = pref.getBoolean(KEY_DARK_MODE, false);
        basicAuthEnabled = pref.getBoolean(KEY_BASIC_AUTH, false);
        zoomEnabled = pref.getBoolean(KEY_ZOOM_ENABLED, true);
        jsEnabled = pref.getBoolean(KEY_JS_ENABLED, true);
        imgBlockEnabled = pref.getBoolean(KEY_IMG_BLOCK_ENABLED, false);
        uaEnabled = pref.getBoolean(KEY_UA_ENABLED, false);
        deskuaEnabled = pref.getBoolean(KEY_DESKUA_ENABLED, false);
        ct3uaEnabled = pref.getBoolean(KEY_CT3UA_ENABLED, false);
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = Math.max(512, Math.min(maxMemory / 32, 2048));
        faviconCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
        WebStorage.getInstance().deleteAllData();
        try {
            WebViewDatabase.getInstance(this).clearFormData();
        } catch (Exception ignored) {
        }
        loadBookmarks();
        historyItems.clear();
        if (pref != null) {
            pref.edit().remove(KEY_HISTORY).apply();
        }

        urlEditText = findViewById(R.id.urlEditText);
        urlContainer = findViewById(R.id.urlContainer);
        urlEditOriginalStrokeColor = urlContainer.getStrokeColor();
        urlEditOriginalStrokeWidth = urlContainer.getStrokeWidth();
        urlEditText.setImeOptions(EditorInfo.IME_ACTION_GO);
        KeyboardSettings.applyToEditor(urlEditText, pref);
        urlEditText.setImeOptions(urlEditText.getImeOptions() | 0x01000000);
        urlEditText.setInputType(urlEditText.getInputType() | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlEditText.setPrivateImeOptions("nm");
        faviconImageView = findViewById(R.id.favicon);
        btnGo = findViewById(R.id.btnGo);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        webViewContainer = findViewById(R.id.webViewContainer);

        webView = createNewWebView();
        webViewContainer.addView(webView);
        webView.loadUrl(START_PAGE);
        boolean shouldClear = getIntent()
            .getBooleanExtra(MainActivity.EXTRA_CLEAR_HISTORY, false);
        if (shouldClear) {
            clear0();
       }

        if (!defaultLoadsImagesAutomaticallyInitialized && webView != null) {
            defaultLoadsImagesAutomatically = webView.getSettings().getLoadsImagesAutomatically();
            defaultLoadsImagesAutomaticallyInitialized = true;
        }
        btnGo.setVisibility(View.GONE);

        copyButton = new ImageView(this);
        copyButton.setImageResource(R.drawable.ic_copy);
        copyButton.setAlpha(0.5f);
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString().trim();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("URL", url);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(SecretActivity.this, "URLをコピーしました", Toast.LENGTH_SHORT).show();
        });
        ViewGroup parentView = (ViewGroup) urlEditText.getParent();
        urlSecurityIcon = findViewById(R.id.urlSecurityIcon);
        pageProgressBar = findViewById(R.id.pageProgressBar);
        int buttonSize = Math.round(36 * getResources().getDisplayMetrics().density);
        int buttonPad = Math.round(8 * getResources().getDisplayMetrics().density);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        copyButton.setPadding(buttonPad, buttonPad, buttonPad, buttonPad);
        copyButton.setBackgroundResource(ripple.resourceId);
        copyButton.setContentDescription("URLをコピー");
        copyButton.setLayoutParams(new LinearLayout.LayoutParams(buttonSize, buttonSize));
        clearButton = new ImageView(this);
        clearButton.setImageResource(R.drawable.ic_close);
        clearButton.setAlpha(0.6f);
        clearButton.setPadding(buttonPad, buttonPad, buttonPad, buttonPad);
        clearButton.setBackgroundResource(ripple.resourceId);
        clearButton.setContentDescription("入力を消去");
        clearButton.setVisibility(View.GONE);
        clearButton.setLayoutParams(new LinearLayout.LayoutParams(buttonSize, buttonSize));
        clearButton.setOnClickListener(v -> {
            urlEditText.setText("");
            urlEditText.requestFocus();
        });
        parentView.addView(clearButton);
        parentView.addView(copyButton);
        urlEditText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateClearButton();
            }
        });
        View.OnLongClickListener urlBarLongClick = v -> {
            if (urlEditMode) {
                return false;
            }
            showUrlBarMenu(v);
            return true;
        };
        urlContainer.setOnLongClickListener(urlBarLongClick);
        urlEditText.setOnLongClickListener(urlBarLongClick);

        urlEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                enterUrlEditMode();
            } else {
                exitUrlEditMode();
            }
        });
        urlContainer.setOnClickListener(v -> {
            if (!urlEditText.hasFocus()) {
                urlEditText.requestFocus();
                enterUrlEditMode();
            } else if (!urlEditMode) {
                enterUrlEditMode();
            } else {
                urlEditText.setSelection(urlEditText.length());
            }
        });
        urlEditText.setOnClickListener(v -> {
            if (!urlEditMode) {
                enterUrlEditMode();
            }
        });
        faviconImageView.setOnClickListener(v -> {
            if (urlEditMode) {
                exitUrlEditMode();
            }
        });

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setEnabled(false);
            swipeRefreshLayout.setOnChildScrollUpCallback((parent1, child) -> true);
            swipeRefreshLayout.setOnRefreshListener(() -> { });
        }
            
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted) {
                        Toast.makeText(this, "ストレージ権限が必要です。アプリを終了します", Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        permissionRequestLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestMultiplePermissions(),
        result -> {
            PermissionRequest request = pendingPermissionRequest;
            WebView requestedWebView = pendingPermissionWebView;
            String requestedOrigin = pendingPermissionOrigin;
            pendingPermissionRequest = null;
            pendingPermissionWebView = null;
            pendingPermissionOrigin = "";
            if (request == null) return;
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (!Boolean.TRUE.equals(granted)) {
                    allGranted = false;
                    break;
                }
            }
            boolean currentOrigin = requestedWebView != null
                    && requestedWebView == getCurrentWebView()
                    && BridgeGuard.isSecureCaptureOrigin(requestedOrigin)
                    && BridgeGuard.sameOrigin(requestedOrigin, requestedWebView.getUrl())
                    && request.getOrigin() != null
                    && BridgeGuard.sameOrigin(requestedOrigin, request.getOrigin().toString());
            try {
                if (allGranted && currentOrigin) {
                    List<String> grantable = getGrantableCaptureResources(request);
                    if (grantable.isEmpty()) {
                        request.deny();
                    } else {
                        request.grant(grantable.toArray(new String[0]));
                        approvedCaptureOrigins.add(requestedOrigin);
                    }
                } else {
                    request.deny();
                    if (!allGranted) {
                        approvedCaptureOrigins.remove(requestedOrigin);
                    }
                }
            } catch (Exception e) {
                try { request.deny(); } catch (Exception ignored) {}
            }
        }
    );
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri dataUri = result.getData().getData();
                        if (filePathCallback != null) {
                            filePathCallback.onReceiveValue(dataUri != null ? new Uri[]{dataUri} : null);
                        }
                    } else if (filePathCallback != null) {
                        filePathCallback.onReceiveValue(null);
                    }
                    filePathCallback = null;
                });

        urlEditText.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
               (keyEvent != null && keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                keyEvent.getAction() == KeyEvent.ACTION_DOWN)) {
                loadUrl();
                exitUrlEditMode();
                return true;
            }
            return false;
        });

        handleIntent(getIntent());
        urlEditText.clearFocus();
        webViewContainer.setFocusableInTouchMode(true);
        webViewContainer.requestFocus();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
        if (findController != null && findController.isShowing()) {
            findController.hide();
            return;
        }
        if (urlEditMode || (urlEditText != null && urlEditText.hasFocus() && !KeyboardSettings.isAlwaysVisible(pref))) {
            exitUrlEditMode();
            return;
        }
        if (customView != null) {
            exitFullScreen();
            return;
        }
        WebView current = getCurrentWebView();
        if (current != null && current.canGoBack()) {
            current.goBack();
            return;
        }
        Toast.makeText(SecretActivity.this, "履歴がありません", Toast.LENGTH_SHORT).show();
         }
       });
    }

    private void clear0() {
        WebView webView = getCurrentWebView();
        if (webView != null) {
            webView.clearHistory();
            webView.clearCache(true);
            webView.clearFormData();
            webView.clearSslPreferences();
            webView.clearMatches();
            clearTabs();
        }
        historyItems.clear();
        if (pref != null) {
            pref.edit().remove(KEY_HISTORY).apply();
        }
        WebStorage.getInstance().deleteAllData();
        WebViewDatabase.getInstance(this).clearFormData();
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
     }


    private final Runnable reloadOpenTabsForPluginsRunnable = new Runnable() {
        @Override
        public void run() {
            reloadOpenTabsForPlugins();
        }
    };

    private void schedulePluginReload() {
        pluginReloadPending = true;
        UiThread.mainHandler().removeCallbacks(reloadOpenTabsForPluginsRunnable);
        UiThread.mainHandler().postDelayed(reloadOpenTabsForPluginsRunnable, 150L);
    }

    private void reloadOpenTabsForPlugins() {
        if (!pluginReloadPending || pluginReloadRunning || isFinishing() || isDestroyed() || pluginManager == null) {
            return;
        }
        pluginReloadRunning = true;
        try {
            pluginManager.reloadIfChanged();
            WebView current = getCurrentWebView();
            if (current != null) {
                String url = null;
                try {
                    url = current.getUrl();
                } catch (Exception ignored) {
                }
                if (url != null && !url.isEmpty()) {
                    PluginRuntime.prepareForFullReload(current);
                    current.stopLoading();
                    current.reload();
                }
            }
            lastPluginRuntimeVersion = pluginManager.runtimeVersion();
            pluginReloadPending = false;
            UiThread.mainHandler().removeCallbacks(reloadOpenTabsForPluginsRunnable);
        } finally {
            pluginReloadRunning = false;
        }
    }

    @Override
    public void onPluginsChanged() {
    }

    @Override
    public void onPluginAutoDisabled(com.coara.browser.plugin.PluginInfo info, String reason) {
    }

    @Override
    public void onPluginRuntimeChanged() {
        pluginReloadPending = true;
        if (hasWindowFocus()) {
            schedulePluginReload();
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) {
                String url = data.toString();
                if (BrowserUrlRouter.isWebUrl(url)) {
                    historyItems.clear();
                    if (pref != null) {
                        pref.edit().remove(KEY_HISTORY).apply();
                    }
                    WebStorage.getInstance().deleteAllData();
                    CookieManager cookieManager = CookieManager.getInstance();
                    cookieManager.removeAllCookies(null);
                    cookieManager.flush();
                    createNewTab(url);
                    WebView current = getCurrentWebView();
                    if (current != null) {
                        ExternalDownloadTabTracker.markExternal(current, url);
                    }
                } else {
                    BrowserUrlRouter.handleUrlLoading(this, getCurrentWebView(), url);
                }
            }
            setIntent(new Intent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && faviconCache != null) {
            try {
                faviconCache.trimToSize(Math.max(1, faviconCache.maxSize() / 2));
            } catch (Exception ignored) {
            }
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && faviconCache != null) {
            try {
                faviconCache.evictAll();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        if (networkStateGuard == null) {
            networkStateGuard = new NetworkStateGuard(this, () -> java.util.Collections.singletonList(getCurrentWebView()));
        }
        networkStateGuard.start();
        super.onResume();
        WebView activeWebView = getCurrentWebView();
        if (activeWebView != null) {
            applyRendererPriority(activeWebView, true);
            try {
                activeWebView.resumeTimers();
                activeWebView.onResume();
            } catch (Exception ignored) {
            }
        }
        TouchpadController.setCurrentWebView(getCurrentWebView());
        if (urlEditMode) {
            KeyboardSettings.onResume(this, urlEditText, pref);
        } else {
            KeyboardSettings.onResumeWebView(this, getCurrentWebView(), pref);
        }
        if (pluginManager != null) {
            pluginManager.reloadIfChanged();
            int runtimeVersion = pluginManager.runtimeVersion();
            if (runtimeVersion != lastPluginRuntimeVersion) {
                lastPluginRuntimeVersion = runtimeVersion;
                pluginReloadPending = true;
            }
        }
        if (pluginReloadPending) {
            schedulePluginReload();
        }
        TouchpadOverlayController.onHostResume(this, pref);
            invalidateOptionsMenu();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (pluginReloadPending) {
                schedulePluginReload();
            }
            if (KeyboardSettings.isAlwaysVisible(pref) && !urlEditMode) {
                KeyboardSettings.showKeyboardForWebView(this, getCurrentWebView(), pref);
            }
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        TouchpadOverlayController.onConfigurationChanged(this, pref);
    }

    @Override
    protected void onPause() {
        if (networkStateGuard != null) {
            networkStateGuard.stop();
        }
        TouchpadOverlayController.onHostPause(this);
        PluginRuntime.onHostPause(this);
        WebView activeWebView = getCurrentWebView();
        if (activeWebView != null) {
            applyRendererPriority(activeWebView, false);
            try {
                activeWebView.onPause();
                activeWebView.pauseTimers();
            } catch (Exception ignored) {
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        PageTranslator.release();
        if (findController != null) {
            findController.release();
        }
        TouchpadOverlayController.hide(this);
        BasicAuthManager.cancelPendingForActivity(SecretActivity.this);
        UiThread.mainHandler().removeCallbacks(reloadOpenTabsForPluginsRunnable);
        if (pluginManager != null) {
            pluginManager.removeListener(this);
        }
        pendingSpaHistoryTasks.clear();
        WebView current = webView;
        if (current != null) {
            TouchpadController.clearCurrentWebView(current);
            webViewContainer.removeView(current);
            releaseBlobBridge(current);
            SafeBrowsingSupport.clear(current);
            try {
                current.stopLoading();
                current.destroy();
            } catch (Exception ignored) {
            }
            webView = null;
        }
        webViewContainer.removeAllViews();
        originalUserAgents.clear();
        backgroundExecutor.shutdown();
        super.onDestroy();
    }


    private void injectLazyLoading(WebView webView) {
        WebViewOptimizationUtils.injectLazyLoading(webView);
    }

    private void applyOptimizedSettings(WebSettings settings) {
    settings.setJavaScriptEnabled(jsEnabled);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(true);
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);
    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setDomStorageEnabled(true);
    settings.setSaveFormData(false);
    settings.setGeolocationEnabled(false);
    settings.setTextZoom(100);
    settings.setDisplayZoomControls(false);
    settings.setBuiltInZoomControls(zoomEnabled);
    settings.setSupportZoom(zoomEnabled);
    settings.setSupportMultipleWindows(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setMediaPlaybackRequiresUserGesture(true);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        WebView.setWebContentsDebuggingEnabled(false);
    }
    CookieManager cookieManager = CookieManager.getInstance();
    cookieManager.setAcceptCookie(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(false);
        }
        if (WebViewOptimizationUtils.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, darkModeEnabled ?
                    WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
        }
        if (WebViewOptimizationUtils.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, java.util.Collections.emptySet());
        }
    }
    private void applyRendererPriority(WebView view, boolean foreground) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, !foreground);
        } catch (Exception ignored) {
        }
    }

    private WebView createNewWebView() {
        KeyboardGameWebView webView = new KeyboardGameWebView(this);
        applyRendererPriority(webView, true);
        webView.setForceNoCacheOnNavigation(true);
        KeyboardSettings.configureWebView(webView, pref);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setBackgroundColor(Color.WHITE);
        if (!NativeMessageBridge.attach(webView, this::handleNativeBridgeMessage)) {
            AndroidBridge androidBridge = new AndroidBridge(webView);
            webView.addJavascriptInterface(androidBridge, "AndroidBridge");
            com.coara.browser.webview.WebViewOptimizationUtils.installNativeBridgeToken(webView, androidBridge.getBridgeToken());
        }
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        String defaultUA = WebViewOptimizationUtils.sanitizeUserAgent(settings.getUserAgentString());
        originalUserAgents.put(webView, defaultUA);
        applyOptimizedSettings(settings);
        if (WebViewOptimizationUtils.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true);
        }
        settings.setUserAgentString(defaultUA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        if (sSetSaveFormDataMethod != null) {
            try {
                sSetSaveFormDataMethod.invoke(settings, false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (sSetDatabaseEnabledMethod != null) {
            try {
                sSetDatabaseEnabledMethod.invoke(settings, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (sSetAppCacheEnabledMethod != null && sSetAppCachePathMethod != null) {
            try {
                sSetAppCacheEnabledMethod.invoke(settings, true);
                sSetAppCachePathMethod.invoke(settings, getCacheDir().getAbsolutePath());
            } catch (Exception e) {
                e.printStackTrace();
               }
            }
         }
        settings.setLoadsImagesAutomatically(!imgBlockEnabled);

        if (uaEnabled) {
            settings.setUserAgentString("DoCoMo/2.0 SH902i(c100;TB)");
        } else if (deskuaEnabled) {
            String desktopUA = defaultUA.replace("Mobile", "").replace("Android", "");
            settings.setUserAgentString(desktopUA);
        } else if (ct3uaEnabled) {
            settings.setUserAgentString("Mozilla/5.0 (Linux; Android 7.0; TAB-A03-BR3 Build/02.05.000; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Safari/537.36");
        }

        BlobDownloadBridge blobDownloadBridge = new BlobDownloadBridge(this, webView);
        blobDownloadBridges.put(webView, blobDownloadBridge);
        webView.addJavascriptInterface(blobDownloadBridge, "BlobDownloader");
        PluginRuntime.attach(this, webView);
        AdBlockEngine.attach(webView);
        com.coara.browser.webview.WebViewOptimizationUtils.installClientCompat(webView);
        SafeBrowsingSupport.recordSafePage(webView, START_PAGE);

        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result != null) {
                final int type = result.getType();
                final String extra = result.getExtra();
                boolean isDataUrl = extra != null && extra.startsWith("data:");
                if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    final String[] options;
                    if (isDataUrl) {
                        options = new String[] {
                            "リンクをコピー",
                            "リンク先を開く",
                            "画像を保存"
                        };
                    } else {
                        options = new String[] {
                            "リンクをコピー",
                            "リンクをダウンロード",
                            "リンク先を開く",
                            "画像を保存"
                        };
                    }
                    new MaterialAlertDialogBuilder(SecretActivity.this)
                        .setTitle("オプションを選択")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                copyLink(extra);
                            } else if (which == 1) {
                                if (isDataUrl) {
                                    WebView current = getCurrentWebView();
                                    if (current != null && extra != null && !extra.isEmpty()) {
                                        current.loadUrl(extra);
                                    }
                                } else {
                                    handleDownload(extra, null, null, null, 0);
                                }
                            } else if (which == 2) {
                                if (isDataUrl) {
                                    if (extra != null && !extra.isEmpty()) {
                                        saveImage(extra);
                                    }
                                } else {
                                    WebView current = getCurrentWebView();
                                    if (current != null && extra != null && !extra.isEmpty()) {
                                        current.loadUrl(extra);
                                    }
                                }
                            } else if (which == 3 && !isDataUrl) {
                                if (extra != null && !extra.isEmpty()) {
                                    saveImage(extra);
                                }
                            }
                        }).show();
                    return true;
                } else if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    final String[] options = {
                        "リンクをコピー",
                        "リンクをダウンロード",
                        "リンク先を開く"
                    };
                    new MaterialAlertDialogBuilder(SecretActivity.this)
                        .setTitle("オプションを選択")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                copyLink(extra);
                            } else if (which == 1) {
                                handleDownload(extra, null, null, null, 0);
                            } else if (which == 2) {
                                WebView current = getCurrentWebView();
                                if (current != null && extra != null && !extra.isEmpty()) {
                                    current.loadUrl(extra);
                                }
                            }
                        }).show();
                    return true;
                } else if (type == WebView.HitTestResult.IMAGE_TYPE) {
                    final String[] options;
                    boolean isDataUrlLocal = extra != null && extra.startsWith("data:");
                    if (isDataUrlLocal) {
                        options = new String[] {
                            "リンクをコピー",
                            "画像を保存"
                        };
                    } else {
                        options = new String[] {
                            "リンクをコピー",
                            "リンクをダウンロード",
                            "リンク先を開く",
                            "画像を保存"
                        };
                    }
                    new MaterialAlertDialogBuilder(SecretActivity.this)
                        .setTitle("オプションを選択")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                copyLink(extra);
                            } else if (which == 1) {
                                if (isDataUrlLocal) {
                                    if (extra != null && !extra.isEmpty()) {
                                        saveImage(extra);
                                    }
                                } else {
                                    handleDownload(extra, null, null, null, 0);
                                }
                            } else if (which == 2 && !isDataUrlLocal) {
                                WebView current = getCurrentWebView();
                                if (current != null && extra != null && !extra.isEmpty()) {
                                    current.loadUrl(extra);
                                }
                            } else if (which == 3 && !isDataUrlLocal) {
                                if (extra != null && !extra.isEmpty()) {
                                    saveImage(extra);
                                }
                            }
                        }).show();
                    return true;
                }
            }
            return false;
        });

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                boolean crashed = detail != null && detail.didCrash();
                String lastUrl = null;
                try {
                    lastUrl = view.getUrl();
                } catch (Exception ignored) {
                }
                String recoverUrl = BrowserAssetLoader.normalizeLegacyAssetUrl(
                        lastUrl == null || lastUrl.isEmpty() || lastUrl.startsWith("blob:") || lastUrl.startsWith("javascript:")
                                ? START_PAGE : lastUrl);
                final String targetUrl = recoverUrl == null || recoverUrl.isEmpty() ? START_PAGE : recoverUrl;
                final boolean wasExternal = ExternalDownloadTabTracker.isExternal(view);
                try {
                    PluginRuntime.onRenderProcessGone(view, crashed);
                } catch (Exception ignored) {
                }
                UiThread.mainHandler().post(() -> recoverFromRenderProcessGone(view, targetUrl, wasExternal));
                return true;
            }

            @Override
            public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponseCompat callback) {
                SafeBrowsingSupport.handle(SecretActivity.this, view, request, threatType, callback);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse local = browserAssetLoader != null ? browserAssetLoader.intercept(request) : null;
                if (local != null) {
                    return local;
                }
                WebResourceResponse blocked = AdBlockEngine.intercept(view, request);
                return blocked != null ? blocked : super.shouldInterceptRequest(view, request);
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                WebResourceResponse local = browserAssetLoader != null ? browserAssetLoader.intercept(url) : null;
                return local != null ? local : super.shouldInterceptRequest(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return false;
                }
                final String targetUrl = request.getUrl().toString();
                if (request.isForMainFrame()) {
                    SafeBrowsingSupport.recordNavigation(view, targetUrl);
                }
                ExternalDownloadTabTracker.onMainFrameNavigation(view, request.isForMainFrame(), request.hasGesture(), request.isRedirect(), targetUrl);
                return BrowserUrlRouter.handleUrlLoading(SecretActivity.this, view, targetUrl, request.isForMainFrame());
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                SafeBrowsingSupport.handleSslError(SecretActivity.this, view, handler, error);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null || url.isEmpty()) {
                    return false;
                }
                SafeBrowsingSupport.recordNavigation(view, url);
                return BrowserUrlRouter.handleUrlLoading(SecretActivity.this, view, url, true);
            }
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (isReload || url == null || url.isEmpty() || view != getCurrentWebView()) {
                    return;
                }
                if (loadedPages.contains(view)) {
                    scheduleSpaHistoryUpdate(view, url);
                }
            }
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loadedPages.remove(view);
                PageTranslator.onPageStarted(view);
                SafeBrowsingSupport.recordNavigation(view, url);
                ExternalDownloadTabTracker.onPageStarted(view, url);
                PluginRuntime.onPageStarted(view, url);
                AdBlockEngine.onPageStarted(view);
                if (findController != null) {
                    findController.onPageStarted(view);
                }
                TouchpadController.onPageStarted(view);
                clearPendingSpaHistory(view);
                view.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
                urlEditText.setText(url);
                updateUrlSecurityIcon();
                super.onPageStarted(view, url, favicon);
            }
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                SafeBrowsingSupport.recordSafePage(view, url);
                PluginRuntime.onPageCommitVisible(view, url);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                com.coara.browser.webview.WebViewOptimizationUtils.refreshNativeBridgeToken(view);
                loadedPages.add(view);
                ExternalDownloadTabTracker.onPageFinished(view, url);
                if (findController != null) {
                    findController.onPageFinished(view);
                }
                PluginRuntime.onPageFinished(view, url);
                TouchpadController.onPageFinished(view);
                KeyboardSettings.configureWebView(view, pref);
                installDownloadHintScript(view);
                  super.onPageFinished(view, url);
                PageTranslator.onPageFinished(view, false);
            if (url.startsWith("https://m.youtube.com") || url.startsWith("https://chatgpt.com/")) {
                UiThread.postDelayed(() -> injectLazyLoading(view), 200);
            }
            if (url.equals(START_PAGE)) {
             faviconImageView.setVisibility(View.GONE);
             urlEditText.setText("");
            } else {
            faviconImageView.setVisibility(View.VISIBLE);
            if (view == getCurrentWebView()) {
            urlEditText.setText(url);
            }
           }
         updateUrlSecurityIcon();
         clearPendingSpaHistory(view);
         addHistory(url, view.getTitle());
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setEnabled(false);
        }
          String jsOverrideHistory = "(function(){" +
          "try{" +
          "if(!window.__coaraHistoryHooked){" +
          "window.__coaraHistoryHooked=true;" +
          "window.__coaraLastHref=location.href;" +
          "var sendNativeUrl=function(href){try{if(window.CoaraNativeBridge&&typeof window.CoaraNativeBridge.postMessage==='function'){window.CoaraNativeBridge.postMessage(JSON.stringify({type:'url',url:href}));return;}}catch(e){}try{AndroidBridge.onUrlChange(href,window.__coaraNativeBridgeToken);}catch(e){}};" +
          "var notifyUrlChange=function(force){" +
          "var href=location.href;" +
          "if(!force&&href===window.__coaraLastHref)return;" +
          "window.__coaraLastHref=href;" +
          "sendNativeUrl(href);" +
          "};" +
          "var pushState=history.pushState;" +
          "history.pushState=function(){var r=pushState.apply(history,arguments);notifyUrlChange(true);return r;};" +
          "var replaceState=history.replaceState;" +
          "history.replaceState=function(){var r=replaceState.apply(history,arguments);notifyUrlChange(true);return r;};" +
          "window.addEventListener('popstate',function(){notifyUrlChange(true);});" +
          "window.addEventListener('hashchange',function(){notifyUrlChange(true);});" +
          "notifyUrlChange(true);" +
          "}else{" +
          "sendNativeUrl(location.href);" +
          "}" +
          "}catch(e){}" +
          "})();"; 
           view.evaluateJavascript(jsOverrideHistory, null); 
          }
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                BasicAuthManager.handleHttpAuthRequest(SecretActivity.this, view, handler, basicAuthEnabled, host, realm);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (pageProgressBar != null && view == getCurrentWebView()) {
                    if (newProgress >= 100) {
                        pageProgressBar.setVisibility(View.INVISIBLE);
                        pageProgressBar.setProgress(0);
                    } else {
                        pageProgressBar.setVisibility(View.VISIBLE);
                        pageProgressBar.setProgress(Math.max(5, newProgress));
                    }
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                               FileChooserParams fileChooserParams) {
                SecretActivity.this.filePathCallback = filePathCallback;
                try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent());
                } catch (Exception e) {
                    SecretActivity.this.filePathCallback = null;
                    Toast.makeText(SecretActivity.this, "ファイル選択エラー", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
           @Override
           public void onPermissionRequest(final PermissionRequest request) {
            try {
                boolean capture = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                        capture = true;
                    }
                }
                final String originKey = request.getOrigin() == null ? "" : request.getOrigin().toString();
                WebView permissionWebView = getCurrentWebView();
                if (!BridgeGuard.isSecureCaptureOrigin(originKey)
                        || permissionWebView == null
                        || !BridgeGuard.sameOrigin(originKey, permissionWebView.getUrl())) {
                    request.deny();
                    return;
                }
                if (capture && !approvedCaptureOrigins.contains(originKey)) {
                    if (isFinishing()) {
                        request.deny();
                        return;
                    }
                    new androidx.appcompat.app.AlertDialog.Builder(SecretActivity.this)
                            .setTitle("カメラ・マイクの許可")
                            .setMessage(originKey + "\nがカメラまたはマイクの使用を求めています。")
                            .setCancelable(false)
                            .setPositiveButton("許可", (d, w) -> {
                                if (permissionWebView != getCurrentWebView()
                                        || !BridgeGuard.sameOrigin(originKey, permissionWebView.getUrl())
                                        || request.getOrigin() == null
                                        || !BridgeGuard.sameOrigin(originKey, request.getOrigin().toString())) {
                                    request.deny();
                                    return;
                                }
                                handleMediaPermission(request);
                            })
                            .setNegativeButton("拒否", (d, w) -> request.deny())
                            .show();
                    return;
                }
            } catch (Exception e) {
                request.deny();
                return;
            }
            handleMediaPermission(request);
           }

           private void handleMediaPermission(final PermissionRequest request) {
           try {
            String[] resources = request.getResources();
            List<String> permissionsNeeded = new ArrayList<>();

            for (String resource : resources) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                    if (ContextCompat.checkSelfPermission(SecretActivity.this, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        permissionsNeeded.add(Manifest.permission.CAMERA);
                    }
                } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    if (ContextCompat.checkSelfPermission(SecretActivity.this, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
                    }
                }
            }
            List<String> grantable = getGrantableCaptureResources(request);
            if (grantable.isEmpty()) {
                request.deny();
                return;
            }
            if (permissionsNeeded.isEmpty()) {
                request.grant(grantable.toArray(new String[0]));
                String origin = request.getOrigin() == null ? "" : request.getOrigin().toString();
                approvedCaptureOrigins.add(origin);
            } else {
                if (pendingPermissionRequest != null) {
                    try {
                        pendingPermissionRequest.deny();
                    } catch (Exception ignored) {
                    }
                }
                WebView requestView = getCurrentWebView();
                String requestOrigin = request.getOrigin() == null ? "" : request.getOrigin().toString();
                if (requestView == null || requestView != getCurrentWebView()
                        || !BridgeGuard.isSecureCaptureOrigin(requestOrigin)
                        || !BridgeGuard.sameOrigin(requestOrigin, requestView.getUrl())) {
                    request.deny();
                    return;
                }
                pendingPermissionRequest = request;
                pendingPermissionWebView = requestView;
                pendingPermissionOrigin = requestOrigin;
                permissionRequestLauncher.launch(permissionsNeeded.toArray(new String[0]));
            }
        } catch (Exception e) {
            e.printStackTrace();
            request.deny();
        }
    }

            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                if (view == getCurrentWebView()) {
                    if (!urlEditMode) faviconImageView.setImageBitmap(icon);
                }
                String currentUrl = view.getUrl();
                if (currentUrl != null) {
                    faviconCache.put(currentUrl, icon);
                }
            }
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webViewContainer.setVisibility(View.GONE);
                FrameLayout decor = (FrameLayout) getWindow().getDecorView();
                decor.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }
                FrameLayout decor = (FrameLayout) getWindow().getDecorView();
                decor.removeView(customView);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                webViewContainer.setVisibility(View.VISIBLE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            WebView current = getCurrentWebView();
            if (current == null || DownloadSupport.isBlank(url)) {
                return;
            }
            if (DownloadSupport.isBlobUrl(url)) {
                handleBlobDownload(url, userAgent, contentDisposition, mimeType, contentLength);
                return;
            }
            long downloadId = handleDownload(url, userAgent, contentDisposition, mimeType, contentLength);
            if (downloadId > 0 && ExternalDownloadTabTracker.isExternal(current)
                    && ExternalDownloadTabTracker.shouldCloseAfterDownload(current, url)) {
                DownloadSupport.scheduleExternalTabClose(SecretActivity.this, current, downloadId, url, () -> closeTab(current));
            }
        });
        return webView;
    }

    private void recoverFromRenderProcessGone(WebView crashed, String recoverUrl, boolean wasExternal) {
        if (crashed == null) {
            return;
        }
        clearPendingSpaHistory(crashed);
        if (crashed == webView) {
            TouchpadController.clearCurrentWebView(crashed);
        }
        ExternalDownloadTabTracker.clear(crashed);
        originalUserAgents.remove(crashed);
        try {
            android.view.ViewParent parent = crashed.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(crashed);
            }
        } catch (Exception ignored) {
        }
        try {
            crashed.stopLoading();
        } catch (Exception ignored) {
        }
        releaseBlobBridge(crashed);
        try {
            crashed.destroy();
        } catch (Exception ignored) {
        }
        if (crashed != webView || isFinishing() || isDestroyed() || webViewContainer == null) {
            if (crashed == webView) {
                webView = null;
            }
            return;
        }
        webView = createNewWebView();
        webViewContainer.removeAllViews();
        webViewContainer.addView(webView);
        if (wasExternal) {
            ExternalDownloadTabTracker.markExternal(webView, recoverUrl);
        }
        TouchpadController.setCurrentWebView(webView);
        if (urlEditText != null) {
            urlEditText.setText(recoverUrl);
        }
        webView.loadUrl(recoverUrl);
    }

    private void releaseBlobBridge(WebView webView) {
        if (webView == null) {
            return;
        }
        SafeBrowsingSupport.clear(webView);
        BlobDownloadBridge bridge = blobDownloadBridges.remove(webView);
        if (bridge != null) {
            try {
                bridge.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    private void closeTab(WebView target) {
        if (target == null || target != webView) {
            return;
        }
        ExternalDownloadTabTracker.clear(target);
        try {
            target.stopLoading();
        } catch (Exception ignored) {
        }
        releaseBlobBridge(target);
        try {
            target.destroy();
        } catch (Exception ignored) {
        }
        webViewContainer.removeView(target);
        webView = createNewWebView();
        webViewContainer.addView(webView);
        webView.loadUrl(START_PAGE);
        urlEditText.setText(START_PAGE);
    }

    private void installDownloadHintScript(WebView view) {
        if (view == null || !jsEnabled) {
            return;
        }
        WebViewOptimizationUtils.installDownloadHints(view);
    }

    private long handleDownload(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        if (DownloadSupport.isBlank(url)) {
            Toast.makeText(SecretActivity.this, "ダウンロードURLが見つかりません", Toast.LENGTH_SHORT).show();
            return -1;
        }
        if (DownloadSupport.isDataUrl(url)) {
            if (!DownloadSupport.saveDataUrl(SecretActivity.this, url, null, mimeType)) {
                Toast.makeText(SecretActivity.this, "データURLの保存に失敗しました", Toast.LENGTH_SHORT).show();
            }
            return -1;
        }
        if (DownloadSupport.isBlobUrl(url)) {
            handleBlobDownload(url, userAgent, contentDisposition, mimeType, contentLength);
            return -1;
        }
        if (!BrowserUrlRouter.isWebUrl(url)) {
            Toast.makeText(SecretActivity.this, "対応していないURLのためダウンロードできません", Toast.LENGTH_LONG).show();
            return -1;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(SecretActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            if (permissionLauncher != null) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            } else {
                Toast.makeText(SecretActivity.this, "ストレージ権限が必要です", Toast.LENGTH_SHORT).show();
            }
            return -1;
        }
        WebView current = getCurrentWebView();
        if (DownloadSupport.shouldPreferDirectFallback(url, contentDisposition, mimeType)) {
            long fallbackId = DownloadFallbackManager.startImmediateFallback(this, current, url, userAgent,
                    contentDisposition, mimeType, contentLength, current != null ? current.getUrl() : null,
                    basicAuthEnabled, "Downloading file...");
            if (fallbackId > 0) {
                Toast.makeText(this, "代替経路でダウンロードを開始しました", Toast.LENGTH_LONG).show();
                return fallbackId;
            }
        }
        try {
            long downloadId = DownloadSupport.enqueue(SecretActivity.this, current, url, userAgent, contentDisposition, mimeType,
                    contentLength, basicAuthEnabled, "Downloading file...");
            Toast.makeText(SecretActivity.this, "ダウンロードを開始しました", Toast.LENGTH_LONG).show();
            return downloadId;
        } catch (SecurityException e) {
            Toast.makeText(SecretActivity.this, "ダウンロードの権限がありません", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(SecretActivity.this, "無効なダウンロードURLです", Toast.LENGTH_LONG).show();
        } catch (IllegalStateException e) {
            if ("insufficient storage".equals(e.getMessage())) {
                Toast.makeText(SecretActivity.this, "空き容量が不足しています", Toast.LENGTH_LONG).show();
            } else {
                long fallbackId = DownloadFallbackManager.startImmediateFallback(SecretActivity.this, current, url, userAgent,
                        contentDisposition, mimeType, contentLength, current != null ? current.getUrl() : null,
                        basicAuthEnabled, "Downloading file...");
                if (fallbackId > 0) {
                    Toast.makeText(SecretActivity.this, "代替経路でダウンロードを開始しました", Toast.LENGTH_LONG).show();
                    return fallbackId;
                }
                Toast.makeText(SecretActivity.this, "ダウンロード機能を利用できません", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            long fallbackId = DownloadFallbackManager.startImmediateFallback(SecretActivity.this, current, url, userAgent,
                    contentDisposition, mimeType, contentLength, current != null ? current.getUrl() : null,
                    basicAuthEnabled, "Downloading file...");
            if (fallbackId > 0) {
                Toast.makeText(SecretActivity.this, "代替経路でダウンロードを開始しました", Toast.LENGTH_LONG).show();
                return fallbackId;
            }
            Toast.makeText(SecretActivity.this, "ダウンロードに失敗しました", Toast.LENGTH_SHORT).show();
        }
        return -1;
    }

    private void handleBlobDownload(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        WebView current = getCurrentWebView();
        if (current == null || DownloadSupport.isBlank(url)) {
            Toast.makeText(SecretActivity.this, "blobダウンロードURLが見つかりません", Toast.LENGTH_SHORT).show();
            return;
        }
        String resolvedMimeType = DownloadSupport.normalizeMimeType(mimeType, null);
        String fileName = DownloadSupport.buildTimestampFileName("blob_download_", resolvedMimeType);
        String token = "blob_" + System.nanoTime() + "_" + Math.abs(new java.util.Random().nextInt());
        String js = "javascript:(function(){" +
                "var token=" + JSONObject.quote(token) + ";" +
                "var fileName=" + JSONObject.quote(fileName) + ";" +
                "var mimeType=" + JSONObject.quote(resolvedMimeType) + ";" +
                "fetch(" + JSONObject.quote(url) + ",{credentials:'include',cache:'no-store'}).then(function(response){" +
                "if(!response.ok){throw new Error('HTTP '+response.status);}" +
                "var totalSize=response.headers.get('Content-Length')||'0';" +
                "var contentDisposition=response.headers.get('Content-Disposition')||'';" +
                "window.BlobDownloader.onBlobStart(token,fileName,mimeType,totalSize,contentDisposition);" +
                "if(response.body&&response.body.getReader){" +
                "var reader=response.body.getReader();var buffer=[];var bufferLen=0;var FLUSH_SIZE=262144;" +
                "function flush(){if(bufferLen===0){return;}var merged=new Uint8Array(bufferLen);var offset=0;for(var i=0;i<buffer.length;i++){merged.set(buffer[i],offset);offset+=buffer[i].length;}var binary='';var chunkSize=8192;for(var j=0;j<merged.length;j+=chunkSize){binary+=String.fromCharCode.apply(null,merged.subarray(j,Math.min(j+chunkSize,merged.length)));}window.BlobDownloader.onBlobChunk(token,btoa(binary));buffer=[];bufferLen=0;}" +
                "function pump(){return reader.read().then(function(result){if(result.done){flush();window.BlobDownloader.onBlobComplete(token);return;}buffer.push(result.value);bufferLen+=result.value.length;if(bufferLen>=FLUSH_SIZE){flush();}return pump();});}return pump();" +
                "}return response.blob().then(function(blob){return new Promise(function(resolve,reject){var CHUNK_BYTES=262144;var offset=0;function sendNext(){if(offset>=blob.size){window.BlobDownloader.onBlobComplete(token);resolve();return;}var end=Math.min(offset+CHUNK_BYTES,blob.size);var part=blob.slice(offset,end);var fr=new FileReader();fr.onloadend=function(){try{var base64data=fr.result;var idx=base64data.indexOf(',');window.BlobDownloader.onBlobChunk(token,idx>=0?base64data.substring(idx+1):base64data);offset=end;sendNext();}catch(e){reject(e);}};fr.onerror=function(){reject(fr.error||new Error('FileReader error'));};fr.readAsDataURL(part);}sendNext();});});" +
                "}).catch(function(error){window.BlobDownloader.onBlobError(token,String(error));});" +
                "})();";
        try {
            BlobDownloadBridge bridge = blobDownloadBridges.get(current);
            if (bridge == null) {
                Toast.makeText(SecretActivity.this, "blobダウンロードを開始できません", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!bridge.authorizeDownload(token)) {
                Toast.makeText(SecretActivity.this, "ダウンロードの処理が混み合っています", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ExternalDownloadTabTracker.isExternal(current)) {
                bridge.registerCloseAfterDownload(token, this, current, url, () -> closeTab(current));
            }
            current.evaluateJavascript(js, null);
        } catch (Exception e) {
            BlobDownloadBridge bridge = blobDownloadBridges.get(current);
            if (bridge != null) {
                bridge.cancelAuthorization(token);
            }
            Toast.makeText(SecretActivity.this, "blobダウンロードを開始できません", Toast.LENGTH_SHORT).show();
        }
    }

    private String generateBlobFileName(String mimeType) {
        String ext = "";
        if (mimeType != null) {
            if (mimeType.contains("pdf")) {
                ext = ".pdf";
            } else if (mimeType.contains("image/png")) {
                ext = ".png";
            } else if (mimeType.contains("image/jpeg")) {
                ext = ".jpg";
            } else if (mimeType.contains("text/html")) {
                ext = ".html";
            }
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return "blob_download_" + timeStamp + ext;
    }

    private void saveImage(String imageUrl) {
    try {
        if (imageUrl != null && imageUrl.startsWith("data:")) {
            int commaIndex = imageUrl.indexOf(',');
            if (commaIndex == -1) {
                Toast.makeText(SecretActivity.this, "無効なデータURL", Toast.LENGTH_SHORT).show();
                return;
            }
            String metadata = imageUrl.substring(5, commaIndex); 
            boolean isBase64 = metadata.contains("base64");
            String mimeType = "image/*";
            if (metadata.contains(";")) {
                mimeType = metadata.split(";")[0];
            }
            byte[] imageData;
            if (isBase64) {
                String base64Data = imageUrl.substring(commaIndex + 1);
                imageData = Base64.decode(base64Data, Base64.DEFAULT);
            } else {
                String dataPart = imageUrl.substring(commaIndex + 1);
                imageData = dataPart.getBytes("UTF-8");
            }
            String fileName = "saved_image_" + System.currentTimeMillis();
            if (mimeType.equalsIgnoreCase("image/png")) {
                fileName += ".png";
            } else if (mimeType.equalsIgnoreCase("image/jpeg")) {
                fileName += ".jpg";
            } else if (mimeType.equalsIgnoreCase("image/bmp")) {
                fileName += ".bmp";
            } else if (mimeType.equalsIgnoreCase("image/gif")) {
                fileName += ".gif";
            } else if (mimeType.equalsIgnoreCase("image/img")) {
                fileName += ".img";
            }
            File picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
            File file = new File(picturesDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(imageData);
                fos.flush();
            }
            Toast.makeText(SecretActivity.this,
                "画像の保存が完了しました\n保存先: " + file.getAbsolutePath(),
                Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(SecretActivity.this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(SecretActivity.this,
                "ストレージ権限が必要です", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request =
            new DownloadManager.Request(Uri.parse(imageUrl));
        request.setMimeType("image/*");
        String cookies = CookieSitePolicy.cookieFor(imageUrl);
        if (cookies != null && !cookies.isEmpty()) {
            request.addRequestHeader("Cookie", cookies);
        }
        String authorization = BasicAuthManager.getAuthorizationHeaderForUrl(imageUrl, basicAuthEnabled);
        if (authorization != null && !authorization.isEmpty()) {
            request.addRequestHeader("Authorization", authorization);
        }
        WebView currentWebView = getCurrentWebView();
        String refererUrl = currentWebView != null ? currentWebView.getUrl() : null;
        if (refererUrl != null && !refererUrl.isEmpty() && BrowserUrlRouter.isWebUrl(refererUrl)) {
            request.addRequestHeader("Referer", refererUrl);
        }
        String fileName = DownloadSupport.sanitizeFileName(URLUtil.guessFileName(imageUrl, null, "image/*"));
        request.setTitle(fileName);
        request.setDescription("画像を保存中...");
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_PICTURES, fileName);
        long downloadId = dm.enqueue(request);
        DownloadHistoryManager.monitorDownloadProgress(SecretActivity.this, downloadId, dm);
        Toast.makeText(SecretActivity.this,
            "画像の保存を開始しました", Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
        Toast.makeText(SecretActivity.this,
            "画像の保存に失敗しました", Toast.LENGTH_SHORT).show();
        e.printStackTrace();
    }
}
    private void exportBookmarksToFile() {
        final String bookmarksJson = pref.getString(KEY_BOOKMARKS, "[]");
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        final File file;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            file = new File(downloadDir, "JSON-bookmark" + timeStamp + ".txt");
        } else {
            file = new File(downloadDir, timeStamp + "-bookmark.json");
        }
        backgroundExecutor.execute(() -> {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bookmarksJson.getBytes("UTF-8"));
                fos.flush();
                runOnUiThread(() ->
                    Toast.makeText(SecretActivity.this, "ブックマークをエクスポートしました: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show()
                );
            } catch (Exception e) {
                runOnUiThread(() ->
                    Toast.makeText(SecretActivity.this, "ブックマークのエクスポートに失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
                e.printStackTrace();
            }
        });
    }
    @Override
    public void onPluginOpenTab(String url) {
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            runOnUiThread(() -> createNewTab(url));
        }
    }

    private void showPluginCommands() {
        final WebView current = getCurrentWebView();
        final java.util.List<PluginRuntime.PluginCommand> commands = PluginRuntime.getCommands(current);
        if (commands.isEmpty()) {
            Toast.makeText(SecretActivity.this, "このページで使えるコマンドはありません", Toast.LENGTH_SHORT).show();
            return;
        }
        View content = getLayoutInflater().inflate(com.coara.browser.R.layout.dialog_plugin_commands, null, false);
        LinearLayout container = content.findViewById(com.coara.browser.R.id.pluginCommandContainer);
        Map<String, java.util.List<PluginRuntime.PluginCommand>> groups = new LinkedHashMap<>();
        for (PluginRuntime.PluginCommand command : commands) {
            String key = command.pluginId == null || command.pluginId.isEmpty() ? command.pluginTitle : command.pluginId;
            java.util.List<PluginRuntime.PluginCommand> group = groups.get(key);
            if (group == null) {
                group = new java.util.ArrayList<>();
                groups.put(key, group);
            }
            group.add(command);
        }
        for (java.util.List<PluginRuntime.PluginCommand> group : groups.values()) {
            PluginRuntime.PluginCommand first = group.get(0);
            View header = getLayoutInflater().inflate(com.coara.browser.R.layout.item_plugin_command_group, container, false);
            ((TextView) header.findViewById(com.coara.browser.R.id.pluginCommandGroupTitle)).setText(first.pluginTitle);
            ((TextView) header.findViewById(com.coara.browser.R.id.pluginCommandGroupCount)).setText(group.size() + "件");
            container.addView(header);
            for (PluginRuntime.PluginCommand command : group) {
                View item = getLayoutInflater().inflate(com.coara.browser.R.layout.item_plugin_command, container, false);
                String title = command.title == null || command.title.isEmpty() ? command.commandId : command.title;
                ((TextView) item.findViewById(com.coara.browser.R.id.pluginCommandTitle)).setText(title);
                ((TextView) item.findViewById(com.coara.browser.R.id.pluginCommandId)).setText("コマンドID: " + command.commandId);
                final String commandId = command.commandId;
                item.setOnClickListener(v -> PluginRuntime.runCommand(current, commandId));
                container.addView(item);
            }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(SecretActivity.this)
                .setTitle("拡張機能コマンド")
                .setView(content)
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void createNewTab(String url) {
        WebView current = getCurrentWebView();
        if (current == null) {
            webView = createNewWebView();
            webViewContainer.removeAllViews();
            webViewContainer.addView(webView);
            current = webView;
        } else {
            ExternalDownloadTabTracker.clear(current);
            try {
                current.stopLoading();
                current.clearHistory();
            } catch (Exception ignored) {
            }
        }
        SafeBrowsingSupport.recordNavigation(current, url);
        current.loadUrl(url);
        urlEditText.setText(url);
    }

    private List<String> getGrantableCaptureResources(final PermissionRequest request) {
        List<String> grantable = new ArrayList<>();
        if (request == null) return grantable;
        String[] requested;
        try {
            requested = request.getResources();
        } catch (Exception e) {
            return grantable;
        }
        for (String resource : requested) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && ContextCompat.checkSelfPermission(SecretActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                grantable.add(resource);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && ContextCompat.checkSelfPermission(SecretActivity.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                grantable.add(resource);
            }
        }
        return grantable;
    }

    private WebView getCurrentWebView() {
        return webView;
    }

    private void updatePullToRefreshState(WebView webView, String url) {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setEnabled(false);
        }
    }

    private void clearPendingSpaHistory(WebView webView) {
        if (webView == null) return;
        Runnable pending = pendingSpaHistoryTasks.remove(webView);
        if (pending != null) {
            UiThread.mainHandler().removeCallbacks(pending);
        }
    }

    private void scheduleSpaHistoryUpdate(final WebView owner, final String url) {
        if (owner == null || url == null || url.isEmpty()) return;
        clearPendingSpaHistory(owner);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    if (owner != getCurrentWebView()) return;
                    String current = owner.getUrl();
                    if (current == null || !current.equals(url)) return;
                    updatePullToRefreshState(owner, url);
                    urlEditText.setText(url);
                    updateUrlSecurityIcon();
                    addHistory(url, owner.getTitle());
                    if (swipeRefreshLayout != null) {
                        swipeRefreshLayout.setEnabled(false);
                    }
                } finally {
                    pendingSpaHistoryTasks.remove(owner);
                }
            }
        };
        pendingSpaHistoryTasks.put(owner, task);
        UiThread.mainHandler().postDelayed(task, 180L);
    }

    private void updateClearButton() {
        if (clearButton == null) {
            return;
        }
        boolean show = urlEditMode && urlEditText != null && urlEditText.length() > 0;
        clearButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateUrlSecurityIcon() {
        if (urlSecurityIcon == null) {
            return;
        }
        WebView current = getCurrentWebView();
        String url = current != null ? current.getUrl() : null;
        if (urlEditMode || url == null) {
            urlSecurityIcon.setVisibility(View.GONE);
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            urlSecurityIcon.setImageResource(R.drawable.ic_lock_small);
            urlSecurityIcon.setContentDescription("保護された通信");
            urlSecurityIcon.setVisibility(View.VISIBLE);
        } else if (lower.startsWith("http://")) {
            urlSecurityIcon.setImageResource(R.drawable.ic_warning_small);
            urlSecurityIcon.setContentDescription("保護されていない通信");
            urlSecurityIcon.setVisibility(View.VISIBLE);
        } else {
            urlSecurityIcon.setVisibility(View.GONE);
        }
    }

    private void showUrlBarMenu(View anchor) {
        final WebView current = getCurrentWebView();
        final String url = current != null ? current.getUrl() : null;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 1, "URLをコピー").setEnabled(url != null && !url.isEmpty());
        menu.getMenu().add(0, 2, 2, "貼り付けて移動");
        menu.getMenu().add(0, 3, 3, "URLを共有").setEnabled(url != null && !url.isEmpty());
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && url != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("URL", url));
                    Toast.makeText(SecretActivity.this, "URLをコピーしました", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            if (id == 2) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                CharSequence text = null;
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                    text = cm.getPrimaryClip().getItemAt(0).coerceToText(SecretActivity.this);
                }
                if (text == null || text.toString().trim().isEmpty()) {
                    Toast.makeText(SecretActivity.this, "クリップボードが空です", Toast.LENGTH_SHORT).show();
                } else {
                    urlEditText.setText(text.toString().trim());
                    loadUrl();
                }
                return true;
            }
            if (id == 3 && url != null) {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, url);
                    startActivity(Intent.createChooser(send, "共有"));
                } catch (Exception ignored) {
                }
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void enterUrlEditMode() {
        if (urlEditMode || urlEditText == null) return;
        urlEditMode = true;
        urlEditSavedIcon = faviconImageView != null ? faviconImageView.getDrawable() : null;
        if (faviconImageView != null) {
            faviconImageView.setImageResource(R.drawable.ic_close);
            faviconImageView.setVisibility(View.VISIBLE);
            faviconImageView.setContentDescription("URL編集を終了");
        }
        if (copyButton != null) copyButton.setVisibility(View.VISIBLE);
        updateClearButton();
        updateUrlSecurityIcon();
        if (urlContainer != null) {
            urlContainer.setStrokeColor(ContextCompat.getColor(this, R.color.colorPrimary));
            urlContainer.setStrokeWidth(2);
        }
        urlEditText.selectAll();
        urlEditText.post(() -> {
            if (urlEditText != null && urlEditText.hasFocus()) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(urlEditText, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void exitUrlEditMode() {
        if (!urlEditMode) return;
        urlEditMode = false;
        if (copyButton != null) copyButton.setVisibility(View.GONE);
        if (clearButton != null) clearButton.setVisibility(View.GONE);
        updateUrlSecurityIcon();
        if (faviconImageView != null) {
            if (urlEditSavedIcon != null) {
                faviconImageView.setImageDrawable(urlEditSavedIcon);
            } else {
                faviconImageView.setImageResource(R.drawable.transparent_vector);
            }
            faviconImageView.setVisibility(View.VISIBLE);
            faviconImageView.setContentDescription("Favicon");
        }
        urlEditSavedIcon = null;
        if (urlContainer != null) {
            urlContainer.setStrokeColor(urlEditOriginalStrokeColor);
            urlContainer.setStrokeWidth(urlEditOriginalStrokeWidth);
        }
        if (urlEditText != null) {
            if (KeyboardSettings.isAlwaysVisible(pref)) {
                KeyboardSettings.showKeyboardForWebView(this, getCurrentWebView(), pref);
            } else {
                KeyboardSettings.hideKeyboard(this, urlEditText);
            }
        }
    }

    private void loadUrl() {
        String input = urlEditText.getText().toString().trim();
        if (input.isEmpty()) {
            return;
        }
        String url = BrowserUrlRouter.normalizeUserInput(input);
        if (url.isEmpty()) {
            return;
        }
        WebView current = getCurrentWebView();
        if (current == null) {
            return;
        }
        ExternalDownloadTabTracker.clear(current);
        if (BrowserUrlRouter.handleUrlLoading(this, current, url)) {
            return;
        }
        SafeBrowsingSupport.recordNavigation(current, url);
        current.loadUrl(url);
    }

    private void handleNativeBridgeMessage(WebView owner, JSONObject message) {
        if (owner == null || message == null || !BridgeGuard.isWebPage(owner.getUrl())) {
            return;
        }
        String type = message.optString("type", "");
        if ("touchpad".equals(type)) {
            if (owner != getCurrentWebView()) {
                return;
            }
            int kind = message.optInt("kind", -1);
            double a = message.optDouble("a", Double.NaN);
            double b = message.optDouble("b", Double.NaN);
            if (Double.isFinite(a) && Double.isFinite(b)) {
                TouchpadController.postHostRequest(owner, kind, (float) a, (float) b);
            }
            return;
        }
        if ("pointerlock".equals(type)) {
            boolean locked = message.optBoolean("locked", false);
            UiThread.post(() -> {
                try {
                    if (owner == getCurrentWebView() && BridgeGuard.isWebPage(owner.getUrl())) {
                        TouchpadController.setPointerLocked(owner, locked);
                    }
                } catch (Exception ignored) {
                }
            });
            return;
        }
        if ("url".equals(type)) {
            String url = message.optString("url", "");
            if (url.length() > 4096 || !BridgeGuard.acceptReportedUrl(owner.getUrl(), url)) {
                return;
            }
            UiThread.post(() -> {
                try {
                    if (owner != getCurrentWebView() || !BridgeGuard.acceptReportedUrl(owner.getUrl(), url)) {
                        return;
                    }
                    scheduleSpaHistoryUpdate(owner, url);
                } catch (Exception ignored) {
                }
            });
        }
    }
private class AndroidBridge {
    private final WebView owner;
    private final String bridgeToken = java.util.UUID.randomUUID().toString().replace("-", "")
            + java.util.UUID.randomUUID().toString().replace("-", "");

    AndroidBridge(WebView owner) {
        this.owner = owner;
    }

    String getBridgeToken() {
        return bridgeToken;
    }

    private boolean isAuthorized(String candidate) {
        return candidate != null && candidate.length() == bridgeToken.length()
                && java.security.MessageDigest.isEqual(
                bridgeToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @JavascriptInterface
    public void touchpadNative(final int kind, final float a, final float b, final String token) {
        if (!isAuthorized(token) || owner != getCurrentWebView() || !BridgeGuard.isWebPage(owner.getUrl())
                || !NativeMessageBridge.isValidTouchpadRequest(kind, a, b)) {
            return;
        }
        TouchpadController.postHostRequest(owner, kind, a, b);
    }
    @JavascriptInterface
    public void onPointerLockChanged(final boolean locked, final String token) {
        if (!isAuthorized(token) || !BridgeGuard.isWebPage(owner.getUrl())) {
            return;
        }
        UiThread.post(() -> {
            try {
                if (owner == getCurrentWebView()) {
                    TouchpadController.setPointerLocked(owner, locked);
                }
            } catch (Exception ignored) {}
        });
    }
    @JavascriptInterface
    public void onUrlChange(final String url, final String token) {
        if (!isAuthorized(token) || !BridgeGuard.isWebPage(owner.getUrl())) {
            return;
        }
        UiThread.post(() -> {
            try {
                WebView current = getCurrentWebView();
                if (current == null || current != owner) return;
                if (!BridgeGuard.acceptReportedUrl(current.getUrl(), url)) return;
                scheduleSpaHistoryUpdate(current, url);
            } catch (Exception ignored) {}
        });
    }
}
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.top_app_bar_menu2, menu);
        return true;
    }
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem darkModeItem = menu.findItem(R.id.action_dark_mode);
        darkModeItem.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        darkModeItem.setCheckable(true);
        darkModeItem.setChecked(darkModeEnabled);
        MenuItem uaItem = menu.findItem(R.id.action_ua);
        if (uaItem != null) uaItem.setChecked(uaEnabled);
        MenuItem deskuaItem = menu.findItem(R.id.action_deskua);
        if (deskuaItem != null) deskuaItem.setChecked(deskuaEnabled);
        MenuItem ct3uaItem = menu.findItem(R.id.action_ct3ua);
        if (ct3uaItem != null) ct3uaItem.setChecked(ct3uaEnabled);
        MenuItem zoomItem = menu.findItem(R.id.action_zoom_toggle);
        if (zoomItem != null) zoomItem.setChecked(zoomEnabled);
        MenuItem jsItem = menu.findItem(R.id.action_js);
        if (jsItem != null) jsItem.setChecked(jsEnabled);
        MenuItem imgItem = menu.findItem(R.id.action_img);
        if (imgItem != null) imgItem.setChecked(imgBlockEnabled);
        MenuItem basicAuthItem = menu.findItem(R.id.action_basic_auth);
        if (basicAuthItem != null) basicAuthItem.setChecked(basicAuthEnabled);
        MenuItem adBlockItem = menu.findItem(R.id.action_adblock);
        if (adBlockItem != null) {
            adBlockItem.setChecked(AdBlockEngine.isEnabled());
            adBlockItem.setTitle(AdBlockEngine.menuTitle());
        }
        return super.onPrepareOptionsMenu(menu);
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_dark_mode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                darkModeEnabled = !darkModeEnabled;
                item.setChecked(darkModeEnabled);
                updateDarkMode();
                pref.edit().putBoolean(KEY_DARK_MODE, darkModeEnabled).apply();
                Toast.makeText(this, "ダークモード " + (darkModeEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "この機能はAndroid 10以上で利用可能です", Toast.LENGTH_SHORT).show();
            }
        } else if (id == R.id.action_adblock) {
            boolean enable = !AdBlockEngine.isEnabled();
            AdBlockEngine.setEnabled(this, enable);
            item.setChecked(enable);
            Toast.makeText(this, "AdBlock " + (enable ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
            WebView current = getCurrentWebView();
            if (current != null) {
                current.reload();
            }
            return true;
        } else if (id == R.id.action_Settings) {
            startActivity(new Intent(SecretActivity.this, SettingsActivity.class));
        } else if (id == R.id.action_keyboard_settings) {
            KeyboardSettings.showSettingsDialog(this, urlEditText, getCurrentWebView(), pref);
        } else if (id == R.id.action_touchpad) {
            TouchpadOverlayController.show(SecretActivity.this, pref);
        } else if (id == R.id.action_Secret) {
            clearSecretDataAndReturnToMain();
            return true;
        } else if (id == R.id.action_plugins) {
            startActivity(new Intent(SecretActivity.this, PluginManagerActivity.class));
        } else if (id == R.id.action_plugin_commands) {
            showPluginCommands();
        } else if (id == R.id.action_downloads) {
            startActivity(new Intent(SecretActivity.this, DownloadHistoryActivity.class));
        } else if (id == R.id.action_qr) {
            startActivity(new Intent(SecretActivity.this, QrCodeActivity.class));
        } else if (id == R.id.action_pgdl) {
            Intent intent = new Intent(SecretActivity.this, pagedl.class);
            intent.putExtra(BasicAuthManager.EXTRA_BASIC_AUTH_ENABLED, basicAuthEnabled);
            startActivity(intent);
        } else if (id == R.id.action_txtphoto) {
            startActivity(new Intent(SecretActivity.this, txtphoto.class));
        } else if (id == R.id.action_num) {
            startActivity(new Intent(SecretActivity.this, num.class));
        } else if (id == R.id.action_asciiart) {
            startActivity(new Intent(SecretActivity.this, asciiart.class));
        } else if (id == R.id.action_grep) {
            startActivity(new Intent(SecretActivity.this, grepmd5appActivity.class));
        } else if (id == R.id.action_htmlview) {
            startActivity(new Intent(SecretActivity.this, htmlview.class));
        } else if (id == R.id.action_bookmark_management) {
            showBookmarksManagementDialog();
        } else if (id == R.id.action_find_in_page) {
            showFindInPageBar();
            return true;
        } else if (id == R.id.action_add_bookmark) {
            addBookmark();
        } else if (id == R.id.action_history) {
            showHistoryDialog();
        } else if (id == R.id.action_export) {
            exportBookmarksToFile();
        } else if (id == R.id.action_import) {
            importBookmarksFromFile();
        } else if (id == R.id.action_Dhistory) {
            Intent intent = new Intent(SecretActivity.this, DownloadHistoryActivity.class);
            intent.putExtra("clear_history", true);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_zoom_toggle) {
            if (item.isChecked()) {
                disableZoom();
                zoomEnabled = false;
            } else {
                enableZoom();
                zoomEnabled = true;
            }
            item.setChecked(zoomEnabled);
            pref.edit().putBoolean(KEY_ZOOM_ENABLED, zoomEnabled).apply();
        } else if (id == R.id.action_js) {
            if (item.isChecked()) {
                disablejs();
                jsEnabled = false;
            } else {
                enablejs();
                jsEnabled = true;
            }
            item.setChecked(jsEnabled);
            pref.edit().putBoolean(KEY_JS_ENABLED, jsEnabled).apply();
        } else if (id == R.id.action_img) {
            if (item.isChecked()) {
                disableimgunlock();
                imgBlockEnabled = false;
            } else {
                enableimgblock();
                imgBlockEnabled = true;
            }
            item.setChecked(imgBlockEnabled);
            pref.edit().putBoolean(KEY_IMG_BLOCK_ENABLED, imgBlockEnabled).apply();
        } else if (id == R.id.action_ua) {
            if (!uaEnabled) {
                if (deskuaEnabled) {
                    disabledeskUA();
                    deskuaEnabled = false;
                    pref.edit().putBoolean(KEY_DESKUA_ENABLED, false).apply();
                }
                if (ct3uaEnabled) {
                    disableCT3UA();
                    ct3uaEnabled = false;
                    pref.edit().putBoolean(KEY_CT3UA_ENABLED, false).apply();
                }
                enableUA();
                uaEnabled = true;
            } else {
                disableUA();
                uaEnabled = false;
            }
            item.setChecked(uaEnabled);
            pref.edit().putBoolean(KEY_UA_ENABLED, uaEnabled).apply();
            invalidateOptionsMenu();
        } else if (id == R.id.action_deskua) {
            if (!deskuaEnabled) {
                if (uaEnabled) {
                    disableUA();
                    uaEnabled = false;
                    pref.edit().putBoolean(KEY_UA_ENABLED, false).apply();
                }
                if (ct3uaEnabled) {
                    disableCT3UA();
                    ct3uaEnabled = false;
                    pref.edit().putBoolean(KEY_CT3UA_ENABLED, false).apply();
                }
                enabledeskUA();
                deskuaEnabled = true;
            } else {
                disabledeskUA();
                deskuaEnabled = false;
            }
            item.setChecked(deskuaEnabled);
            pref.edit().putBoolean(KEY_DESKUA_ENABLED, deskuaEnabled).apply();
            invalidateOptionsMenu();
        } else if (id == R.id.action_ct3ua) {
            if (!ct3uaEnabled) {
                if (uaEnabled) {
                    disableUA();
                    uaEnabled = false;
                    pref.edit().putBoolean(KEY_UA_ENABLED, false).apply();
                }
                if (deskuaEnabled) {
                    disabledeskUA();
                    deskuaEnabled = false;
                    pref.edit().putBoolean(KEY_DESKUA_ENABLED, false).apply();
                }
                enableCT3UA();
                ct3uaEnabled = true;
            } else {
                disableCT3UA();
                ct3uaEnabled = false;
            }
            item.setChecked(ct3uaEnabled);
            pref.edit().putBoolean(KEY_CT3UA_ENABLED, ct3uaEnabled).apply();
            invalidateOptionsMenu();
        } else if (id == R.id.action_basic_auth) {
            if (!basicAuthEnabled) {
                basicAuthEnabled = true;
                item.setChecked(true);
                Toast.makeText(SecretActivity.this, "Basic認証 ON", Toast.LENGTH_SHORT).show();
            } else {
                basicAuthEnabled = false;
                item.setChecked(false);
                clearBasicAuthCacheAndReload();
                Toast.makeText(SecretActivity.this, "Basic認証 OFF", Toast.LENGTH_SHORT).show();
            }
            pref.edit().putBoolean(KEY_BASIC_AUTH, basicAuthEnabled).apply();
        } else if (id == R.id.action_clear_history) {
            WebView current = getCurrentWebView();
            if (current != null) {
                current.clearHistory();
            }
            historyItems.clear();
            if (pref != null) {
                pref.edit().remove(KEY_HISTORY).apply();
            }
            clearWebStorage();
            clearPageCache();
            clearTabs();
            WebViewDatabase.getInstance(SecretActivity.this).clearFormData();
            BasicAuthManager.clear(SecretActivity.this);
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookies(null);
            cookieManager.flush();
            urlEditText.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            urlEditText.setRawInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            urlEditText.setPrivateImeOptions("nm");
            urlEditText.setAutofillHints("");
            urlEditText.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            String currentText = urlEditText.getText().toString();
            urlEditText.setText("");
            urlEditText.setText(currentText);
            Toast.makeText(SecretActivity.this, "履歴、フォームデータ、検索候補、及びタブとCookieを消去しました", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_negapoji) {
            applyNegapoji();
        } else if (id == R.id.action_translate) {
            translatePageToJapanese();
            return true;
        } else if (id == R.id.action_translate_settings) {
            TranslateSettingsDialog.show(this, true);
            return true;
        } else if (id == R.id.action_screenshot) {
            takeScreenshot();
        }
        return super.onOptionsItemSelected(item);
    }

    private void clearBasicAuthCacheAndReload() {
        BasicAuthManager.clear(SecretActivity.this);
        WebView current = getCurrentWebView();
        if (current != null) {
            current.stopLoading();
            current.clearCache(true);
            String currentUrl = current.getUrl();
            if (currentUrl != null && !currentUrl.isEmpty()) {
                current.loadUrl(currentUrl);
            } else {
                current.reload();
            }
        }
    }

    private void translatePageToJapanese() {
        WebView target = getCurrentWebView();
        if (target == null) {
            Toast.makeText(SecretActivity.this, "翻訳するページが見つかりません", Toast.LENGTH_SHORT).show();
            return;
        }
        PageTranslator.toggle(target, false);
    }
    private void clearSecretDataAndReturnToMain() {
        WebView current = webView;
        if (current != null) {
            try {
                current.stopLoading();
                current.clearHistory();
                current.clearCache(true);
                current.clearFormData();
                current.clearSslPreferences();
                current.clearMatches();
                current.removeAllViews();
            } catch (Exception ignored) {
            }
            releaseBlobBridge(current);
            try {
                current.destroy();
            } catch (Exception ignored) {
            }
        }
        webView = null;
        webViewContainer.removeAllViews();
        historyItems.clear();
        if (faviconCache != null) {
            faviconCache.evictAll();
        }

        clearWebStorage();

        WebViewDatabase db = WebViewDatabase.getInstance(this);
        BasicAuthManager.clear(SecretActivity.this);
        try {
            db.clearFormData();
        } catch (Exception ignored) {
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();

        if (urlEditText != null) {
            urlEditText.setText("");
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        finish();
    }

    private void applyNegapoji() {
        String js = "javascript:(function() {" +
                "document.documentElement.style.filter = 'invert(1)';" +
                "var media = document.getElementsByTagName('img');" +
                "for (var i = 0; i < media.length; i++) {" +
                "    media[i].style.filter = 'invert(1)';" +
                "}" +
                "})()";
        getCurrentWebView().evaluateJavascript(js, null);
    }

    @SuppressWarnings("deprecation")
    private void clearWebStorage() {
        WebStorage.getInstance().deleteAllData();
    }

    private void reloadCurrentPage() {
        WebView currentWebView = getCurrentWebView();
        if (currentWebView != null) {
            currentWebView.clearCache(true);
            String currentUrl = currentWebView.getUrl();
            if (currentUrl != null && !currentUrl.isEmpty()) {
                currentWebView.loadUrl(currentUrl);
            }
        }
    }

    private void clearPageCache() {
        WebView current = getCurrentWebView();
        if (current != null) {
            current.clearCache(true);
        }
    }

    private void clearTabs() {
        WebView current = getCurrentWebView();
        if (current == null) {
            webView = createNewWebView();
            webViewContainer.removeAllViews();
            webViewContainer.addView(webView);
            current = webView;
        }
        ExternalDownloadTabTracker.clear(current);
        try {
            current.stopLoading();
            current.clearHistory();
        } catch (Exception ignored) {
        }
        current.loadUrl(START_PAGE);
        if (current.getParent() != webViewContainer) {
            webViewContainer.removeAllViews();
            webViewContainer.addView(current);
        }
    }

    private void takeScreenshot() {
    View rootView = getWindow().getDecorView().getRootView();
    int width = rootView.getWidth();
    int height = rootView.getHeight();
    if (width <= 0 || height <= 0) {
        Toast.makeText(SecretActivity.this, "スクリーンショット取得エラー: ビューサイズが無効", Toast.LENGTH_SHORT).show();
        return;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Handler handler = UiThread.mainHandler();
        final android.view.Window window = getWindow();
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            try {
                PixelCopy.request(window, bitmap, copyResult -> {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    if (copyResult == PixelCopy.SUCCESS) {
                        saveScreenshot(bitmap);
                    } else {
                        Toast.makeText(SecretActivity.this, "スクリーンショットの取得に失敗しました", Toast.LENGTH_SHORT).show();
                    }
                }, handler);
            } catch (IllegalArgumentException e) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                Toast.makeText(SecretActivity.this, "スクリーンショットの取得に失敗しました", Toast.LENGTH_SHORT).show();
            }
        }, 120L);
    } else {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        rootView.draw(canvas);
        saveScreenshot(bitmap);
    }
}

private void saveScreenshot(Bitmap bitmap) {
    backgroundExecutor.execute(() -> {
        try {
            File screenshotDir = new File(Environment.getExternalStorageDirectory(), "DCIM/Screenshot");
            if (!screenshotDir.exists()) {
                screenshotDir.mkdirs();
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = timeStamp + ".png";
            File screenshotFile = new File(screenshotDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(screenshotFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }
            runOnUiThread(() ->
                Toast.makeText(SecretActivity.this, "スクリーンショットを保存しました: " + screenshotFile.getAbsolutePath(), Toast.LENGTH_LONG).show()
            );
        } catch (Exception e) {
            runOnUiThread(() ->
                Toast.makeText(SecretActivity.this, "スクリーンショット保存中にエラー: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        }
    });
}

    private void updateDarkMode() {
        WebView current = getCurrentWebView();
        if (current == null) {
            return;
        }
        WebSettings settings = current.getSettings();
        if (WebViewOptimizationUtils.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, darkModeEnabled ?
                    WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
        }
        current.reload();
    }

    private void enableCT3UA() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 7.0; TAB-A03-BR3 Build/02.05.000; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Safari/537.36");
        Toast.makeText(SecretActivity.this, "CT3UA適用", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void disableCT3UA() {
        WebSettings settings = getCurrentWebView().getSettings();
        String originalUA = originalUserAgents.get(getCurrentWebView());
        if (originalUA != null) {
            settings.setUserAgentString(originalUA);
        }
        Toast.makeText(SecretActivity.this, "CT3UA解除", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void enabledeskUA() {
        WebSettings settings = getCurrentWebView().getSettings();
        String originalUA = originalUserAgents.get(getCurrentWebView());
        if (originalUA == null) {
            originalUA = settings.getUserAgentString();
        }
        String desktopUA = originalUA.replace("Mobile", "").replace("Android", "");
        settings.setUserAgentString(desktopUA);
        Toast.makeText(SecretActivity.this, "デスクトップ表示有効", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void disabledeskUA() {
        WebSettings settings = getCurrentWebView().getSettings();
        String originalUA = originalUserAgents.get(getCurrentWebView());
        if (originalUA != null) {
            settings.setUserAgentString(originalUA);
        }
        reloadCurrentPage();
        Toast.makeText(SecretActivity.this, "デスクトップ表示無効", Toast.LENGTH_SHORT).show();
    }

    private void enableUA() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setUserAgentString("DoCoMo/2.0 SH902i(c100;TB)");
        Toast.makeText(SecretActivity.this, "ガラケーUA有効", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void disableUA() {
        WebSettings settings = getCurrentWebView().getSettings();
        String originalUA = originalUserAgents.get(getCurrentWebView());
        if (originalUA != null) {
            settings.setUserAgentString(originalUA);
        }
        Toast.makeText(SecretActivity.this, "ガラケーUA解除", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void disablejs() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setJavaScriptEnabled(false);
        Toast.makeText(SecretActivity.this, "JavaScript無効", Toast.LENGTH_SHORT).show();
    }

    private void enablejs() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setJavaScriptEnabled(jsEnabled);
        Toast.makeText(SecretActivity.this, "JavaScript有効", Toast.LENGTH_SHORT).show();
    }

    private void enableZoom() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        Toast.makeText(SecretActivity.this, "ズームを有効にしました", Toast.LENGTH_SHORT).show();
    }

    private void disableZoom() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        reloadCurrentPage();
        Toast.makeText(SecretActivity.this, "ズームを無効にしました", Toast.LENGTH_SHORT).show();
    }

    private void enableimgblock() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setLoadsImagesAutomatically(false);
        reloadCurrentPage();
        Toast.makeText(SecretActivity.this, "画像ブロック有効", Toast.LENGTH_SHORT).show();
    }

    private void disableimgunlock() {
        WebView webView = getCurrentWebView();
        webView.getSettings().setLoadsImagesAutomatically(defaultLoadsImagesAutomatically);
        webView.clearCache(true);
        webView.reload();
        Toast.makeText(SecretActivity.this, "画像ブロック無効", Toast.LENGTH_SHORT).show();
    }
    private FindInPageController findController;
    private NetworkStateGuard networkStateGuard;

    private FindInPageController findController() {
        if (findController == null) {
            findController = new FindInPageController(this, pref, this::getCurrentWebView);
        }
        return findController;
    }

    private void showFindInPageBar() {
        findController().show();
    }

    private void hideFindInPageBar() {
        findController().hide();
    }

    private void showHistoryDialog() {
        HistoryDialog.show(this, historyItems, new HistoryDialog.Host() {
            @Override
            public Bitmap favicon(String url) {
                return faviconCache.get(url);
            }

            @Override
            public void open(String url) {
                WebView current = getCurrentWebView();
                if (current != null) {
                    current.loadUrl(url);
                }
            }

            @Override
            public void copy(String url) {
                copyLink(url);
            }

            @Override
            public void changed() {
                saveHistory();
            }
        });
    }


    private void showBookmarksManagementDialog() {
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "ブックマークがありません", Toast.LENGTH_SHORT).show();
            return;
        }
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("ブックマーク")
            .setNegativeButton("閉じる", null)
            .setView(recyclerView)
            .create();
        BookmarkAdapter adapter = new BookmarkAdapter(bookmarks, true, dialog);
        recyclerView.setAdapter(adapter);
        dialog.show();
    }

    private void showEditBookmarkDialog(final int position, final BookmarkAdapter adapter) {
        Bookmark bm = bookmarks.get(position);
        View editView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_bookmark, null);
        EditText etTitle = editView.findViewById(R.id.editTitle);
        EditText etUrl = editView.findViewById(R.id.editUrl);
        etTitle.setText(bm.getTitle());
        etUrl.setText(bm.getUrl());
        new MaterialAlertDialogBuilder(SecretActivity.this)
            .setTitle("ブックマーク")
            .setView(editView)
            .setPositiveButton("保存", (dialog, which) -> {
                String newTitle = BookmarkStore.sanitizeBookmarkTitle(etTitle.getText().toString());
                String newUrl = BookmarkStore.sanitizeBookmarkUrl(BrowserUrlRouter.normalizeUserInput(etUrl.getText().toString().trim()));
                if (newUrl == null) {
                    Toast.makeText(SecretActivity.this, "HTTPまたはHTTPSのURLを入力してください", Toast.LENGTH_SHORT).show();
                    return;
                }
                bookmarks.set(position, new Bookmark(newTitle, newUrl));
                saveBookmarks();
                adapter.notifyDataSetChanged();
                Toast.makeText(SecretActivity.this, "保存しました", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("キャンセル", null)
            .show();
    }

    private void addBookmark() {
        WebView current = getCurrentWebView();
        if (current == null) return;
        String currentUrl = BookmarkStore.sanitizeBookmarkUrl(current.getUrl());
        String title = BookmarkStore.sanitizeBookmarkTitle(current.getTitle());
        if (currentUrl != null && bookmarks.size() >= BookmarkStore.MAX_SAVED_BOOKMARKS) {
            Toast.makeText(SecretActivity.this, "ブックマークは最大件数に達しています", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentUrl != null) {
            bookmarks.add(new Bookmark(title, currentUrl));
            saveBookmarks();
            Toast.makeText(SecretActivity.this, "ブックマークを追加しました", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(SecretActivity.this, "このページはブックマークできません", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadBookmarks() {
        bookmarks.clear();
        String jsonStr = pref.getString(KEY_BOOKMARKS, "[]");
        if (!jsonStr.isEmpty()) {
            parseAndAddBookmarks(jsonStr);
        }
    }
    private final ActivityResultLauncher<Intent> filePickerLauncher =
    registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == Activity.RESULT_OK &&
                result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) {
                    handleFileImport(uri);
                } else {
                    Toast.makeText(SecretActivity.this,
                        "ファイルが選択されませんでした", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(SecretActivity.this,
                    "ファイル選択がキャンセルされました", Toast.LENGTH_SHORT).show();
            }
        });
    private void importBookmarksFromFile() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        intent.setType("*/*");
    } else {
        intent.setType("application/json");
    }
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    filePickerLauncher.launch(intent);
    }
    private void handleFileImport(Uri uri) {
    try {
        String json = readTextFromUri(uri);
        parseAndImportBookmarks(json);
        Toast.makeText(SecretActivity.this, "ブックマークをインポートしました", Toast.LENGTH_SHORT).show();
    } catch (IOException e) {
        Toast.makeText(SecretActivity.this, "ファイルの読み取りに失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        e.printStackTrace();
    } catch (JSONException e) {
        Toast.makeText(SecretActivity.this, "JSON解析エラー: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        e.printStackTrace();
      }
    }
    private String readTextFromUri(Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("ファイルが指定されていません");
        }
        InputStream inputStream = getContentResolver().openInputStream(uri);
        return com.coara.browser.util.TextFileReader.readAll(inputStream, BookmarkStore.MAX_IMPORT_CHARS);
    }

    private void parseAndImportBookmarks(String jsonStr) throws JSONException {
        if (jsonStr == null || jsonStr.length() > BookmarkStore.MAX_IMPORT_CHARS) {
            throw new JSONException("入力データが上限を超えています");
        }
        JSONArray array = new JSONArray(jsonStr);
        if (array.length() > BookmarkStore.MAX_IMPORT_BOOKMARKS) {
            throw new JSONException("ブックマーク件数が上限を超えています");
        }
        ArrayList<Bookmark> imported = new ArrayList<>(Math.min(array.length(), BookmarkStore.MAX_IMPORT_BOOKMARKS));
        ArrayList<String> faviconUrls = new ArrayList<>(50);
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String title = obj.optString("title", "Untitled");
            title = BookmarkStore.sanitizeBookmarkTitle(title);
            String url = BookmarkStore.sanitizeBookmarkUrl(obj.optString("url", ""));
            if (url == null) continue;
            imported.add(new Bookmark(title, url));
            if (faviconUrls.size() < 50) faviconUrls.add(url);
        }
        if (imported.isEmpty()) {
            throw new JSONException("有効なブックマークがありません");
        }
        bookmarks.clear();
        bookmarks.addAll(imported);
        saveBookmarks();
        for (String url : faviconUrls) {
            backgroundExecutor.execute(() -> {
                Bitmap favicon = fetchFavicon(url);
                if (favicon != null) {
                    runOnUiThread(() -> faviconCache.put(url, favicon));
                }
            });
        }
    }

    private void parseAndAddBookmarks(String jsonStr) {
        if (jsonStr == null || jsonStr.length() > BookmarkStore.MAX_STORED_BOOKMARK_CHARS) {
            return;
        }
        try {
            JSONArray array = new JSONArray(jsonStr);
            int limit = Math.min(array.length(), BookmarkStore.MAX_SAVED_BOOKMARKS);
            for (int i = 0; i < limit; i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String title = obj.optString("title", "Untitled");
                title = BookmarkStore.sanitizeBookmarkTitle(title);
                String url = BookmarkStore.sanitizeBookmarkUrl(obj.optString("url", ""));
                if (url != null) {
                    bookmarks.add(new Bookmark(title, url));
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void saveBookmarks() {
        JSONArray array = new JSONArray();
        for (Bookmark bm : bookmarks) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("title", bm.getTitle());
                obj.put("url", bm.getUrl());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            array.put(obj);
        }
        pref.edit().putString(KEY_BOOKMARKS, array.toString()).apply();
    }
private void loadHistory() {
    historyItems.clear();
}

private void saveHistory() {
    if (pref != null) {
        pref.edit().remove(KEY_HISTORY).apply();
    }
}

private String normalizeUrl(String url) {  
    try {
        Uri uri = Uri.parse(url);
        return uri.buildUpon().fragment(null).build().toString();  
    } catch (Exception e) {
        return url;
    }
}

private void addHistory(String url, String title) {
    if (url == null || url.isEmpty() || url.equals(START_PAGE))
        return;
    String lowerUrl = url.toLowerCase(java.util.Locale.ROOT);
    if (lowerUrl.startsWith("about:") || lowerUrl.startsWith("data:") || lowerUrl.startsWith("javascript:")
            || lowerUrl.startsWith("blob:") || lowerUrl.startsWith("chrome-error:")
            || lowerUrl.startsWith("file:///android_asset") || lowerUrl.startsWith("file:///android_res")
            || lowerUrl.startsWith("https://appassets.androidplatform.net/assets/")
            || lowerUrl.startsWith("https://appassets.androidplatform.net/res/"))
        return;
    String normalizedUrl = normalizeUrl(url);
    if (!historyItems.isEmpty() && normalizeUrl(historyItems.get(historyItems.size() - 1).getUrl()).equals(normalizedUrl))
        return;
    historyItems.add(new HistoryItem(title == null ? "" : title, url, System.currentTimeMillis()));
    while (historyItems.size() > MAX_HISTORY_SIZE) {
        historyItems.remove(0);
    }
}
    public void exitFullScreen() {
        if (customView != null) {
            FrameLayout decor = (FrameLayout)getWindow().getDecorView();
            decor.removeView(customView);
            customView = null;
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
            webViewContainer.setVisibility(View.VISIBLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private void copyLink(String link) {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("リンク", link);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(SecretActivity.this, "リンクをコピーしました", Toast.LENGTH_SHORT).show();
    }

    private Bitmap fetchFavicon(String bookmarkUrl) {
        return com.coara.browser.util.FaviconLoader.fetch(bookmarkUrl);
    }


    private class BookmarkAdapter extends RecyclerView.Adapter<BookmarkAdapter.BookmarkViewHolder> {
        private final List<Bookmark> items;
        private final boolean managementMode;
        private final AlertDialog dialog;
        public BookmarkAdapter(List<Bookmark> items, boolean managementMode, AlertDialog dialog) {
            this.items = items;
            this.managementMode = managementMode;
            this.dialog = dialog;
        }
        @Override
        public BookmarkViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bookmark, parent, false);
            return new BookmarkViewHolder(view);
        }
        @Override
        public void onBindViewHolder(BookmarkViewHolder holder, int position) {
            Bookmark bm = items.get(position);
            holder.title.setText(bm.getTitle());
            holder.url.setText(bm.getUrl());
            Bitmap icon = faviconCache.get(bm.getUrl());
            if (icon != null) {
                holder.favicon.setImageBitmap(icon);
            } else {
                holder.favicon.setImageResource(R.drawable.transparent_vector);
            }
            holder.itemView.setOnClickListener(v -> {
                String safeUrl = BookmarkStore.sanitizeBookmarkUrl(bm.getUrl());
                WebView current = getCurrentWebView();
                if (safeUrl != null && current != null) {
                    current.loadUrl(safeUrl);
                    dialog.dismiss();
                } else {
                    Toast.makeText(SecretActivity.this, "このURLはブックマークから開けません", Toast.LENGTH_SHORT).show();
                }
            });
            if (managementMode) {
            holder.itemView.setOnLongClickListener(v -> {
                int currentPosition = holder.getBindingAdapterPosition();
                if (currentPosition == RecyclerView.NO_POSITION) return true;
                String[] options = {"編集", "削除"};
                new MaterialAlertDialogBuilder(SecretActivity.this)
                        .setTitle("操作を選択")
                        .setItems(options, (dialogInterface, which) -> {
                            if (which == 0) {
                                showEditBookmarkDialog(currentPosition, this);
                            } else if (which == 1) {
                                items.remove(currentPosition);
                                notifyItemRemoved(currentPosition);
                                saveBookmarks();
                                Toast.makeText(SecretActivity.this, "削除しました", Toast.LENGTH_SHORT).show();
                            }
                        }).show();
                return true;
            });
        }
    }
        @Override
        public int getItemCount() { return items.size(); }
        class BookmarkViewHolder extends RecyclerView.ViewHolder {
            ImageView favicon;
            TextView title;
            TextView url;
            public BookmarkViewHolder(View itemView) {
                super(itemView);
                favicon = itemView.findViewById(R.id.bookmarkFavicon);
                title = itemView.findViewById(R.id.bookmarkTitle);
                url = itemView.findViewById(R.id.bookmarkUrl);
            }
        }
    }
}
