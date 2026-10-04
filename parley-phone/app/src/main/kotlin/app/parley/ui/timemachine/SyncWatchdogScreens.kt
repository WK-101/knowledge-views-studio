// The watchdog's destination and its graph live together with its screens.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.timemachine

import app.parley.data.backup.SyncWatch
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.ContactsContract
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.SyncDisabled
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.backup.WatchEvent
import app.parley.common.backup.WatchKind
import app.parley.common.record.AccountKinds
import app.parley.common.record.ContactRecord
import app.parley.common.ux.Tips
import app.parley.data.AccountRef
import app.parley.data.backup.LostNumbers
import app.parley.ui.Banner
import app.parley.ui.Destination
import app.parley.ui.EmptyState
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleyTopBar
import app.parley.ui.PersonRow
import app.parley.ui.Spacing
import app.parley.ui.appVm
import app.parley.ui.common.CoachMark
import app.parley.ui.common.Format
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** "Restore from snapshot" for one watchdog event (by its fingerprint). */
object WatchRoutes {
    @Serializable data class Restore(val event: String) : Destination
}

fun NavGraphBuilder.watchGraph(nav: NavController) {
    composable<WatchRoutes.Restore> { e -> WatchRestoreScreen(appVm(), e.toRoute<WatchRoutes.Restore>().event, back = { nav.popBackStack() }) }
}

/** The watchdog's words, shared by its card and its notification. Accounts are named; people never are. */
object WatchText {
    fun account(res: Resources, type: String?, name: String?): String =
        if (type == null || AccountKinds.isLocalType(type)) res.getString(R.string.ppl_phone) else AccountRef(type, name).displayLabel

    fun title(res: Resources, e: WatchEvent): String {
        val where = account(res, e.accountType, e.accountName)
        return when (e.kind) {
            WatchKind.CONTACTS_VANISHED -> res.getQuantityString(R.plurals.watch_vanished_title, e.count, e.count, where)
            WatchKind.ACCOUNT_EMPTIED -> res.getQuantityString(R.plurals.watch_emptied_title, e.count, e.count, where)
            WatchKind.ACCOUNT_REMOVED -> res.getString(R.string.watch_removed_title, where)
            WatchKind.NUMBERS_LOST -> res.getQuantityString(R.plurals.watch_numbers_title, e.count, e.count)
            WatchKind.MASTER_SYNC_OFF -> res.getString(R.string.watch_master_off_title)
            WatchKind.SYNC_OFF -> res.getString(R.string.watch_sync_off_title, where)
        }
    }

    fun body(res: Resources, e: WatchEvent, context: Context): String = when (e.kind) {
        WatchKind.CONTACTS_VANISHED, WatchKind.ACCOUNT_EMPTIED -> res.getString(R.string.watch_vanished_body, since(context, e.since))
        WatchKind.ACCOUNT_REMOVED ->
            if (e.count > 0) res.getQuantityString(R.plurals.watch_removed_body, e.count, e.count) else res.getString(R.string.watch_removed_body_kept)
        WatchKind.NUMBERS_LOST -> res.getString(R.string.watch_numbers_body, since(context, e.since))
        WatchKind.MASTER_SYNC_OFF -> res.getString(R.string.watch_master_off_body)
        WatchKind.SYNC_OFF -> res.getQuantityString(R.plurals.watch_sync_off_body, e.total, e.total)
    }

    /** "Since yesterday", "Since Mon", "Since 12 Mar". */
    private fun since(context: Context, at: Long): String =
        if (DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS)) context.getString(R.string.watch_since_yesterday)
        else context.getString(R.string.watch_since, Format.shortWhen(context, at))
}

/** Android's account sync settings, on the contacts authority. */
fun openSyncSettings(context: Context, failed: () -> Unit) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).putExtra(Settings.EXTRA_AUTHORITIES, arrayOf(ContactsContract.AUTHORITY)))
    }.onFailure { failed() }
}

/**
 * Contact health check › the watchdog's cards (one per event not yet answered), with its one-time explainer above.
 * Each card: "Restore from snapshot" (when contacts can come back), "It was me" (dismiss for good), and the account
 * sync settings.
 */
