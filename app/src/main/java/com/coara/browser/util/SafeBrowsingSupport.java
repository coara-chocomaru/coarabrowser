package com.coara.browser.util;

import android.app.Activity;
import android.net.Uri;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import androidx.webkit.SafeBrowsingResponseCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.coara.browser.R;

public final class SafeBrowsingSupport {
    private static final Map<WebView, String> SAFE_PAGES = Collections.synchronizedMap(new WeakHashMap<WebView, String>());
    private static final Map<WebView, String> NAVIGATION_URLS = Collections.synchronizedMap(new WeakHashMap<WebView, String>());
    private static final Map<WebView, SslErrorHandler> SSL_HANDLERS = Collections.synchronizedMap(new WeakHashMap<WebView, SslErrorHandler>());
    private static final Map<WebView, String> SSL_URLS = Collections.synchronizedMap(new WeakHashMap<WebView, String>());
    private static final Map<WebView, Boolean> SAFE_PROMPTS = Collections.synchronizedMap(new WeakHashMap<WebView, Boolean>());
    private static boolean initialized;

    private SafeBrowsingSupport() {
    }

    @SuppressWarnings("deprecation")
    public static synchronized void initialize(Activity activity) {
        if (activity == null || initialized) {
            return;
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
                WebViewCompat.startSafeBrowsing(activity, success -> { });
                initialized = true;
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clear(WebView view) {
        if (view == null) {
            return;
        }
        SAFE_PAGES.remove(view);
        NAVIGATION_URLS.remove(view);
        SslErrorHandler pending = SSL_HANDLERS.remove(view);
        if (pending != null) {
            try {
                pending.cancel();
            } catch (Throwable ignored) {
            }
        }
        SSL_URLS.remove(view);
        SAFE_PROMPTS.remove(view);
    }


    public static void recordNavigation(WebView view, String url) {
        if (view == null || url == null || url.isEmpty()) {
            return;
        }
        NAVIGATION_URLS.put(view, url);
    }

    public static void recordSafePage(WebView view, String url) {
        if (view == null || url == null || url.isEmpty()) {
            return;
        }
        String bypassed = SSL_URLS.get(view);
        if (bypassed != null && bypassed.equals(url)) {
            return;
        }
        SAFE_PAGES.put(view, url);
    }

    public static void handleSslError(Activity activity, WebView view, SslErrorHandler handler, SslError error) {
        if (handler == null) {
            return;
        }
        if (view == null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            cancelSsl(view, handler, null);
            return;
        }

        String url = "";
        try {
            if (error != null) {
                url = error.getUrl();
            }
        } catch (Throwable ignored) {
        }
        if (url == null || url.isEmpty()) {
            try {
                url = view.getUrl();
            } catch (Throwable ignored) {
            }
        }

        String navigationUrl = NAVIGATION_URLS.get(view);
        if (url == null) {
            url = "";
        }
        if (!isMainFrameSslNavigation(view, url, navigationUrl)) {
            cancelSslOnly(handler);
            return;
        }

        SslErrorHandler old = SSL_HANDLERS.put(view, handler);
        SSL_URLS.put(view, url);
        if (old != null && old != handler) {
            try {
                old.cancel();
            } catch (Throwable ignored) {
            }
        }

        Uri uri = null;
        try {
            uri = Uri.parse(url);
        } catch (Throwable ignored) {
        }
        String host = uri != null && uri.getHost() != null ? uri.getHost() : url;
        StringBuilder message = new StringBuilder();
        message.append("このサイトは、正しいHTTPS証明書を確認できないため、安全な接続を確立できませんでした。\n\n");
        message.append("このまま開くと、通信内容が第三者に見られたり改ざんされたりする可能性があります。特にパスワード、認証コード、クレジットカード情報などは入力しないでください。\n\n");
        message.append("問題の理由:\n");
        message.append(sslReason(error)).append("\n\n");
        if (host != null && !host.isEmpty()) {
            message.append("対象サイト: ").append(host).append("\n");
        }
        if (!url.isEmpty()) {
            message.append("URL: ").append(url).append("\n\n");
        }
        message.append("「前のページへ戻る」を選ぶと安全なページへ戻ります。\n");
        message.append("「このページを開く」を選ぶと、証明書エラーを無視して通信を続行します。これは安全ではない可能性があります。");

        final String targetUrl = url;
        final SslErrorHandler targetHandler = handler;
        final String targetHost = host == null || host.isEmpty() ? "確認できませんでした" : host;
        final String reasonText = sslReason(error);
        final boolean[] decided = {false};
        Runnable show = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    cancelSsl(view, targetHandler, targetUrl);
                    return;
                }
                try {
                    View content = activity.getLayoutInflater().inflate(R.layout.dialog_security_warning, null, false);
                    TextView summary = content.findViewById(R.id.securityWarningSummary);
                    TextView subSummary = content.findViewById(R.id.securityWarningSubSummary);
                    TextView reasonView = content.findViewById(R.id.securityWarningReason);
                    TextView targetView = content.findViewById(R.id.securityWarningTarget);
                    TextView urlView = content.findViewById(R.id.securityWarningUrl);
                    TextView adviceView = content.findViewById(R.id.securityWarningAdvice);
                    summary.setText("この接続ではプライバシーが保護されません");
                    subSummary.setText("HTTPS証明書を確認できないため、安全な接続を確立できませんでした。");
                    reasonView.setText(reasonText);
                    targetView.setText(targetHost);
                    urlView.setText(targetUrl.isEmpty() ? "URLを取得できませんでした" : targetUrl);
                    adviceView.setText("このまま開くと、通信内容が第三者に見られたり改ざんされたりする可能性があります。パスワード、認証コード、クレジットカード情報などは入力しないでください。\n\n「前のページへ戻る」では安全なページへ戻ります。「このページを開く」では証明書エラーを無視して続行します。");
                    androidx.appcompat.app.AlertDialog warningDialog = new MaterialAlertDialogBuilder(activity)
                            .setView(content)
                            .setNegativeButton("前に戻る", (buttonDialog, which) -> {
                                if (decided[0]) {
                                    return;
                                }
                                decided[0] = true;
                                cancelSsl(view, targetHandler, targetUrl);
                            })
                            .setPositiveButton("このページを開く", (buttonDialog, which) -> {
                                if (decided[0]) {
                                    return;
                                }
                                decided[0] = true;
                                proceedSsl(view, targetHandler, targetUrl);
                            })
                            .setOnCancelListener(cancelDialog -> {
                                if (decided[0]) {
                                    return;
                                }
                                decided[0] = true;
                                cancelSsl(view, targetHandler, targetUrl);
                            })
                            .show();
                    styleSecurityWarningButtons(warningDialog);
                } catch (Throwable t) {
                    cancelSsl(view, targetHandler, targetUrl);
                }
            }
        };
        activity.runOnUiThread(show);
    }

    private static String sslReason(SslError error) {
        if (error == null) {
            return "HTTPS証明書の検証に失敗しました。";
        }
        StringBuilder out = new StringBuilder();
        appendReason(out, error, SslError.SSL_EXPIRED, "証明書の有効期限が切れています。");
        appendReason(out, error, SslError.SSL_NOTYETVALID, "証明書がまだ有効になっていません。");
        appendReason(out, error, SslError.SSL_IDMISMATCH, "証明書の対象ホスト名とアクセス先が一致していません。");
        appendReason(out, error, SslError.SSL_UNTRUSTED, "証明書を発行した認証局を信頼できません。");
        appendReason(out, error, SslError.SSL_DATE_INVALID, "証明書の日付が正しくありません。");
        appendReason(out, error, SslError.SSL_INVALID, "証明書に問題があるか、一般的なSSLエラーが発生しました。");
        if (out.length() == 0) {
            out.append("証明書の検証に失敗しました。");
        }
        return out.toString();
    }

    private static void appendReason(StringBuilder out, SslError error, int type, String text) {
        try {
            if (error.hasError(type)) {
                if (out.length() > 0) {
                    out.append("\n");
                }
                out.append("・").append(text);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isMainFrameSslNavigation(WebView view, String errorUrl, String navigationUrl) {
        if (errorUrl == null || errorUrl.isEmpty()) {
            return false;
        }
        if (navigationUrl != null && !navigationUrl.isEmpty() && errorUrl.equals(navigationUrl)) {
            return true;
        }
        if (view != null) {
            try {
                String currentUrl = view.getUrl();
                if (errorUrl.equals(currentUrl)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void proceedSsl(WebView view, SslErrorHandler handler, String url) {
        try {
            handler.proceed();
            if (view != null) {
                SSL_URLS.put(view, url == null ? "" : url);
            }
        } catch (Throwable ignored) {
            cancelSsl(view, handler, url);
        } finally {
            if (view != null) {
                SSL_HANDLERS.remove(view);
            }
        }
    }

    private static void cancelSslOnly(SslErrorHandler handler) {
        try {
            handler.cancel();
        } catch (Throwable ignored) {
        }
    }

    private static void cancelSsl(WebView view, SslErrorHandler handler, String url) {
        try {
            handler.cancel();
        } catch (Throwable ignored) {
        }
        if (view != null) {
            SSL_HANDLERS.remove(view);
            SSL_URLS.remove(view);
            view.post(() -> restoreSafePage(view, url));
        }
    }

    private static void restoreSafePage(WebView view, String unsafeUrl) {
        if (view == null) {
            return;
        }
        String safe = SAFE_PAGES.get(view);
        if (safe == null || safe.isEmpty() || safe.equals(unsafeUrl)) {
            return;
        }
        try {
            String current = view.getUrl();
            if (safe.equals(current)) {
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (view.canGoBack()) {
                view.goBack();
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            view.loadUrl(safe);
        } catch (Throwable ignored) {
        }
    }

    public static boolean handle(Activity activity, WebView view, WebResourceRequest request,
                                 int threatType, SafeBrowsingResponseCompat response) {
        if (response == null) {
            return true;
        }
        if (request != null && !request.isForMainFrame()) {
            backToSafety(response);
            return true;
        }
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            backToSafety(response);
            return true;
        }
        if (view != null) {
            synchronized (SAFE_PROMPTS) {
                if (SAFE_PROMPTS.containsKey(view)) {
                    backToSafety(response);
                    return true;
                }
                SAFE_PROMPTS.put(view, Boolean.TRUE);
            }
        }

        String url = request != null && request.getUrl() != null
                ? request.getUrl().toString()
                : safeUrl(view);
        String host = "";
        try {
            Uri uri = Uri.parse(url == null ? "" : url);
            if (uri.getHost() != null) {
                host = uri.getHost();
            }
        } catch (Throwable ignored) {
        }

        String reason = reasonFor(threatType);
        String detail = detailFor(threatType);
        StringBuilder message = new StringBuilder();
        message.append("WebView の安全確認で、既知の危険なページとして検出されました。\n\n");
        message.append("判定理由: ").append(reason).append("\n");
        message.append(detail).append("\n\n");
        if (!host.isEmpty()) {
            message.append("対象サイト: ").append(host).append("\n");
        }
        if (url != null && !url.isEmpty()) {
            message.append("URL: ").append(url).append("\n\n");
        }
        message.append("前のページへ戻る場合、このページは読み込みを続行しません。\n");
        message.append("このページを開く場合は、危険性を理解した上で安全確認を無視して表示を続行します。パスワード、認証情報、カード情報などの入力は慎重に判断してください。");

        final String targetUrl = url == null ? "" : url;
        final boolean[] decided = {false};
        try {
            View content = activity.getLayoutInflater().inflate(R.layout.dialog_security_warning, null, false);
            TextView summary = content.findViewById(R.id.securityWarningSummary);
            TextView subSummary = content.findViewById(R.id.securityWarningSubSummary);
            TextView reasonView = content.findViewById(R.id.securityWarningReason);
            TextView targetView = content.findViewById(R.id.securityWarningTarget);
            TextView urlView = content.findViewById(R.id.securityWarningUrl);
            TextView adviceView = content.findViewById(R.id.securityWarningAdvice);
            summary.setText("危険なページとして検出されました");
            subSummary.setText("ブラウザの安全確認サービスが、このページを危険と判定しました。");
            reasonView.setText(reason + "\n\n" + detail);
            targetView.setText(host.isEmpty() ? "確認できませんでした" : host);
            urlView.setText(targetUrl.isEmpty() ? "URLを取得できませんでした" : targetUrl);
            adviceView.setText("このページを開くと、マルウェア感染やフィッシングなどの被害につながる可能性があります。パスワード、認証コード、カード情報などの入力は避けてください。\n\n「前のページへ戻る」では安全なページへ戻ります。「このページを開く」では安全確認を無視して表示を続行します。");
            androidx.appcompat.app.AlertDialog warningDialog = new MaterialAlertDialogBuilder(activity)
                    .setView(content)
                    .setNegativeButton("前に戻る", (buttonDialog, which) -> {
                        if (decided[0]) {
                            return;
                        }
                        decided[0] = true;
                        if (view != null) SAFE_PROMPTS.remove(view);
                        backToSafety(response);
                    })
                    .setPositiveButton("このページを開く", (buttonDialog, which) -> {
                        if (decided[0]) {
                            return;
                        }
                        decided[0] = true;
                        if (view != null) SAFE_PROMPTS.remove(view);
                        proceed(response);
                    })
                    .setOnCancelListener(cancelDialog -> {
                        if (decided[0]) {
                            return;
                        }
                        decided[0] = true;
                        if (view != null) SAFE_PROMPTS.remove(view);
                        backToSafety(response);
                    })
                    .show();
            styleSecurityWarningButtons(warningDialog);
        } catch (Throwable error) {
            if (view != null) SAFE_PROMPTS.remove(view);
            backToSafety(response);
            try {
                Toast.makeText(activity, "危険なページをブロックしました", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static void styleSecurityWarningButtons(androidx.appcompat.app.AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        try {
            android.widget.Button negative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
            android.widget.Button positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            if (negative == null || positive == null) {
                return;
            }
            android.view.ViewParent parent = negative.getParent();
            if (parent instanceof android.widget.LinearLayout) {
                android.widget.LinearLayout buttonBar = (android.widget.LinearLayout) parent;
                buttonBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                buttonBar.setGravity(android.view.Gravity.CENTER);
                android.view.ViewGroup.LayoutParams negativeParams = negative.getLayoutParams();
                android.view.ViewGroup.LayoutParams positiveParams = positive.getLayoutParams();
                if (negativeParams instanceof android.widget.LinearLayout.LayoutParams && positiveParams instanceof android.widget.LinearLayout.LayoutParams) {
                    android.widget.LinearLayout.LayoutParams negativeLayout = (android.widget.LinearLayout.LayoutParams) negativeParams;
                    android.widget.LinearLayout.LayoutParams positiveLayout = (android.widget.LinearLayout.LayoutParams) positiveParams;
                    negativeLayout.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                    negativeLayout.weight = 0f;
                    negativeLayout.gravity = android.view.Gravity.CENTER_VERTICAL;
                    negativeLayout.setMargins(8, 0, 8, 0);
                    positiveLayout.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                    positiveLayout.weight = 0f;
                    positiveLayout.gravity = android.view.Gravity.CENTER_VERTICAL;
                    positiveLayout.setMargins(8, 0, 8, 0);
                    negative.setLayoutParams(negativeLayout);
                    positive.setLayoutParams(positiveLayout);
                }
                negative.setAllCaps(false);
                positive.setAllCaps(false);
            }
            if (dialog.getWindow() != null) {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                dialog.getWindow().getWindowManager().getDefaultDisplay().getMetrics(metrics);
                int width = (int) (metrics.widthPixels * 0.92f);
                int maxWidth = (int) (560 * metrics.density);
                width = Math.min(width, maxWidth);
                int minWidth = (int) (300 * metrics.density);
                width = Math.max(width, minWidth);
                dialog.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String safeUrl(WebView view) {
        if (view == null) {
            return "";
        }
        try {
            String url = view.getUrl();
            return url == null ? "" : url;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void backToSafety(SafeBrowsingResponseCompat response) {
        try {
            response.backToSafety(true);
        } catch (Throwable ignored) {
        }
    }

    private static void proceed(SafeBrowsingResponseCompat response) {
        try {
            response.proceed(true);
        } catch (Throwable ignored) {
        }
    }

    private static String reasonFor(int threatType) {
        switch (threatType) {
            case WebViewClient.SAFE_BROWSING_THREAT_MALWARE:
                return "マルウェアの可能性";
            case WebViewClient.SAFE_BROWSING_THREAT_PHISHING:
                return "フィッシングの可能性";
            case WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE:
                return "望ましくないソフトウェアの可能性";
            case WebViewClient.SAFE_BROWSING_THREAT_BILLING:
                return "不正または欺瞞的な課金につながる可能性";
            default:
                return "既知の危険なコンテンツとして検出";
        }
    }

    private static String detailFor(int threatType) {
        switch (threatType) {
            case WebViewClient.SAFE_BROWSING_THREAT_MALWARE:
                return "このサイトでは、端末やデータに被害を与えるマルウェアの配布が確認されている可能性があります。不要なアプリやファイルのインストールを求められる場合があります。";
            case WebViewClient.SAFE_BROWSING_THREAT_PHISHING:
                return "正規サイトを装って、ID、パスワード、認証コード、カード情報などを入力させるフィッシングの危険がある可能性があります。";
            case WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE:
                return "ブラウザや端末の設定を望ましくない形に変更したり、不要なソフトウェアをインストールさせたりする危険がある可能性があります。";
            case WebViewClient.SAFE_BROWSING_THREAT_BILLING:
                return "不透明な請求、意図しない定期購入、欺瞞的な支払い誘導などにつながる危険がある可能性があります。";
            default:
                return "WebView の安全確認サービスが、このURLを安全に表示し続けることが望ましくないと判定しました。";
        }
    }
}
