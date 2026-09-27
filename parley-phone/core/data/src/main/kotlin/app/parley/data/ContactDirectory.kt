package app.parley.data

import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.Collator

/**
 * The phone's contacts as the screens show them (named and sorted by first or last name, as set in Settings) and the
 * number → contact index built from them. Shared by every view model that names calls or searches people, so the
 * list is sorted and indexed once; the work stops shortly after the last screen using it goes away.
 */
class ContactDirectory(contacts: ContactsRepository, settings: SettingsRepository, val countryIso: String, scope: CoroutineScope) {
    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    val contacts: StateFlow<List<ContactSummary>?> = combine(contacts.contacts, settings.settings.map { it.sortByFirstName }.distinctUntilChanged()) { list, first ->
        when {
            list == null -> null
            first -> list
            else -> list.map { it.copy(displayName = it.displayNameAlt) }.sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
        }
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
