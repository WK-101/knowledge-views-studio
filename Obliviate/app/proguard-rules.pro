# Obliviate ProGuard rules.
# Minification is disabled for release by default (see build.gradle.kts) to
# guarantee reproducible sideloadable builds. These rules are here for when
# shrinking is later enabled.
-keepattributes *Annotation*
-keep class com.obliviate.app.** { *; }
