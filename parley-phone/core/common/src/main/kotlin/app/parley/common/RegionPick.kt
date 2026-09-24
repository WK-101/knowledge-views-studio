package app.parley.common

import java.util.Locale

/**
 * The region national phone numbers are read with. SIM first, then the network; only then locales, and among them
 * the system's own (Parley's per-app language may be a bare language such as "ar" without a country, and must never
 * decide how numbers are read). Returns "" when nothing gives a country.
 */
object RegionPick {
    fun pick(sim: String?, network: String?, systemLocaleCountries: List<String?>, defaultLocaleCountry: String?): String =
        (listOf(sim, network) + systemLocaleCountries + defaultLocaleCountry)
            .firstOrNull { it != null && it.length == 2 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' } }
            ?.uppercase(Locale.ROOT).orEmpty()
}
