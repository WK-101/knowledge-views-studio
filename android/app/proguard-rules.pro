# ============================================================================
# Kairo — R8 keep rules (R85).
#
# Release builds now run R8 with CODE + RESOURCE shrinking (isMinifyEnabled +
# isShrinkResources), which strips unused classes/methods and unused resources.
# OBFUSCATION IS DELIBERATELY OFF (-dontobfuscate): renaming is the single
# biggest source of hard-to-diagnose runtime breakage in a data-critical,
# reflection-touching app, and this build cannot be profiled/smoke-tested in
# CI. Keeping names also means crash stack traces stay human-readable for an
# app that ships no network crash reporting — the user can read a trace
# directly. Shrinking (not renaming) carries the size/startup win.
#
# The reflective surface was audited (R85): kotlinx.serialization (59
# @Serializable types drive backup/settings JSON), SQLCipher's JNI, Room's
# generated code, ZXing, and manifest-declared components. No Class.forName /
# getIdentifier / newInstance anywhere. The rules below cover exactly that set.
# ============================================================================

-dontobfuscate
-verbose

# ----------------------------------------------------------------------------
# kotlinx.serialization — the backup/restore + settings format depends on the
# generated $$serializer descriptors surviving the shrink. Without these the
# JSON schema silently changes and old backups fail to restore (data loss).
# (Official rules from Kotlin/kotlinx.serialization, kept explicitly rather
# than relying on bundled consumer rules.)
# ----------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses
-dontnote kotlinx.serialization.**

# Keep the Companion of every @Serializable class.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
# Keep serializer() on companion objects (default and named) of @Serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep INSTANCE.serializer() of @Serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
# Belt-and-braces: keep every generated serializer under the app package.
-keep,includedescriptorclasses class com.todocompanion.app.**$$serializer { *; }
-keepclassmembers class com.todocompanion.app.** {
    *** Companion;
}

# ----------------------------------------------------------------------------
# SQLCipher (net.zetetic:android-database-sqlcipher 4.5.4) — JNI bindings; the
# native library resolves these classes/methods by name, so keep all of them.
# ----------------------------------------------------------------------------
-keep class net.sqlcipher.** { *; }
-keep interface net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# ----------------------------------------------------------------------------
# Room — generated *_Impl classes and the database subclass. Room ships its own
# consumer rules; this is a safety net.
# ----------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ----------------------------------------------------------------------------
# ZXing (offline QR encoding) — small, pure-Java; keep to avoid stripping the
# reflectively-selected format writers.
# ----------------------------------------------------------------------------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ----------------------------------------------------------------------------
# Enums used with enumValueOf<>() from stored strings (ThemePrefs, Settings).
# proguard-android-optimize.txt already keeps values()/valueOf(String); this
# makes the intent explicit for the app's serialized enums.
# ----------------------------------------------------------------------------
-keepclassmembers enum com.todocompanion.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ----------------------------------------------------------------------------
# Common absent-at-compile-time references pulled in transitively; silence the
# shrink so the build does not fail on them.
# ----------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
