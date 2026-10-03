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
import app.parley.common.people.ContactSearch
import app.parley.common.people.LifeEvents
import app.parley.common.people.PersonExtra
import app.parley.common.record.Messengers
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.Permissions
import app.parley.data.PhoneEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
 */
class PeopleIndex(
    private val context: Context,
    contacts: ContactsRepository,
    scope: CoroutineScope,
    /** Deferred with the contact list itself (see [app.parley.data.StartGate]). */
    started: SharingStarted = SharingStarted.Eagerly,
) {
    private val cr = context.contentResolver

    val data: StateFlow<PeopleIndexData> = contacts.contacts
        .map { if (it == null) PeopleIndexData() else load() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, started, PeopleIndexData())

    private class Acc(id: Long, region: String?) {
        var company = ""
        var title = ""
        var nickname = ""
        val accounts = LinkedHashSet<String>()
        val labels = HashSet<String>()
        var deceased = false
        val search = ContactSearch.Builder(id, region)
    }

    private fun load(): PeopleIndexData {
        if (!Permissions.has(context, Manifest.permission.READ_CONTACTS)) return PeopleIndexData(loaded = true)
        val region = PhoneEnv.countryIso(context)
        val byId = HashMap<Long, Acc>()
        fun acc(id: Long) = byId.getOrPut(id) { Acc(id, region) }

        val titles = HashMap<Long, String>()
        query(Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.TITLE, Groups.SYSTEM_ID, Groups.AUTO_ADD), "${Groups.DELETED}=0") { c ->
            if (c.getString(2) == null && c.getInt(3) == 0) c.getString(1)?.takeIf { it.isNotBlank() }?.let { titles[c.getLong(0)] = it.trim() }
        }

        val accountContacts = HashMap<AccountRef, MutableSet<Long>>()
        query(RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED}=0") { c ->
            val id = c.getLong(0)
            val type = c.getString(1)
            if (Messengers.isMessengerAccount(type)) return@query
            val a = AccountRef(type, c.getString(2))
            accountContacts.getOrPut(a) { HashSet() } += id
            acc(id).accounts += a.displayLabel
        }

        // Every field the Contacts search looks at, plus what the lists need (company, nickname, labels, a death date).
        val kinds = ContactSearch.ROW_KINDS + GroupMembership.CONTENT_ITEM_TYPE
        query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.MIMETYPE) + COLUMNS,
            "${Data.MIMETYPE} IN (${kinds.joinToString(",") { "?" }})",
            kinds.toTypedArray(),
        ) { c ->
            val a = acc(c.getLong(0))
            val mime = c.getString(1) ?: return@query
            // Data columns from the third on: data1 is index 2.
            val get: (String) -> String? = { col -> COLUMNS.indexOf(col).takeIf { it >= 0 }?.let { c.getString(it + 2) } }
            when (mime) {
                Organization.CONTENT_ITEM_TYPE -> if (a.company.isEmpty() && a.title.isEmpty()) {
                    a.company = get(Data.DATA1).orEmpty().trim()
                    a.title = get(Data.DATA4).orEmpty().trim() // Organization.TITLE = DATA4
                }
                Nickname.CONTENT_ITEM_TYPE -> if (a.nickname.isEmpty()) a.nickname = get(Data.DATA1).orEmpty().trim()
                GroupMembership.CONTENT_ITEM_TYPE -> get(Data.DATA1)?.toLongOrNull()?.let { titles[it] }?.let { a.labels += it }
                Event.CONTENT_ITEM_TYPE -> if (LifeEvents.isDeath(get(Data.DATA2)?.toIntOrNull() ?: 0, get(Data.DATA3))) a.deceased = true
            }
            a.search.row(mime, get)
        }

        val extras = byId.mapValues { (_, a) -> PersonExtra(a.company, a.title, a.nickname, a.accounts.toList(), a.labels, a.deceased) }
        val labelCounts = HashMap<String, Int>()
        extras.values.forEach { e -> e.labels.forEach { labelCounts[it] = (labelCounts[it] ?: 0) + 1 } }
        titles.values.forEach { labelCounts.putIfAbsent(it, 0) }
        val search = byId.mapValues { (_, a) ->
            a.labels.forEach { a.search.label(it) }
            a.accounts.forEach { a.search.account(it) }
            a.search.build()
        }
        return PeopleIndexData(extras, accountContacts.mapValues { it.value.size }, labelCounts, loaded = true, search = search)
    }

    private companion object {
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
