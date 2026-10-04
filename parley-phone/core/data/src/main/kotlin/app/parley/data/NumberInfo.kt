package app.parley.data

import app.parley.common.GeoLanguages
import app.parley.common.circle.GoodTime
import com.google.i18n.phonenumbers.PhoneNumberToTimeZonesMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline phone-number facts via libphonenumber's bundled data: where a number is from
 * ("Mountain View, CA" / "Germany"), its region, and a flag emoji. Never touches the network.
 */
object NumberInfo {
    private val util by lazy { PhoneNumberUtil.getInstance() }
    private val geocoder by lazy { PhoneNumberOfflineGeocoder.getInstance() }
    private val cache = ConcurrentHashMap<String, String>()

    fun location(number: String?, countryIso: String, locale: Locale = Locale.getDefault()): String? {
        if (number.isNullOrBlank()) return null
        val key = "$number|$countryIso|${locale.language}"
        cache[key]?.let { return it.ifEmpty { null } }
        val result = try {
            val parsed = util.parse(number, countryIso.uppercase(Locale.ROOT))
            if (!util.isValidNumber(parsed)) null
            else {
                val sameCountry = util.getRegionCodeForNumber(parsed) == countryIso.uppercase(Locale.ROOT)
                // Only some languages' place names ship (see GeoLanguages); others ask in English.
                val lang = Locale.forLanguageTag(GeoLanguages.forLanguage(locale.language))
                if (sameCountry && !GeoLanguages.hasAreaNames(parsed.countryCode)) {
                    // Its area file isn't in the APK, and the geocoder would fail reading it: the country alone.
                    countryName(countryIso, lang)
                } else {
                    // With the phone's own country: a number from there gets its area, a foreign one its country (a
                    // null region would make the geocoder fail, and a foreign area file may not be in the APK).
                    geocoder.getDescriptionForNumber(parsed, lang, countryIso.uppercase(Locale.ROOT)).ifBlank { null }
                }
            }
        } catch (_: Exception) {
            null
        }
        cache[key] = result.orEmpty()
        return result
    }

    /** A country's name in [lang], as the geocoder words it when it has no area for a number. */
    private fun countryName(countryIso: String, lang: Locale): String? =
        Locale.Builder().setRegion(countryIso.uppercase(Locale.ROOT)).build().getDisplayCountry(lang).ifBlank { null }

    /** The answer [location] already worked out, without working it out (for a first frame on the main thread). */
    fun cachedLocation(number: String?, countryIso: String, locale: Locale = Locale.getDefault()): String? =
        if (number.isNullOrBlank()) null else cache["$number|$countryIso|${locale.language}"]?.ifEmpty { null }

    /**
     * Off the main thread: loads libphonenumber's metadata and the geocoder data of [countryIso] (the country most
     * callers come from), so the first incoming call or Recents scroll doesn't pay for it.
     */
    fun warm(countryIso: String) {
        runCatching {
            val example = util.getExampleNumber(countryIso.uppercase(Locale.ROOT)) ?: return
            location(util.format(example, PhoneNumberUtil.PhoneNumberFormat.E164), countryIso)
        }
    }

    fun region(number: String?, countryIso: String): String? = try {
        if (number.isNullOrBlank()) null else util.getRegionCodeForNumber(util.parse(number, countryIso.uppercase(Locale.ROOT)))?.takeIf { it != "ZZ" }
    } catch (_: Exception) {
        null
    }

    private val zones by lazy { PhoneNumberToTimeZonesMapper.getInstance() }

    /**
     * The time zone of [number] from its country and area code (offline, libphonenumber's map), or null when it
     * can't be told (unknown, or a country with several offsets and no area to go by).
     */
    fun timeZone(number: String?, countryIso: String, now: Long = System.currentTimeMillis()): ZoneId? = try {
        if (number.isNullOrBlank()) null else {
            val parsed = util.parse(number, countryIso.uppercase(Locale.ROOT))
            val ids = zones.getTimeZonesForNumber(parsed).filter { it != PhoneNumberToTimeZonesMapper.getUnknownTimeZone() }
            GoodTime.zoneOf(ids, now)
        }
    } catch (_: Exception) {
        null
    }

    fun flag(region: String?): String? {
        if (region == null || region.length != 2) return null
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + region[0].uppercaseChar().code)) + String(Character.toChars(base + region[1].uppercaseChar().code))
    }

    fun isValid(number: String, countryIso: String): Boolean = try {
        util.isValidNumber(util.parse(number, countryIso.uppercase(Locale.ROOT)))
    } catch (_: Exception) {
        false
    }
}
