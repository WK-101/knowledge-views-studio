package app.parley.common.phone

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.prefixmapper.PhonePrefixMap
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a number is from ("Mountain View, CA", or the country), offline: libphonenumber's offline geocoder, reading the
 * area names from [PhoneData.AREA_NAMES] rather than from hundreds of class-path files. The answers are the geocoder's
 * own (`PhoneNumberOfflineGeocoder.getDescriptionForNumber`; AreaNamesTest compares the two), for the languages whose
 * names ship ([app.parley.common.GeoLanguages]).
 */
object AreaNames {
    private val util: PhoneNumberUtil get() = PhoneData.util

    /** Area-name files read so far, by name ("1650_en"); an empty map stands for a file that isn't there. */
    private val maps = ConcurrentHashMap<String, PhonePrefixMap>()
    private val missing = PhonePrefixMap()

    /**
     * The area of [number] when it is from [userRegion] (the phone's country), else its country, in [language]; empty
     * when it can't be told.
     */
    fun describe(number: PhoneNumber, language: Locale, userRegion: String): String {
        val type = util.getNumberType(number)
        return when {
            type == PhoneNumberUtil.PhoneNumberType.UNKNOWN -> ""
            !util.isNumberGeographical(type, number.countryCode) -> countryName(number, language)
            util.getRegionCodeForNumber(number) == userRegion -> area(number, language)
            else -> regionName(util.getRegionCodeForNumber(number), language)
        }
    }

    private fun area(number: PhoneNumber, language: Locale): String {
        // Some countries (Argentina) put a mobile token before the area code: it goes before looking the area up.
        val token = PhoneNumberUtil.getCountryMobileToken(number.countryCode)
        val national = util.getNationalSignificantNumber(number)
        val lookFor = if (token.isNotEmpty() && national.startsWith(token)) {
            try {
                util.parse(national.substring(token.length), util.getRegionCodeForCountryCode(number.countryCode))
            } catch (_: NumberParseException) {
                number
            }
        } else {
            number
        }
        return lookup(lookFor, language.language).ifEmpty { countryName(number, language) }
    }

    /** The area name in [lang], else in English (as the geocoder falls back, except for Chinese, Japanese and Korean). */
    private fun lookup(number: PhoneNumber, lang: String): String {
        val cc = number.countryCode
        // Numbers in North America are split into one file per area code ("1650"), the rest by country code.
        val prefix = if (cc != 1) cc else NANPA_BASE + (number.nationalNumber / NANPA_AREA_DIVISOR).toInt()
        val found = map("${prefix}_$lang")?.lookup(number)
        if (!found.isNullOrEmpty() || lang in NO_ENGLISH_FALLBACK) return found.orEmpty()
        return map("${prefix}_en")?.lookup(number).orEmpty()
    }

    private fun map(file: String): PhonePrefixMap? = maps.getOrPut(file) {
        PhoneData.indexedEntry(PhoneData.AREA_NAMES, file)?.let { bytes ->
            PhonePrefixMap().apply { ObjectInputStream(ByteArrayInputStream(bytes)).use { readExternal(it) } }
        } ?: missing
    }.takeIf { it !== missing }

    private fun countryName(number: PhoneNumber, language: Locale): String {
        val regions = util.getRegionCodesForCountryCode(number.countryCode)
        if (regions.size == 1) return regionName(regions[0], language)
        // Shared country codes (+1, +7, +44…): the one region where the number is valid, or nothing if several.
        val valid = regions.filter { util.isValidNumberForRegion(number, it) }
        return if (valid.size == 1) regionName(valid[0], language) else ""
    }

    private fun regionName(region: String?, language: Locale): String = when (region) {
        null, UNKNOWN_REGION, NON_GEO_REGION -> ""
        else -> Locale.Builder().setRegion(region).build().getDisplayCountry(language)
    }

    private const val NANPA_BASE = 1000
    private const val NANPA_AREA_DIVISOR = 10_000_000L
    private const val UNKNOWN_REGION = "ZZ"
    private const val NON_GEO_REGION = "001"
    private val NO_ENGLISH_FALLBACK = setOf("zh", "ja", "ko")
}
