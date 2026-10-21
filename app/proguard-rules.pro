# Launch It ProGuard Rules

# Keep Room entities
-keep class com.launchit.app.model.** { *; }

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainCoroutineFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

# WebView
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
    public boolean *(android.webkit.WebView, java.lang.String);
}
