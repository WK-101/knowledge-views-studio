package app.parley.blocking

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.parley.R
import app.parley.common.BlockRule
import app.parley.common.PhoneNumbers
import app.parley.common.RuleKind
import app.parley.common.RuleTools
import app.parley.common.RuleType
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv

/** Blocking actions shared by the Blocking screen, Recents, number history, notifications and the QS tile. */
object BlockingActions {
    const val DAY = 86_400_000L

    /** B21: let unknown callers ring for [minutes] (0 = stop now). */
    suspend fun snooze(c: DataContainer, minutes: Int) {
        val until = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        c.settings.update { it.copy(screening = it.screening.copy(snoozeUntil = until)) }
    }

    fun snoozeRemaining(c: DataContainer, now: Long = System.currentTimeMillis()): Long =
        (c.settings.settings.value.screening.snoozeUntil - now).coerceAtLeast(0)

    /** B1: an allow rule for this exact number, forever or for [hours]. Replaces an older temporary one. */
    suspend fun allowNumber(c: DataContainer, number: String, hours: Int? = null, note: String? = null) {
        val iso = PhoneEnv.countryIso(c.appContext)
        val pattern = RuleTools.check(number, RuleType.EXACT, iso).pattern
        val existing = c.blocks.rules.value.firstOrNull { it.kind == RuleKind.ALLOW && it.type == RuleType.EXACT && PhoneNumbers.same(it.pattern, number, iso) }
        val rule = (existing ?: BlockRule(pattern = pattern, type = RuleType.EXACT, kind = RuleKind.ALLOW)).copy(
            enabled = true,
            expiresAt = hours?.let { System.currentTimeMillis() + it * 3_600_000L },
            note = note ?: existing?.note,
        )
        c.blocks.saveRule(rule)
    }

    /** B3: "Not spam" on a blocked call: always allow it, and stop the list that reported it from doing so again. */
    suspend fun notSpam(c: DataContainer, number: String, packId: String?) {
        allowNumber(c, number, note = c.appContext.getString(R.string.blk_not_spam))
        c.lists.suppress(packId, number, PhoneEnv.countryIso(c.appContext))
    }

    /** B22: allow every number that shares all but the last [keepDigits] digits ("the office's other lines"). */
    suspend fun allowPrefix(c: DataContainer, number: String, dropDigits: Int, name: String?) {
        val iso = PhoneEnv.countryIso(c.appContext)
        val e = PhoneNumbers.toE164(number, iso) ?: PhoneNumbers.clean(number)
        val prefix = e.dropLast(dropDigits.coerceIn(1, 6))
        c.blocks.saveRule(BlockRule(pattern = prefix, type = RuleType.PREFIX, kind = RuleKind.ALLOW, note = name?.let { c.appContext.getString(R.string.blk_note_other_lines, it) } ?: c.appContext.getString(R.string.blk_note_office_lines)))
    }

    suspend fun blockNumberRule(c: DataContainer, number: String, note: String? = null) {
        val iso = PhoneEnv.countryIso(c.appContext)
        c.blocks.addRules(listOf(BlockRule(pattern = RuleTools.check(number, RuleType.EXACT, iso).pattern, type = RuleType.EXACT, note = note)))
    }

    // ---------- Hand-offs (no permission, the user finishes in another app) ----------

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.blk_no_app), Toast.LENGTH_SHORT).show()
        false
    }

    /** B8: opens the browser with the number as a search. Always behind a confirmation (it leaves the phone). */
    fun searchWeb(context: Context, number: String, baseUrl: String) {
        val q = PhoneNumbers.toE164(number, PhoneEnv.countryIso(context)) ?: number
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(baseUrl + Uri.encode(q))).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** B25: carriers' spam short code (US, UK, Canada, Ireland…): the SMS app opens with the number filled in. */
    const val CARRIER_SPAM_SHORT_CODE = "7726"

    fun reportToCarrier(context: Context, number: String) {
        launch(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", CARRIER_SPAM_SHORT_CODE, null)).putExtra("sms_body", number))
    }

    data class Regulator(val name: String, val url: String)

    /** Official complaint pages; opened in the browser only after a confirmation. */
    fun regulatorFor(iso: String): Regulator? = when (iso.uppercase()) {
        "US" -> Regulator("FTC (donotcall.gov)", "https://www.donotcall.gov/report.html")
        "CA" -> Regulator("CRTC National Do Not Call List", "https://lnnte-dncl.gc.ca/en/Consumer/Register-your-report")
        "GB" -> Regulator("ICO", "https://ico.org.uk/make-a-complaint/nuisance-calls-and-messages/")
        "IE" -> Regulator("ComReg", "https://www.comreg.ie/")
        "FR" -> Regulator("SignalConso", "https://signal.conso.gouv.fr/")
        "DE" -> Regulator("Bundesnetzagentur", "https://www.bundesnetzagentur.de/rufnummernmissbrauch")
        "AU" -> Regulator("ACMA", "https://www.acma.gov.au/complain-about-spam-and-telemarketing")
        "NZ" -> Regulator("Department of Internal Affairs", "https://www.dia.govt.nz/Spam-Complaints")
        "ES" -> Regulator("AEPD", "https://www.aepd.es/")
        "IT" -> Regulator("Garante Privacy", "https://www.garanteprivacy.it/")
        "IN" -> Regulator("TRAI DND", "https://www.trai.gov.in/")
        "MX" -> Regulator("PROFECO (REPEP)", "https://repep.profeco.gob.mx/")
        else -> null
    }

    fun openRegulator(context: Context, r: Regulator, number: String) {
        app.parley.ui.common.Intents.copy(context, number)
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** B27: the SMS app opens with the reply filled in; Parley never sends SMS itself. */
    fun replyIntent(number: String, text: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", text)
}
