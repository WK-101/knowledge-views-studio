# :core:common exposes ez-vcard, which the updater never uses; let R8 drop it quietly.
-dontwarn ezvcard.**
-dontwarn com.fasterxml.jackson.**
-dontwarn freemarker.**
-dontwarn org.jsoup.**

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
