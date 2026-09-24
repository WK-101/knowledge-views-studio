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
     * Until settings have been read from disk we can't know, so we assume screening is on.
     */
    fun isActive(): Boolean {
        if (!settings.loaded.value) return true
        val s = settings.settings.value.screening
        return s.blockHidden || s.blockNonContacts || s.blockNeighbourSpoofing || s.blockFailedVerification || s.blockInvalid ||
            s.offHours.enabled || s.ringLoudFavourites || s.ringLoudRepeat || s.likelySpamRingtone != null || s.repeatRingtone != null ||
            s.busyReply || blocks.rules.value.any { it.enabled } || lists?.hasEnabledPacks() == true
    }

    /** SIM rules need the phone account, which only the InCallService path has (B9). */
    fun hasSimRules(): Boolean = blocks.rules.value.any { it.enabled && it.simId != null }

    suspend fun screen(number: String?, hidden: Boolean, verification: Verification): Decision =
        screenCall(ScreenRequest(number, hidden, verification)).decision

    /** Live screening: decide, then log, count and notify in the background. */
    suspend fun screenCall(req: ScreenRequest): ScreeningResult {
        val s = currentSettings()
        val now = System.currentTimeMillis()
        val g = gather(req, s, now, replayHistory = null)
        val rules = blocks.enabledRules()
        val result = pipeline.screen(g.facts, rules, s, PolicyClock.of(now)).let {
            // Contact, then label, then default: a contact's own ringtone is played by the system, so no label tone then.
            if (g.contactHasRingtone && it.allowedBy == app.parley.common.AllowReason.CONTACT) it.copy(ringtone = null) else it
        }
        scope.launch { runCatching { commit(req, g, result, s, now) } }
        return result
    }

    /** "Test this call": the same decision with no log, no counters and no notification. */
    suspend fun test(number: String?, hidden: Boolean = number.isNullOrBlank(), at: Long = System.currentTimeMillis()): ScreeningResult {
        val s = currentSettings()
        val g = gather(ScreenRequest(number, hidden), s, at, replayHistory = null)
        return pipeline.test(g.facts, blocks.enabledRules(), s, PolicyClock.of(at))
    }

    /**
     * Replays the incoming calls of the last [days] (B13). Pure reads: no log, counters, notifications or
     * rate-limit state are touched. With a [candidateRule] or [candidatePack], also reports what it would add.
     */
    suspend fun dryRun(calls: List<app.parley.common.CallEntry>, days: Int = 7, candidateRule: BlockRule? = null, candidatePack: ParsedPack? = null): DryRun {
        val s = currentSettings()
        val now = System.currentTimeMillis()
        val since = now - days * 86_400_000L
        val iso = PhoneEnv.countryIso(context)
        val incoming = calls.filter { it.date >= since && it.type != CallType.OUTGOING && it.type != CallType.UNKNOWN }
        val contactCache = HashMap<String, Boolean>()
        val replay = incoming.map { e ->
            val key = PhoneNumbers.matchKey(e.number)
            val isContact = !e.presentationHidden && e.number.isNotBlank() && contactCache.getOrPut(key) { contacts.isContact(e.number) != false }
            ReplayCall(e.number, e.date, e.type, e.presentationHidden || e.number.isBlank(), e.durationSec, isContact)
        }
        val rules = blocks.enabledRules()
        val factsCache = HashMap<ReplayCall, IncomingCallFacts>()
        suspend fun factsFor(c: ReplayCall): IncomingCallFacts = factsCache.getOrPut(c) {
            gather(ScreenRequest(c.number.takeIf { !c.hidden }, c.hidden), s, c.time, replayHistory = calls, knownContact = c.isContact).facts
        }
        // Gather once (suspending), then replay purely.
        replay.forEach { factsFor(it) }
        val base = pipeline.dryRun(replay, rules, s) { factsCache.getValue(it) }
        val candidate = when {
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

    private suspend fun gather(req: ScreenRequest, s: ScreeningSettings, at: Long, replayHistory: List<app.parley.common.CallEntry>?, knownContact: Boolean? = null): Gathered {
        val number = req.number?.takeIf { it.isNotBlank() }
        val iso = PhoneEnv.countryIso(context)
        if (number == null || req.hidden) {
            return Gathered(IncomingCallFacts(number = null, hidden = true, isContact = false, verification = req.verification, countryIso = iso, simId = req.simId), null)
        }
        val parts = PhoneNumbers.forwardedParts(number)
        val primary = parts.first()
        // If contacts can't be checked (no permission, provider failing) fail open: never block a real contact.
        var lookupFailed = false
        val isContact = knownContact ?: run {
            val inContacts = parts.map { contacts.isContact(it) }
            val inVault = parts.map { p -> runCatching { vault.lookup(p) != null }.getOrNull() }
            val yes = inContacts.any { it == true } || inVault.any { it == true }
            lookupFailed = !yes && (inContacts.any { it == null } || inVault.any { it == null })
            yes || lookupFailed
        }
        var starred = false
        var labels = emptySet<Long>()
        var labelFailed = false
        var contactName: String? = null
        var contactRingtone: String? = null
        val rules = blocks.rules.value
        val needLabels = rules.any { it.enabled && it.type == RuleType.LABEL } || (s.offHours.enabled && s.offHours.allow == OffHoursAllow.LABEL)
        if (isContact && !lookupFailed) {
            try {
                contactDetails(primary)?.let { d ->
                    starred = d.starred
                    contactName = d.name
                    contactRingtone = d.ringtone
                    if (needLabels) labels = d.groups(context)
                } ?: run {
                    // A vault contact: never starred, no labels.
                    contactName = runCatching { vault.lookup(primary)?.second?.name }.getOrNull()
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

    private class ContactBits(val id: Long, val name: String?, val starred: Boolean, val ringtone: String?) {
        fun groups(context: Context): Set<Long> {
            val ids = HashSet<Long>()
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(id.toString(), ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE), null,
            )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) } ?: throw IllegalStateException("contacts unavailable")
            return ids
        }
    }

    private fun contactDetails(number: String): ContactBits? {
        if (!Permissions.has(context, android.Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(
            uri, arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.STARRED, ContactsContract.PhoneLookup.CUSTOM_RINGTONE),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) ContactBits(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getString(3)) else null }
    }

    private var lastLog: Triple<String, Long, Long>? = null

    private suspend fun commit(req: ScreenRequest, g: Gathered, result: ScreeningResult, s: ScreeningSettings, now: Long) {
        val number = g.facts.number
        val shouldLog = result.blocked || (!g.facts.isContact && result.allowedBy != app.parley.common.AllowReason.EMERGENCY)
        var logId: Long? = null
        if (shouldLog) {
            val key = if (number == null) "hidden" else PhoneNumbers.matchKey(number)
            // The InCallService may screen again with the SIM known: keep only the newer decision.
            lastLog?.let { (k, id, t) -> if (k == key && now - t < 30_000) blocks.deleteScreened(id) }
            logId = blocks.logScreened(number, result, req.callerName, req.simId, now)
            lastLog = Triple(key, logId, now)
        }
        result.rule?.takeIf { it.id > 0 }?.let { blocks.recordHit(it.id, now) }
        onScreened?.invoke(ScreenedCall(req, result, g.facts.isContact, g.contactName, logId, s))
    }
}
