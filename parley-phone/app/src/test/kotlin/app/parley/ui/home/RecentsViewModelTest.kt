package app.parley.ui.home

import android.app.Application
import android.provider.CallLog.Calls
import app.parley.RecentFilter
import app.parley.RecentGroup
import app.parley.common.CallType
import app.parley.common.calls.RecentsLayout
import app.parley.common.history.HistoryFilter
import app.parley.common.history.TypeGroup
import app.parley.common.ux.ListSections.Place
import app.parley.testing.AppTestbed
import app.parley.testing.AppTestbed.Companion.DAY
import app.parley.testing.AppTestbed.Companion.HOUR
import app.parley.testing.AppTestbed.Companion.MINUTE
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Recents' list: the grouping and day headers (with each row's place in its day's card), the chips, the search, the
 * saved filters, the selection, private calls and discreet mode, and the missed calls still to return, over the real
 * call log and contacts (fake providers).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecentsViewModelTest {
    private lateinit var t: AppTestbed
    private val zone = TimeZone.getDefault()

    /**
     * Midday of the day the view model takes as today, in the past. The clock is fixed at 18:00 that day (UTC), so
     * every call a test places a few minutes or hours before [today] is on the same day, whenever the test runs.
     */
    private var today = 0L
    private var now = 0L

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        t = AppTestbed()
        val wall = System.currentTimeMillis()
        today = wall - wall % DAY - DAY + 12 * HOUR
        now = today + 6 * HOUR
    }

    @After fun tearDown() {
        t.close()
        TimeZone.setDefault(zone)
    }

    private fun recents(): RecentsViewModel = t.viewModel { RecentsViewModel(t.c) { now } }.also { vm ->
        t.keep(vm.list)
        t.keep(vm.unreturnedMissed)
        t.keep(vm.unknownToday)
    }

    /** The rows once the list has loaded and [ready] holds. */
    private fun RecentsViewModel.groupsWhen(what: String = "the calls", ready: (List<RecentGroup>) -> Boolean = { it.isNotEmpty() }): List<RecentGroup> {
        t.until({ "$what, got ${list.value?.groups?.map { g -> g.title to g.calls.map { it.type } }}" }) { list.value?.groups?.let(ready) == true }
        return list.value!!.groups
    }

    private fun RecentsViewModel.titles(ready: (List<RecentGroup>) -> Boolean = { true }) = groupsWhen(ready = ready).map { it.title }

    // ---------------------------------------------------------------- grouping and days

    @Test fun calls_in_a_row_from_one_number_on_one_day_share_a_row() {
        t.call("+44 20 7946 0001", today, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0001", today - MINUTE, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0002", today - 2 * MINUTE, Calls.OUTGOING_TYPE, 30)
        val groups = recents().groupsWhen { it.size == 2 }
        assertEquals(2, groups[0].calls.size)
        assertEquals(CallType.MISSED, groups[0].latest.type)
        assertEquals(1, groups[1].calls.size)
    }

    @Test fun the_same_number_on_two_days_is_two_rows() {
        t.call("+44 20 7946 0001", today, Calls.INCOMING_TYPE, 10)
        t.call("+44 20 7946 0001", today - DAY, Calls.INCOMING_TYPE, 10)
        assertEquals(2, recents().groupsWhen { it.size == 2 }.size)
    }

    @Test fun chronological_layout_gives_every_call_its_own_row() {
        runBlocking { t.c.settings.update { it.copy(recentsLayout = RecentsLayout.CHRONOLOGICAL) } }
        t.call("+44 20 7946 0001", today, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0001", today - MINUTE, Calls.MISSED_TYPE)
        val groups = recents().groupsWhen { it.size == 2 }
        assertTrue(groups.all { it.calls.size == 1 })
    }

    @Test fun each_day_has_a_header_and_its_rows_know_their_place_in_the_card() {
        t.call("+44 20 7946 0001", today, Calls.INCOMING_TYPE, 5)
        t.call("+44 20 7946 0002", today - MINUTE, Calls.INCOMING_TYPE, 5)
        t.call("+44 20 7946 0003", today - 2 * MINUTE, Calls.INCOMING_TYPE, 5)
        t.call("+44 20 7946 0004", today - DAY, Calls.OUTGOING_TYPE, 5)
        t.call("+44 20 7946 0005", today - 2 * DAY, Calls.OUTGOING_TYPE, 5)
        t.call("+44 20 7946 0006", today - 2 * DAY - MINUTE, Calls.OUTGOING_TYPE, 5)
        val vm = recents()
        vm.groupsWhen { it.size == 6 }
        val rows = vm.list.value!!.rows
        val shape = rows.map { if (it is RecentsRow.Day) "day" else (it as RecentsRow.Call).place.name }
        assertEquals(listOf("day", "FIRST", "MIDDLE", "LAST", "day", "ONLY", "day", "FIRST", "LAST"), shape)
        // Keys are stable and distinct, so the lazy list keeps rows across updates.
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test fun a_day_header_is_keyed_by_its_first_row() {
        t.call("+44 20 7946 0001", today, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        val g = vm.groupsWhen().single()
        val day = vm.list.value!!.rows.first() as RecentsRow.Day
        assertEquals("h" + g.key, day.key)
        assertEquals(Place.ONLY, (vm.list.value!!.rows[1] as RecentsRow.Call).place)
    }

    @Test fun a_contacts_calls_carry_the_contact_and_its_name() {
        t.contact("Ada", "Lovelace", "+44 20 7946 0001")
        t.call("+442079460001", today, Calls.INCOMING_TYPE, 5)
        val g = recents().groupsWhen("the contact's name") { it.singleOrNull()?.contact != null }.single()
        assertEquals("Ada Lovelace", g.title)
    }

    @Test fun a_withheld_number_is_a_hidden_row() {
        t.call("", today, Calls.MISSED_TYPE, hidden = true)
        val g = recents().groupsWhen().single()
        assertTrue(g.hidden)
        assertTrue(g.key.startsWith("hidden"))
        assertTrue(g.title.isNotBlank())
    }

    // ---------------------------------------------------------------- chips

    private fun typedCalls() {
        t.call("+44 20 7946 0001", today, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0002", today - MINUTE, Calls.REJECTED_TYPE)
        t.call("+44 20 7946 0003", today - 2 * MINUTE, Calls.INCOMING_TYPE, 20)
        t.call("+44 20 7946 0004", today - 3 * MINUTE, Calls.OUTGOING_TYPE, 20)
        t.call("+44 20 7946 0005", today - 4 * MINUTE, Calls.BLOCKED_TYPE)
    }

    private fun typesWith(filter: RecentFilter): Set<CallType> {
        typedCalls()
        val vm = recents()
        vm.groupsWhen { it.size == 5 }
        vm.filter.value = filter
        return vm.groupsWhen("the $filter chip") { it.size < 5 }.map { it.latest.type }.toSet()
    }

    @Test fun the_missed_chip_keeps_missed_and_declined_calls() {
        assertEquals(setOf(CallType.MISSED, CallType.REJECTED), typesWith(RecentFilter.MISSED))
    }

    @Test fun the_incoming_chip_keeps_answered_calls() {
        assertEquals(setOf(CallType.INCOMING), typesWith(RecentFilter.INCOMING))
    }

    @Test fun the_outgoing_chip_keeps_calls_made() {
        assertEquals(setOf(CallType.OUTGOING), typesWith(RecentFilter.OUTGOING))
    }

    @Test fun the_blocked_chip_keeps_blocked_calls() {
        assertEquals(setOf(CallType.BLOCKED), typesWith(RecentFilter.BLOCKED))
    }

    @Test fun the_unknown_and_contacts_chips_split_by_who_called() {
        t.contact("Ada", "", "+44 20 7946 0001")
        val v = t.privateContact("Grace", "+44 20 7946 0009")
        t.call("+442079460001", today, Calls.INCOMING_TYPE, 5)
        t.call("+44 20 7946 0002", today - MINUTE, Calls.INCOMING_TYPE, 5)
        runBlocking { t.c.vault.storePrivateCall(v, "+44 20 7946 0009", "Grace", today - 2 * MINUTE, 5, Calls.INCOMING_TYPE) }
        val vm = recents()
        vm.groupsWhen("every caller") { g -> g.size == 3 && g.any { it.contact != null } && g.any { it.vaultId != null } }
        vm.filter.value = RecentFilter.UNKNOWN
        assertEquals(listOf("+44 20 7946 0002"), vm.groupsWhen("unknown callers") { it.size == 1 }.map { it.number })
        vm.filter.value = RecentFilter.CONTACTS
        val known = vm.groupsWhen("contacts") { it.size == 2 }
        assertEquals(setOf("Ada", "Grace"), known.map { it.title }.toSet())
    }

    @Test fun a_chip_tapped_is_remembered_for_the_next_launch() {
        val vm = recents()
        vm.setFilter(RecentFilter.MISSED)
        assertEquals(RecentFilter.MISSED, vm.filter.value)
        t.until("the chip to be stored") { t.c.settings.settings.value.recentsFilter == "MISSED" }
        vm.setFilter(RecentFilter.ALL)
        t.until("All to be stored as nothing") { t.c.settings.settings.value.recentsFilter == "" }
    }

    @Test fun recents_opens_on_the_chip_used_last() {
        runBlocking { t.c.settings.update { it.copy(recentsFilter = "UNKNOWN", rememberRecentsFilter = true) } }
        val vm = recents()
        t.until("the chip to come back") { vm.filter.value == RecentFilter.UNKNOWN }
    }

    @Test fun recents_never_opens_on_the_blocked_chip() {
        runBlocking { t.c.settings.update { it.copy(recentsFilter = "BLOCKED", rememberRecentsFilter = true) } }
        val vm = recents()
        t.call("+44 20 7946 0001", today, Calls.MISSED_TYPE)
        vm.groupsWhen()
        assertEquals(RecentFilter.ALL, vm.filter.value)
    }

    @Test fun the_chip_is_not_restored_when_remembering_is_off() {
        runBlocking { t.c.settings.update { it.copy(recentsFilter = "MISSED", rememberRecentsFilter = false) } }
        val vm = recents()
        t.call("+44 20 7946 0001", today, Calls.MISSED_TYPE)
        vm.groupsWhen()
        assertEquals(RecentFilter.ALL, vm.filter.value)
    }

    // ---------------------------------------------------------------- search

    @Test fun the_search_finds_a_name() {
        t.contact("Ada", "Lovelace", "+44 20 7946 0001")
        t.call("+442079460001", today, Calls.INCOMING_TYPE, 5)
        t.call("+44 20 7946 0002", today - MINUTE, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        vm.groupsWhen { g -> g.size == 2 && g.any { it.contact != null } }
        vm.query.value = "love"
        assertEquals(listOf("Ada Lovelace"), vm.titles { it.size == 1 })
    }

    @Test fun the_search_finds_a_number() {
        t.call("+44 20 7946 0001", today, Calls.INCOMING_TYPE, 5)
        t.call("+44 20 7946 0002", today - MINUTE, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        vm.groupsWhen { it.size == 2 }
        vm.query.value = "0002"
        assertEquals(listOf("+44 20 7946 0002"), vm.groupsWhen { it.size == 1 }.map { it.number })
    }

    @Test fun a_search_without_a_match_shows_nothing() {
        t.call("+44 20 7946 0001", today, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        vm.groupsWhen()
        vm.query.value = "zebra"
        assertTrue(vm.groupsWhen("no match") { it.isEmpty() }.isEmpty())
        assertTrue(vm.list.value!!.rows.isEmpty())
    }

    // ---------------------------------------------------------------- saved filters

    @Test fun a_saved_filter_keeps_only_its_call_types() {
        typedCalls()
        val vm = recents()
        vm.groupsWhen { it.size == 5 }
        t.c.history.activeFilter.value = HistoryFilter(name = "Out", types = setOf(TypeGroup.OUTGOING))
        assertEquals(listOf(CallType.OUTGOING), vm.groupsWhen("the saved filter") { it.size == 1 }.map { it.latest.type })
    }

    @Test fun a_saved_filter_by_length_keeps_longer_calls() {
        t.call("+44 20 7946 0001", today, Calls.INCOMING_TYPE, 600)
        t.call("+44 20 7946 0002", today - MINUTE, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        vm.groupsWhen { it.size == 2 }
        t.c.history.activeFilter.value = HistoryFilter(minDurationSec = 60)
        assertEquals(listOf("+44 20 7946 0001"), vm.groupsWhen("long calls") { it.size == 1 }.map { it.number })
    }

    @Test fun show_all_clears_the_chip_and_the_saved_filter() {
        typedCalls()
        val vm = recents()
        vm.groupsWhen { it.size == 5 }
        vm.filter.value = RecentFilter.MISSED
        t.c.history.activeFilter.value = HistoryFilter(types = setOf(TypeGroup.OUTGOING))
        vm.groupsWhen("nothing left") { it.isEmpty() }
        vm.showAll()
        assertEquals(RecentFilter.ALL, vm.filter.value)
        assertTrue(t.c.history.activeFilter.value.isEmpty)
        assertEquals(5, vm.groupsWhen("every call") { it.size == 5 }.size)
    }

    // ---------------------------------------------------------------- selection and delete

    @Test fun rows_are_selected_and_cleared() {
        t.call("+44 20 7946 0001", today, Calls.INCOMING_TYPE, 5)
        t.call("+44 20 7946 0002", today - MINUTE, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        val (a, b) = vm.groupsWhen { it.size == 2 }
        vm.toggleSelected(a)
        vm.toggleSelected(b)
        assertEquals(setOf(a.key, b.key), vm.selection.value)
        vm.toggleSelected(a)
        assertEquals(setOf(b.key), vm.selection.value)
        vm.clearSelection()
        assertTrue(vm.selection.value.isEmpty())
    }

    @Test fun delete_removes_the_rows_calls_from_the_call_log() {
        t.call("+44 20 7946 0001", today, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0001", today - MINUTE, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0002", today - 2 * MINUTE, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        val g = vm.groupsWhen { it.size == 2 }.first()
        var count = 0
        var undo: (suspend () -> Unit)? = null
        vm.delete(g) { n, back -> count = n; undo = back }
        t.until("the calls to go") { t.callLog.rows().size == 1 && undo != null }
        assertEquals("+44 20 7946 0002", t.callLog.rows().single()["number"])
        assertEquals(2, count)
        // The sheet's Undo puts both calls back.
        runBlocking { undo?.invoke() }
        t.until("the calls to come back") { t.callLog.rows().size == 3 }
    }

    @Test fun delete_on_the_selection_bar_removes_every_chosen_row_with_one_undo() {
        t.call("+44 20 7946 0001", today, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0001", today - MINUTE, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0002", today - 2 * MINUTE, Calls.OUTGOING_TYPE, 5)
        t.call("+44 20 7946 0003", today - 3 * MINUTE, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        val (a, b, _) = vm.groupsWhen { it.size == 3 }
        var count = 0
        var undo: (suspend () -> Unit)? = null
        vm.deleteMany(listOf(a, b)) { n, back -> count = n; undo = back }
        t.until("the chosen calls to go") { t.callLog.rows().size == 1 && undo != null }
        assertEquals("+44 20 7946 0003", t.callLog.rows().single()["number"])
        assertEquals(3, count)
        // One Undo brings all three back.
        runBlocking { undo?.invoke() }
        t.until("the calls to come back") { t.callLog.rows().size == 4 }
    }

    // ---------------------------------------------------------------- private contacts

    @Test fun a_private_contacts_calls_open_the_private_contact() {
        val v = t.privateContact("Grace", "+44 20 7946 0009")
        runBlocking { t.c.vault.storePrivateCall(v, "+44 20 7946 0009", "Grace", today, 5, Calls.INCOMING_TYPE) }
        val g = recents().groupsWhen("the private call") { it.singleOrNull()?.vaultId != null }.single()
        assertEquals(v, g.vaultId)
        assertTrue(g.latest.id < 0)
    }

    @Test fun discreet_mode_leaves_private_calls_out() {
        val v = t.privateContact("Grace", "+44 20 7946 0009")
        runBlocking { t.c.vault.storePrivateCall(v, "+44 20 7946 0009", "Grace", today, 5, Calls.INCOMING_TYPE) }
        t.call("+44 20 7946 0001", today - MINUTE, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        vm.groupsWhen("both calls") { it.size == 2 }
        runBlocking { t.c.settings.update { it.copy(hideVault = true) } }
        val left = vm.groupsWhen("the private call to go") { it.size == 1 }
        assertNull(left.single().vaultId)
        assertEquals("+44 20 7946 0001", left.single().number)
    }

    // ---------------------------------------------------------------- calls to return

    @Test fun a_missed_call_not_returned_is_counted_until_you_call_back() {
        val missed = t.call("+44 20 7946 0001", today - 10 * MINUTE, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0002", today - 20 * MINUTE, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0002", today, Calls.OUTGOING_TYPE, 30)
        val vm = recents()
        t.until("the calls to return") { vm.unreturnedMissed.value.isNotEmpty() }
        assertEquals(setOf(missed), vm.unreturnedMissed.value)
    }

    @Test fun unknown_callers_today_are_counted_once_each() {
        t.contact("Ada", "", "+44 20 7946 0001")
        t.call("+442079460001", today, Calls.INCOMING_TYPE, 5)
        t.call("+44 20 7946 0002", today - MINUTE, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0002", today - 2 * MINUTE, Calls.MISSED_TYPE)
        t.call("+44 20 7946 0003", today - 3 * MINUTE, Calls.INCOMING_TYPE, 5)
        t.call("+44 20 7946 0004", today - 2 * DAY, Calls.INCOMING_TYPE, 5)
        val vm = recents()
        t.until("the count") { vm.unknownToday.value == 2 }
        assertEquals(2, vm.unknownToday.value)
    }

    @Test fun the_list_is_null_until_the_call_log_has_loaded_then_holds_the_rows() {
        t.call("+44 20 7946 0001", today, Calls.INCOMING_TYPE, 5)
        val vm = t.viewModel { RecentsViewModel(t.c) { now } }
        assertNull(vm.list.value)
        t.keep(vm.list)
        assertNotNull(vm.groupsWhen())
    }
}
