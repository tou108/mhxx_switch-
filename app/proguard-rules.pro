# Keep WebView JavaScript interfaces
-keepclassmembers class com.mhxx.snipe.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}
# Keep Bluetooth classes
-keep class com.mhxx.snipe.SwitchHidController { *; }
-keep class com.mhxx.snipe.MacroEngine { *; }
-keep class com.mhxx.snipe.MacroAction { *; }
