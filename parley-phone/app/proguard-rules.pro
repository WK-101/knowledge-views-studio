# ez-vcard is only used from :core:data; keep its property classes used reflectively.
-keep class ezvcard.property.** { *; }
-keep class ezvcard.io.scribe.** { *; }
-dontwarn ezvcard.**
-dontwarn com.fasterxml.jackson.**
-dontwarn freemarker.**
-dontwarn org.jsoup.**
