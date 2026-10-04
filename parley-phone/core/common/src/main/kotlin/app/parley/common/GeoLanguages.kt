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

    /**
     * Country calling codes whose area names don't ship at all: China (86) and Australia (61), the two largest files
     * (about 580 KB of the APK together). app/build.gradle.kts drops them (keep the two lists in step). Numbers from
     * these countries are described by the country alone, the geocoder's own answer when it knows no area.
     */
    val COUNTRIES_WITHOUT_AREAS: Set<Int> = setOf(86, 61)

    /** Whether the area names ("Mountain View, CA") of calling code [countryCode] are in the APK. */
    fun hasAreaNames(countryCode: Int): Boolean = countryCode !in COUNTRIES_WITHOUT_AREAS

    /** The language to ask the geocoder in for an app or system [language] code. */
    fun forLanguage(language: String?): String = language?.lowercase(Locale.ROOT)?.takeIf { it in SHIPPED } ?: "en"
}
