# ez-vcard (used from :core:common's VCardMapper) reads two things of its property classes reflectively: the copy
# constructor (VCardProperty.copy() looks it up by the class) and the @SupportedVersions annotation (which properties a
# vCard 2.1 or 3.0 file may hold). Both survive; names, unused methods and fields may go. Its scribes are created
# with `new` in ScribeIndex and use no reflection, so R8 treats them like any other code. (The default rules keep
# runtime annotations on kept classes.)
-keep,allowobfuscation @interface ezvcard.SupportedVersions
-keep,allowobfuscation class ezvcard.property.** { <init>(...); }
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

# WorkManager instantiates workers by the class name stored in its database, so a worker that nothing in the code
# names any more (one kept only to run jobs an earlier version queued) must survive shrinking too. WorkManager's own
# rule keeps only the names of workers that are kept anyway. Every worker keeps its class and constructor.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
