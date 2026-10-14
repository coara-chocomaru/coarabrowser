-keepattributes RuntimeVisibleAnnotations,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-allowaccessmodification
-repackageclasses ''

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep,allowoptimization public class com.coara.browser.BrowserApplication {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.MainActivity {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.SecretActivity {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.grepmd5appActivity {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.exec {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.htmlview {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.util.DownloadFallbackService {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.util.DownloadCompletionReceiver {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.DownloadOpenActivity {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.DownloadHistoryActivity {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.pagedl {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.num {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.txtphoto {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.asciiart {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.QrCodeActivity {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.plugin.PluginManagerActivity {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.SettingsActivity {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.TouchpadActivity {
    public <init>(...);
}

-keep,allowoptimization public class com.coara.browser.SecretTouchpadActivity {
    public <init>(...);
}

-keepclassmembers,allowoptimization class com.coara.browser.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers,allowoptimization class com.coara.browser.SecretActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers,allowoptimization class com.coara.browser.exec$JSInterface {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers,allowoptimization class com.coara.browser.webview.BlobDownloadBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers,allowoptimization class com.coara.browser.plugin.PluginBridge {
    @android.webkit.JavascriptInterface <methods>;
}
