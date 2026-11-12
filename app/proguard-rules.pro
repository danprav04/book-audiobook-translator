# Proguard rules for Polyglot Books app module
# Preserves Kotlin reflection, Coroutines, Room, Tink, OkHttp, Foliate WebView JavascriptInterface

# Foliate JavascriptInterface & ReaderBridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class io.github.danprav04.polyglotbooks.feature.reader.ReaderBridge { *; }

# Media3 & ExoPlayer
-keep class androidx.media3.** { *; }

# Tink Security
-keep class com.google.crypto.tink.** { *; }
