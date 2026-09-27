package app.parley.ui.journal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.ui.history.DeletedCallsList
import app.parley.ui.timemachine.SnapshotChanges
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold

/** The tabs of History & undo, in order; [key] is what a route's `tab` argument names. */
enum class HistoryTab(val key: String) {
    CONTACTS("contacts"),
    CALLS("calls"),
    SNAPSHOTS("snapshots"),
    ;

    companion object {
        fun of(key: String?): HistoryTab = entries.firstOrNull { it.key == key } ?: CONTACTS
    }
}

/**
 * History & undo: the one place to get something back. Contacts deleted or changed in Parley (30 days), calls
 * deleted in Parley (30 days) and the daily snapshots of the address book (6 months), each on its own tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryHubScreen(vm: AppViewModel, initial: HistoryTab, back: () -> Unit, open: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(initial.ordinal) }
    ParleyScaffold(topBar = {
        ParleyTopBar(stringResource(R.string.jr_title), onBack = back)
    }) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                HistoryTab.entries.forEach { t ->
                    Tab(tab == t.ordinal, { tab = t.ordinal }, text = { Text(stringResource(t.label)) })
                }
            }
            when (HistoryTab.entries[tab]) {
                HistoryTab.CONTACTS -> JournalList(vm, open, onShowSnapshots = { tab = HistoryTab.SNAPSHOTS.ordinal }, Modifier.fillMaxSize())
                HistoryTab.CALLS -> DeletedCallsList(vm, Modifier.fillMaxSize())
                HistoryTab.SNAPSHOTS -> SnapshotChanges(vm, open, Modifier.fillMaxSize())
            }
        }
    }
}

private val HistoryTab.label: Int
    get() = when (this) {
        HistoryTab.CONTACTS -> R.string.jr_tab_contacts
        HistoryTab.CALLS -> R.string.jr_tab_calls
        HistoryTab.SNAPSHOTS -> R.string.jr_tab_snapshots
    }
