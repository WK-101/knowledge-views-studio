package app.parley.common.calls

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber.CountryCodeSource
import com.google.i18n.phonenumbers.ShortNumberInfo
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Assisted dialling abroad (L6), offline with libphonenumber. While a SIM is abroad (its network's country isn't the
 * SIM's), a number typed or saved in the SIM's home format ("020 7946 0958") would reach the visited country's
 * network as a local number. [convert] offers the international form ("+44 20 7946 0958") instead; the user can
 * still dial it as typed. [localSimHint] suggests, once per trip, a second SIM that is local where the user is.
 *
 * Emergency numbers, short codes, USSD/MMI codes, service numbers (freephone, premium, shared cost…) and numbers
 * that already carry a country code are never touched.
 */
object AssistedDial {
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }
    private val short: ShortNumberInfo by lazy { ShortNumberInfo.getInstance() }

    /** Shortest national number that is converted; anything shorter is a short code or a service number. */
    private const val MIN_DIGITS = 6

    /** Number types that are personal lines reachable from abroad; anything else (services) is left as typed. */
    private val LINES = setOf(
        PhoneNumberType.FIXED_LINE, PhoneNumberType.MOBILE, PhoneNumberType.FIXED_LINE_OR_MOBILE,
        PhoneNumberType.VOIP, PhoneNumberType.PERSONAL_NUMBER,
    )

    /** One SIM as telephony reports it now. Countries are ISO 3166 codes; null when unknown (no service, no SIM). */
    data class Sim(
        val id: String,
        val label: String,
        val simCountry: String?,
        val networkCountry: String?,
        /** TelephonyManager.isNetworkRoaming (also true for national roaming, which isn't "abroad"). */
        val roaming: Boolean = false,
    )

    /** The SIM is in another country than its own: both countries are known and differ. */
    fun abroad(sim: Sim): Boolean {
        val home = iso(sim.simCountry) ?: return false
        val there = iso(sim.networkCountry) ?: return false
        return home != there
    }

    /**
     * What to dial instead of [typed]: [dial] (E.164, with any pause or wait digits kept), shown as [shown]
     * ("+44 20 7946 0958"). [home] is the SIM's country, [visited] where the phone is. [alsoLocal]: the number is also
     * a valid number in [visited], so dialling it as typed may be what the user meant.
     */
    data class Plan(val dial: String, val shown: String, val home: String, val visited: String, val alsoLocal: Boolean)

    /**
     * The international form of [typed] for a call on [sim], or null to dial it as typed. [emergency]: the platform's
     * check said it is an emergency number (never rewritten; see [EmergencyPolicy]).
     */
    fun convert(typed: String, sim: Sim, emergency: Boolean = false): Plan? {
        if (emergency || !abroad(sim)) return null
        val home = iso(sim.simCountry) ?: return null
        val visited = iso(sim.networkCountry) ?: return null
        val (digits, tail) = split(typed) ?: return null
        // Both countries share a calling code (the US and Canada, the UK and Jersey): the number works as typed.
        val homeCode = util.getCountryCodeForRegion(home)
        if (homeCode == 0 || homeCode == util.getCountryCodeForRegion(visited)) return null
        if (shortOrEmergency(digits, home) || shortOrEmergency(digits, visited)) return null
        val n = homeLine(digits, home, homeCode) ?: return null
        val alsoLocal = runCatching { util.isValidNumberForRegion(util.parse(digits, visited), visited) }.getOrDefault(false)
        return Plan(
            dial = util.format(n, PhoneNumberFormat.E164) + tail,
            shown = util.format(n, PhoneNumberFormat.INTERNATIONAL) + tail,
            home = home,
            visited = visited,
            alsoLocal = alsoLocal,
        )
    }

    /**
     * [typed] as its digits and the pause (,) or wait (;) digits that follow them unchanged (an extension, a PIN), or
     * null when it isn't a plain number: "+", "*", "#" and letters mean the user wrote something else on purpose.
     */
    private fun split(typed: String): Pair<String, String>? {
        val raw = EmergencyPolicy.asciiDigits(typed.trim())
        if (raw.isEmpty() || EmergencyPolicy.isFallbackEmergencyNumber(raw)) return null
        val cut = raw.indexOfFirst { it == ',' || it == ';' }
        val base = if (cut >= 0) raw.substring(0, cut) else raw
        if (base.any { !it.isDigit() && it !in SEPARATORS }) return null
        val digits = base.filter { it.isDigit() }
        if (digits.length < MIN_DIGITS) return null
        return digits to (if (cut >= 0) raw.substring(cut) else "")
    }

    /** [digits] read as a personal line in [home]'s national format, or null (another form, invalid, a service). */
    private fun homeLine(digits: String, home: String, homeCode: Int): PhoneNumber? {
        val n = try {
            util.parseAndKeepRawInput(digits, home)
        } catch (_: NumberParseException) {
            return null
        }
        // "0044…" or "011…": an international prefix already says where to go.
        if (n.countryCodeSource != CountryCodeSource.FROM_DEFAULT_COUNTRY || n.countryCode != homeCode) return null
        return n.takeIf { util.isValidNumberForRegion(it, home) && util.getNumberType(it) in LINES }
    }

    /** An emergency number or a short code (directory enquiries, helplines, the carrier's services) in [region]. */
    private fun shortOrEmergency(digits: String, region: String): Boolean {
        if (short.isEmergencyNumber(digits, region)) return true
        if (digits.length > MAX_SHORT) return false
        val n = try {
            util.parse(digits, region)
        } catch (_: NumberParseException) {
            return false
        }
        return short.isValidShortNumberForRegion(n, region)
    }

    /** Short codes are at most this long (EU 116 xxx, French 118 xxx are six). */
    private const val MAX_SHORT = 8

    /** A second SIM that is local where a roaming SIM is: "SIM 1 is roaming; SIM 2 is local". [trip] is remembered. */
    data class LocalSimHint(val roaming: Sim, val local: Sim, val trip: String)

    /**
     * Suggest [sims]' local SIM when the call goes out on [chosenId] while it is abroad, once per trip: nothing when
     * [hintedTrip] is this trip already. A local SIM has service in the same country as the roaming one and is at home
     * there.
     */
    fun localSimHint(sims: List<Sim>, chosenId: String?, hintedTrip: String?): LocalSimHint? {
        val chosen = sims.firstOrNull { it.id == chosenId } ?: return null
        if (!abroad(chosen)) return null
        val there = iso(chosen.networkCountry) ?: return null
        val local = sims.firstOrNull { it.id != chosen.id && iso(it.networkCountry) == there && iso(it.simCountry) == there } ?: return null
        val trip = trip(chosen) ?: return null
        if (trip == hintedTrip) return null
        return LocalSimHint(chosen, local, trip)
    }

    /** "<SIM id>|<visited country>": one trip of one SIM, for "once per trip". */
    fun trip(sim: Sim): String? = if (abroad(sim)) sim.id + "|" + iso(sim.networkCountry) else null

    /** The remembered [hintedTrip] is over (that SIM is home again, or elsewhere): forget it so the next trip asks. */
    fun tripOver(sims: List<Sim>, hintedTrip: String?): Boolean {
        if (hintedTrip.isNullOrEmpty()) return false
        val id = hintedTrip.substringBeforeLast('|')
        val sim = sims.firstOrNull { it.id == id } ?: return false
        return trip(sim) != hintedTrip
    }

    private const val SEPARATORS = " -(). /"

    private fun iso(c: String?): String? = c?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
}

/** Settings › Calls › Abroad: both on by default, since they only ever act while a SIM is abroad. */
@Serializable
data class AssistedDialConfig(
    /** Offer the international form of a number in the home format ([AssistedDial.convert]). */
    val assistedDialling: Boolean = true,
    /** Suggest a local SIM once per trip ([AssistedDial.localSimHint]). */
    val localSimHint: Boolean = true,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String?): AssistedDialConfig =
            if (text.isNullOrBlank()) AssistedDialConfig() else runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(AssistedDialConfig())

        fun encode(c: AssistedDialConfig): String = json.encodeToString(serializer(), c)
    }
}
