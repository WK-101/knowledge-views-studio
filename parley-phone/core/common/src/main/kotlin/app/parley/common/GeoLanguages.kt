package app.parley.common

import java.util.Locale

/**
 * The languages of the offline place names ("Mountain View, CA") that ship in the APK. libphonenumber's geocoder has
 * files for about 35 languages; the APK keeps English and the app's languages it has data for, and
 * app/build.gradle.kts drops the rest (keep the two lists in step). Asking for a dropped language would read a
 * file that isn't there, so any other language asks in English, which is also the geocoder's own fallback.
 */
object GeoLanguages {
    val SHIPPED: Set<String> = setOf("en", "de", "es", "fr", "pt", "ar")

    /** The language to ask the geocoder in for an app or system [language] code. */
    fun forLanguage(language: String?): String = language?.lowercase(Locale.ROOT)?.takeIf { it in SHIPPED } ?: "en"
}
