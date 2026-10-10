package app.parley.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.BuildConfigInfo
import app.parley.R
import app.parley.blocking.BlockingActions
import app.parley.common.ux.Capability
import app.parley.common.ux.CapabilityAction
import app.parley.common.ux.CapabilityCatalog
import app.parley.common.ux.CapabilitySearch
import app.parley.common.ux.Job
import app.parley.common.ux.AppScreen
import app.parley.common.ux.CapabilityTarget
import app.parley.common.StartTab
import app.parley.NavEvent
import app.parley.RecentFilter
import app.parley.messaging.MessagingInbox
import app.parley.telecom.ui.ScamSignsGuide
import app.parley.ui.activityViewModel
import app.parley.ui.home.RecentsViewModel
import app.parley.security.AppLock
import app.parley.ui.EmptyState
import app.parley.ui.LinkRow
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.SegmentedGroupScope
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.SwitchRow
import app.parley.ui.blocking.BlockingDialog
import app.parley.ui.blocking.BlockingDialogs
import app.parley.ui.rowColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tools, the one hub (it was "What Parley can do", and Tools was a separate page): every row of
 * [CapabilityCatalog], grouped by the job it does, one line each; a tap opens the feature. Each job shows its featured
 * rows and folds the rest under "n more", so the page stays short; a search box filters as you type and then shows
 * every match. Lock now and Expecting a call work right here. What this release added comes first, under "New in …".
 * Reached from every tab's ⋮ › Tools, the top of Settings and the What's new card.
 */
@Composable
fun CapabilitiesScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    // Jobs opened past their featured rows, by name (a plain string survives rotation and process death).
    var opened by rememberSaveable { mutableStateOf("") }
    // Shown texts, so search finds what the screen says (the English reference texts keep matching too).
    val texts = remember(res) {
        CapabilityCatalog.rows.associate { c -> c.key to CapabilityText.of(c).let { (t, s) -> res.getString(t) to res.getString(s) } }
    }
    // Lock now means nothing without the app lock; with it on, Lock now takes the App lock row's place (one row each time).
    val rows = remember(s.appLock) { hubRows(s.appLock) }
    val recents: RecentsViewModel = activityViewModel()
    var scamGuide by rememberSaveable { mutableStateOf(false) }
    ScamGuideHost(scamGuide) { scamGuide = false }
    val shown = remember(query, texts, rows) { CapabilitySearch.search(query, rows) { texts.getValue(it.key) } }
    val version = remember { BuildConfigInfo.versionName(context) }
    val fresh = remember(version, rows) { CapabilityCatalog.newIn(version).filter { it in rows } }
    val snoozing = s.screening.snoozeActive(System.currentTimeMillis())

    fun SegmentedGroupScope.row(c: Capability) = item(c.key) {
        val (title, summary) = texts.getValue(c.key)
        HubRow(c, title, summary, snoozing) { run(vm, recents, scope, c, snoozing) { scamGuide = true } }
    }

    SettingsScaffold(stringResource(R.string.discover_title), back) {
        HubSearchField(query) { query = it }
        if (shown.isEmpty()) {
            EmptyState(
                Icons.Rounded.SearchOff, stringResource(R.string.discover_no_match, query.trim()),
                action = stringResource(R.string.ux_empty_clear_search), onAction = { query = "" },
            )
        }
        val searching = query.isNotBlank()
        if (!searching && fresh.isNotEmpty()) {
            SegmentedGroup(stringResource(R.string.discover_new_in, CapabilityCatalog.majorMinor(version))) { fresh.forEach { row(it) } }
        }
        val openJobs = opened.split(',').filter { it.isNotEmpty() }.toSet()
        Job.entries.forEach { job ->
            val (top, rest) = split(shown.filter { it.job == job }, searching)
            val open = job.name in openJobs
            SegmentedGroup(stringResource(CapabilityText.job(job))) {
                top.forEach { row(it) }
                if (open) rest.forEach { row(it) }
                if (rest.isNotEmpty()) item("more_${job.name}") {
                    MoreRow(open, rest.size, stringResource(CapabilityText.job(job))) {
                        opened = (if (open) openJobs - job.name else openJobs + job.name).joinToString(",")
                    }
                }
            }
        }
    }
}

