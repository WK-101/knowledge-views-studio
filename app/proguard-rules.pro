# ============================================================================
# Cairn — R8 / ProGuard rules
# Conservative keeps: obfuscate + strip libraries and dead resources, but never
# remove or rename classes reached reflectively (Room codegen, Hilt, WorkManager
# worker instantiation, RemoteViews/widget) or by name.
# ============================================================================

# --- Attributes (reflection-friendly + readable crash line numbers) --------
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- App code: keep the data model, DB, workers, widget, notifications ------
# Room entities/DAOs are used via generated code; workers are instantiated by
# WorkManager via class name; widget/notification receivers are referenced by name.
-keep class com.cairn.reader.data.db.** { *; }
-keep class com.cairn.reader.data.export.** { *; }
-keep class com.cairn.reader.work.** { *; }
-keep class com.cairn.reader.widget.** { *; }
-keep class com.cairn.reader.notifications.** { *; }

# --- WorkManager: keep ListenableWorker subclasses and their (Context, Params) ctor ---
-keep class * extends androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- SQLCipher (native JNI; must not be renamed/stripped) -------------------
-keep class net.zetetic.** { *; }
-keep class androidx.sqlite.** { *; }
-dontwarn net.zetetic.**

# --- Hilt / Dagger ---------------------------------------------------------
-dontwarn dagger.hilt.**
-dontwarn javax.annotation.**
-keep class dagger.hilt.** { *; }

# --- Readability4J uses slf4j; keep it quiet and intact --------------------
-dontwarn org.slf4j.**
-keep class net.dankito.readability4j.** { *; }

# --- jsoup -----------------------------------------------------------------
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- OkHttp / Okio (optional platform deps referenced but not bundled) ------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Kotlinx coroutines ----------------------------------------------------
-dontwarn kotlinx.coroutines.**

# --- WebView JS bridge (SelectorPicker exposes @JavascriptInterface) --------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Enums (values()/valueOf used when parsing stored string state) ---------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Compose is handled by AGP's bundled consumer rules --------------------
