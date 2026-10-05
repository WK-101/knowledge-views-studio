package app.parley

import app.parley.common.BlockAction
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
import app.parley.common.people.NameOrder
import app.parley.common.CallType
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
import app.parley.common.circle.Agenda
import app.parley.calls.AgendaBridge
import app.parley.telecom.AgendaHooks
import app.parley.data.circle.CircleRepository
import app.parley.calls.PrivateCallLogSweep
import app.parley.calls.ToCallReminders
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallSource
import java.time.ZoneId
import app.parley.telecom.InCallAppearance
import app.parley.telecom.HelperUi
import app.parley.telecom.SafeWordPrompt
import app.parley.common.calls.SafeWords
import app.parley.calls.ExpectedCallHints
import app.parley.calls.NeverCallsYouFacts
import app.parley.telecom.TelecomDependencies
import app.parley.telecom.SimTip
import app.parley.common.catching
import app.parley.data.security.Concealment
import app.parley.calls.NumberSignals
import app.parley.telecom.MenuMemoryHooks
import app.parley.calls.MenuMemoryBridge
import app.parley.calls.CaseFileBridge
import app.parley.telecom.CaseFileHooks
import app.parley.ui.common.Format
import app.parley.work.HistoryWorker
import app.parley.telecom.ScreenOutcome
import app.parley.telecom.PostCallAction
import app.parley.blocking.BlockFlow
import app.parley.telecom.NumberMemoryLine
import app.parley.common.memory.NumberMemory
import app.parley.ui.memory.NumberMemoryText
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.CallerPhoto
import app.parley.common.people.ContactRef
import app.parley.common.calls.RingFacts
import app.parley.common.calls.SpeakerDefault
import app.parley.common.calls.VerifyCallBack
import app.parley.security.AppLock
import android.provider.ContactsContract.CommonDataKinds.Phone
import app.parley.common.AllowReason
import app.parley.common.ScreeningResult
import app.parley.data.TemporaryContacts
import app.parley.messaging.TemporaryContact
import app.parley.common.calls.RingtoneSource
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calls.DriveProfileConfig
import app.parley.common.calls.CallerHaptics
import app.parley.common.extras.CallerChoice
import app.parley.common.extras.CallerChoices
import app.parley.data.ScreenRequest
import app.parley.common.VerdictKind
import app.parley.common.BlockReason
import app.parley.common.Decision
import app.parley.common.spam.RangeProposal
import app.parley.data.calls.ReputationLearner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