/**
 * What a tap on [c] does: Expecting a call asks for how long (or ends it), Lock now locks, Voicemail opens Recents on
 * its chip, the others open their screen.
 */
private fun run(vm: AppViewModel, recents: RecentsViewModel, scope: CoroutineScope, c: Capability, snoozing: Boolean, onScamCheck: () -> Unit) {
    when (c.action) {
        CapabilityAction.EXPECTING_CALL ->
            if (!snoozing) BlockingDialogs.show(BlockingDialog.Snooze) else scope.launch { BlockingActions.snooze(vm.c, 0) }
        CapabilityAction.LOCK_NOW -> AppLock.lockNowByUser()
        // The inbox is a Recents filter.
        CapabilityAction.VOICEMAIL -> {
            recents.filter.value = RecentFilter.VOICEMAIL
            vm.navigate(NavEvent.Tab(StartTab.RECENTS))
        }
        // A sheet the screen shows.
        CapabilityAction.SCAM_CHECK -> onScamCheck()
        null -> {
            // "Introduce myself…" from here starts with nobody chosen: the screen lets you choose.
            if (c.target == CapabilityTarget.Screen(AppScreen.INTRODUCE)) MessagingInbox.introTargets = emptyList()
            vm.navigate(capabilityEvent(c.target))
        }
    }
}

@Composable
private fun HubSearchField(query: String, onQuery: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.discover_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.set_clear)) }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listInset),
    )
}

/** A job's rows: shown at once, and folded under "n more". While searching every match shows; otherwise the featured ones. */
private fun split(rows: List<Capability>, searching: Boolean): Pair<List<Capability>, List<Capability>> =
    if (searching) rows to emptyList() else rows.partition { it.featured }

/** One row: a switch for Expecting a call, otherwise a link (Lock now locks; the others open their feature). */
@Composable
private fun HubRow(c: Capability, title: String, summary: String, snoozing: Boolean, onTap: () -> Unit) {
    if (c.action == CapabilityAction.EXPECTING_CALL) {
        SwitchRow(title, if (snoozing) stringResource(R.string.set_expecting_call_on) else summary, snoozing) { onTap() }
    } else {
        LinkRow(title, summary) { onTap() }
    }
}

/**
 * "n more" under a job's featured rows, or "Show fewer" once open. TalkBack hears the job and the state: "4 more,
 * collapsed, double-tap to show 4 more in Stop spam".
 */
@Composable
private fun MoreRow(open: Boolean, count: Int, job: String, toggle: () -> Unit) {
    val label = if (open) stringResource(R.string.discover_fewer) else pluralStringResource(R.plurals.discover_more, count, count)
    val action = if (open) stringResource(R.string.discover_fewer_in, job) else pluralStringResource(R.plurals.discover_more_in, count, count, job)
    val state = stringResource(if (open) R.string.blk_expanded else R.string.blk_collapsed)
    ParleyListItem(
        modifier = Modifier
            .clickable(role = Role.Button, onClickLabel = action, onClick = toggle)
            .semantics { stateDescription = state },
        headlineContent = { Text(label, color = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.primary) },
        colors = rowColors(),
    )
}

/** The hub's rows: Lock now only while the app lock is on, App lock only while it's off. */
private fun hubRows(appLock: Boolean): List<Capability> =
    CapabilityCatalog.rows.filter { if (it.action == CapabilityAction.LOCK_NOW) appLock else !(it.key == APP_LOCK_ROW && appLock) }

/** The App lock row, which Lock now replaces while the app lock is on. */
private const val APP_LOCK_ROW = "app_lock"

/** Tools › Is this a scam?: the call screen's checklist, while [open]. */
@Composable
private fun ScamGuideHost(open: Boolean, close: () -> Unit) {
    if (open) ScamSignsGuide(close)
}
