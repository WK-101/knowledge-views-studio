package com.cairn.reader.data.repo

import com.cairn.reader.data.db.RuleDao
import com.cairn.reader.domain.rules.Rule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** CRUD + ordering for the on-device automation rules. */
@Singleton
class RuleRepository @Inject constructor(
    private val ruleDao: RuleDao,
) {
    fun observeRules(): Flow<List<Rule>> = ruleDao.observeAll().map { list -> list.map { Rule.from(it) } }

    suspend fun get(id: String): Rule? = ruleDao.get(id)?.let { Rule.from(it) }

    /** Save a new or edited rule. New rules go to the end of the list. */
    suspend fun save(rule: Rule) {
        val ordered = if (ruleDao.get(rule.id) == null && rule.sortOrder == 0) {
            rule.copy(sortOrder = ruleDao.count())
        } else rule
        ruleDao.upsert(ordered.toEntity())
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = ruleDao.setEnabled(id, enabled)

    suspend fun delete(id: String) = ruleDao.deleteById(id)

    /** Persist a new manual ordering (drag-to-reorder in the rules list). */
    suspend fun reorder(idsInOrder: List<String>) {
        idsInOrder.forEachIndexed { index, id ->
            ruleDao.get(id)?.let { ruleDao.update(it.copy(sortOrder = index)) }
        }
    }
}
