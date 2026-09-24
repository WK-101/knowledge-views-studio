package app.parley

import android.content.Context
import android.content.Intent
import app.parley.common.Decision
import app.parley.common.CallType
import app.parley.common.PhoneNumbers
import app.parley.data.db.CallNoteEntity
import app.parley.common.Verification
import app.parley.data.DataContainer
import app.parley.data.NumberInfo
import app.parley.data.PhoneEnv
import app.parley.telecom.CallerDisplay
import app.parley.telecom.InCallAppearance
import app.parley.telecom.TelecomDependencies
import app.parley.telecom.ScreenOutcome
import app.parley.data.ScreenRequest
import app.parley.common.VerdictKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppTelecomDependencies(private val app: Context, private val c: DataContainer) : TelecomDependencies {

    override val appearance: StateFlow<InCallAppearance> = c.settings.settings
        .map { s -> InCallAppearance(s.themeMode, s.amoledBlack, s.dynamicColor, s.density, s.answerGesture, s.quickReplies) }
        .stateIn(c.scope, SharingStarted.Eagerly, InCallAppearance())

    override suspend fun callerInfo(number: String): CallerDisplay? = withContext(Dispatchers.IO) {
        val last = lastCallSummary(number)
        c.contacts.lookup(number)?.let {
            val note = it.lookupKey?.let { k -> c.meta.meta(k)?.pinnedNote }
            CallerDisplay(it.name, it.photoUri, it.numberLabel, it.contactId, it.lookupKey, note, last)
        } ?: c.vault.lookup(number)?.let { (_, info) -> CallerDisplay(info.name, null, info.numberLabel, null, null, null, last) }
    }

    private fun lastCallSummary(number: String): String? {
        val key = PhoneNumbers.matchKey(number)
        val prev = c.callLog.calls.value.orEmpty().firstOrNull { PhoneNumbers.matchKey(it.number) == key } ?: return null
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(prev.date, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
        val kind = when (prev.type) {
            CallType.MISSED -> "Missed call"
            CallType.OUTGOING -> "You called"
            else -> "Last call"
        }
        val dur = prev.durationSec.takeIf { it > 0 }?.let { " · ${it / 60}m ${it % 60}s" }.orEmpty()
        return "$kind $ago$dur"
    }

    override fun describeNumber(number: String): String? = NumberInfo.location(number, PhoneEnv.countryIso(app))

    override fun unknownRingtone(): String? = c.settings.settings.value.unknownRingtone

    override fun saveCallNote(number: String?, connectTimeMillis: Long, text: String) {
        c.scope.launch {
            c.meta.addCallNote(
                CallNoteEntity(
                    numberKey = PhoneNumbers.matchKey(number), callDate = if (connectTimeMillis > 0) connectTimeMillis else System.currentTimeMillis(), text = text,
                ),
            )
        }
    }

    override fun onCallEnded(number: String?, incoming: Boolean, connectTimeMillis: Long) {
        if (number.isNullOrBlank()) return
        c.scope.launch {
            if (!c.settings.current().privateVaultHistory) return@launch
            if (c.vault.lookup(number) == null) return@launch
            // Telecom writes the call log shortly after the call ends; sweep a few times.
            repeat(3) {
                kotlinx.coroutines.delay(2500)
                c.vault.sweepCallLog(System.currentTimeMillis() - 6 * 60 * 60 * 1000L)
            }
        }
    }

    override fun screeningActive(): Boolean = c.screener.isActive()

    override suspend fun screen(number: String?, hidden: Boolean, verification: Verification): Decision =
        withContext(Dispatchers.IO) { c.screener.screen(number, hidden, verification) }

    // ---- Blocking & screening (B2, B9, B10, B23, B24) ----

    override suspend fun screenCall(number: String?, hidden: Boolean, verification: Verification, accountId: String?, callerName: String?): ScreenOutcome =
        withContext(Dispatchers.IO) {
            val r = c.screener.screenCall(ScreenRequest(number, hidden, verification, accountId, callerName))
            ScreenOutcome(
                decision = r.decision,
                verdict = r.verdict?.text,
                warn = r.verdict?.kind == VerdictKind.LIKELY_SPAM,
                ringtone = r.ringtone,
                ringLoud = r.ringLoud,
            )
        }

    override fun simRulesActive(): Boolean = c.screener.hasSimRules()

    override fun startsEmergencyWindow(number: String): Boolean =
        c.settings.settings.value.screening.emergencyExtras.any { PhoneNumbers.same(it, number, PhoneEnv.countryIso(app)) }

    override fun onRingFinished(number: String?, startedAt: Long, ringMillis: Long, answered: Boolean) {
        if (number.isNullOrBlank()) return
        c.scope.launch { runCatching { c.blocks.addRing(number, startedAt, ringMillis, answered) } }
    }

    override suspend fun preferredAccountId(number: String): String? = withContext(Dispatchers.IO) { c.prefs.simFor(number) }

    override fun mainIntent(context: Context, dialpad: Boolean): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(if (dialpad) MainActivity.ACTION_ADD_CALL else Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun contactIntent(context: Context, contactId: Long?, number: String?): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_CALLER)
            .putExtra(MainActivity.EXTRA_CONTACT_ID, contactId ?: -1L)
            .putExtra(MainActivity.EXTRA_NUMBER, number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