@Composable
fun SyncWatchdogCards(vm: AppViewModel, open: (Destination) -> Unit) {
    val memory by vm.c.syncWatch.memory.collectAsStateWithLifecycle()
    Column(Modifier.animateContentSize()) {
        CoachMark(Tips.SYNC_WATCHDOG, stringResource(R.string.watch_tip))
        memory.pending.asReversed().forEach { e -> WatchCard(vm, e, open) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchCard(vm: AppViewModel, e: WatchEvent, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val title = WatchText.title(res, e)
    val sync = e.kind == WatchKind.SYNC_OFF || e.kind == WatchKind.MASTER_SYNC_OFF
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = ParleyShapes.card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listInset, vertical = Spacing.xs),
    ) {
        Column(Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (sync) Icons.Rounded.SyncDisabled else Icons.Rounded.PersonOff, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.m))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            Text(WatchText.body(res, e, context), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs, end = Spacing.s))
            FlowRow(
                Modifier.fillMaxWidth().padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
                verticalArrangement = Arrangement.Center,
            ) {
                val failed = res.getString(R.string.ppl_sync_settings_failed)
                TextButton({ openSyncSettings(context) { vm.toast(failed) } }) { Text(stringResource(R.string.ppl_open_sync_settings)) }
                TextButton({
                    vm.c.syncWatch.dismiss(e)
                    vm.toast(res.getString(R.string.watch_it_was_me_done))
                }) { Text(stringResource(R.string.watch_it_was_me)) }
                if (e.restorable) FilledTonalButton({ open(WatchRoutes.Restore(e.fingerprint)) }) { Text(stringResource(R.string.watch_restore)) }
            }
        }
    }
}

/** One row to restore: a vanished contact, or a contact's lost numbers. */
private data class Pick(val key: String, val name: String, val lines: List<String>, val record: ContactRecord? = null, val numbers: LostNumbers? = null)

/** What the restore screen holds: the rows, the ticked ones, and what the last restore wrote (for Undo). */
private class RestoreState(val numbers: Boolean) {
    var picks by mutableStateOf<List<Pick>?>(null)
    var chosen by mutableStateOf<Set<String>>(emptySet())
    var busy by mutableStateOf(false)

    /** How many came back, and the raw contact or data row ids written. */
    var done by mutableStateOf<Pair<Int, List<Long>>?>(null)

    /** Contacts of the card that came back by themselves (sync recovered): never restored twice. */
    var cameBack by mutableStateOf(0)
}

/** Brings [selected] back; contacts that came back since the screen opened are skipped and said (M2). */
private suspend fun restoreContacts(vm: AppViewModel, st: RestoreState, event: WatchEvent?, selected: List<Pick>): SyncWatch.Restored {
    val res = vm.getApplication<Application>().resources
    val r = vm.c.syncWatch.restore(selected.mapNotNull { it.record })
    if (r.cameBack > 0) vm.toast(res.getQuantityString(R.plurals.watch_came_back, r.cameBack, r.cameBack))
    // Every one of them is back: nothing left to restore here, and the card goes.
    if (r.restored == 0 && r.cameBack > 0 && event != null) {
        vm.c.syncWatch.expire(event)
        st.picks = emptyList()
    }
    return r
}

