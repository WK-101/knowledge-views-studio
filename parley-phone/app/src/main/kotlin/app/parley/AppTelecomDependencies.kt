package app.parley

import android.text.format.DateUtils
import android.util.Log
import app.parley.common.PhoneIdentity
import android.content.Context
import android.content.Intent
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
import app.parley.data.db.CallUsageEntity
import app.parley.common.Verification
import app.parley.common.calltime.CallTimePlan
import app.parley.calltime.CallTimePlanner
import app.parley.data.DataContainer
import app.parley.data.EmergencyNumbers
import app.parley.data.vault.VaultCallChoices
import app.parley.data.NumberInfo
import app.parley.data.PhoneEnv
import app.parley.telecom.CallManager
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
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calls.CallerHaptics
import app.parley.common.extras.CallerChoice
import app.parley.common.extras.CallerChoices
import app.parley.common.people.ContactRef
import app.parley.data.ScreenRequest
import app.parley.common.VerdictKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppTelecomDependencies(private val app: Context, private val c: DataContainer) : TelecomDependencies {
    private companion object {
        /** After the last call ends, this long before the full app loads. */
        const val CALL_SETTLE_MS = 10_000L
    }

    override val appearance: StateFlow<InCallAppearance> = combine(c.settings.settings, c.settings.loaded) { s, loaded ->
        // "Hide screen content" reaches the call screen; it stays secure until the settings are read.
        InCallAppearance(s.themeMode, s.amoledBlack, s.dynamicColor, s.density, s.answerGesture, s.quickReplies, secureScreen = s.secureScreen, loaded = loaded,
            callBackground = s.callBackground,
        )
        // Built inside the flow (on the container's scope), not here on the main thread in Application.onCreate.
    }.combine(flow { emitAll(c.extras.simple) }) { look, simple ->
        // Simple mode's incoming screen (large buttons, ask before declining, the caller's name spoken).
        if (!simple.enabled) look else look.copy(simpleMode = true, confirmDecline = simple.confirmDecline, speakCallerName = simple.speakName)
    }.stateIn(c.scope, SharingStarted.Eagerly, InCallAppearance())

    override suspend fun callerInfo(number: String, accountId: String?): CallerDisplay? = withContext(Dispatchers.IO) {
        val last = lastCallSummary(number, PhoneEnv.countryIso(app, accountId))
        c.contacts.lookup(number)?.let {
            if (it.work) {
                // A work-profile contact: its name and photo only (it can't be opened or noted from here).
                return@withContext CallerDisplay(it.name, it.photoUri, it.numberLabel, null, null, null, last, subtitle = app.getString(R.string.caller_work_profile))
            }
            val note = it.lookupKey?.let { k -> c.meta.meta(k)?.pinnedNote }
            // Job and company under the name.
            val org = c.contacts.organization(it.contactId)
            val cfg = c.circle.config.value
            val choices = callerChoices(it.lookupKey) { c.contacts.labelTitlesOf(it.contactId) }
            CallerDisplay(
                it.name, it.photoUri, it.numberLabel, it.contactId, it.lookupKey, note, last, backgroundUri = c.people.backgrounds.forLookupKey(it.lookupKey),
                subtitle = CallerCard.subtitle(org?.second, org?.first),
                // The last note and open promises; the call screen decides whether the lock screen may show them.
                memory = it.lookupKey?.let { k -> runCatching { memoryFor(k, it.contactId, number, cfg.memoryOnLockScreen) }.getOrNull() },
                memoryPrompt = cfg.memoryPrompt,
                pronouns = runCatching { c.contacts.pronounsOf(it.contactId) }.getOrNull(),
                vibration = choices.vibration, autoAnswerChosen = choices.autoAnswer, ownRingtone = it.customRingtone,
            )
        } ?: c.vault.lookup(number, PhoneEnv.countryIso(app, accountId))?.let { (id, info) ->
            // Discreet mode: a private contact shows as its number only, everywhere (call screen, lock screen and
            // notifications), like an unknown caller, so nothing reveals it is in the vault (as for missed calls, F14).
            if (c.settings.current().hideVault) return@withContext null
            // A private contact's card comes from its caller-ID copy, so it shows while the phone is locked.
            val card = c.vault.callerCard(id)
            // Its vibration, auto-answer and labels are in the same caller-ID copy (readable while the phone is locked).
            val choices = callerChoices(ContactRef.privateKey(id)) { c.privateLabels.titlesOf(id) }
            CallerDisplay(
                info.name, card?.photoUri, info.numberLabel, null, null, card?.note, last,
                subtitle = card?.subtitle, context = CallerCard.context(card?.context),
                pronouns = card?.pronouns, vibration = choices.vibration, autoAnswerChosen = choices.autoAnswer,
                // Its own ringtone reaches Parley's ringer through screening already.
            )
        }
    }

    /**
     * The caller's haptic caller ID and auto-answer: their own ([key]: their Parley key), else their labels' ([labels]
     * is read only when a label has either, so most calls never look labels up).
     */
    private suspend fun callerChoices(key: String?, labels: suspend () -> Set<String>): CallerChoice {
        val own = key?.let { runCatching { c.extras.choiceFor(it) }.getOrNull() } ?: CallerChoice()
        val labelPolicies = c.extras.labelCallChoices()
        if (labelPolicies.isEmpty()) return own
        val titles = runCatching { labels() }.getOrDefault(emptySet())
        return CallerChoice(
            vibration = CallerHaptics.resolve(own.vibration, titles, labelPolicies.mapNotNull { (t, p) -> p.vibration?.let { t to it } }.toMap()),
            autoAnswer = own.autoAnswer || CallerChoices.labelAutoAnswer(titles, labelPolicies),
        )
    }

    override fun autoAnswer(): CallExtrasConfig = c.callExtras.config.value

    /**
     * "Last call 3 days ago · 4 min", from the call history (archive included) once it is loaded; in a process started
     * for this call, from one small call-log query instead of loading the whole history while the phone rings.
     */
    private fun lastCallSummary(number: String, region: String): String? {
        val prev = (if (c.history.calls.value != null) c.history.lastCallWith(number, region) else c.callLog.lastCallWith(number, region))
            ?: return null
        val ago = DateUtils.getRelativeTimeSpanString(prev.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val kind = when (prev.type) {
            CallType.MISSED -> R.string.caller_last_missed
            CallType.OUTGOING -> R.string.caller_last_outgoing
            else -> R.string.caller_last_call
        }
        val line = app.getString(kind, ago)
        return prev.durationSec.takeIf { it > 0 }?.let { line + app.getString(R.string.main_separator) + Format.duration(it) } ?: line
    }

    override fun describeNumber(number: String): String? = NumberInfo.location(number, PhoneEnv.countryIso(app))

    override fun callerZone(number: String, accountId: String?): String? = NumberInfo.timeZone(number, PhoneEnv.countryIso(app, accountId))?.id

    override fun unknownRingtone(): String? = c.settings.settings.value.unknownRingtone

    override fun saveCallNote(number: String?, connectTimeMillis: Long, text: String) {
        c.scope.launch {
            c.meta.addCallNote(
                CallNoteEntity(
                    numberKey = PhoneIdentity.key(number, PhoneEnv.countryIso(app)), callDate = if (connectTimeMillis > 0) connectTimeMillis else System.currentTimeMillis(), text = text,
                ),
            )
        }
    }

    /** Newest note (not the pinned one, which the call screen already shows) and open promises. */
    private suspend fun memoryFor(lookupKey: String, contactId: Long?, number: String, onLockScreen: Boolean): CallerMemory? {
        val region = PhoneEnv.countryIso(app)
        val numbers = (
            c.contacts.contacts.value?.firstOrNull { it.lookupKey == lookupKey }?.phones?.map { it.number }
                ?: contactId?.let { runCatching { c.contacts.numbersOf(it) }.getOrNull() }.orEmpty()
            ) + number
        val keys = numbers.flatMap { PhoneIdentity.lookupKeys(it, region) }.toSet()
        val notes = c.circle.notesFor(lookupKey, keys).filter { it.source != CircleRepository.NoteSource.PINNED }
        val m = CallerMemory(
            lastNote = notes.firstOrNull()?.text?.let { Promises.preview(it) },
            promises = notes.flatMap { n -> Promises.open(n.text).map { it.text } },
            onLockScreen = onLockScreen,
        )
        return m.takeUnless { it.isEmpty }
    }

    /** The note becomes a call note (on the contact's timeline); "follow up in" sets a one-off reminder. */
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
        // A process started for this call loads the rest of the app once no call is left (Recents is often next).
        if (!c.fullStart.isOpen) {
            c.scope.launch {
                delay(CALL_SETTLE_MS)
                if (CallManager.state.value.none { it.isLive }) c.startFull()
            }
        }
        if (number.isNullOrBlank()) return
        c.scope.launch {
            if (!c.settings.current().privateVaultHistory) return@launch
            if (c.vault.lookup(number) == null) return@launch
            // Telecom writes the call log shortly after the call ends; sweep a few times.
            repeat(3) {
                delay(2500)
                c.vault.sweepCallLog(System.currentTimeMillis() - 6 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onCallUsage(number: String?, accountId: String?, incoming: Boolean, connectTimeMillis: Long, durationSec: Long) {
        // Only while a rule counts allowances: nothing about calls is kept for its own sake.
        if (c.calling.config.value.rules.none { it.hasQuota }) return
        c.scope.launch(Dispatchers.IO) {
            val found = number?.let { runCatching { c.contacts.lookup(it) }.getOrNull() }
            runCatching {
                c.callUsage.record(
                    CallUsageEntity(
                        startedAt = connectTimeMillis, durationSec = durationSec, incoming = incoming,
                        lineKey = number?.let { PhoneIdentity.key(it, PhoneEnv.countryIso(app, accountId)) }?.ifEmpty { null },
                        contactKey = found?.lookupKey?.takeIf { !found.work }, accountId = accountId,
                    ),
                )
            }.onFailure { Log.w("Parley", "Call-usage ledger write failed", it) }
        }
    }

    private val planner by lazy { CallTimePlanner(c) }

    override suspend fun callTimePlan(number: String?, accountId: String?, incoming: Boolean): CallTimePlan = planner.plan(number, accountId, incoming)

    override suspend fun silenceOverQuota(number: String, accountId: String?): Boolean = planner.silenceIncoming(number, accountId)

    override fun callHaptics(): Boolean = c.calling.config.value.haptics

    // ---- Phone ----

    override fun connectHaptic(): Boolean = c.calling.config.value.connectHaptic

    /**
     * The block rule is written before the call is declined; its id lets the call-ended screen undo it. The caller
     * waits for this to finish (never cancels it), so what the card says is what's in the database. The in-memory
     * rules the call path reads are refreshed whatever happened.
     */
    override suspend fun blockForDecline(number: String): Long? = withContext(Dispatchers.IO) {
        val pattern = runCatching { RuleTools.check(number, RuleType.EXACT, PhoneEnv.countryIso(app)).pattern.trim() }.getOrNull()
            ?: return@withContext null
        suspend fun existing() = c.blocks.allRules().any {
            it.enabled && it.kind == RuleKind.BLOCK && it.type == RuleType.EXACT && it.pattern.trim() == pattern && it.simId == null && it.schedule == null
        }
        try {
            runCatching {
                if (existing()) 0L else c.blocks.saveRule(BlockRule(pattern = pattern, type = RuleType.EXACT, note = app.getString(R.string.blk_note_block_decline)))
            }.getOrElse {
                // The write reported an error: say "blocked" only when the rule is really there (no Undo then).
                if (runCatching { existing() }.getOrDefault(false)) 0L else null
            }
        } finally {
            runCatching { c.blocks.enabledRules() } // refreshes the in-memory rules the call path reads
        }
    }

    override suspend fun undoBlockForDecline(ruleId: Long) {
        withContext(Dispatchers.IO) {
            runCatching {
                c.blocks.deleteRule(ruleId)
                c.blocks.enabledRules()
            }
        }
    }

    /** Retry places the call directly: the user already went through the checks for this number. */
    override suspend fun redial(number: String, accountId: String?): String? = withContext(Dispatchers.IO) {
        (c.placer.call(number, accountId) as? PlaceResult.Failed)?.let { PlaceFailureText.placeFailure(app, it.reason) }
    }

    // Memory only (the call path runs on the main thread): settings, rules and label ringtones are warmed at app
    // start, and anything not read yet counts as "on".
    // Private contacts' ringtones, "send to voicemail" and labels are applied by screening too (Android never sees them);
    // until the vault's flag is read (warmed at start, separately from the people settings) it counts as "on" as well.
    override fun screeningActive(): Boolean = c.screener.isActive() || !c.peoplePrefs.loaded || c.peoplePrefs.settings.value.labelRingtones.isNotEmpty() ||
        !VaultCallChoices.loaded || VaultCallChoices.any

    // ---- Blocking & screening ----

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
                    r.ringtone == null || r.contactTone -> null
                    r.allowedBy == AllowReason.RULE && r.rule?.ringtone != null -> r.rule?.title
                    r.allowedBy == AllowReason.CONTACT || r.allowedBy == null ->
                        c.peoplePrefs.settings.value.labelRingtones.entries.firstOrNull { it.value == r.ringtone }?.key
                    else -> null
                },
            )
        }

    /** Where the screener's ringtone comes from. */
    private fun ringtoneSource(r: ScreeningResult): RingtoneSource? = when {
        r.ringtone == null -> null
        r.contactTone -> RingtoneSource.CONTACT
        r.allowedBy == AllowReason.RULE && r.rule?.ringtone != null -> RingtoneSource.RULE
        r.allowedBy == AllowReason.REPEAT -> RingtoneSource.REPEAT
        r.allowedBy == AllowReason.DEFAULT -> RingtoneSource.LIKELY_SPAM
        else -> RingtoneSource.LABEL
    }

    // ---- Calls ----

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

    override fun startsEmergencyWindow(number: String): Boolean {
        val extras = c.settings.settings.value.screening.emergencyExtras
        if (extras.isEmpty()) return false
        val iso = PhoneEnv.countryIso(app)
        return extras.any { PhoneNumbers.same(it, number, iso) }
    }

    override fun isEmergencyNumber(number: String): Boolean = EmergencyNumbers.isEmergency(app, number)

    override fun onRingFinished(number: String?, startedAt: Long, ringMillis: Long, answered: Boolean) {
        if (number.isNullOrBlank()) return
        c.scope.launch { runCatching { c.blocks.addRing(number, startedAt, ringMillis, answered) } }
    }

    override suspend fun preferredAccountId(number: String): String? = withContext(Dispatchers.IO) { c.prefs.simFor(number) ?: c.extras.labelSimFor(number) }

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