class AppTelecomDependencies(private val app: Context, private val c: DataContainer) :
    TelecomDependencies,
    // I6: menu memory lives in its own bridge.
    MenuMemoryHooks by MenuMemoryBridge(app, c),
    // Case files too.
    CaseFileHooks by CaseFileBridge(app, c),
    // And the agenda (things to talk about).
    AgendaHooks by AgendaBridge(app, c) {
    private companion object {
        /** After the last call ends, this long before the full app loads. */
        const val CALL_SETTLE_MS = 10_000L
    }

    override val appearance: StateFlow<InCallAppearance> = combine(c.settings.settings, c.settings.loaded) { s, loaded ->
        // "Hide screen content" reaches the call screen; it stays secure until the settings are read.
        InCallAppearance(s.themeMode, s.amoledBlack, s.dynamicColor, s.density, s.answerGesture, s.quickReplies, secureScreen = s.secureScreen, loaded = loaded,
            callBackground = s.callBackground, lockScreenCaller = s.lockScreenCaller, nameReply = s.nameReply,
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
                val photo = it.photoUri.takeIf { c.settings.settings.value.showCallerPhoto }
                return@withContext CallerDisplay(it.name, photo, it.numberLabel, null, null, null, last, subtitle = app.getString(R.string.caller_work_profile))
            }
            val cfg = c.circle.config.value
            val lastFirst = c.settings.settings.value.showNamesLastFirst
            // The extra reads run side by side, so they stay well inside the call path's lookup time (a timeout there
            // treats a contact as unknown).
            val (parts, choices) = coroutineScope {
                // The agenda's items have a card of their own, with their own lock-screen rules.
                val note = async { it.lookupKey?.let { k -> Agenda.withoutItems(c.meta.meta(k)?.pinnedNote) } }
                // Job and company under the name.
                val org = async { c.contacts.organization(it.contactId) }
                val pronouns = async { runCatching { c.contacts.pronounsOf(it.contactId) }.getOrNull() }
                val nativeName = async { catching { c.contacts.nativeNameOf(it.contactId) }.getOrNull() }
                // The last note and open promises; the call screen decides whether the lock screen may show them.
                val memory = async { it.lookupKey?.let { k -> runCatching { memoryFor(k, it.contactId, number, cfg.memoryOnLockScreen) }.getOrNull() } }
                val choices = async { callerChoices(it.lookupKey) { c.contacts.labelTitlesOf(it.contactId) } }
                // "Show names as" last name first: the "Family, Given" form, as in the lists.
                val alternative = async { if (lastFirst) runCatching { c.contacts.alternativeName(it.contactId) }.getOrNull() else null }
                CallerParts(note.await(), org.await(), pronouns.await(), memory.await(), alternative.await(), nativeName.await()) to choices.await()
            }
            // Settings › Calls › "Show contact photo on the call screen", or the contact's own choice.
            val photo = showsPhoto(it.lookupKey)
            CallerDisplay(
                NameOrder.shown(it.name, parts.alternative, lastFirst), it.photoUri.takeIf { photo }, it.numberLabel, it.contactId, it.lookupKey,
                parts.note, last,
                backgroundUri = if (photo) c.people.backgrounds.forLookupKey(it.lookupKey) else null,
                subtitle = CallerCard.subtitle(parts.org?.second, parts.org?.first),
                memory = parts.memory,
                memoryPrompt = cfg.memoryPrompt,
                pronouns = parts.pronouns,
                nativeName = parts.nativeName,
                vibration = choices.vibration, autoAnswerChosen = choices.autoAnswer, ownRingtone = it.customRingtone,
                favourite = it.starred,
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
                // "Show names as" last name first, as for the address book's contacts above.
                NameOrder.shown(info.name, info.alternativeName, c.settings.settings.value.showNamesLastFirst),
                card?.photoUri?.takeIf { showsPhoto(ContactRef.privateKey(id)) }, info.numberLabel, null, null, Agenda.withoutItems(card?.note), last,
                subtitle = card?.subtitle, context = CallerCard.context(card?.context),
                pronouns = card?.pronouns, nativeName = card?.nativeName, vibration = choices.vibration, autoAnswerChosen = choices.autoAnswer,
                favourite = info.starred,
                // Its own ringtone reaches Parley's ringer through screening already.
            )
        } ?: archivedCaller(number, PhoneEnv.countryIso(app, accountId), last)
    }

    /**
     * An archived contact: out of the address book, still named here (and on the lock screen as the call screen's rules
     * allow), with its note for calls, which waits under its archived key.
     */
    private suspend fun archivedCaller(number: String, region: String, last: String?): CallerDisplay? {
        val card = catching { c.archive.lookup(number, region) }.getOrNull() ?: return null
        // The agenda's items have their card of their own (AgendaStore finds the archived contact too).
        val note = catching { Agenda.withoutItems(c.meta.meta(card.parleyKey)?.pinnedNote) }.getOrNull()
        val subtitle = if (card.company.isBlank()) app.getString(R.string.archive_caller) else app.getString(R.string.archive_caller_at, card.company)
        return CallerDisplay(card.name, null, null, null, null, note, last, subtitle = subtitle)
    }

    /** What [callerInfo] reads beside the contact lookup. */
    private data class CallerParts(
        val note: String?,
        val org: Pair<String, String>?,
        val pronouns: String?,
        val memory: CallerMemory?,
        /** The "Family, Given" name, read only when names show last name first. */
        val alternative: String?,
        /** Their name in their own language. */
        val nativeName: String? = null,
    )

    /** Whether the call screen shows this contact's photo and call-screen picture (read from memory). */
    private fun showsPhoto(key: String?): Boolean =
        CallerPhoto.shows(c.settings.settings.value.showCallerPhoto, runCatching { c.people.backgrounds.photoChoice(key) }.getOrNull())

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

    override fun answerWithRtt(): Boolean = c.settings.settings.value.answerWithRtt

    override fun driveProfile(): DriveProfileConfig = c.driveProfile.config.value

    /** I11: a contact, a private contact (discreet mode or not) or an archived one; only the yes or no reaches the call path. */
    override suspend fun isSavedCaller(number: String, accountId: String?): Boolean = withContext(Dispatchers.IO) {
        c.contacts.lookup(number) != null || c.vault.lookup(number, PhoneEnv.countryIso(app, accountId)) != null ||
            catching { c.archive.lookup(number, PhoneEnv.countryIso(app, accountId)) != null }.getOrDefault(false)
    }

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
            val id = c.meta.addCallNote(
                CallNoteEntity(
                    numberKey = PhoneIdentity.key(number, PhoneEnv.countryIso(app)), callDate = if (connectTimeMillis > 0) connectTimeMillis else System.currentTimeMillis(), text = text,
                ),
            )
            // I7: "they'll call back Tue" in a call note can expect that call, from that number only (a hidden or
            // unknown line opens nothing: the window would cover everyone).
            if (!number.isNullOrBlank() && id > 0) {
                runCatching {
                    val (name, isPrivate) = ExpectedCallHints.caller(c, number)
                    ExpectedCallHints.noteSaved(c, name, text, ExpectedCallHints.callNoteKey(id), number = number, privateName = isPrivate)
                }
            }
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

    /**
     * The note becomes a call note (on the contact's timeline); "follow up in" puts the person on the To call list for
     * that morning (one reminder with the other calls due then, instead of a notification of its own).
     */
    override fun rememberAfterCall(number: String, connectTimeMillis: Long, note: String?, followUpDays: Int?) {
        c.scope.launch {
            if (!note.isNullOrBlank()) saveCallNote(number, connectTimeMillis, note)
            if (followUpDays != null) {
                val now = System.currentTimeMillis()
                ToCallReminders.remind(app, number, null, ToCall.inDays(followUpDays, now, ZoneId.systemDefault()), ToCallSource.FOLLOW_UP, now)
            }
        }
    }

    /** "Decline & remind" and the post-call card's "Remind me": the To call list (I9). */
    override fun remindToCall(number: String, accountId: String?, at: Long) {
        c.scope.launch { runCatching { ToCallReminders.remind(app, number, accountId, at) } }
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
        // A private contact's call leaves the system call log as soon as Telecom writes it (and at the next start, if
        // this process ends first).
        PrivateCallLogSweep.afterCall(app, c, number)
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

    /** I2: "Block this range?" works from your own calls, gathered now (the call has ended). */
    override suspend fun rangeProposal(number: String, accountId: String?): RangeProposal? = withContext(Dispatchers.IO) {
        if (!c.settings.current().screening.learnFromCalls) return@withContext null
        runCatching { ReputationLearner.proposeRange(c, number, accountId) }.getOrNull()
    }

    /**
     * I2: a prefix rule for the range, written like "Block & decline"'s (its id lets the card undo it). It silences by
     * default (L8): a range covers a thousand numbers, mostly strangers, and repeat callers never pass a rule.
     */
    override suspend fun blockRange(prefix: String, action: BlockAction): Long? = withContext(Dispatchers.IO) {
        val pattern = runCatching { RuleTools.check(prefix, RuleType.PREFIX, PhoneEnv.countryIso(app)).pattern.trim() }.getOrNull()
            ?: return@withContext null
        suspend fun existing() = c.blocks.allRules().any {
            it.enabled && it.kind == RuleKind.BLOCK && it.type == RuleType.PREFIX && it.pattern.trim() == pattern && it.simId == null && it.schedule == null
        }
        try {
            runCatching {
                if (existing()) {
                    0L
                } else {
                    c.blocks.saveRule(BlockRule(pattern = pattern, type = RuleType.PREFIX, action = action, note = app.getString(R.string.blk_note_range)))
                }
            }.getOrElse { if (runCatching { existing() }.getOrDefault(false)) 0L else null }
        } finally {
            runCatching { c.blocks.enabledRules() }
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
                // A sales line silenced from your calls: its own quiet line on the call screen says so.
                verdict = r.verdict?.text?.takeIf { (r.decision as? Decision.Block)?.reason != BlockReason.PERSONAL_REPUTATION },
                warn = r.verdict?.kind == VerdictKind.LIKELY_SPAM,
                // Rule, then the label page's ringtone (resolved by the screener from its own contact lookup).
                ringtone = r.ringtone,
                ringLoud = r.ringLoud,
                deferredToSim = r.deferredToSim,
                ringtoneSource = ringtoneSource(r),
                rangThrough = r.rangThrough,
                reputation = r.reputation,
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

    override fun proximityOnceAnswered(): Boolean = c.callExtras.config.value.proximityOnceAnswered

    override fun speakerDefault(): SpeakerDefault = c.callExtras.config.value.speakerDefault

    override fun flipToSilence(): Boolean = c.callExtras.config.value.flipToSilence

    override fun tipSeen(id: String): Boolean = id in c.ux.state.value.seenTips

    override fun markTipSeen(id: String) {
        c.scope.launch(Dispatchers.IO) { runCatching { c.ux.dismissTip(id) } }
    }

    override fun onCallQuality(number: String?, facts: CallQualityFacts) {
        c.scope.launch(Dispatchers.IO) {
            runCatching {
                // Like ring facts: calls with private contacts leave no trace outside the vault with "Private call history" on.
                if (number != null && c.settings.current().privateVaultHistory && c.vault.lookup(number) != null) return@runCatching
                c.callQuality.add(number, facts)
            }
        }
    }

    // ---- "Calls to Ana drop less on SIM 2" ----

    override suspend fun simTipAfterDrop(number: String, facts: CallQualityFacts?): SimTip? = withContext(Dispatchers.IO) {
        // Names a person: not while Parley is locked, and never a private contact that's hidden.
        if (appLocked()) return@withContext null
        val contact = c.contacts.lookup(number)?.takeIf { !it.work }
        val (name, numbers) = if (contact != null) {
            contact.name to c.contacts.numbersOf(contact.contactId)
        } else {
            if (c.settings.current().hideVault || Concealment.hiding) return@withContext null
            val (vaultId, info) = c.vault.lookup(number) ?: return@withContext null
            info.name to (c.vault.summary(vaultId)?.numbers ?: listOf(number))
        }
        val tip = NumberSignals.simTip(c, numbers.ifEmpty { listOf(number) }, c.sims.accounts(), facts) ?: return@withContext null
        if (!NumberSignals.offerAfterCall(c, tip)) return@withContext null
        SimTip(tip.simId, tip.simLabel, name, numbers, tip.lineKeys)
    }

    override fun answerSimTip(tip: SimTip, accept: Boolean) {
        c.scope.launch(Dispatchers.IO) {
            catching { NumberSignals.answerSim(c, tip.numbers, NumberSignals.SimTip(tip.simId, tip.simLabel, tip.keys), accept) }
        }
    }

    // ---- "Check it's really them" (I3) ----

    /** Parley's app lock is on and locked: the call screen lists no contacts then. */
    private fun appLocked(): Boolean = AppLock.locked.value && c.settings.settings.value.appLock

    override suspend fun savedNumbersFor(number: String, accountId: String?): List<VerifyCallBack.Saved> = withContext(Dispatchers.IO) {
        val iso = PhoneEnv.countryIso(app, accountId)
        val res = app.resources
        c.contacts.lookup(number)?.takeIf { !it.work }?.let { info ->
            // While Parley is locked, only the number the call screen already shows.
            if (appLocked()) return@withContext listOf(VerifyCallBack.Saved(info.name, number, info.numberLabel))
            val summary = c.contacts.contacts.value?.firstOrNull { it.id == info.contactId } ?: c.contacts.loadNow().firstOrNull { it.id == info.contactId }
            val org = c.contacts.organization(info.contactId)?.first?.isNotBlank() == true
            val phones = summary?.phones.orEmpty().map { p ->
                VerifyCallBack.Saved(info.name, p.number, Phone.getTypeLabel(res, p.type, p.label).toString(), organisation = org)
            }
            return@withContext phones.ifEmpty { listOf(VerifyCallBack.Saved(info.name, number, info.numberLabel)) }
        }
        // A private contact (never in discreet mode, where it shows as a plain number).
        if (c.settings.current().hideVault) return@withContext emptyList()
        c.vault.lookup(number, iso)?.let { (id, info) ->
            if (appLocked()) return@withContext listOf(VerifyCallBack.Saved(info.name, number, info.numberLabel))
            val numbers = c.vault.summary(id)?.numbers.orEmpty()
            return@withContext numbers.map { VerifyCallBack.Saved(info.name, it) }.ifEmpty { listOf(VerifyCallBack.Saved(info.name, number, info.numberLabel)) }
        }
        emptyList()
    }

    /** Every saved number for "Send to another number"; private contacts only outside discreet mode. */
    override suspend fun handOffTargets(): List<VerifyCallBack.Saved>? = withContext(Dispatchers.IO) {
        if (appLocked()) return@withContext null
        val res = app.resources
        val contacts = (c.contacts.contacts.value ?: c.contacts.loadNow()).flatMap { s ->
            s.phones.map { p -> VerifyCallBack.Saved(s.displayName, p.number, Phone.getTypeLabel(res, p.type, p.label).toString()) }
        }
        if (c.settings.current().hideVault) return@withContext contacts
        contacts + c.vault.summariesNow().flatMap { v -> v.numbers.map { VerifyCallBack.Saved(v.name, it) } }
    }

    override suspend fun savedOrganisations(): List<VerifyCallBack.Saved>? = withContext(Dispatchers.IO) {
        if (appLocked()) return@withContext null
        val companies = c.contacts.organizations()
        if (companies.isEmpty()) return@withContext emptyList()
        val res = app.resources
        val all = c.contacts.contacts.value ?: c.contacts.loadNow()
        all.filter { it.id in companies }.flatMap { s ->
            val company = companies.getValue(s.id)
            // "My bank" saved as a company, or a person at one: the company's name leads when it's the contact's name.
            val name = if (s.displayName.equals(company, ignoreCase = true)) company else s.displayName + app.getString(R.string.main_separator) + company
            s.phones.map { p -> VerifyCallBack.Saved(name, p.number, Phone.getTypeLabel(res, p.type, p.label).toString(), organisation = true) }
        }
    }

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

    // ---- Family safety (WP-8)

    override suspend fun safeWordsFor(number: String?, accountId: String?): List<SafeWordPrompt> = withContext(Dispatchers.IO) {
        val words = runCatching { c.familySafety.safeWords() }.getOrDefault(emptyMap())
        if (words.isEmpty()) return@withContext emptyList()
        // A saved member of the label needs no check: their labels, a private contact's too (unless discreet mode
        // hides it, when it counts as an unknown caller like everywhere else).
        val labels = number?.let { n -> callerLabels(n, accountId) }.orEmpty()
        SafeWords.offeredFor(words, labels).mapNotNull { t -> words[t]?.let { SafeWordPrompt(t, it.question) } }
    }

    /** The labels [number] is a saved member of (a private contact's too, unless discreet mode hides it); null when unknown. */
    private suspend fun callerLabels(number: String, accountId: String?): Set<String>? {
        c.contacts.lookup(number)?.takeIf { !it.work }?.let { return runCatching { c.contacts.labelTitlesOf(it.contactId) }.getOrDefault(emptySet()) }
        if (c.settings.current().hideVault) return null
        val (id, _) = c.vault.lookup(number, PhoneEnv.countryIso(app, accountId)) ?: return null
        return runCatching { c.privateLabels.titlesOf(id) }.getOrDefault(emptySet())
    }

    override suspend fun safeWordAnswer(label: String): String? = withContext(Dispatchers.IO) { c.familySafety.safeWord(label)?.answer }

    override fun appLockLocked(): Boolean = appLocked()

    override suspend fun helpers(): List<HelperUi> = withContext(Dispatchers.IO) {
        val discreet = c.settings.current().hideVault
        // A private helper shows as their number in discreet mode, like a private caller.
        c.familySafety.helpers().map { h -> HelperUi(if (h.private && discreet) h.number else h.name, h.number) }
    }

    override fun postCallIntent(context: Context, action: PostCallAction, number: String): Intent =
        IntentRoutes.own(context)
            .setAction(MainActivity.ACTION_POST_CALL)
            .putExtra(MainActivity.EXTRA_POST_CALL_ACTION, action.name)
            .putExtra(MainActivity.EXTRA_NUMBER, number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override suspend fun isBlocked(number: String): Boolean = withContext(Dispatchers.IO) { BlockFlow.now(c, number).blocked }

    override suspend fun savePrivately(number: String, name: String): String? = withContext(Dispatchers.IO) {
        val saved = runCatching { TemporaryContacts.save(c, name, number, private = true) }.getOrNull() ?: return@withContext null
        val days = TemporaryContacts.DEFAULT_DAYS
        app.resources.getQuantityString(if (saved.private) R.plurals.caller_saved_private_days else R.plurals.caller_saved_days, days, days)
    }

    /**
     * I1: the best thing Parley remembers about [number] (not a contact), in words. The call screen shows it only while
     * the phone is unlocked; private sources only while the vault is unlocked and never in discreet mode.
     */
    override suspend fun numberMemory(number: String, accountId: String?): NumberMemoryLine? = withContext(Dispatchers.IO) {
        val hint = c.numberMemory.best(number, NumberMemory.Place.CALL, PhoneEnv.countryIso(app, accountId)) ?: return@withContext null
        NumberMemoryLine(NumberMemoryText.line(app, hint))
    }

    /** "This number never calls you": a saved organisation whose line you have only ever called. */
    override suspend fun neverCallsYou(number: String, accountId: String?): Boolean = NeverCallsYouFacts.shows(c, number, accountId)

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
        return extras.any { PhoneIdentity.same(it, number, iso) }
    }

    override fun isEmergencyNumber(number: String): Boolean = EmergencyNumbers.isEmergency(app, number)

    override fun onRingFinished(number: String?, startedAt: Long, ringMillis: Long, answered: Boolean) {
        if (number.isNullOrBlank()) return
        c.scope.launch { runCatching { c.blocks.addRing(number, startedAt, ringMillis, answered) } }
    }

    override suspend fun preferredAccountId(number: String): String? = withContext(Dispatchers.IO) { c.prefs.simFor(number) ?: c.extras.labelSimFor(number) }

    override fun mainIntent(context: Context, dialpad: Boolean): Intent =
        IntentRoutes.own(context)
            .setAction(if (dialpad) MainActivity.ACTION_ADD_CALL else Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun contactIntent(context: Context, contactId: Long?, number: String?): Intent =
        IntentRoutes.own(context)
            .setAction(MainActivity.ACTION_SHOW_CALLER)
            .putExtra(MainActivity.EXTRA_CONTACT_ID, contactId ?: -1L)
            .putExtra(MainActivity.EXTRA_NUMBER, number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
