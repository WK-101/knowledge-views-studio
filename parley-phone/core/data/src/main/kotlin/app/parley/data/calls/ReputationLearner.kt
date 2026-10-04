package app.parley.data.calls

import app.parley.common.BlockRule
import app.parley.common.CallEntry
import app.parley.common.PhoneIdentity
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.spam.CallReputation
import app.parley.common.spam.RangeProposal
import app.parley.common.spam.RepCall
import app.parley.common.spam.RepKind
import app.parley.data.DataContainer
import app.parley.data.EmergencyNumbers
import app.parley.data.PhoneEnv
import app.parley.data.db.CallRingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.abs

/**
 * I2: gathers your own call history for [CallReputation] (calls of the last 90 days, ring lengths, what screening
 * stopped, the numbers you blocked, your contacts and private contacts) and stores what it learned in
 * [ReputationStore]. Runs in the daily maintenance worker, and for the post-call "Block this range?" offer; never while
 * a call rings. Calls with private contacts are never part of it (the archive leaves them out).
 */
object ReputationLearner {
    private const val RING_MATCH_MS = 15_000L
    private const val SCREEN_MATCH_MS = 60_000L

    /** What the scorer needs, gathered once. */
    class Input(val calls: List<RepCall>, val blocked: Set<String>, val knownLines: Set<String>, val known: (String) -> Boolean)

    /** The daily run: learn again, or forget everything with "Learn from your calls" off. False when nothing could be read. */
    suspend fun learn(c: DataContainer, now: Long = System.currentTimeMillis()): Boolean {
        if (!c.settings.current().screening.learnFromCalls) {
            c.reputation.clear()
            return true
        }
        val input = gather(c, now) ?: return false
        val index = withContext(Dispatchers.Default) {
            CallReputation.index(input.calls, now, ZoneId.systemDefault(), input.blocked, input.knownLines, input.known)
        }
        return c.reputation.replace(index)
    }

    /** "Block this range?" after a call with [number] (on the SIM [accountId]): the narrowest prefix, or null. */
    suspend fun proposeRange(c: DataContainer, number: String, accountId: String?, now: Long = System.currentTimeMillis()): RangeProposal? {
        val line = PhoneIdentity.e164(number, PhoneEnv.countryIso(c.appContext, accountId)) ?: return null
        val input = gather(c, now) ?: return null
        return withContext(Dispatchers.Default) { CallReputation.proposeRange(line, input.calls, now, input.knownLines, input.known) }
    }

    suspend fun gather(c: DataContainer, now: Long): Input? = withContext(Dispatchers.IO) {
        val ctx = c.appContext
        val since = now - CallReputation.WINDOW_DAYS * 86_400_000L
        val calls = c.history.awaitCalls() ?: return@withContext null
        val home = PhoneEnv.countryIso(ctx)
        val isoBySim = HashMap<String?, String>()
        fun iso(accountId: String?) = isoBySim.getOrPut(accountId) { PhoneEnv.countryIso(ctx, accountId) }

        // Ring lengths, by the key Parley's ringer filed them under.
        val rings = runCatching { c.blocks.ringsSince(since) }.getOrDefault(emptyList()).groupBy { it.numberKey }
        // Calls Parley's screening blocked or silenced: they say nothing about what you did.
        val screened = runCatching { c.blocks.screenedSince(since) }.getOrDefault(emptyList())
            .filter { !it.allowed && !it.number.isNullOrBlank() }
            .mapNotNull { e -> PhoneIdentity.e164(e.number, iso(e.simId))?.let { it to e.time } }
            .groupBy({ it.first }, { it.second })

        // Older calls are passed too: the scorer only counts recent ones, but trusts a number you ever called or talked to.
        val out = calls.mapNotNull { e -> repCall(e, iso(e.accountId), home, rings, screened) }

        val numbers = knownNumbers(c)
        val knownSet = PhoneIdentity.KnownSet(numbers, home)
        val knownLines = numbers.mapNotNullTo(HashSet()) { PhoneIdentity.e164(it, home) }
        val extras = c.settings.current().screening.emergencyExtras
        val known: (String) -> Boolean = { line ->
            line in knownSet || EmergencyNumbers.isEmergency(ctx, line) || extras.any { PhoneIdentity.same(it, line, home) }
        }
        Input(out, blockedLines(c, home), knownLines, known)
    }

    /** Your contacts' and private contacts' numbers: never scored, and their ranges neither. */
    private suspend fun knownNumbers(c: DataContainer): List<String> {
        val contacts = c.contacts.contacts.value ?: runCatching { c.contacts.loadNow() }.getOrDefault(emptyList())
        val privateNumbers = runCatching { c.vault.allNumbers() }.getOrDefault(emptyList())
        return contacts.flatMap { ct -> ct.phones.map { it.number } } + privateNumbers
    }

    /** One call as the scorer sees it, or null for a hidden number, a short code or an unknown type. */
    private fun repCall(e: CallEntry, iso: String, home: String, rings: Map<String, List<CallRingEntity>>, screened: Map<String, List<Long>>): RepCall? {
        if (e.presentationHidden || e.number.isBlank()) return null
        val line = PhoneIdentity.e164(e.number, iso) ?: return null
        val type = CallReputation.kindOf(e.type) ?: return null
        val stopped = type != RepKind.OUTGOING && screened[line].orEmpty().any { abs(it - e.date) <= SCREEN_MATCH_MS }
        val kind = if (stopped) RepKind.SCREENED else type
        val ring = if (kind == RepKind.MISSED) {
            PhoneIdentity.keyForms(e.number, home).flatMap { rings[it].orEmpty() }.firstOrNull { abs(it.startedAt - e.date) <= RING_MATCH_MS }?.ringMs
        } else {
            null
        }
        return RepCall(line, e.date, kind, e.durationSec, ring)
    }

    /** Numbers you blocked one by one: exact block rules and Android's list. */
    private suspend fun blockedLines(c: DataContainer, iso: String): Set<String> {
        val rules: List<BlockRule> = runCatching { c.blocks.enabledRules() }.getOrDefault(emptyList())
        val fromRules = rules.filter { it.kind == RuleKind.BLOCK && it.type == RuleType.EXACT }.map { it.pattern }
        val system = runCatching { c.blocks.loadSystemNow().map { it.number } }.getOrDefault(emptyList())
        return (fromRules + system).mapNotNullTo(HashSet()) { PhoneIdentity.e164(it, iso) }
    }
}
