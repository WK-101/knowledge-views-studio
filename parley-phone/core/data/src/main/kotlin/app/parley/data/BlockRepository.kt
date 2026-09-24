package app.parley.data

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.NotifyLevel
import app.parley.common.PhoneNumbers
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.ScreeningResult
import app.parley.common.TraceCodec
import app.parley.data.db.AppDatabase
import app.parley.data.db.BlockRuleEntity
import app.parley.data.db.BlockedCallEntity
import app.parley.data.db.CallRingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

data class SystemBlockedNumber(val id: Long, val number: String)

/** What Recents and the caller card show for a number that was screened (B2). */
data class VerdictSummary(val text: String, val kind: String?, val blocked: Boolean, val time: Long, val entryId: Long)

class BlockRepository(private val context: Context, db: AppDatabase, scope: CoroutineScope) {
    private val dao = db.blockDao()
    private val cr = context.contentResolver

    val rules: StateFlow<List<BlockRule>> = dao.rules()
        .map { list -> list.map { it.toRule() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Blocked calls only (the blocked log). */
    val blockedCalls: Flow<List<BlockedCallEntity>> = dao.blockedCalls()

    /** Blocked calls and unknown callers that were let through, newest first. */
    val screenedCalls: Flow<List<BlockedCallEntity>> = dao.screenedCalls()

    /** Latest screening verdict per number (match key), for Recents' second line. */
    val verdictIndex: StateFlow<Map<String, VerdictSummary>> = dao.screenedCalls()
        .map { list ->
            val m = HashMap<String, VerdictSummary>()
            list.forEach { e ->
                val n = e.number ?: return@forEach
                val text = e.verdict ?: return@forEach
                m.putIfAbsent(PhoneNumbers.matchKey(n), VerdictSummary(text, e.verdictKind, !e.allowed, e.time, e.id))
            }
            m as Map<String, VerdictSummary>
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Lazily, emptyMap())

    /** Ring lengths of the last 30 days (wangiri guard). */
    val rings: StateFlow<List<CallRingEntity>> = dao.rings(System.currentTimeMillis() - 30L * 86_400_000L)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val systemList: StateFlow<List<SystemBlockedNumber>> = cr.changes(BlockedNumbers.CONTENT_URI)
        .map { loadSystem() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    suspend fun saveRule(rule: BlockRule): Long = dao.upsertRule(rule.toEntity())

    /** Adds rules that aren't there yet (same kind, type and pattern). Returns how many were added. */
    suspend fun addRules(list: List<BlockRule>): Int {
        val existing = dao.allRules().map { "${it.kind}|${it.type}|${it.pattern}" }.toHashSet()
        val fresh = list.filter { existing.add("${it.kind.name}|${it.type.name}|${it.pattern.trim()}") }
        if (fresh.isNotEmpty()) dao.insertRules(fresh.map { it.toEntity().copy(id = 0) })
        return fresh.size
    }

    suspend fun deleteRule(id: Long) = dao.deleteRule(id)

    suspend fun enabledRules(): List<BlockRule> = dao.enabledRules().map { it.toRule() }

    suspend fun recordHit(ruleId: Long, time: Long = System.currentTimeMillis()) = dao.recordHit(ruleId, time)

    suspend fun deleteExpiredRules(now: Long = System.currentTimeMillis()) = dao.deleteExpiredRules(now)

    suspend fun logBlocked(number: String?, reason: String, action: BlockAction) {
        dao.logBlocked(BlockedCallEntity(number = number, reason = reason, action = action.name, time = System.currentTimeMillis()))
    }

    /** Stores a screened call with its trace. */
    suspend fun logScreened(number: String?, result: ScreeningResult, callerName: String?, simId: String?, time: Long = System.currentTimeMillis()): Long {
        val block = result.decision as? app.parley.common.Decision.Block
        return dao.logScreened(
            BlockedCallEntity(
                number = number,
                reason = block?.reason?.name ?: "ALLOWED_" + (result.allowedBy?.name ?: "DEFAULT"),
                action = block?.action?.name ?: "ALLOW",
                time = time,
                allowed = block == null,
                trace = TraceCodec.encode(result.trace),
                verdict = result.verdict?.text ?: block?.let { "Blocked: " + app.parley.common.CallPolicy.reasonLabel(it.reason) },
                verdictKind = result.verdict?.kind?.name,
                ruleId = result.rule?.id?.takeIf { it > 0 },
                packId = result.listHit?.packId,
                failedOpen = result.failedOpen,
                callerName = callerName,
                simId = simId,
            ),
        )
    }

    suspend fun screenedSince(since: Long) = dao.screenedSince(since)

    suspend fun deleteScreened(id: Long) = dao.deleteScreened(id)

    suspend fun pruneScreened(now: Long = System.currentTimeMillis()) {
        dao.pruneAllowed(now - 30L * 86_400_000L)
        dao.pruneRings(now - 60L * 86_400_000L)
    }

    /** Times Parley blocked this number recently (repeat-caller check), in every stored form. */
    suspend fun recentBlockedTimes(number: String, since: Long): List<Long> {
        val forms = PhoneNumbers.forwardedParts(number).flatMap { listOf(it, PhoneNumbers.clean(it)) }.distinct()
        return dao.blockedTimes(forms, since)
    }

    suspend fun clearBlockedLog() = dao.clearBlocked()

    suspend fun lastBlocked(number: String): Long? = dao.lastBlocked(number)

    suspend fun addRing(number: String, startedAt: Long, ringMs: Long, answered: Boolean) =
        dao.addRing(CallRingEntity(numberKey = PhoneNumbers.matchKey(number), startedAt = startedAt, ringMs = ringMs, answered = answered))

    suspend fun ringsFor(number: String) = dao.ringsFor(PhoneNumbers.matchKey(number))

    fun canUseSystemList(): Boolean = try {
        BlockedNumberContract.canCurrentUserBlockNumbers(context)
    } catch (_: Exception) {
        false
    }

    fun loadSystemNow(): List<SystemBlockedNumber> = loadSystem()

    private fun loadSystem(): List<SystemBlockedNumber> =
        cr.safeQuery(BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER))?.use { c ->
            buildList { while (c.moveToNext()) add(SystemBlockedNumber(c.getLong(0), c.getString(1).orEmpty())) }
        }.orEmpty()

    suspend fun isSystemBlocked(number: String): Boolean = withContext(Dispatchers.IO) {
        try {
            BlockedNumberContract.isBlocked(context, number)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun blockNumber(number: String): Boolean = withContext(Dispatchers.IO) {
        try {
            cr.insert(BlockedNumbers.CONTENT_URI, ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }) != null
        } catch (_: Exception) {
            false
        }
    }

    suspend fun unblockNumber(number: String) = withContext(Dispatchers.IO) {
        try {
            BlockedNumberContract.unblock(context, number)
        } catch (_: Exception) {
            0
        }
    }

    private fun BlockRule.toEntity() = BlockRuleEntity(
        id = id, pattern = pattern.trim(), type = type.name, action = action.name, enabled = enabled, note = note,
        kind = kind.name, simId = simId, schedule = schedule?.encode(), notify = notify.name, ringtone = ringtone,
        expiresAt = expiresAt, hitCount = hitCount, lastHitAt = lastHitAt, label = label,
    )

    private fun BlockRuleEntity.toRule() = BlockRule(
        id = id,
        pattern = pattern,
        type = runCatching { RuleType.valueOf(type) }.getOrDefault(RuleType.EXACT),
        action = runCatching { BlockAction.valueOf(action) }.getOrDefault(BlockAction.REJECT),
        enabled = enabled,
        note = note,
        kind = runCatching { RuleKind.valueOf(kind) }.getOrDefault(RuleKind.BLOCK),
        simId = simId,
        schedule = Schedule.decode(schedule),
        notify = runCatching { NotifyLevel.valueOf(notify) }.getOrDefault(NotifyLevel.DEFAULT),
        ringtone = ringtone,
        expiresAt = expiresAt,
        hitCount = hitCount,
        lastHitAt = lastHitAt,
        label = label,
    )
}
