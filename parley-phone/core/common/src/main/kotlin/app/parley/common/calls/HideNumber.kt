package app.parley.common.calls

import java.util.Locale

/**
 * Hide my number (CLIR) for one outgoing call. Parley keeps no global "always hide" switch; instead the right
 * supplementary-service code is put in front of the number for that one call ([apply]), so the network is *asked* to
 * withhold the caller ID just this once. The code comes from the calling line's country ([codes]); the 3GPP default
 * `#31#`/`*31#` is used wherever the country is unknown or has no confirmed override.
 *
 * The prefix is a dialling instruction, never a number Parley keeps: [strip] takes it back off a number read from the
 * call log so Recents, number history, Insights and redial name the person and reach them with their plain number.
 *
 * Nothing here ever touches emergency numbers (the call path keeps those away before dialling), service/USSD codes or
 * internet/SIP calls ([mayPrefix]); the per-call and policy choices are resolved by [resolve].
 */
object HideNumber {
    /** The withhold ("hide") and reveal ("show") codes a line dials for one call. */
    data class Codes(val hide: String, val show: String)

    /** 3GPP TS 22.030: `#31#` withholds and `*31#` reveals the caller ID for one call. The fallback everywhere else. */
    val DEFAULT = Codes("#31#", "*31#")

    /** What to do with the caller ID for one call. */
    enum class Clir {
        /** Put the hide code in front: ask the network to withhold the number. */
        HIDE,

        /** Put the show code in front: ask the network to reveal it (for a line that withholds by default). */
        SHOW,

        /** Dial as it is: add nothing. */
        PLAIN,
    }

    /**
     * NANP countries that dial `*67`/`*82` ("North America"). The SIM/line country may be any of these; a plain US or
     * Canadian SIM is the common case, the shared +1 territories the rest.
     */
    private val NANP = setOf(
        "US", "CA", "AG", "AI", "AS", "BB", "BM", "BS", "DM", "DO", "GD", "GU", "JM", "KN", "KY", "LC", "MP", "MS",
        "PR", "SX", "TC", "TT", "VC", "VG", "VI",
    )

    /** Region overrides by the line's ISO 3166-1 alpha-2 country. Only codes confirmed on a real line ship (S2). */
    private val BY_COUNTRY: Map<String, Codes> = buildMap {
        for (c in NANP) put(c, Codes("*67", "*82"))
        put("GB", Codes("141", "1470")) // United Kingdom
        put("AU", Codes("1831", "1832")) // Australia
        put("JP", Codes("184", "186")) // Japan
    }

    /** The codes for a line in [country] (ISO 3166-1 alpha-2), or [DEFAULT] when the country is unknown or unlisted. */
    fun codes(country: String?): Codes = country?.trim()?.uppercase(Locale.ROOT)?.let { BY_COUNTRY[it] } ?: DEFAULT

    /** The code a [country] line dials to [hide] (true) or show (false) its number for one call. */
    fun code(country: String?, hide: Boolean): String = codes(country).let { if (hide) it.hide else it.show }

    /**
     * What to do for one call: the choice made for this call ([perCall], non-null) wins over any policy; otherwise any
     * policy that hides — the number's own contact ([contact]), a label it is in ([label]), or the Situation on now
     * ([situation]). [Clir.PLAIN] (nothing added) when neither a choice nor a policy asks to hide, so the caller ID is
     * left exactly as the line sends it by default. Off everywhere until something opts in.
     */
    fun resolve(perCall: Boolean?, contact: Boolean = false, label: Boolean = false, situation: Boolean = false): Clir = when {
        perCall == true -> Clir.HIDE
        perCall == false -> Clir.SHOW
        contact || label || situation -> Clir.HIDE
        else -> Clir.PLAIN
    }

