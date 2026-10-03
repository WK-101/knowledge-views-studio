# :core:common exposes ez-vcard, which the updater never uses; let R8 drop it quietly.
-dontwarn ezvcard.**
-dontwarn com.fasterxml.jackson.**
-dontwarn freemarker.**
-dontwarn org.jsoup.**

# WorkManager instantiates workers by the class name stored in its database: keep every worker and its constructor.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
