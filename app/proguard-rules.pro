# Cairn ProGuard/R8 rules

# Readability4J uses slf4j; keep it quiet and intact.
-dontwarn org.slf4j.**
-keep class net.dankito.readability4j.** { *; }

# jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Kotlinx coroutines
-dontwarn kotlinx.coroutines.**

# Keep Room generated + entities (Room handles most, but be safe with reflection-free setup)
-keep class com.cairn.reader.data.db.** { *; }
