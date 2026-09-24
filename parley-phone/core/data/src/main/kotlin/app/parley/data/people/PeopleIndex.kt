package app.parley.data.people

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import app.parley.common.people.LifeEvents
import app.parley.common.people.PersonExtra
import app.parley.common.record.Messengers
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.Permissions
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
) {
    fun countFor(a: AccountRef): Int = accountCounts[a] ?: 0

    /** "Google · me@x (212)". */
    fun labelWithCount(a: AccountRef): String = "${a.displayLabel} (${countFor(a)})"
}

/**
 * Per-contact fields the lists need beyond the contact summaries (company, title, nickname, accounts, labels,
 * date of death), recomputed off the main thread whenever the address book changes.
 */
class PeopleIndex(private val context: Context, contacts: ContactsRepository, scope: CoroutineScope) {
    private val cr = context.contentResolver

    val data: StateFlow<PeopleIndexData> = contacts.contacts
        .map { if (it == null) PeopleIndexData() else load() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, PeopleIndexData())

    private class Acc {
        var company = ""
        var title = ""
        var nickname = ""
        val accounts = LinkedHashSet<String>()
        val labels = HashSet<String>()
        var deceased = false
    }

    private fun load(): PeopleIndexData {
        if (!Permissions.has(context, android.Manifest.permission.READ_CONTACTS)) return PeopleIndexData(loaded = true)
        val byId = HashMap<Long, Acc>()
        fun acc(id: Long) = byId.getOrPut(id) { Acc() }

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

        query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4),
            "${Data.MIMETYPE} IN (?,?,?,?)",
            arrayOf(Organization.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, GroupMembership.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE),
        ) { c ->
            val a = acc(c.getLong(0))
            when (c.getString(1)) {
                Organization.CONTENT_ITEM_TYPE -> if (a.company.isEmpty() && a.title.isEmpty()) {
                    a.company = c.getString(2).orEmpty().trim()
                    a.title = c.getString(5).orEmpty().trim() // Organization.TITLE = DATA4
                }
                Nickname.CONTENT_ITEM_TYPE -> if (a.nickname.isEmpty()) a.nickname = c.getString(2).orEmpty().trim()
                GroupMembership.CONTENT_ITEM_TYPE -> c.getString(2)?.toLongOrNull()?.let { titles[it] }?.let { a.labels += it }
                Event.CONTENT_ITEM_TYPE -> if (LifeEvents.isDeath(c.getInt(3), c.getString(4))) a.deceased = true
            }
        }

        val extras = byId.mapValues { (_, a) -> PersonExtra(a.company, a.title, a.nickname, a.accounts.toList(), a.labels, a.deceased) }
        val labelCounts = HashMap<String, Int>()
        extras.values.forEach { e -> e.labels.forEach { labelCounts[it] = (labelCounts[it] ?: 0) + 1 } }
        titles.values.forEach { labelCounts.putIfAbsent(it, 0) }
        return PeopleIndexData(extras, accountContacts.mapValues { it.value.size }, labelCounts, loaded = true)
    }

    private inline fun query(
        uri: android.net.Uri,
        projection: Array<String>,
        selection: String? = null,
        args: Array<String>? = null,
        each: (android.database.Cursor) -> Unit,
    ) {
        val c = try {
            cr.query(uri, projection, selection, args, null)
        } catch (_: Exception) {
            null
        } ?: return
        c.use { while (it.moveToNext()) each(it) }
    }
}
