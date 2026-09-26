package app.parley.data

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

/**
 * Offline phone-number facts via libphonenumber's bundled data: where a number is from
 * ("Mountain View, CA" / "Germany"), its region, and a flag emoji. Never touches the network.
 */
object NumberInfo {
    private val util by lazy { PhoneNumberUtil.getInstance() }
    private val geocoder by lazy { PhoneNumberOfflineGeocoder.getInstance() }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun location(number: String?, countryIso: String, locale: Locale = Locale.getDefault()): String? {
        if (number.isNullOrBlank()) return null
        val key = "$number|$countryIso|${locale.language}"
        cache[key]?.let { return it.ifEmpty { null } }
        val result = try {
            val parsed = util.parse(number, countryIso.uppercase(Locale.ROOT))
            if (!util.isValidNumber(parsed)) null
            else {
                val sameCountry = util.getRegionCodeForNumber(parsed) == countryIso.uppercase(Locale.ROOT)
                geocoder.getDescriptionForNumber(parsed, locale, if (sameCountry) countryIso.uppercase(Locale.ROOT) else null).ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
        cache[key] = result.orEmpty()
        return result
    }

    fun region(number: String?, countryIso: String): String? = try {
        if (number.isNullOrBlank()) null else util.getRegionCodeForNumber(util.parse(number, countryIso.uppercase(Locale.ROOT)))?.takeIf { it != "ZZ" }
    } catch (_: Exception) {
        null
    }

    private val zones by lazy { com.google.i18n.phonenumbers.PhoneNumberToTimeZonesMapper.getInstance() }

    /**
     * X1: the time zone of [number] from its country and area code (offline, libphonenumber's map), or null when it
     * can't be told (unknown, or a country with several offsets and no area to go by).
     */
    fun timeZone(number: String?, countryIso: String, now: Long = System.currentTimeMillis()): java.time.ZoneId? = try {
        if (number.isNullOrBlank()) null else {
            val parsed = util.parse(number, countryIso.uppercase(Locale.ROOT))
            val ids = zones.getTimeZonesForNumber(parsed).filter { it != com.google.i18n.phonenumbers.PhoneNumberToTimeZonesMapper.getUnknownTimeZone() }
            app.parley.common.circle.GoodTime.zoneOf(ids, now)
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
