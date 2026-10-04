package app.parley.data

import android.Manifest
import app.parley.common.catching
import app.parley.common.AllowReason
import app.parley.common.calls.ExpectedWindow
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.CallEntry
import app.parley.common.LabelRefs
import app.parley.common.PhoneIdentity
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import app.parley.common.BlockRule
import app.parley.common.CallType
import app.parley.common.Decision
import app.parley.common.IncomingCallFacts
import app.parley.common.OffHoursAllow
import app.parley.common.PastCall
import app.parley.common.PolicyClock
import app.parley.common.RuleType
import app.parley.common.ScreeningResult
import app.parley.common.ScreeningSettings
import app.parley.common.Verification
import app.parley.common.blocking.ReplayCall
import app.parley.common.blocking.ReplayReport
import app.parley.common.blocking.ScreeningEffects
import app.parley.common.blocking.ScreeningPipeline
import app.parley.common.spam.ParsedPack
import app.parley.common.spam.Reputation
import app.parley.data.calls.ReputationStore
import app.parley.data.vault.VaultRepository
import app.parley.common.security.Concealed
import app.parley.data.security.Concealment
import app.parley.common.people.PrivateLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.parley.common.suspendRunCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An incoming call to screen. [simId] is only known on the InCallService path (the screening service has none). */
data class ScreenRequest(
    val number: String?,
    val hidden: Boolean,
    val verification: Verification = Verification.NOT_VERIFIED,
    val simId: String? = null,
    /** Caller name sent by the network (CNAP). */
    val callerName: String? = null,
)

/** A live screening result handed to the app (notifications) after Telecom already has its answer. */
data class ScreenedCall(
    val request: ScreenRequest,
    val result: ScreeningResult,
    val isContact: Boolean,
    val contactName: String?,
    val logId: Long?,
    val settings: ScreeningSettings,
)

/** Replay of the last days: what the current rules would do, and optionally what a candidate would add. */
data class DryRun(val current: ReplayReport, val candidate: ReplayReport?) {
    val added get() = candidate?.newlyBlocked(current).orEmpty()
}

