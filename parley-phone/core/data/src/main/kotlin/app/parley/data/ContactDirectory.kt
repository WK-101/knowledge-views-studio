package app.parley.data

import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.people.Collation
import app.parley.common.people.NameOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The phone's contacts as the screens show them (sorted and named by first or last name, as "Sort by" and "Show names as" are set) and the
 * number → contact index built from them. Shared by every view model that names calls or searches people, so the
 * list is sorted and indexed once; the work stops shortly after the last screen using it goes away.
 */
class ContactDirectory(contacts: ContactsRepository, settings: SettingsRepository, val countryIso: String, scope: CoroutineScope) {
    /** Sorts with collation keys made once per name ([Collation]). */
    private val order = Collation.Order()

    val contacts: StateFlow<List<ContactSummary>?> = combine(
        contacts.contacts,
        settings.settings.map { it.sortByFirstName to it.showNamesLastFirst }.distinctUntilChanged(),
    ) { list, (byFirst, lastFirst) ->
        list?.let { NameOrder.apply(it, byFirst, lastFirst, order) }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    /** Number → contact by line ([PhoneIdentity]), for naming call-log entries. */
    val numberIndex: StateFlow<PhoneIdentity.LineMap<ContactSummary>> = this.contacts.map { list ->
        val m = PhoneIdentity.LineMap<ContactSummary>(countryIso)
        list.orEmpty().forEach { ct -> ct.phones.forEach { p -> m.putIfAbsent(p.number, ct) } }
        m
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), PhoneIdentity.LineMap(countryIso))

    companion object {
        /** Survives a configuration change (the screens resubscribe within it) without recomputing. */
        const val STOP_AFTER_MS = 5_000L
    }
}