/**
 * "Restore from snapshot" for one event: the contacts as they were before (preselected), a preview of each, and an
 * undo once restored. Vanished contacts come back as new contacts in their accounts; lost numbers are added back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchRestoreScreen(vm: AppViewModel, fingerprint: String, back: () -> Unit) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val watch = vm.c.syncWatch
    val event = remember(fingerprint) { watch.pending(fingerprint) }
    val st = remember(event) { RestoreState(event?.kind == WatchKind.NUMBERS_LOST) }
    LaunchedEffect(event) {
        val e = event ?: run { st.picks = emptyList(); return@LaunchedEffect }
        val list = if (st.numbers) {
            watch.lostNumbers(e).map { n -> Pick(n.key, n.name, n.rows.mapNotNull { describe(res, it) }, numbers = n) }
        } else {
            val v = watch.vanished(e)
            st.cameBack = v.cameBack
            v.records.map { r -> Pick(r.key, r.displayName, lines(res, r).take(3), record = r) }
        }
        st.picks = list
        st.chosen = list.map { it.key }.toSet()
    }
    val restore: () -> Unit = {
        val selected = st.picks.orEmpty().filter { it.key in st.chosen }
        st.busy = true
        scope.launch {
            val (ids, count) = if (st.numbers) {
                watch.restoreNumbers(selected.mapNotNull { it.numbers }).let { it to it.size }
            } else {
                restoreContacts(vm, st, event, selected).let { it.rawIds to it.restored }
            }
            st.busy = false
            if (ids.isNotEmpty() && event != null) {
                st.done = count to ids
                watch.dismiss(event)
            }
        }
    }
    val undo: () -> Unit = {
        val ids = st.done?.second.orEmpty()
        st.busy = true
        scope.launch {
            if (st.numbers) watch.undoNumbers(ids) else watch.undoRestore(ids)
            event?.let { watch.reopen(it) }
            st.busy = false
            st.done = null
            vm.toast(res.getString(R.string.watch_undone))
        }
    }
    ParleyScaffold(
        topBar = { ParleyTopBar(stringResource(R.string.watch_restore_title), onBack = back) },
        bottomBar = { RestoreBar(st, restore) },
    ) { p ->
        val list = st.picks
        when {
            list == null -> CircularProgressIndicator(Modifier.padding(p).padding(Spacing.xxl))
            list.isEmpty() -> EmptyState(
                Icons.Rounded.History, stringResource(R.string.watch_nothing_title), stringResource(R.string.watch_nothing_body), Modifier.padding(p),
                action = stringResource(R.string.ux_empty_back), onAction = back,
            )
            else -> RestoreList(st, list, undo, Modifier.fillMaxSize().padding(p))
        }
    }
}

@Composable
private fun RestoreBar(st: RestoreState, restore: () -> Unit) {
    if (st.picks.isNullOrEmpty() || st.done != null) return
    val n = st.chosen.size
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.l),
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (st.busy) CircularProgressIndicator(Modifier.size(24.dp).padding(end = Spacing.s), strokeWidth = 2.dp)
            Button(restore, enabled = !st.busy && n > 0) {
                Text(pluralStringResource(if (st.numbers) R.plurals.watch_numbers_button else R.plurals.watch_restore_button, n, n))
            }
        }
    }
}

@Composable
private fun RestoreList(st: RestoreState, list: List<Pick>, undo: () -> Unit, modifier: Modifier) {
    val res = LocalResources.current
    val picking = st.done == null
    LazyColumn(modifier) {
        st.done?.let { (n, _) ->
            item(key = "done") {
                val text = res.getQuantityString(if (st.numbers) R.plurals.watch_numbers_restored else R.plurals.watch_restored, n, n)
                Banner(text, action = stringResource(R.string.dc_undo).takeIf { !st.busy }, onAction = undo)
            }
        }
        item(key = "intro") {
            Text(
                if (st.numbers) {
                    stringResource(R.string.watch_numbers_intro)
                } else {
                    pluralStringResource(R.plurals.watch_restore_intro, list.size) +
                        (if (st.cameBack > 0) " " + res.getQuantityString(R.plurals.watch_came_back, st.cameBack, st.cameBack) else "")
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.listInset, vertical = Spacing.s),
            )
        }
        if (picking) {
            item(key = "all") {
                val all = st.chosen.size == list.size
                ParleyListItem(
                    modifier = Modifier.toggleable(all, role = Role.Checkbox) { on -> st.chosen = if (on) list.map { it.key }.toSet() else emptySet() },
                    headlineContent = { Text(stringResource(R.string.watch_select_all), style = MaterialTheme.typography.labelLarge) },
                    trailingContent = { Checkbox(all, onCheckedChange = null) },
                )
            }
        }
        items(list, key = { it.key }) { pick ->
            val on = pick.key in st.chosen
            val toggle = if (picking) {
                Modifier.toggleable(on, role = Role.Checkbox) { v -> st.chosen = if (v) st.chosen + pick.key else st.chosen - pick.key }
            } else {
                Modifier
            }
            PersonRow(
                pick.name, null,
                modifier = Modifier.heightIn(min = 56.dp).then(toggle),
                supportingContent = if (pick.lines.isEmpty()) null else ({ Text(pick.lines.joinToString("\n")) }),
                trailingContent = if (picking) ({ Checkbox(on, onCheckedChange = null) }) else null,
            )
        }
    }
}
