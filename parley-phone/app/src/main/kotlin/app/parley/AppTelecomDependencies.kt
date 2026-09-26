package app.parley

import android.content.Context
import android.content.Intent
import app.parley.common.Decision
import app.parley.common.BlockRule
import app.parley.common.RuleKind
import app.parley.common.RuleTools
import app.parley.common.RuleType
import app.parley.data.PlaceResult
import app.parley.blocking.DialText as PlaceFailureText
import app.parley.common.people.CallerCard
import app.parley.common.CallType
import app.parley.common.PhoneNumbers
import app.parley.data.db.CallNoteEntity
import app.parley.common.Verification
import app.parley.common.calltime.CallTimePlan
import app.parley.calltime.CallTimePlanner
import app.parley.data.DataContainer
import app.parley.data.NumberInfo
import app.parley.data.PhoneEnv
import app.parley.telecom.CallerDisplay
import app.parley.telecom.CallerMemory
import app.parley.common.circle.Promises
import app.parley.data.circle.CircleRepository
import app.parley.work.FollowUpWorker
import app.parley.telecom.InCallAppearance
import app.parley.telecom.TelecomDependencies
import app.parley.ui.common.Format
import app.parley.work.HistoryWorker
import app.parley.telecom.ScreenOutcome
import app.parley.telecom.PostCallAction
import app.parley.common.calls.RingFacts
import app.parley.common.AllowReason
import app.parley.common.ScreeningResult
import app.parley.data.TemporaryContacts
import app.parley.messaging.TemporaryContact
import app.parley.common.calls.RingtoneSource
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

    override val appearance: StateFlow<InCallAppearance> = kotlinx.coroutines.flow.combine(c.settings.settings, c.settings.loaded) { s, loaded ->
        // G3/P3: "Hide screen content" reaches the call screen; it stays secure until the settings are read.
        InCallAppearance(s.themeMode, s.amoledBlack, s.dynamicColor, s.density, s.answerGesture, s.quickReplies, secureScreen = s.secureScreen, loaded = loaded)
    }.stateIn(c.scope, SharingStarted.Eagerly, InCallAppearance())

    override suspend fun callerInfo(number: String): CallerDisplay? = callerInfo(number, null)

    override suspend fun callerInfo(number: String, accountId: String?): CallerDisplay? = withContext(Dispatchers.IO) {
        val last = lastCallSummary(number)
        c.contacts.lookup(number)?.let {
            if (it.work) {
                // I9: a work-profile contact: its name and photo only (it can't be opened or noted from here).
                return@withContext CallerDisplay(it.name, it.photoUri, it.numberLabel, null, null, null, last, subtitle = app.getString(R.string.caller_work_profile))
            }
            val note = it.lookupKey?.let { k -> c.meta.meta(k)?.pinnedNote }
            // I6: job and company under the name.
            val org = c.contacts.organization(it.contactId)
            val cfg = c.circle.config.value
            CallerDisplay(
                it.name, it.photoUri, it.numberLabel, it.contactId, it.lookupKey, note, last, backgroundUri = c.people.backgrounds.forLookupKey(it.lookupKey),
                subtitle = CallerCard.subtitle(org?.second, org?.first),
                // R8/R9: the last note and open promises; the call screen decides whether the lock screen may show them.
                memory = it.lookupKey?.let { k -> runCatching { memoryFor(k, number, cfg.memoryOnLockScreen) }.getOrNull() },
                memoryPrompt = cfg.memoryPrompt,
            )
        } ?: c.vault.lookup(number, PhoneEnv.countryIso(app, accountId))?.let { (id, info) ->
            // Discreet mode: a private contact shows as its number only, everywhere (call screen, lock screen and
            // notifications), like an unknown caller, so nothing reveals it is in the vault (as for missed calls, F14).
            if (c.settings.current().hideVault) return@withContext null
            // I6: a private contact's card comes from its caller-ID copy, so it shows while the phone is locked.
            val card = c.vault.callerCard(id)
            CallerDisplay(
                info.name, card?.photoUri, info.numberLabel, null, null, card?.note, last,
                subtitle = card?.subtitle, context = CallerCard.context(card?.context),
            )
        }
    }

    private fun lastCallSummary(number: String): String? {
        val key = PhoneNumbers.matchKey(number)
        val prev = c.callLog.calls.value.orEmpty().firstOrNull { PhoneNumbers.matchKey(it.number) == key } ?: return null
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(prev.date, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
        val kind = when (prev.type) {
            CallType.MISSED -> R.string.caller_last_missed
            CallType.OUTGOING -> R.string.caller_last_outgoing
            else -> R.string.caller_last_call
        }
        val line = app.getString(kind, ago)
        return prev.durationSec.takeIf { it > 0 }?.let { line + app.getString(R.string.main_separator) + Format.duration(it) } ?: line
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

    /** R8/R9: newest note (not the pinned one, which the call screen already shows) and open promises. */
    private suspend fun memoryFor(lookupKey: String, number: String, onLockScreen: Boolean): CallerMemory? {
        val keys = (c.contacts.contacts.value?.firstOrNull { it.lookupKey == lookupKey }?.phones?.map { PhoneNumbers.matchKey(it.number) }.orEmpty() + PhoneNumbers.matchKey(number)).toSet()
        val notes = c.circle.notesFor(lookupKey, keys).filter { it.source != CircleRepository.NoteSource.PINNED }
        val m = CallerMemory(
            lastNote = notes.firstOrNull()?.text?.let { Promises.preview(it) },
            promises = notes.flatMap { n -> Promises.open(n.text).map { it.text } },
            onLockScreen = onLockScreen,
        )
        return m.takeUnless { it.isEmpty }
    }

    /** R8: the note becomes a call note (on the contact's timeline); "follow up in" sets a one-off reminder. */
    override fun rememberAfterCall(number: String, connectTimeMillis: Long, note: String?, followUpDays: Int?) {
        c.scope.launch {
            if (!note.isNullOrBlank()) saveCallNote(number, connectTimeMillis, note)
            if (followUpDays != null) {
                val found = withContext(Dispatchers.IO) { runCatching { c.contacts.lookup(number) }.getOrNull() }
                val key = found?.lookupKey?.takeIf { !found.work } ?: return@launch
                FollowUpWorker.schedule(app, key, found.contactId, followUpDays)
            }
        }
    }

    override fun onCallEnded(number: String?, incoming: Boolean, connectTimeMillis: Long) {
        // Archive the call and check plan minutes once Telecom has written the call log.
        HistoryWorker.checkSoon(app)
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

    private val planner by lazy { CallTimePlanner(c) }

    override suspend fun callTimePlan(number: String?, accountId: String?, incoming: Boolean): CallTimePlan = planner.plan(number, accountId, incoming)

    override suspend fun silenceOverQuota(number: String, accountId: String?): Boolean = planner.silenceIncoming(number, accountId)

    override fun callHaptics(): Boolean = c.calling.config.value.haptics

    // ---- v3.2 phone (P2, P6, P7) ----

    override fun connectHaptic(): Boolean = c.calling.config.value.connectHaptic

    /** P2: the block rule is written before the call is declined; its id lets the call-ended screen undo it. */
    override suspend fun blockForDecline(number: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val pattern = RuleTools.check(number, RuleType.EXACT, PhoneEnv.countryIso(app)).pattern.trim()
            val exists = c.blocks.allRules().any {
                it.enabled && it.kind == RuleKind.BLOCK && it.type == RuleType.EXACT && it.pattern.trim() == pattern && it.simId == null && it.schedule == null
            }
            val id = if (exists) {
                0L
            } else {
                c.blocks.saveRule(BlockRule(pattern = pattern, type = RuleType.EXACT, note = app.getString(R.string.blk_note_block_decline)))
            }
            c.blocks.enabledRules() // refreshes the in-memory rules the call path reads
            id
        }.getOrNull()
    }

    override suspend fun undoBlockForDecline(ruleId: Long) {
        withContext(Dispatchers.IO) {
            runCatching {
                c.blocks.deleteRule(ruleId)
                c.blocks.enabledRules()
            }
        }
    }

    /** P6: Retry places the call directly: the user already went through the checks for this number. */
    override suspend fun redial(number: String, accountId: String?): String? = withContext(Dispatchers.IO) {
        (c.placer.call(number, accountId) as? PlaceResult.Failed)?.let { PlaceFailureText.placeFailure(app, it.reason) }
    }

    // Memory only (the call path runs on the main thread): settings, rules and label ringtones are warmed at app
    // start, and anything not read yet counts as "on".
    override fun screeningActive(): Boolean = c.screener.isActive() || !c.peoplePrefs.loaded || c.peoplePrefs.settings.value.labelRingtones.isNotEmpty()

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
                // Rule, then the label page's ringtone (resolved by the screener from its own contact lookup).
                ringtone = r.ringtone,
                ringLoud = r.ringLoud,
                deferredToSim = r.deferredToSim,
                ringtoneSource = ringtoneSource(r),
                ringtoneName = when {
                    r.ringtone == null -> null
                    r.allowedBy == AllowReason.RULE && r.rule?.ringtone != null -> r.rule?.title
                    r.allowedBy == AllowReason.CONTACT || r.allowedBy == null ->
                        c.peoplePrefs.settings.value.labelRingtones.entries.firstOrNull { it.value == r.ringtone }?.key
                    else -> null
                },
            )
        }

    /** V9: where the screener's ringtone comes from. */
    private fun ringtoneSource(r: ScreeningResult): RingtoneSource? = when {
        r.ringtone == null -> null
        r.allowedBy == AllowReason.RULE && r.rule?.ringtone != null -> RingtoneSource.RULE
        r.allowedBy == AllowReason.REPEAT -> RingtoneSource.REPEAT
        r.allowedBy == AllowReason.DEFAULT -> RingtoneSource.LIKELY_SPAM
        else -> RingtoneSource.LABEL
    }

    // ---- v3.1 calls (V4, V6, V9) ----

    override fun proximityEnabled(): Boolean = c.callExtras.config.value.proximitySensor

    override fun onRingFacts(number: String?, facts: RingFacts) {
        c.scope.launch(Dispatchers.IO) {
            runCatching {
                // Calls with private contacts leave no trace outside the vault when "Private call history" is on.
                if (number != null && c.settings.current().privateVaultHistory && c.vault.lookup(number) != null) return@runCatching
                // Android played the tone: say whether it was the contact's own or the default.
                val refined = if (facts.ringtone == RingtoneSource.SYSTEM && number != null) {
                    facts.copy(ringtone = if (contactRingtone(number) != null) RingtoneSource.CONTACT else RingtoneSource.DEFAULT)
                } else {
                    facts
                }
                c.ringFacts.add(number, refined)
            }
        }
    }

    private fun contactRingtone(number: String): String? = runCatching { c.contacts.lookup(number)?.customRingtone }.getOrNull()

    override fun postCallIntent(context: Context, action: PostCallAction, number: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_POST_CALL)
            .putExtra(MainActivity.EXTRA_POST_CALL_ACTION, action.name)
            .putExtra(MainActivity.EXTRA_NUMBER, number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override suspend fun savePrivately(number: String, name: String): String? = withContext(Dispatchers.IO) {
        val saved = runCatching { TemporaryContacts.save(c, name, number, private = true) }.getOrNull() ?: return@withContext null
        val days = TemporaryContacts.DEFAULT_DAYS
        app.resources.getQuantityString(if (saved.private) R.plurals.caller_saved_private_days else R.plurals.caller_saved_days, days, days)
    }

    override fun suggestedName(number: String): String {
        val iso = PhoneEnv.countryIso(app)
        val shown = TemporaryContact.suggestedName(number, null, iso.uppercase())
        return NumberInfo.location(number, iso)?.let { it + app.getString(R.string.main_separator) + shown } ?: shown
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
