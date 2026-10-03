# ez-vcard (used from :core:common's VCardMapper): keep its property classes and scribes, used reflectively.
-keep class ezvcard.property.** { *; }
-keep class ezvcard.io.scribe.** { *; }
-dontwarn ezvcard.**
-dontwarn com.fasterxml.jackson.**
-dontwarn freemarker.**
-dontwarn org.jsoup.**

# Defence in depth: no logging reaches release builds. Warnings and errors often carry an exception whose message
# can hold a number or a name, and R8 applies these rules to every module in the app. Crash reports keep their own
# scrubbed copy (CrashStore).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
