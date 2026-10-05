package app.parley.data.people

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import app.parley.common.ContactSummary
import app.parley.common.people.ContactSearch
import app.parley.common.people.LifeEvents
import app.parley.common.people.NativeNames
import app.parley.common.people.PersonExtra
import app.parley.common.record.Messengers
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.Permissions
import app.parley.data.PhoneEnv
import app.parley.data.messaging.Romanizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** An account with how many contacts it holds (shown wherever accounts are listed). */
data class AccountCount(val account: AccountRef, val contacts: Int)

data class PeopleIndexData(
    val extras: Map<Long, PersonExtra> = emptyMap(),
    /** Distinct contacts per account. */
    val accountCounts: Map<AccountRef, Int> = emptyMap(),
    /** Contacts per label title. */
    val labelCounts: Map<String, Int> = emptyMap(),
    val loaded: Boolean = false,
    /**
     * Every field of each contact, prepared for the Contacts search and its filters ([ContactSearch]). Memory only,
     * rebuilt with the rest whenever the address book changes.
     */
    val search: Map<Long, ContactSearch.Doc> = emptyMap(),
) {
    fun countFor(a: AccountRef): Int = accountCounts[a] ?: 0

    /** "Google · me@x (212)". */
    fun labelWithCount(a: AccountRef): String = "${a.displayLabel} (${countFor(a)})"
}

/**
 * Per-contact fields the lists need beyond the contact summaries (company, title, nickname, accounts, labels,
 * date of death) and every field the Contacts search and filters look at, recomputed off the main thread whenever the
 * address book changes (the contact list follows Android's change notifications).
 *
 * After the first build only the contacts that changed are read again, so a sync touching three contacts reads three
 * contacts' rows, not every row of the address book. "Changed" is Android's last-updated time of the contact, which
 * any row moves (a label, company, nickname, note, address…), as well as its summary. A renamed label, a new region
 * or a large change builds everything again.
 */
