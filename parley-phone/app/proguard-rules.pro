# ez-vcard (used from :core:common's VCardMapper): keep its property classes and scribes, used reflectively.
-keep class ezvcard.property.** { *; }
-keep class ezvcard.io.scribe.** { *; }
-dontwarn ezvcard.**
-dontwarn com.fasterxml.jackson.**
-dontwarn freemarker.**
-dontwarn org.jsoup.**

# Defence in depth: debug/verbose logging never reaches release builds (no phone numbers in logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