    /**
     * Whether a CLIR code may be put in front of [number] on a [country] line: never for a blank number, a
     * service/USSD/MMI code ([serviceCode]), an internet/SIP call ([internet]), or a number that already begins with a
     * code ([strip] takes one off, or it starts with `*`/`#`). Emergency numbers never reach here: the call path dials
     * them on their own.
     */
    fun mayPrefix(number: String, serviceCode: Boolean, internet: Boolean = false, country: String? = null): Boolean {
        val n = number.trim()
        if (n.isEmpty() || serviceCode || internet) return false
        if (n.startsWith("*") || n.startsWith("#")) return false
        return strip(n, country) == n
    }

    /**
     * [number] to dial for [clir] on a [country] line, with the exclusions applied: [number] unchanged for
     * [Clir.PLAIN], a number that may not be prefixed ([mayPrefix]), otherwise the hide or show code in front.
     */
    fun apply(number: String, country: String?, clir: Clir, serviceCode: Boolean, internet: Boolean = false): String {
        if (clir == Clir.PLAIN || !mayPrefix(number, serviceCode, internet, country)) return number
        return code(country, hide = clir == Clir.HIDE) + number.trim()
    }

    /** Codes that contain `*` or `#`: no dialled number begins with these, so they are stripped wherever they lead. */
    private val SYMBOL = (BY_COUNTRY.values + DEFAULT).flatMap { listOf(it.hide, it.show) }.filter { it.any { ch -> ch == '*' || ch == '#' } }.distinct()

    /**
     * The digit-only codes a [country] line may dial (UK `141`/`1470`, Australia `1831`/`1832`, Japan `184`/`186`),
     * longest first; empty where the country dials a symbol code or is unknown.
     *
     * Digit codes are stripped only for the line's own country, because a number dialled elsewhere can begin with the
     * same digits: a North-American "1 410 …" or "1 860 …" would otherwise be mistaken for the UK or Japanese code.
     */
    private fun digitCodes(country: String?): List<String> =
        codes(country).let { listOf(it.hide, it.show) }.filter { it.all(Char::isDigit) }.sortedByDescending { it.length }

    /**
     * [number] with a leading CLIR code taken off, or [number] unchanged. The MMI (`#31#`/`*31#`) and North-American
     * (`*67`/`*82`) forms are removed whenever they lead, since no dialled number begins with `*` or `#`. A region
     * digit code (`141`/`1470`, `1831`/`1832`, `184`/`186`) is removed only for its own [country] line and only when a
     * trunk `0` or an international `+` follows it — a plain local number of that country begins with the trunk `0` or
     * a `+`, never with the bare code digits, so a real number is left alone.
     */
    fun strip(number: String, country: String? = null): String {
        val n = number.trim()
        for (c in SYMBOL) if (n.startsWith(c)) return n.substring(c.length).trimStart()
        for (c in digitCodes(country)) if (n.startsWith(c) && (n.getOrNull(c.length) == '0' || n.getOrNull(c.length) == '+')) return n.substring(c.length)
        return number
    }

    /**
     * Whether [number] as dialled begins with a hide code for a [country] line (so the call asked the network to
     * withhold the number). The symbol hide codes (`#31#`, `*67`) are recognised for any line; the digit code only for
     * its own country, as [strip].
     */
    fun wasHidden(number: String, country: String? = null): Boolean {
        val n = number.trim()
        if (n.startsWith("#31#") || (n.startsWith("*67") && n.length > 3)) return true
        val hide = codes(country).hide
        return hide.all(Char::isDigit) && n.startsWith(hide) && (n.getOrNull(hide.length) == '0' || n.getOrNull(hide.length) == '+')
    }

    /**
     * Whether to offer "Call again showing your number" after an outgoing call placed with the hide code ([wasHidden])
     * didn't go through. A call that connected, or that the user ended themselves, isn't offered it. Only an end that
     * looks like the other side turning it away is worth the guess: some people and lines refuse withheld numbers.
     */
    fun offerShowAgain(wasHidden: Boolean, connected: Boolean, userEnded: Boolean, code: EndCode?): Boolean {
        if (!wasHidden || connected || userEnded) return false
        return when (code) {
            EndCode.REMOTE, EndCode.REJECTED, EndCode.BUSY, EndCode.RESTRICTED -> true
            else -> false
        }
    }
}
