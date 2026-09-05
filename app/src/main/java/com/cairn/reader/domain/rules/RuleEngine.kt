package com.cairn.reader.domain.rules

import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.db.RuleDao
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.repo.CollectionRepository
import com.cairn.reader.data.repo.TagRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the user's automation rules against an item — entirely on-device, no server, no cloud.
 * Inoreader-style "if a new article matches X, do Y", but private by construction. Called for each
 * genuinely-new item during sync (and for manually-saved items), it evaluates enabled rules in
 * order and applies the actions of those that match. A rule marked "stop after" halts the chain.
 *
 * Depends only on DAOs and the tag/collection repositories (which themselves depend only on DAOs),
 * so it introduces no dependency cycle with FeedRepository.
 */
@Singleton
class RuleEngine @Inject constructor(
    private val ruleDao: RuleDao,
    private val itemDao: ItemDao,
    private val tagRepository: TagRepository,
    private val collectionRepository: CollectionRepository,
) {
    /** True if any enabled rule exists — lets callers skip the work entirely on the common path. */
    suspend fun hasEnabledRules(): Boolean = ruleDao.enabledRules().isNotEmpty()

    /**
     * Evaluate every enabled rule against [item] and apply matching actions.
     * [plainText] is the article's plain body (already computed by the caller) so CONTENT rules can
     * match without re-parsing. Returns the number of rules that fired.
     */
    suspend fun apply(item: ItemEntity, source: SourceEntity?, plainText: String): Int {
        val rules = ruleDao.enabledRules().map { Rule.from(it) }
        if (rules.isEmpty()) return 0
        var fired = 0
        for (rule in rules) {
            if (rule.conditions.isEmpty()) continue
            if (!matches(rule, item, source, plainText)) continue
            fired++
            applyActions(rule, item)
            if (rule.stopAfter) break
        }
        return fired
    }

    private fun matches(rule: Rule, item: ItemEntity, source: SourceEntity?, plainText: String): Boolean {
        val results = rule.conditions.map { c -> evaluate(c, item, source, plainText) }
        return if (rule.matchAll) results.all { it } else results.any { it }
    }

    private fun evaluate(c: RuleCondition, item: ItemEntity, source: SourceEntity?, plainText: String): Boolean {
        val hay = when (c.field) {
            RuleField.TITLE -> item.title
            RuleField.CONTENT -> listOf(item.excerpt.orEmpty(), plainText).joinToString(" ")
            RuleField.AUTHOR -> item.author.orEmpty()
            RuleField.URL -> item.url
            RuleField.FEED -> source?.title.orEmpty()
            RuleField.FOLDER -> source?.folder.orEmpty()
            RuleField.ANY -> listOf(item.title, item.author.orEmpty(), item.excerpt.orEmpty(), plainText).joinToString(" ")
        }
        val needle = c.value.trim()
        if (needle.isBlank() && c.op != RuleOp.MATCHES) return false
        val h = hay.lowercase()
        val n = needle.lowercase()
        return when (c.op) {
            RuleOp.CONTAINS -> h.contains(n)
            RuleOp.NOT_CONTAINS -> !h.contains(n)
            RuleOp.EQUALS -> h.trim() == n
            RuleOp.STARTS_WITH -> h.trimStart().startsWith(n)
            RuleOp.ENDS_WITH -> h.trimEnd().endsWith(n)
            RuleOp.MATCHES -> runCatching { Regex(needle, RegexOption.IGNORE_CASE).containsMatchIn(hay) }.getOrDefault(false)
        }
    }

    private suspend fun applyActions(rule: Rule, item: ItemEntity) {
        val now = System.currentTimeMillis()
        for (a in rule.actions) {
            runCatching {
                when (a.type) {
                    RuleActionType.MARK_READ -> itemDao.setRead(item.id, true, now)
                    RuleActionType.STAR -> itemDao.setStarred(item.id, true, now)
                    RuleActionType.READ_LATER -> itemDao.setReadLater(item.id, true, now)
                    RuleActionType.ARCHIVE -> itemDao.setArchived(item.id, true, now)
                    RuleActionType.TRASH -> itemDao.setTrashed(item.id, now)
                    RuleActionType.ADD_TAG -> a.value?.takeIf { it.isNotBlank() }?.let { tagRepository.addToItem(item.id, it) }
                    RuleActionType.ADD_TO_COLLECTION -> a.value?.takeIf { it.isNotBlank() }?.let {
                        collectionRepository.setInCollection(item.id, it, true)
                    }
                }
            }
        }
    }
}
