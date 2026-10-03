package app.parley.data

import app.parley.common.LineType
import app.parley.common.NumberValidity
import app.parley.common.PhoneIdentity
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Offline libphonenumber facts that screening rules use: region, line type, validity. */
data class NumberFactsResult(val region: String?, val lineType: LineType, val validity: NumberValidity)

object NumberFacts {
    private val util by lazy { PhoneNumberUtil.getInstance() }
    private val cache = ConcurrentHashMap<String, NumberFactsResult>()
    private val UNKNOWN = NumberFactsResult(null, LineType.UNKNOWN, NumberValidity.UNKNOWN)

    fun of(number: String?, countryIso: String): NumberFactsResult {
        if (number.isNullOrBlank()) return UNKNOWN
        val first = PhoneIdentity.forwardedParts(number).first()
        // Short codes, service numbers and alphanumeric senders are never judged "invalid".
        if (PhoneIdentity.digits(first).length < 6 || PhoneIdentity.isServiceCode(first)) return UNKNOWN
        val key = "$first|$countryIso"
        cache[key]?.let { return it }
        val r = try {
            val parsed = util.parse(PhoneIdentity.e164(first, countryIso) ?: first, countryIso.uppercase(Locale.ROOT))
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
