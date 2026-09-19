package com.cairn.reader.data.repo

import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.domain.follow.FollowSpec
import com.cairn.reader.util.coRunCatching
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Followed authors & topics (Content Engine P5). A Follow is a standing query resolved live against
 * the whole local archive — every stored item, however old, from any channel — so a followed author
 * or topic surfaces new matches automatically without a per-site subscription.
 *
 * Persistence rides on the same DataStore string-set as saved searches (see [PreferencesRepository]);
 * resolution rides on the existing FTS index ([ItemDao.search]) and the byline query
 * ([ItemDao.itemsByAuthor]). No new table, no migration.
 */
@Singleton
class FollowsRepository @Inject constructor(
    private val itemDao: ItemDao,
    private val prefs: PreferencesRepository,
) {
    /** The followed authors & topics, decoded and stably ordered, as a live stream. */
    fun observeFollows(): Flow<List<FollowSpec>> =
        prefs.preferences.map { FollowSpec.decodeAll(it.follows) }

    suspend fun follow(spec: FollowSpec) = prefs.addFollow(spec.encode())
    suspend fun unfollow(spec: FollowSpec) = prefs.removeFollow(spec.encode())

    /** Resolve one follow to its matching articles across the whole archive, newest first. */
    suspend fun resolve(spec: FollowSpec): List<ItemListRow> = when (spec.kind) {
        FollowSpec.Kind.AUTHOR -> coRunCatching { itemDao.itemsByAuthor(spec.value) }.getOrDefault(emptyList())
        FollowSpec.Kind.TOPIC -> {
            val match = ftsMatch(spec.value)
            if (match.isBlank()) emptyList() else coRunCatching { itemDao.search(match) }.getOrDefault(emptyList())
        }
    }

    /**
     * Turn a free-text topic into a safe FTS4 MATCH expression: keep alphanumeric tokens (≥2 chars),
     * lower-case them, and AND them together (implicit AND). This sidesteps FTS syntax errors from
     * punctuation/quotes in the raw keyword and matches articles containing all the topic's words.
     */
    private fun ftsMatch(topic: String): String =
        TOKEN.findAll(topic.lowercase()).map { it.value }.filter { it.length >= 2 }.joinToString(" ")

    private companion object {
        val TOKEN = Regex("[\\p{L}\\p{Nd}]+")
    }
}
