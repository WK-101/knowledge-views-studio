package app.parley.data

import android.content.Context
import android.net.Uri
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import app.parley.common.BlockRule
import app.parley.common.CallType
import app.parley.common.Decision
import app.parley.common.IncomingCallFacts
import app.parley.common.OffHoursAllow
import app.parley.common.PastCall
import app.parley.common.PhoneNumbers
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

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

/** Replay of the last days (B13): what the current rules would do, and optionally what a candidate would add. */
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
    private val vault: app.parley.data.vault.VaultRepository,
    /** Logging happens here, after the decision is returned, so it never delays Telecom's answer. */
    private val scope: CoroutineScope,
    private val lists: SpamListStore? = null,
    /** Ringtone per label title, from the label pages (the one store for label ringtones). */
    private val labelRingtones: suspend () -> Map<String, String> = { emptyMap() },
) {
    /** Set by the app to post per-verdict notifications. Called off the call path. */
    @Volatile
    var onScreened: ((ScreenedCall) -> Unit)? = null

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
            s.busyReply || rules.isNotEmpty() || lists?.hasEnabledPacks() == true
    }

    /**
     * SIM rules need the phone account, which only the InCallService path has (B9). Before the rules have been
     * read this says yes, so a decision made without the SIM is checked again rather than trusted.
     */
    fun hasSimRules(): Boolean = blocks.rulesCache?.any { it.simId != null } ?: true

    /** Reads settings and rules once, off the main thread, so [isActive] and [hasSimRules] answer from memory. */
    suspend fun warm() {
        settings.current()
        runCatching { blocks.enabledRules() }
    }

    suspend fun screen(number: String?, hidden: Boolean, verification: Verification): Decision =
        screenCall(ScreenRequest(number, hidden, verification)).decision

    /**
     * Live screening: decide, then log, count and notify in the background. The result's ringtone includes the
     * caller's label ringtone (contact's own tone, then label, then default), from the same lookup.
     */
    suspend fun screenCall(req: ScreenRequest): ScreeningResult {
        val s = currentSettings()
        val now = System.currentTimeMillis()
        // Rules from the database, not the flow: in a process just started for this call the flow is still empty.
        val rules = blocks.enabledRules()
        val tones = runCatching { labelRingtones() }.getOrDefault(emptyMap())
        val g = gather(req, s, now, replayHistory = null, rules = rules, tones = tones)
        val result = pipeline.screen(g.facts, rules, s, PolicyClock.of(now)).let {
            when {
                it.blocked -> it
                // A contact's own ringtone is played by the system, so no label tone then.
                g.contactHasRingtone && it.allowedBy == app.parley.common.AllowReason.CONTACT -> it.copy(ringtone = null)
                it.ringtone == null && g.facts.isContact && !g.contactHasRingtone ->
                    it.copy(ringtone = app.parley.common.LabelRefs.ringtoneFor(g.facts.contactLabels, tones))
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
     * Replays the incoming calls of the last [days] (B13). Pure reads: no log, counters, notifications or
     * rate-limit state are touched. With a [candidateRule] or [candidatePack], also reports what it would add.
     */
    suspend fun dryRun(
        calls: List<app.parley.common.CallEntry>,
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
            val key = PhoneNumbers.lineKey(e.number, PhoneEnv.countryIso(context, e.accountId))
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
                    val hit = if (candidatePack != null && lists != null) c.number.takeIf { !c.hidden }?.let { lists.lookupIn(candidatePack, it, iso) } else null
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

    private suspend fun currentSettings(): ScreeningSettings = settings.current().let { it.screening.copy(repeatCallers = it.repeatCallerRingsThrough) }

    private class Gathered(val facts: IncomingCallFacts, val contactName: String?, val contactHasRingtone: Boolean = false)

    private suspend fun gather(
        req: ScreenRequest,
        s: ScreeningSettings,
        at: Long,
        replayHistory: List<app.parley.common.CallEntry>?,
        knownContact: Boolean? = null,
        rules: List<BlockRule>,
        tones: Map<String, String> = emptyMap(),
    ): Gathered {
        val number = req.number?.takeIf { it.isNotBlank() }
        // F7: national numbers are read with the country of the SIM that took the call, when known.
        val iso = PhoneEnv.countryIso(context, req.simId)
        if (number == null || req.hidden) {
            return Gathered(IncomingCallFacts(number = null, hidden = true, isContact = false, verification = req.verification, countryIso = iso, simId = req.simId), null)
        }
        val parts = PhoneNumbers.forwardedParts(number)
        val primary = parts.first()
        // If contacts can't be checked (no permission, provider failing) fail open: never block a real contact.
        var lookupFailed = false
        val isContact = knownContact ?: run {
            val inContacts = parts.map { contacts.isContact(it) }
            val inVault = parts.map { p -> runCatching { vault.lookup(p, iso) != null }.getOrNull() }
            val yes = inContacts.any { it == true } || inVault.any { it == true }
            lookupFailed = !yes && (inContacts.any { it == null } || inVault.any { it == null })
            yes || lookupFailed
        }
        var starred = false
        var labels = emptySet<String>()
        var labelFailed = false
        var contactName: String? = null
        var contactRingtone: String? = null
        val needLabels = rules.any { it.enabled && it.type == RuleType.LABEL } || (s.offHours.enabled && s.offHours.allow == OffHoursAllow.LABEL) ||
            tones.isNotEmpty()
        if (isContact && !lookupFailed) {
            try {
                contactDetails(primary)?.let { d ->
                    starred = d.starred
                    contactName = d.name
                    contactRingtone = d.ringtone
                    // Titles in every account: label rules, off hours and ringtones name labels by title.
                    if (needLabels) labels = contacts.labelTitlesOrNull(d.id) ?: throw IllegalStateException("contacts unavailable")
                } ?: run {
                    // A vault contact: never starred, no labels.
                    contactName = runCatching { vault.lookup(primary, iso)?.second?.name }.getOrNull()
                }
            } catch (_: Exception) {
                labelFailed = true
            }
        }
        val nf = NumberFacts.of(primary, iso)
        val lookup = if (!isContact) lists?.lookup(number, iso) else null
        val history = if (!isContact && (s.allowDialled || s.allowAnswered || s.repeatCallers)) history(number, at, replayHistory, iso) else emptyList()
        val blockedAttempts = if (!isContact && s.repeatCallers && replayHistory == null) {
            runCatching { blocks.recentBlockedTimes(number, at - s.repeatWindowMinutes * 60_000L - 1000) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val facts = IncomingCallFacts(
            number = number,
            hidden = false,
            isContact = isContact,
            verification = req.verification,
            countryIso = iso,
            ownNumbers = if (s.blockNeighbourSpoofing) sims.ownNumbers() else emptyList(),
            inSystemBlockList = replayHistory == null && blocks.isSystemBlocked(primary),
            isEmergency = PhoneEnv.isEmergency(context, primary),
            contactLookupFailed = lookupFailed,
            contactStarred = starred,
            contactLabels = labels,
            labelLookupFailed = labelFailed,
            callerName = req.callerName,
            simId = req.simId,
            region = nf.region,
            lineType = nf.lineType,
            validity = nf.validity,
            listHits = lookup?.hits.orEmpty(),
            listLookupFailed = lookup?.failed == true,
            history = history,
            blockedAttempts = blockedAttempts,
        )
        return Gathered(facts, contactName, contactRingtone != null)
    }

    /** Earlier calls with [number] before [at], newest first. */
    private fun history(number: String, at: Long, replay: List<app.parley.common.CallEntry>?, iso: String): List<PastCall> {
        if (replay != null) {
            return replay.asSequence()
                .filter { it.date < at && !it.presentationHidden && PhoneNumbers.same(it.number, number, iso) }
                .map { PastCall(it.date, it.type == CallType.OUTGOING, it.durationSec) }
                .take(50).toList()
        }
        if (!Permissions.has(context, android.Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<PastCall>()
        for (part in PhoneNumbers.forwardedParts(number)) {
            try {
                context.contentResolver.query(
                    Uri.withAppendedPath(Calls.CONTENT_FILTER_URI, Uri.encode(part)),
                    arrayOf(Calls.DATE, Calls.TYPE, Calls.DURATION),
                    "${Calls.DATE} < ?", arrayOf(at.toString()), "${Calls.DATE} DESC",
                )?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n++ < 50) out += PastCall(c.getLong(0), c.getInt(1) == Calls.OUTGOING_TYPE, c.getLong(2))
                }
            } catch (_: Exception) {
            }
        }
        return out.sortedByDescending { it.time }
    }

    private class ContactBits(val id: Long, val name: String?, val starred: Boolean, val ringtone: String?)

    private fun contactDetails(number: String): ContactBits? {
        if (!Permissions.has(context, android.Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(
            uri, arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.STARRED, ContactsContract.PhoneLookup.CUSTOM_RINGTONE),
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
        val number = g.facts.number
        val key = if (number == null) "hidden" else PhoneNumbers.lineKey(number, PhoneEnv.countryIso(context, req.simId))
        committed.entries.removeAll { now - it.value.at > RESCREEN_WINDOW_MS }
        val prev = committed[key]
        val entry = prev ?: Committed(now, null, HashSet(), HashSet()).also { committed[key] = it }
        val shouldLog = result.blocked || (!g.facts.isContact && result.allowedBy != app.parley.common.AllowReason.EMERGENCY)
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

    private companion object {
        const val RESCREEN_WINDOW_MS = 30_000L
    }
}