/** Gathers the facts about an incoming call and asks the pure [app.parley.common.CallPolicy] for a decision. */
class CallScreener(
    private val context: Context,
    private val contacts: ContactsRepository,
    private val blocks: BlockRepository,
    private val sims: SimRepository,
    private val settings: SettingsRepository,
    private val vault: VaultRepository,
    /** Logging happens here, after the decision is returned, so it never delays Telecom's answer. */
    private val scope: CoroutineScope,
    private val lists: SpamListStore? = null,
    /** Ringtone per label title, from the label pages (the one store for label ringtones). */
    private val labelRingtones: suspend () -> Map<String, String> = { emptyMap() },
    /** Earlier calls with the caller (repeat callers, "called back"), read from the system log. */
    private val callLog: CallLogRepository? = null,
    /** I2: what your own calls say about a number, learned daily; looked up in memory here. */
    private val reputation: ReputationStore? = null,
    /** The family spam shield: verdicts shared in shared labels, looked up in memory. */
    private val family: app.parley.data.sync.shared.FamilyShieldStore? = null,
) {
    /** Set by the app to post per-verdict notifications. Called off the call path. */
    @Volatile
    var onScreened: ((ScreenedCall) -> Unit)? = null

    /**
     * Situations: whether one is on or can switch itself on (memory only), and the look at their triggers made before
     * a call is screened, so a window that began while Parley wasn't running applies to this call. Set by the container.
     */
    @Volatile
    var situationsWatching: () -> Boolean = { false }

    @Volatile
    var beforeScreen: (suspend () -> Unit)? = null

    private val effects = object : ScreeningEffects {
        override fun onScreened(facts: IncomingCallFacts, result: ScreeningResult) = Unit
    }
    private val pipeline = ScreeningPipeline(effects)

    /**
     * True when any screening feature is on; lets the call path skip I/O entirely otherwise.
     * Numbers on the system block list are already rejected by Telecom before we see the call.
     * Until settings and rules have been read from disk we can't know, so we assume screening is on.
     * Memory only: [warm] reads both off the main thread at app start.
     */
    fun isActive(): Boolean {
        if (!settings.loaded.value) return true
        val rules = blocks.rulesCache ?: return true
        val s = settings.settings.value.screening
        return s.blockHidden || s.blockNonContacts || s.blockNeighbourSpoofing || s.blockFailedVerification || s.blockInvalid ||
            s.offHours.enabled || s.ringLoudFavourites || s.ringLoudRepeat || s.likelySpamRingtone != null || s.repeatRingtone != null ||
            s.busyReply || rules.isNotEmpty() || lists?.hasEnabledPacks() == true ||
            (s.learnFromCalls && reputation?.mayHaveEntries == true) || runCatching { situationsWatching() }.getOrDefault(false) ||
            family?.mayMatch() == true
    }

    /**
     * SIM rules need the phone account, which only the InCallService path has. Before the rules have been
     * read this says yes, so a decision made without the SIM is checked again rather than trusted.
     */
    fun hasSimRules(): Boolean = blocks.rulesCache?.any { it.simId != null } ?: true

    /** Reads settings and rules once, off the main thread, so [isActive] and [hasSimRules] answer from memory. */
    suspend fun warm() {
        settings.current()
        runCatching { blocks.enabledRules() }
        runCatching { reputation?.load() }
        catching { family?.load() }
    }

    suspend fun screen(number: String?, hidden: Boolean, verification: Verification): Decision =
        screenCall(ScreenRequest(number, hidden, verification)).decision

    /**
     * Live screening: decide, then log, count and notify in the background. The result's ringtone includes the
     * caller's label ringtone (contact's own tone, then label, then default), from the same lookup.
     */
    suspend fun screenCall(req: ScreenRequest): ScreeningResult {
        // Never longer than a moment: the call is screened with what is set if the look takes longer (the look only
        // waits; a switch it started finishes in the background).
        beforeScreen?.let { look -> suspendRunCatching { withTimeoutOrNull(SITUATION_LOOK_MS) { look() } } }
        val s = currentSettings()
        val now = System.currentTimeMillis()
        // A process just started for this call: the shield's verdicts are read once (no-op afterwards).
        catching { family?.load() }
        // Rules from the database, not the flow: in a process just started for this call the flow is still empty.
        val rules = blocks.enabledRules()
        val tones = runCatching { labelRingtones() }.getOrDefault(emptyMap())
        val g = gather(req, s, now, replayHistory = null, rules = rules, tones = tones)
        val result = pipeline.screen(g.facts, rules, s, PolicyClock.of(now)).let {
            when {
                it.blocked -> it
                // A private contact's "Send to voicemail" (Android applies a device contact's itself): declined unrung.
                g.privateVoicemail && it.allowedBy != AllowReason.EMERGENCY ->
                    it.copy(decision = Decision.Block(BlockAction.REJECT, BlockReason.SEND_TO_VOICEMAIL), ringtone = null, ringLoud = false)
                // A contact's own ringtone is played by the system, so no label tone then.
                g.contactHasRingtone && it.allowedBy == AllowReason.CONTACT -> it.copy(ringtone = null)
                // A private contact's own ringtone: Android never sees it, so Parley's ringer plays it (a rule's wins).
                g.privateTone != null && it.allowedBy != AllowReason.RULE -> it.copy(ringtone = g.privateTone, contactTone = true)
                it.ringtone == null && g.facts.isContact && !g.contactHasRingtone ->
                    it.copy(ringtone = LabelRefs.ringtoneFor(g.facts.contactLabels, tones))
                else -> it
            }
        }
        scope.launch { runCatching { commit(req, g, result, s, now) } }
        return result
    }

    /** "Test this call": the same decision with no log, no counters and no notification. */
    suspend fun test(number: String?, hidden: Boolean = number.isNullOrBlank(), at: Long = System.currentTimeMillis()): ScreeningResult {
        val s = currentSettings()
        val rules = blocks.enabledRules()
        val g = gather(ScreenRequest(number, hidden), s, at, replayHistory = null, rules = rules)
        return pipeline.test(g.facts, rules, s, PolicyClock.of(at))
    }

    /**
     * Replays the incoming calls of the last [days]. Pure reads: no log, counters, notifications or
     * rate-limit state are touched. With a [candidateRule] or [candidatePack], also reports what it would add.
     */
    suspend fun dryRun(
        calls: List<CallEntry>,
        days: Int = 7,
        candidateRule: BlockRule? = null,
        candidatePack: ParsedPack? = null,
        /** A group of rules to try together (rule-pack templates). */
        candidateRules: List<BlockRule> = emptyList(),
        /** Setting changes to try (rule-pack templates); applied to a copy, never saved. */
        candidateSettings: ((ScreeningSettings) -> ScreeningSettings)? = null,
    ): DryRun {
        val s = currentSettings()
        val now = System.currentTimeMillis()
        val since = now - days * 86_400_000L
        val iso = PhoneEnv.countryIso(context)
        val incoming = calls.filter { it.date >= since && it.type != CallType.OUTGOING && it.type != CallType.UNKNOWN }
        val contactCache = HashMap<String, Boolean>()
        val replay = incoming.map { e ->
            val key = PhoneIdentity.key(e.number, PhoneEnv.countryIso(context, e.accountId))
            val isContact = !e.presentationHidden && e.number.isNotBlank() && contactCache.getOrPut(key) { contacts.isContact(e.number) != false }
            ReplayCall(e.number, e.date, e.type, e.presentationHidden || e.number.isBlank(), e.durationSec, isContact)
        }
        val rules = blocks.enabledRules()
        val factsCache = HashMap<ReplayCall, IncomingCallFacts>()
        suspend fun factsFor(c: ReplayCall): IncomingCallFacts = factsCache.getOrPut(c) {
            gather(ScreenRequest(c.number.takeIf { !c.hidden }, c.hidden), s, c.time, replayHistory = calls, knownContact = c.isContact, rules = rules).facts
        }
        // Gather once (suspending), then replay purely.
        replay.forEach { factsFor(it) }
        val base = pipeline.dryRun(replay, rules, s) { factsCache.getValue(it) }
        val candidate = when {
            candidateRules.isNotEmpty() || candidateSettings != null -> {
                val extra = listOfNotNull(candidateRule) + candidateRules
                val cs = candidateSettings?.invoke(s) ?: s
                pipeline.dryRun(replay, rules + extra, cs) { c ->
                    val f = factsCache.getValue(c)
                    val hit = if (candidatePack != null && lists != null) c.number.takeIf { !c.hidden }?.let {
                        lists.lookupIn(candidatePack, it, iso)
                    } else null
                    if (hit == null) f else f.copy(listHits = f.listHits + hit)
                }
            }
            candidateRule != null -> pipeline.dryRun(replay, rules + candidateRule, s) { factsCache.getValue(it) }
            candidatePack != null && lists != null -> pipeline.dryRun(replay, rules, s) { c ->
                val f = factsCache.getValue(c)
                val hit = c.number.takeIf { !c.hidden }?.let { lists.lookupIn(candidatePack, it, iso) }
                if (hit == null) f else f.copy(listHits = f.listHits + hit)
            }
            else -> null
        }
        return DryRun(base, candidate)
    }

    fun currentSettingsNow(): ScreeningSettings = settings.settings.value.let { it.screening.copy(repeatCallers = it.repeatCallerRingsThrough) }

    private suspend fun currentSettings(): ScreeningSettings = settings.current().let {
        it.screening.copy(repeatCallers = it.repeatCallerRingsThrough, expected = runCatching { expectedWindows() }.getOrDefault(emptyList()))
    }

    /** I7: the expected-call windows in force (sealed in their own store); none until set at start. */
    @Volatile
    var expectedWindows: suspend () -> List<ExpectedWindow> = { emptyList() }

    private class Gathered(
        val facts: IncomingCallFacts,
        val contactName: String?,
        val contactHasRingtone: Boolean = false,
        /** A private contact's own ringtone and "send to voicemail" (sealed in its vault entry). */
        val privateTone: String? = null,
        val privateVoicemail: Boolean = false,
    )

    /** What the contacts provider says about a number: a contact (with its row when it is a personal one), not one, or unknown. */
    private sealed interface ContactAnswer {
        class Found(val bits: ContactBits?) : ContactAnswer
        object NotFound : ContactAnswer
        object Unknown : ContactAnswer
    }

    /** A vault lookup: [hit] is the private contact (null: none). A lookup that failed is a null [VaultAnswer]. */
    private class VaultAnswer(val hit: Pair<Long, CallerInfo>?)

    /**
     * Gathers the facts, the lookups at the same time: the contacts provider, the vault, the spam lists, the call log,
     * the blocked-call log, Android's block list and the SIMs answer independently, and the call screening service has
     * 3 s in all. Each number is looked up once (one PhoneLookup row gives "is a contact", the name, the star and the
     * ringtone; the vault's answer also names a private caller). The lookups only an unknown caller needs start with
     * the others and are dropped for a contact. Only the labels wait, for the contact's id.
     */
    private suspend fun gather(
        req: ScreenRequest,
        s: ScreeningSettings,
        at: Long,
        replayHistory: List<CallEntry>?,
        knownContact: Boolean? = null,
        rules: List<BlockRule>,
        tones: Map<String, String> = emptyMap(),
    ): Gathered = coroutineScope {
        val number = req.number?.takeIf { it.isNotBlank() }
        // National numbers are read with the country of the SIM that took the call, when known.
        val iso = PhoneEnv.countryIso(context, req.simId)
        if (number == null || req.hidden) {
            return@coroutineScope Gathered(
                IncomingCallFacts(number = null, hidden = true, isContact = false, verification = req.verification, countryIso = iso, simId = req.simId),
                null,
            )
        }
        val parts = PhoneIdentity.forwardedParts(number)
        val primary = parts.first()
        val io = Dispatchers.IO
        val live = replayHistory == null
        val contactLookups = if (knownContact == null) parts.map { p -> async(io) { contactLookup(p) } } else emptyList()
        val vaultLookups = if (knownContact == null) parts.map { p -> async(io) { vaultLookup(p, iso) } } else emptyList()
        val maybeUnknown = knownContact != true
        // Only an unknown caller needs these. They run beside this scope, not in it: a contact's call is answered
        // without waiting for them (a pack index being parsed, a call-log query), and they are dropped then.
        val unknownOnly = SideLookups(io, coroutineContext.job)
        // Kept as results: a failure counts (as before) only when the answer is used, never for a contact's call.
        val listLookup = if (maybeUnknown) unknownOnly.start { catching { lists?.lookup(number, iso) } } else null
        val wantsHistory = s.allowDialled || s.allowAnswered || s.repeatCallers
        val pastCalls = if (maybeUnknown && wantsHistory) unknownOnly.start { catching { history(number, at, replayHistory, iso) } } else null
        val blockedLookup = if (maybeUnknown && s.repeatCallers && live) unknownOnly.start { blockedTimes(number, at, s) } else null
        val systemBlocked = if (live) async(io) { blocks.isSystemBlocked(primary) } else null
        val ownNumbers = if (s.blockNeighbourSpoofing) async(io) { sims.ownNumbers() } else null

        // If contacts can't be checked (no permission, provider failing) fail open: never block a real contact.
        val inContacts = contactLookups.awaitAll()
        val inVault = vaultLookups.awaitAll()
        var lookupFailed = false
        val isContact = knownContact ?: run {
            val yes = inContacts.any { it is ContactAnswer.Found } || inVault.any { it?.hit != null }
            lookupFailed = !yes && (inContacts.any { it is ContactAnswer.Unknown } || inVault.any { it == null })
            yes || lookupFailed
        }
        val needLabels = rules.any { it.enabled && it.type == RuleType.LABEL } || (s.offHours.enabled && s.offHours.allow == OffHoursAllow.LABEL) ||
            tones.isNotEmpty()
        val who = if (isContact && !lookupFailed) caller(primary, iso, inContacts.firstOrNull(), inVault.firstOrNull(), needLabels) else Caller()
        val nf = NumberFacts.of(primary, iso)
        val emergency = EmergencyNumbers.isEmergency(context, primary)
        val lookup = if (!isContact) listLookup?.await()?.getOrThrow() else null
        val history = if (!isContact) pastCalls?.await()?.getOrThrow().orEmpty() else emptyList()
        val blockedAttempts = if (!isContact) blockedLookup?.await().orEmpty() else emptyList()
        // Not needed for a contact: dropped, never waited for.
        if (isContact) unknownOnly.drop()
        val facts = IncomingCallFacts(
            number = number,
            hidden = false,
            isContact = isContact,
            verification = req.verification,
            countryIso = iso,
            ownNumbers = ownNumbers?.await().orEmpty(),
            inSystemBlockList = systemBlocked?.await() == true,
            isEmergency = emergency,
            contactLookupFailed = lookupFailed,
            contactStarred = who.starred,
            contactLabels = who.labels,
            labelLookupFailed = who.labelFailed,
            callerName = req.callerName,
            simId = req.simId,
            region = nf.region,
            lineType = nf.lineType,
            validity = nf.validity,
            listHits = lookup?.hits.orEmpty(),
            listLookupFailed = lookup?.failed == true,
            history = history,
            blockedAttempts = blockedAttempts,
            reputation = reputationOf(primary, iso, s, unknown = !isContact && !emergency && live),
            // Memory only; never asked for a contact (saved or private) or an emergency number.
            family = if (!isContact && !emergency) catching { family?.match(primary, iso) }.getOrNull() else null,
        )
        Gathered(facts, who.name, who.ringtone != null, who.privateTone, who.privateVoicemail)
    }

    /** Who a known caller is: a device contact's row, or a private contact's choices. */
    private class Caller(
        val name: String? = null,
        val starred: Boolean = false,
        val labels: Set<String> = emptySet(),
        val labelFailed: Boolean = false,
        val ringtone: String? = null,
        val privateTone: String? = null,
        val privateVoicemail: Boolean = false,
    )

    /**
     * The contact behind [primary], from the answers already gathered ([contact], [private]; a replay, which knew the
     * contact already, asks now). Labels are read only when [needLabels].
     */
    @Suppress("TooGenericExceptionCaught") // Labels that can't be read are a fact of their own (labelLookupFailed).
    private suspend fun caller(primary: String, iso: String, contact: ContactAnswer?, private: VaultAnswer?, needLabels: Boolean): Caller = try {
        val personal = ((contact ?: withContext(Dispatchers.IO) { contactLookup(primary) }) as? ContactAnswer.Found)?.bits
        if (personal != null) {
            // Titles in every account: label rules, off hours and ringtones name labels by title.
            val labels = if (needLabels) labelsOf(personal.id) else emptySet()
            Caller(personal.name, personal.starred, labels, ringtone = personal.ringtone)
        } else {
            privateCaller((private ?: vaultLookup(primary, iso))?.hit, needLabels)?.let { p ->
                Caller(p.name, p.starred, p.labels, privateTone = p.ringtone, privateVoicemail = p.sendToVoicemail)
            } ?: Caller()
        }
    } catch (_: Exception) {
        Caller(labelFailed = true)
    }

    private suspend fun blockedTimes(number: String, at: Long, s: ScreeningSettings): List<Long> =
        runCatching { blocks.recentBlockedTimes(number, at - s.repeatWindowMinutes * 60_000L - 1000) }.getOrDefault(emptyList())

    private suspend fun labelsOf(contactId: Long): Set<String> =
        withContext(Dispatchers.IO) { contacts.labelTitlesOrNull(contactId) } ?: error("contacts unavailable")

    /** One vault lookup; null when it failed (the caller fails open). */
    private suspend fun vaultLookup(number: String, iso: String): VaultAnswer? = runCatching { VaultAnswer(vault.lookup(number, iso)) }.getOrNull()

    /**
     * One PhoneLookup for [number] with everything the call path reads, and the work profile's lookup when the personal
     * one finds nothing (as [ContactsRepository.isContact]).
     */
    private fun contactLookup(number: String): ContactAnswer {
        if (number.isBlank()) return ContactAnswer.NotFound
        if (!Permissions.has(context, Manifest.permission.READ_CONTACTS)) return ContactAnswer.Unknown
        val personal = try {
            contactDetails(number)?.let { ContactAnswer.Found(it) } ?: ContactAnswer.NotFound
        } catch (_: Exception) {
            ContactAnswer.Unknown
        }
        if (personal is ContactAnswer.Found || !WorkProfile.exists(context)) return personal
        val work = try {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup._ID), null, null, null,
            )?.use { it.count > 0 }
        } catch (_: Exception) {
            // The policy may forbid the lookup: then the personal answer stands.
            null
        }
        return if (work == true) ContactAnswer.Found(null) else personal
    }

    /**
     * I2: a lookup only (learned in the daily run). Only for [unknown] callers: never contacts or emergency numbers, nor
     * replays of past calls (today's index was learned from those very calls).
     */
    private fun reputationOf(number: String, iso: String, s: ScreeningSettings, unknown: Boolean): Reputation? {
        if (!unknown || !s.learnFromCalls) return null
        val store = reputation ?: return null
        // From memory only (L7): an index not read yet gives no tag this time and is read in the background.
        if (!store.isLoaded) scope.launch(Dispatchers.IO) { runCatching { store.load() } }
        return runCatching { store.lookupLoaded(number, iso) }.getOrNull()
    }

    /** Earlier calls with [number] before [at], newest first. */
    private fun history(number: String, at: Long, replay: List<CallEntry>?, iso: String): List<PastCall> {
        if (replay != null) {
            return replay.asSequence()
                .filter { it.date < at && !it.presentationHidden && PhoneIdentity.same(it.number, number, iso) }
                .map { PastCall(it.date, it.type == CallType.OUTGOING, it.durationSec) }
                .take(50).toList()
        }
        return runCatching { callLog?.pastCalls(number, at) }.getOrNull().orEmpty()
            .map { PastCall(it.date, it.type == CallType.OUTGOING, it.durationSec) }
    }

    private class ContactBits(val id: Long, val name: String?, val starred: Boolean, val ringtone: String?)

    private class PrivateCaller(val name: String?, val starred: Boolean, val labels: Set<String>, val ringtone: String?, val sendToVoicemail: Boolean)

    /**
     * A private caller: Parley's own star, labels, ringtone and "send to voicemail", from its sealed caller-ID copy
     * (readable while the phone is locked); the address book knows nothing of them. Null when no private contact has it.
     */
    private suspend fun privateCaller(hit: Pair<Long, CallerInfo>?, needLabels: Boolean): PrivateCaller? {
        hit ?: return null
        val p = runCatching { vault.summary(hit.first) }.getOrNull() ?: return PrivateCaller(hit.second.name, false, emptySet(), null, false)
        val labels = if (needLabels && p.labels.isNotEmpty()) {
            PrivateLabels.titles(p.labels, runCatching { contacts.groups().map { PrivateLabels.Group(it.id, it.title) } }.getOrDefault(emptyList()))
        } else {
            emptySet()
        }
        // I21: after a duress unlock their own ringtone would set the call apart from an unknown number's; rules,
        // labels and "send to voicemail" still apply, so nobody who was kept out rings through.
        val ringtone = p.ringtone.takeUnless { Concealment.hides(Concealed.PRIVATE_RINGTONES) }
        return PrivateCaller(hit.second.name, p.starred, labels, ringtone, p.sendToVoicemail)
    }

    private fun contactDetails(number: String): ContactBits? {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(
            uri, arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.STARRED,
                ContactsContract.PhoneLookup.CUSTOM_RINGTONE,
            ),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) ContactBits(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getString(3)) else null }
    }

    /** What was already logged, counted and notified for a number screened moments ago. */
    private class Committed(val at: Long, var logId: Long?, val hits: MutableSet<Long>, val notified: MutableSet<String>)

    private val commitLock = Mutex()
    private val committed = HashMap<String, Committed>()

    /**
     * Logs, counts and notifies once per call. The InCallService screens again what the screening service already
     * screened (to apply per-SIM rules): within [RESCREEN_WINDOW_MS] of the same number, only the newer log entry
     * is kept, a rule hit is counted once and the same verdict is notified once.
     */
    private suspend fun commit(req: ScreenRequest, g: Gathered, result: ScreeningResult, s: ScreeningSettings, now: Long) = commitLock.withLock {
        // A private contact sent to voicemail: the user's own choice for them, not a block, and never logged outside the vault.
        if ((result.decision as? Decision.Block)?.reason == BlockReason.SEND_TO_VOICEMAIL) return@withLock
        val number = g.facts.number
        val key = if (number == null) "hidden" else PhoneIdentity.key(number, PhoneEnv.countryIso(context, req.simId))
        committed.entries.removeAll { now - it.value.at > RESCREEN_WINDOW_MS }
        val prev = committed[key]
        val entry = prev ?: Committed(now, null, HashSet(), HashSet()).also { committed[key] = it }
        val shouldLog = result.blocked || (!g.facts.isContact && result.allowedBy != AllowReason.EMERGENCY)
        var logId: Long? = null
        if (shouldLog) {
            entry.logId?.let { blocks.deleteScreened(it) }
            logId = blocks.logScreened(number, result, req.callerName, req.simId, now)
            entry.logId = logId
        }
        result.rule?.takeIf { it.id > 0 && entry.hits.add(it.id) }?.let { blocks.recordHit(it.id, now) }
        // A decision deferred to the SIM-aware path isn't final: that path notifies.
        if (result.deferredToSim) return@withLock
        val signature = "${result.decision}|${result.verdict?.kind}|${result.rule?.id}"
        if (entry.notified.add(signature)) onScreened?.invoke(ScreenedCall(req, result, g.facts.isContact, g.contactName, logId, s))
    }

    companion object {
        private const val RESCREEN_WINDOW_MS = 30_000L

        /**
         * How long the look at the Situations' triggers may hold up screening (or an outgoing call's SIM): the switch
         * itself goes on in the background after it.
         */
        const val SITUATION_LOOK_MS = 400L
    }
}
