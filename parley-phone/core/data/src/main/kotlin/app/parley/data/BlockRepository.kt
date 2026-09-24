package app.parley.data

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.RuleType
import app.parley.data.db.AppDatabase
import app.parley.data.db.BlockRuleEntity
import app.parley.data.db.BlockedCallEntity
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

class BlockRepository(private val context: Context, db: AppDatabase, scope: CoroutineScope) {
    private val dao = db.blockDao()
    private val cr = context.contentResolver

    val rules: StateFlow<List<BlockRule>> = dao.rules()
        .map { list -> list.map { it.toRule() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val blockedCalls: Flow<List<BlockedCallEntity>> = dao.blockedCalls()

    val systemList: StateFlow<List<SystemBlockedNumber>> = cr.changes(BlockedNumbers.CONTENT_URI)
        .map { loadSystem() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    suspend fun saveRule(rule: BlockRule) {
        dao.upsertRule(BlockRuleEntity(rule.id, rule.pattern.trim(), rule.type.name, rule.action.name, rule.enabled, rule.note))
    }

    suspend fun deleteRule(id: Long) = dao.deleteRule(id)

    suspend fun enabledRules(): List<BlockRule> = dao.enabledRules().map { it.toRule() }

    suspend fun logBlocked(number: String?, reason: String, action: BlockAction) {
        dao.logBlocked(BlockedCallEntity(number = number, reason = reason, action = action.name, time = System.currentTimeMillis()))
    }

    suspend fun clearBlockedLog() = dao.clearBlocked()

    suspend fun lastBlocked(number: String): Long? = dao.lastBlocked(number)

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

    private fun BlockRuleEntity.toRule() = BlockRule(
        id = id,
        pattern = pattern,
        type = runCatching { RuleType.valueOf(type) }.getOrDefault(RuleType.EXACT),
        action = runCatching { BlockAction.valueOf(action) }.getOrDefault(BlockAction.REJECT),
        enabled = enabled,
        note = note,
    )
}
