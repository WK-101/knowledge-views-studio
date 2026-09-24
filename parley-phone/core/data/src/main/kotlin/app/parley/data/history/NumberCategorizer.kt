package app.parley.data.history

import app.parley.common.history.NumberCategory
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType

/** Offline number classification with libphonenumber's `getNumberType`, relative to the SIM country. */
object NumberCategorizer {
    private val util by lazy { PhoneNumberUtil.getInstance() }

    fun categorize(number: String, simCountryIso: String): NumberCategory {
        val parsed = try {
            util.parse(number, simCountryIso.uppercase())
        } catch (_: Exception) {
            return NumberCategory.OTHER
        }
        val type = util.getNumberType(parsed)
        // Free numbers are free wherever they are.
        if (type == PhoneNumberType.TOLL_FREE) return NumberCategory.TOLL_FREE
        val region = util.getRegionCodeForNumber(parsed)
        if (region != null && !region.equals(simCountryIso, ignoreCase = true) &&
            parsed.countryCode != util.getCountryCodeForRegion(simCountryIso.uppercase())
        ) return NumberCategory.INTERNATIONAL
        return when (type) {
            PhoneNumberType.MOBILE -> NumberCategory.MOBILE
            PhoneNumberType.FIXED_LINE -> NumberCategory.LANDLINE
            PhoneNumberType.FIXED_LINE_OR_MOBILE -> NumberCategory.MOBILE_OR_LANDLINE
            else -> NumberCategory.OTHER
        }
    }
}
