package com.coara.browser.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.List;

public final class WebViewEngineInfo {
    private WebViewEngineInfo() {
    }

    public static String collect(Context context) {
        StringBuilder result = new StringBuilder();
        PackageInfo provider = null;
        try {
            provider = WebViewCompat.getCurrentWebViewPackage(context.getApplicationContext());
        } catch (Throwable ignored) {
        }

        result.append("WebView / Chromium エンジン\n\n");
        result.append("Android API: ").append(Build.VERSION.SDK_INT).append('\n');
        if (provider != null) {
            result.append("プロバイダ: ").append(provider.packageName == null ? "不明" : provider.packageName).append('\n');
            result.append("エンジンバージョン: ")
                    .append(provider.versionName == null || provider.versionName.isEmpty() ? "不明" : provider.versionName)
                    .append('\n');
        } else {
            result.append("プロバイダ: 取得できません\n");
            result.append("設定 > 開発者向けオプション > WebView 実装 を確認してください\n");
        }

        List<String> features = new ArrayList<>();
        addFeature(features, "Safe Browsing", WebViewFeature.SAFE_BROWSING_ENABLE);
        addFeature(features, "Document Start Script", WebViewFeature.DOCUMENT_START_SCRIPT);
        addFeature(features, "Back/Forward Cache", WebViewFeature.BACK_FORWARD_CACHE);
        addFeature(features, "Web Message Listener", WebViewFeature.WEB_MESSAGE_LISTENER);
        addFeature(features, "Algorithmic Darkening", WebViewFeature.ALGORITHMIC_DARKENING);
        addFeature(features, "Service Worker Cache Mode", WebViewFeature.SERVICE_WORKER_CACHE_MODE);
        addFeature(features, "複数プロファイル", WebViewFeature.MULTI_PROFILE);
        addFeature(features, "HTTPキャッシュ管理", WebViewFeature.HTTP_CACHE_MANAGER);
        addFeature(features, "事前接続キュー", WebViewFeature.ENQUEUE_PRECONNECT);
        addFeature(features, "ファビコン制御", WebViewFeature.DOWNLOAD_FAVICONS_ENABLED);

        result.append("\n利用可能な機能\n");
        if (features.isEmpty()) {
            result.append("対応機能を取得できません\n");
        } else {
            for (String feature : features) {
                result.append("• ").append(feature).append('\n');
            }
        }
        result.append("\nこのアプリはAndroidのWebViewプロバイダが提供するChromiumエンジンを使用します。\n");
        result.append("Chromeアプリ本体やChrome専用サービスを内蔵するものではなく、利用できる機能は端末のWebViewバージョンに依存します。");
        return result.toString();
    }

    private static void addFeature(List<String> features, String label, String feature) {
        try {
            if (WebViewFeature.isFeatureSupported(feature)) {
                features.add(label);
            }
        } catch (Throwable ignored) {
        }
    }
}
