# kotlinx.serialization keeps its own rules via the library; keep our @Serializable models' serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.dcrux.** {
    *** Companion;
}
-keepclasseswithmembers class app.dcrux.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Jsoup optional dependency
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**
# JavaScript bridge used by the YouTube player page
-keepclassmembers class app.dcrux.ui.video.PlayerBridge {
    @android.webkit.JavascriptInterface <methods>;
}
