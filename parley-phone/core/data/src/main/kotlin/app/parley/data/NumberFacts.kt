package app.parley.data

import app.parley.common.LineType
import app.parley.common.NumberValidity
import app.parley.common.PhoneNumbers
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** Offline libphonenumber facts that screening rules use: region, line type, validity (B14, B20, B10, B11). */
data class NumberFactsResult(val region: String?, val lineType: LineType, val validity: NumberValidity)

object NumberFacts {
    private val util by lazy { PhoneNumberUtil.getInstance() }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, NumberFactsResult>()
    private val UNKNOWN = NumberFactsResult(null, LineType.UNKNOWN, NumberValidity.UNKNOWN)

    fun of(number: String?, countryIso: String): NumberFactsResult {
        if (number.isNullOrBlank()) return UNKNOWN
        val first = PhoneNumbers.forwardedParts(number).first()
        // Short codes, service numbers and alphanumeric senders are never judged "invalid".
        if (PhoneNumbers.digits(first).length < 6 || PhoneNumbers.isServiceCode(first)) return UNKNOWN
        val key = "$first|$countryIso"
        cache[key]?.let { return it }
        val r = try {
            val parsed = util.parse(PhoneNumbers.toE164(first, countryIso) ?: first, countryIso.uppercase(Locale.ROOT))
            val validity = when {
                !util.isPossibleNumber(parsed) -> NumberValidity.IMPOSSIBLE
                !util.isValidNumber(parsed) -> NumberValidity.INVALID
                else -> NumberValidity.VALID
            }
            val region = util.getRegionCodeForNumber(parsed)?.takeIf { it != "ZZ" }
            val type = if (validity == NumberValidity.VALID) mapType(util.getNumberType(parsed)) else LineType.UNKNOWN
            NumberFactsResult(region, type, validity)
        } catch (_: Exception) {
            // Unparseable input is not proof of an invalid number: stay neutral.
            UNKNOWN
        }
        if (cache.size > 500) cache.clear()
        cache[key] = r
        return r
    }

    private fun mapType(t: PhoneNumberUtil.PhoneNumberType): LineType = when (t) {
        PhoneNumberUtil.PhoneNumberType.MOBILE -> LineType.MOBILE
        PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> LineType.FIXED_LINE
        PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE -> LineType.FIXED_LINE_OR_MOBILE
        PhoneNumberUtil.PhoneNumberType.TOLL_FREE -> LineType.TOLL_FREE
        PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE -> LineType.PREMIUM_RATE
        PhoneNumberUtil.PhoneNumberType.SHARED_COST -> LineType.SHARED_COST
        PhoneNumberUtil.PhoneNumberType.VOIP -> LineType.VOIP
        PhoneNumberUtil.PhoneNumberType.PERSONAL_NUMBER -> LineType.PERSONAL_NUMBER
        PhoneNumberUtil.PhoneNumberType.PAGER -> LineType.PAGER
        PhoneNumberUtil.PhoneNumberType.UAN -> LineType.UAN
        PhoneNumberUtil.PhoneNumberType.VOICEMAIL -> LineType.VOICEMAIL
        else -> LineType.UNKNOWN
    }
}
