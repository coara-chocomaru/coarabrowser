-keepattributes RuntimeVisibleAnnotations,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-allowaccessmodification
-repackageclasses ''

-keepclassmembers,allowoptimization class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers,allowoptimization class * implements androidx.webkit.WebViewCompat$WebMessageListener {
    public void onPostMessage(...);
}

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

-dontnote **
