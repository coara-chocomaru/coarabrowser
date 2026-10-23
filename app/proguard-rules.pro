-keepattributes RuntimeVisibleAnnotations,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keepclassmembers,allowoptimization class com.coara.browser.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers,allowoptimization class com.coara.browser.SecretActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers,allowoptimization class com.coara.browser.webview.BlobDownloadBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers,allowoptimization class com.coara.browser.plugin.PluginBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers,allowoptimization class com.coara.browser.** implements androidx.webkit.WebViewCompat$WebMessageListener {
    public void onPostMessage(android.webkit.WebView, androidx.webkit.WebMessageCompat, android.net.Uri, boolean, androidx.webkit.JavaScriptReplyProxy);
}

-keepnames class com.coara.browser.MainActivity

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