class PeopleIndex(
    private val context: Context,
    private val contacts: ContactsRepository,
    scope: CoroutineScope,
    /** When the index follows the contact list; the container stops it a while after no screen needs it. */
    started: SharingStarted = SharingStarted.Eagerly,
) {
    private val cr = context.contentResolver

    // The list, and the loads that found changed rows without changing the list (it isn't sent again then).
    val data: StateFlow<PeopleIndexData> = combine(contacts.contacts, contacts.rowsChanged) { list, _ -> list }
        .map { if (it == null) PeopleIndexData() else update(it) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, started, PeopleIndexData())

    /** What the last build read, so the next one reads only what changed. */
    private class Built(
        val list: List<ContactSummary>,
        val region: String?,
        val titles: Map<Long, String>,
        val entries: Map<Long, Entry>,
        val data: PeopleIndexData,
        /** Each contact's last-updated time when it was read (null: unknown, so read again next time). */
        val stamps: Map<Long, Long?>,
    )

    private class Entry(val extra: PersonExtra, val accounts: Set<AccountRef>, val doc: ContactSearch.Doc)

    @Volatile private var built: Built? = null

    /** How the last update went, for tests: whether it patched the previous index, and how many contacts it read. */
    internal data class UpdateStats(val incremental: Boolean, val read: Int)

    @Volatile internal var lastUpdate = UpdateStats(false, 0)
        private set

    private class Acc(id: Long, region: String?) {
        var company = ""
        var title = ""
        var nickname = ""
        var nativeName = ""
        val accounts = LinkedHashSet<String>()
        val accountRefs = LinkedHashSet<AccountRef>()
        val labels = HashSet<String>()
        var deceased = false
        // Names in other scripts are searched by their Latin spelling too, made here once per contact.
        val search = ContactSearch.Builder(id, region, latin = Romanizer)

        /** One data row of the contact: what the lists show from it, and everything the search looks at. */
        fun row(mime: String, get: (String) -> String?, titles: Map<Long, String>) {
            when (mime) {
                Organization.CONTENT_ITEM_TYPE -> if (company.isEmpty() && title.isEmpty()) {
                    company = get(Data.DATA1).orEmpty().trim()
                    title = get(Data.DATA4).orEmpty().trim() // Organization.TITLE = DATA4
                }
                // A nickname labelled "Name in Russian" is the name in their language (NativeNames), not a nickname.
                Nickname.CONTENT_ITEM_TYPE -> if (NativeNames.isRow(mime, get)) {
                    if (nativeName.isEmpty()) nativeName = NativeNames.fromRow(get).shown
                } else if (nickname.isEmpty()) {
                    nickname = get(Data.DATA1).orEmpty().trim()
                }
                GroupMembership.CONTENT_ITEM_TYPE -> get(Data.DATA1)?.toLongOrNull()?.let { titles[it] }?.let { labels += it }
                Event.CONTENT_ITEM_TYPE -> if (LifeEvents.isDeath(get(Data.DATA2)?.toIntOrNull() ?: 0, get(Data.DATA3))) deceased = true
            }
            search.row(mime, get)
        }
    }

    private fun update(list: List<ContactSummary>): PeopleIndexData {
        if (!Permissions.has(context, Manifest.permission.READ_CONTACTS)) {
            built = null
            return PeopleIndexData(loaded = true)
        }
        val region = PhoneEnv.countryIso(context)
        val titles = groupTitles()
        // Taken before the rows are read: a change landing in between moves the time again and is read next time.
        val stamps = HashMap<Long, Long?>(list.size * 2)
        list.forEach { stamps[it.id] = contacts.lastUpdated(it.id) }
        val prev = built?.takeIf { it.region == region && it.titles == titles }
        if (prev != null) {
            if (prev.list === list && prev.stamps == stamps) return prev.data
            patch(prev, list, region, titles, stamps)?.let { return it }
        }
        val entries = read(null, region, titles)
        lastUpdate = UpdateStats(incremental = false, read = entries.size)
        return publish(list, region, titles, entries, unchanged = false, prev = null, stamps = stamps)
    }

    /**
     * [prev] with the contacts [list] changed read again and those it no longer has dropped; null when too many
     * changed (then everything is read).
     */
    private fun patch(
        prev: Built,
        list: List<ContactSummary>,
        region: String?,
        titles: Map<Long, String>,
        stamps: Map<Long, Long?>,
    ): PeopleIndexData? {
        val before = HashMap<Long, ContactSummary>(prev.list.size * 2)
        prev.list.forEach { before[it.id] = it }
        val ids = HashSet<Long>(list.size * 2)
        val changed = ArrayList<Long>()
        for (c in list) {
            ids += c.id
            // An equal summary isn't enough: labels, company, notes, addresses… aren't in it, but move the time.
            val stamp = stamps[c.id]
            if (before[c.id] != c || stamp == null || prev.stamps[c.id] != stamp) changed += c.id
        }
        if (changed.size > INCREMENTAL_MAX) return null
        val removed = prev.entries.keys.count { it !in ids }
        val entries = HashMap(prev.entries)
        entries.keys.retainAll(ids)
        changed.forEach { entries.remove(it) }
        changed.chunked(IN_CHUNK).forEach { chunk -> entries.putAll(read(chunk, region, titles)) }
        lastUpdate = UpdateStats(incremental = true, read = changed.size)
        return publish(list, region, titles, entries, unchanged = changed.isEmpty() && removed == 0, prev, stamps)
    }

    private fun publish(
        list: List<ContactSummary>,
        region: String?,
        titles: Map<Long, String>,
        entries: Map<Long, Entry>,
        unchanged: Boolean,
        prev: Built?,
        stamps: Map<Long, Long?>,
    ): PeopleIndexData {
        val data = if (unchanged && prev != null) prev.data else {
            val labelCounts = HashMap<String, Int>()
            val accountCounts = HashMap<AccountRef, Int>()
            entries.values.forEach { e ->
                e.extra.labels.forEach { labelCounts[it] = (labelCounts[it] ?: 0) + 1 }
                e.accounts.forEach { accountCounts[it] = (accountCounts[it] ?: 0) + 1 }
            }
            titles.values.forEach { labelCounts.putIfAbsent(it, 0) }
            PeopleIndexData(
                entries.mapValues { it.value.extra }, accountCounts, labelCounts, loaded = true, search = entries.mapValues { it.value.doc },
            )
        }
        built = Built(list, region, titles, entries, data, stamps)
        return data
    }

    /** User label titles by group row id (a small table, read on every update to notice a renamed label). */
    private fun groupTitles(): Map<Long, String> {
        val titles = HashMap<Long, String>()
        query(Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.TITLE, Groups.SYSTEM_ID, Groups.AUTO_ADD), "${Groups.DELETED}=0") { c ->
            if (c.getString(2) == null && c.getInt(3) == 0) c.getString(1)?.takeIf { it.isNotBlank() }?.let { titles[c.getLong(0)] = it.trim() }
        }
        return titles
    }

    /** The entries of contacts [ids] (everyone when null). */
    private fun read(ids: List<Long>?, region: String?, titles: Map<Long, String>): Map<Long, Entry> {
        val byId = HashMap<Long, Acc>()
        fun acc(id: Long) = byId.getOrPut(id) { Acc(id, region) }
        val only = ids?.let { " AND ${RawContacts.CONTACT_ID} IN (${it.joinToString(",")})" }.orEmpty()

        val rawProjection = arrayOf(RawContacts.CONTACT_ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME)
        query(RawContacts.CONTENT_URI, rawProjection, "${RawContacts.DELETED}=0$only") { c ->
            val id = c.getLong(0)
            val type = c.getString(1)
            if (Messengers.isMessengerAccount(type)) return@query
            val a = AccountRef(type, c.getString(2))
            acc(id).accounts += a.displayLabel
            acc(id).accountRefs += a
        }

        // Every field the Contacts search looks at, plus what the lists need (company, nickname, labels, a death date).
        val kinds = ContactSearch.ROW_KINDS + GroupMembership.CONTENT_ITEM_TYPE
        val onlyData = ids?.let { " AND ${Data.CONTACT_ID} IN (${it.joinToString(",")})" }.orEmpty()
        query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.MIMETYPE) + COLUMNS,
            "${Data.MIMETYPE} IN (${kinds.joinToString(",") { "?" }})$onlyData",
            kinds.toTypedArray(),
        ) { c ->
            val mime = c.getString(1) ?: return@query
            // Data columns from the third on: data1 is index 2.
            acc(c.getLong(0)).row(mime, { col -> COLUMNS.indexOf(col).takeIf { it >= 0 }?.let { c.getString(it + 2) } }, titles)
        }

        return byId.mapValues { (_, a) ->
            a.labels.forEach { a.search.label(it) }
            a.accounts.forEach { a.search.account(it) }
            Entry(PersonExtra(a.company, a.title, a.nickname, a.accounts.toList(), a.labels, a.deceased, a.nativeName), a.accountRefs, a.search.build())
        }
    }

    private companion object {
        /** More changed contacts than this (a first account sync, a restore) build the whole index again. */
        const val INCREMENTAL_MAX = 500

        /** Ids per `IN (…)` selection. */
        const val IN_CHUNK = 500

        /** DATA1 to DATA10, and DATA11 for an address's RFC 9554 parts ([app.parley.common.people.AddressParts.COLUMN]). */
        val COLUMNS = arrayOf(
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10, Data.DATA11,
        )
    }

    private inline fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        args: Array<String>? = null,
        each: (Cursor) -> Unit,
    ) {
        val c = try {
            cr.query(uri, projection, selection, args, null)
        } catch (_: Exception) {
            null
        } ?: return
        c.use { while (it.moveToNext()) each(it) }
    }
}
