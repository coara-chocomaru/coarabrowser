package com.coara.browser.util;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

public final class BrowserAssetLoader {
    private final WebViewAssetLoader assetLoader;

    public BrowserAssetLoader(Context context) {
        Context appContext = context.getApplicationContext();
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(appContext))
                .addPathHandler("/res/", new WebViewAssetLoader.ResourcesPathHandler(appContext))
                .build();
    }

    @Nullable
    public WebResourceResponse intercept(WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        return assetLoader.shouldInterceptRequest(request.getUrl());
    }

    @Nullable
    public WebResourceResponse intercept(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        return assetLoader.shouldInterceptRequest(Uri.parse(url));
    }

    public static String normalizeLegacyAssetUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (url.startsWith(BrowserConstants.LEGACY_ASSET_PREFIX)) {
            return BrowserConstants.APP_ASSET_BASE_URL + url.substring(BrowserConstants.LEGACY_ASSET_PREFIX.length());
        }
        if (url.startsWith(BrowserConstants.LEGACY_RESOURCE_PREFIX)) {
            return "https://appassets.androidplatform.net/res/" + url.substring(BrowserConstants.LEGACY_RESOURCE_PREFIX.length());
        }
        return url;
    }

    public static boolean isAppAssetUrl(String url) {
        return url != null && url.startsWith(BrowserConstants.APP_ASSET_BASE_URL);
    }
}
