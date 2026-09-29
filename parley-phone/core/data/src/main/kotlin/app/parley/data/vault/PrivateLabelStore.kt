package app.parley.data.vault

import app.parley.common.people.PrivateLabels
import app.parley.data.ContactsRepository
import app.parley.data.GroupInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Label membership of private contacts: who of them is in which label. The labels themselves (names, ids, accounts)
 * are the address book's groups; the membership is Parley's, kept in each private contact's sealed caller-ID copy
 * ([VaultRepository.updateCallerChoices]), so it is backed up, restored, deleted and converted with the contact, can
 * change without unlocking, and is read by the call path while the phone is locked. Nothing about it is ever written
 * to the address book: other apps can't tell a private contact is in a label, or exists.
 *
 * Label pages, the Contacts filters and counts, the label policies (SIM, rhythm) and ringtones, label rules and call
 * time limits read it beside the address book's own membership. "Make visible" turns it into group rows; "Make
 * private" turns the contact's group rows into it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateLabelStore(private val vault: VaultRepository, private val contacts: ContactsRepository, scope: CoroutineScope) {
    /** Bumped after Parley renames, merges or deletes labels (groups changes aren't announced with contacts). */
    private val changes = MutableStateFlow(0)

    /** The address book's labels now; empty when contacts can't be read (then stored titles are trusted). */
    fun groupsNow(): List<PrivateLabels.Group> =
        runCatching { contacts.groups().map { PrivateLabels.Group(it.id, it.title) } }.getOrDefault(emptyList())

    /** Private contact id → the titles of its labels, for Parley's own lists (label pages, filters, counts). */
    val titles: StateFlow<Map<Long, Set<String>>> = combine(vault.contacts, contacts.contacts, changes) { v, _, _ -> v }
        .mapLatest { list ->
            if (list.none { it.labels.isNotEmpty() }) return@mapLatest emptyMap()
            val groups = groupsNow()
            list.associate { it.id to PrivateLabels.titles(it.labels, groups) }.filterValues { it.isNotEmpty() }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyMap())

    /** Titles of private contact [vaultId]'s labels, read now (the call path, rules, policies). */
    suspend fun titlesOf(vaultId: Long): Set<String> = withContext(Dispatchers.IO) {
        val s = vault.summary(vaultId) ?: return@withContext emptySet()
        if (s.labels.isEmpty()) emptySet() else PrivateLabels.titles(s.labels, groupsNow())
    }

    /** Titles of the labels of the private contact with [number], or null when no private contact has it. */
    suspend fun titlesForNumber(number: String, countryIso: String? = null): Set<String>? = withContext(Dispatchers.IO) {
        val hit = runCatching { vault.lookup(number, countryIso) }.getOrNull() ?: return@withContext null
        titlesOf(hit.first)
    }

    /** Private contacts (vault ids) in the label [title]. */
    suspend fun membersOf(title: String): Set<Long> = withContext(Dispatchers.IO) {
        val groups = groupsNow()
        vault.summariesNow().filter { s -> s.labels.isNotEmpty() && title.trim() in PrivateLabels.titles(s.labels, groups) }.map { it.id }.toSet()
    }

    /** Adds private contacts [vaultIds] to [group]'s label; returns how many joined (others were in it already). */
    suspend fun add(vaultIds: Collection<Long>, group: GroupInfo): Int {
        var n = 0
        val g = PrivateLabels.Group(group.id, group.title)
        vaultIds.forEach { id ->
            vault.updateCallerChoices(id) { s ->
                val next = PrivateLabels.add(s.labels, g)
                if (next.size != s.labels.size) n++
                s.copy(labels = next)
            }
        }
        changes.update { it + 1 }
        return n
    }

    /** Takes private contacts [vaultIds] out of the label [title] (every account's group of it). */
    suspend fun remove(title: String, vaultIds: Collection<Long>) {
        val groups = groupsNow()
        vaultIds.forEach { id -> vault.updateCallerChoices(id) { s -> s.copy(labels = PrivateLabels.remove(s.labels, title, groups)) } }
        changes.update { it + 1 }
    }

    /**
     * Labels about to be renamed or merged in Parley ([moves]: old title → new title): private members follow. Called
     * before the address book changes, while every membership still resolves to its label's title now.
     */
    suspend fun renamed(moves: Map<String, String>) {
        if (moves.isEmpty()) return
        val groups = groupsNow()
        val targets = PrivateLabels.canonical(groups)
        vault.summariesNow().filter { it.labels.isNotEmpty() }.forEach { s ->
            vault.updateCallerChoices(s.id) {
                val now = PrivateLabels.resolve(it.labels, groups) + PrivateLabels.unresolved(it.labels, groups)
                it.copy(labels = PrivateLabels.renamed(now, moves, targets))
            }
        }
        changes.update { it + 1 }
    }

    /** A label about to be deleted in Parley: no private contact stays in it. */
    suspend fun deleted(title: String) {
        val groups = groupsNow()
        vault.summariesNow().filter { it.labels.isNotEmpty() }.forEach { s ->
            vault.updateCallerChoices(s.id) { it.copy(labels = PrivateLabels.remove(it.labels, title, groups)) }
        }
        changes.update { it + 1 }
    }
}
