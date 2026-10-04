package app.parley.ui.sync.shared

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.ThreeWayMerge.Side
import app.parley.common.sync.shared.CardField
import app.parley.common.sync.shared.HistoryItem
import app.parley.common.sync.shared.SharedCards
import app.parley.common.sync.shared.SharedLabelHistory
import app.parley.common.sync.shared.SharedLabelHistory.Phrase
import app.parley.common.sync.shared.SharedLabelMembership
import app.parley.common.ux.Tips
import app.parley.data.sync.shared.SharedLabelState
import app.parley.data.sync.shared.SharedRunResult
import app.parley.ui.Banner
import app.parley.ui.BannerTone
import app.parley.ui.ChoiceRow
import app.parley.ui.Destination
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.Spacing
import app.parley.ui.common.CoachMark
import app.parley.ui.kitStrings
import app.parley.ui.rowColors
import app.parley.work.FolderSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many changes the label page shows before "All changes". */
private const val PAGE_CHANGES = 5

/** Words for shared labels' states and history, shared by the label page and the sharing screens. */
internal object SharedLabelTexts {
    fun field(res: Resources, f: CardField): String = res.getString(
        when (f) {
            CardField.NAME -> R.string.shl_f_name
            CardField.NICKNAME -> R.string.shl_f_nickname
            CardField.ORGANISATION -> R.string.shl_f_organisation
            CardField.PHONES -> R.string.shl_f_phones
            CardField.EMAILS -> R.string.shl_f_emails
            CardField.ADDRESSES -> R.string.shl_f_addresses
            CardField.WEBSITES -> R.string.shl_f_websites
            CardField.DATES -> R.string.shl_f_dates
            CardField.RELATIONS -> R.string.shl_f_relations
            CardField.NOTE -> R.string.shl_f_note
            CardField.CHAT -> R.string.shl_f_chat
            CardField.PRONOUNS -> R.string.shl_f_pronouns
        },
    )

    /** "Ana changed Dr Lee's number" ("You …" for this phone's own changes). */
    fun line(res: Resources, item: HistoryItem, me: String?): String {
        val who = if (item.memberHex == me) res.getString(R.string.shl_you) else item.memberName.ifBlank { res.getString(R.string.shl_someone) }
        val whom = item.contactName
        return when (val p = SharedLabelHistory.phrase(item)) {
            Phrase.Added -> res.getString(R.string.shl_h_added, who, whom)
            Phrase.Removed -> res.getString(R.string.shl_h_removed, who, whom)
            is Phrase.Changed -> res.getString(R.string.shl_h_changed, who, whom, field(res, p.field))
            is Phrase.ChangedTwo -> res.getString(R.string.shl_h_changed_two, who, whom, field(res, p.first), field(res, p.second))
            Phrase.Edited -> res.getString(R.string.shl_h_edited, who, whom)
        }
    }

    fun ago(at: Long): String = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    /** The state's problem, or null when it syncs fine. */
    fun problem(res: Resources, s: SharedLabelState): String? = when {
        s.membership is SharedLabelMembership.State.KeyChanged -> res.getString(R.string.shl_status_key_changed)
        else -> when (s.lastResult) {
            SharedRunResult.FOLDER_GONE -> res.getString(R.string.shl_status_folder_gone)
            SharedRunResult.FOLDER_LOADING -> res.getString(R.string.shl_status_loading)
            SharedRunResult.NO_PERMISSION -> res.getString(R.string.shl_status_no_permission)
            SharedRunResult.KEY_CHANGED -> res.getString(R.string.shl_status_key_changed)
            SharedRunResult.CANT_SIGN -> res.getString(R.string.shl_status_cant_sign)
            SharedRunResult.NOT_A_LABEL -> res.getString(R.string.shl_status_not_label)
            SharedRunResult.LABEL_GONE -> res.getString(R.string.shl_status_label_gone)
            SharedRunResult.PAUSED -> res.getQuantityString(R.plurals.shl_status_paused, s.pendingDeletions, s.pendingDeletions)
            SharedRunResult.SYNCED, null -> null
        }
    }

    /** A quiet note for a label that syncs, or null: files it can't read (M2), a header it doesn't follow (M3). */
    fun notice(res: Resources, s: SharedLabelState): String? = when {
        !SharedLabelMembership.syncs(s.membership) -> null
        s.headerWarning -> res.getString(R.string.shl_status_header)
        s.unreadable.isNotEmpty() -> res.getString(R.string.shl_status_unreadable)
        else -> null
    }

    fun status(res: Resources, s: SharedLabelState): String =
        if (s.lastSyncAt > 0) res.getString(R.string.shl_status_synced, ago(s.lastSyncAt)) else res.getString(R.string.shl_status_never)
}

/** This phone's key hash in a shared label (its own history lines say "You"), read off the main thread (it unseals the key). */
@Composable
internal fun rememberMyHex(vm: AppViewModel): String? = produceState<String?>(null) {
    value = withContext(Dispatchers.IO) { vm.c.people.cardIdentity.publicKey()?.let { app.parley.common.sync.shared.SharedLabelFiles.keyHex(it) } }
}.value

/**
 * The label page's "Shared" part: the status, contacts changed on two phones, the members, and the latest changes with
 * a filter per member. Nothing when the label isn't shared.
 */
@Composable
fun SharedLabelSection(vm: AppViewModel, title: String, open: (Destination) -> Unit) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val shared = vm.c.sharedLabels
    LaunchedEffect(Unit) { shared.load() }
    val states by shared.states.collectAsStateWithLifecycle()
    val s = states.firstOrNull { it.title == title } ?: return
    val me = rememberMyHex(vm)
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    fun sync(allow: Boolean = false) {
        syncing = true
        scope.launch {
            shared.sync(s.labelId, allow)
            syncing = false
        }
    }
    Column {
        Section(stringResource(R.string.shl_section))
        CoachMark(Tips.SHARED_LABEL, stringResource(R.string.shl_tip))
        SharedLabelTexts.problem(res, s)?.let { p ->
            val paused = s.lastResult == SharedRunResult.PAUSED
            Banner(
                p, tone = BannerTone.WARNING,
                action = if (paused) stringResource(R.string.shl_apply) else null, onAction = if (paused) ({ sync(allow = true) }) else null,
            )
        }
        if (s.pending.isNotEmpty()) {
            Banner(
                pluralStringResource(R.plurals.shl_conflicts, s.pending.size, s.pending.size),
                icon = Icons.AutoMirrored.Rounded.MergeType, action = stringResource(R.string.shl_choose), onAction = { choosing = true },
            )
        }
        // In discreet mode nothing may hint that private contacts exist.
        val discreet = vm.settings.collectAsStateWithLifecycle().value.hideVault
        if (s.privateLeftOut > 0 && !discreet) {
            Text(
                pluralStringResource(R.plurals.shl_private_left, s.privateLeftOut, s.privateLeftOut), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
        ParleyListItem(
            modifier = Modifier.clickable(enabled = !syncing) { sync() },
            leadingContent = { Icon(Icons.Rounded.Sync, null) },
            headlineContent = { Text(SharedLabelTexts.status(res, s)) },
            supportingContent = { Text(listOfNotNull(s.folderName, SharedLabelTexts.notice(res, s)).joinToString("\n")) },
            trailingContent = { TextButton({ sync() }, enabled = !syncing) { Text(stringResource(R.string.shl_sync_now)) } },
            colors = rowColors(),
        )
        ParleyListItem(
            modifier = Modifier.clickable { open(SharedLabelRoutes.Manage(s.labelId)) },
            leadingContent = { Icon(Icons.Rounded.Group, null) },
            headlineContent = { Text(stringResource(R.string.shl_manage)) },
            supportingContent = {
                val none = pluralStringResource(R.plurals.shl_members_n, 0, 0)
                Text(s.members.joinToString(stringResource(R.string.main_separator)) { it.name }.ifEmpty { none })
            },
            colors = rowColors(),
        )
        HistoryList(s, me, filter, onFilter = { filter = it }, limit = PAGE_CHANGES)
        if (s.history.size > PAGE_CHANGES) {
            TextButton({ open(SharedLabelRoutes.Manage(s.labelId)) }, Modifier.padding(horizontal = Spacing.s)) {
                Text(stringResource(R.string.shl_all_changes))
            }
        }
    }
    if (choosing) {
        ConflictDialog(vm, s, onDone = { choosing = false })
    }
    val context = LocalContext.current
    LaunchedEffect(s.labelId) { FolderSyncWorker.reschedule(context) }
}

/** Member chips, then the changes (newest first), at most [limit]. */
@Composable
internal fun HistoryList(s: SharedLabelState, me: String?, filter: String?, onFilter: (String?) -> Unit, limit: Int = Int.MAX_VALUE) {
    val res = LocalResources.current
    Text(
        stringResource(R.string.shl_changes), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
    )
    val authors = s.history.map { it.memberHex to it.memberName }.distinctBy { it.first }
    if (authors.size > 1) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            FilterChip(filter == null, { onFilter(null) }, label = { Text(stringResource(R.string.shl_everyone)) })
            authors.forEach { (hex, name) ->
                val label = if (hex == me) stringResource(R.string.shl_you) else name
                FilterChip(filter == hex, { onFilter(if (filter == hex) null else hex) }, label = { Text(label) })
            }
        }
    }
    val items = SharedLabelHistory.filter(s.history, filter).take(limit)
    if (items.isEmpty()) {
        Text(
            stringResource(R.string.shl_changes_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
    }
    items.forEach { item ->
        ParleyListItem(
            leadingContent = { Icon(Icons.Rounded.History, null) },
            headlineContent = {
                val text = res.getString(R.string.shl_h_when, SharedLabelTexts.line(res, item, me), SharedLabelTexts.ago(item.at))
                Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            colors = rowColors(),
        )
    }
}

/** One contact at a time: per field, "Ana's" or "Yours"; the rest is merged already. */
@Composable
private fun ConflictDialog(vm: AppViewModel, s: SharedLabelState, onDone: () -> Unit) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val sid = s.pending.keys.firstOrNull() ?: run { onDone(); return }
    val p = s.pending.getValue(sid)
    val entry = s.entries[sid]
    // Picks survive rotation: the fields that take theirs, by name.
    var theirs by rememberSaveable(sid) { mutableStateOf(emptyList<String>()) }
    var mineCard by remember(sid) { mutableStateOf<app.parley.common.record.ContactRecord?>(null) }
    LaunchedEffect(sid) {
        mineCard = entry?.let { e -> vm.c.records.read(e.contactId, fullPhoto = false)?.let(SharedCards::project) }
    }
    val theirsCard = remember(p.theirs) { SharedCards.decode(p.theirs) }
    val who = p.authorName.ifBlank { stringResource(R.string.shl_someone) }
    val empty = stringResource(R.string.shl_conflict_empty)
    ParleyDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.shl_conflict_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.shl_conflict_body, who, theirsCard?.displayName.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                p.fields.sorted().forEach { f ->
                    val takeTheirs = f.name in theirs
                    ChoiceRow(
                        SharedLabelTexts.field(res, f).replaceFirstChar { it.uppercase() },
                        listOf(stringResource(R.string.shl_conflict_theirs, who), stringResource(R.string.shl_conflict_mine)),
                        if (takeTheirs) 0 else 1,
                    ) { i -> theirs = if (i == 0) theirs + f.name else theirs - f.name }
                    ConflictValue(stringResource(R.string.shl_conflict_theirs, who), theirsCard?.let { SharedCards.text(it, f) }?.ifBlank { null } ?: empty)
                    ConflictValue(stringResource(R.string.shl_conflict_mine), mineCard?.let { SharedCards.text(it, f) }?.ifBlank { null } ?: empty)
                }
            }
        },
        confirmButton = {
            TextButton({
                val picks = p.fields.associateWith { if (it.name in theirs) Side.THEIRS else Side.MINE }
                scope.launch {
                    if (!vm.c.sharedLabels.resolve(s.labelId, sid, picks)) vm.toast(res.getString(R.string.shl_conflict_failed))
                    onDone()
                }
            }) { Text(stringResource(R.string.shl_conflict_apply)) }
        },
        dismissButton = { TextButton(onDone) { Text(kitStrings().cancel) } },
    )
}

@Composable
private fun ConflictValue(side: String, value: String) {
    Text(
        stringResource(R.string.shl_conflict_value, side, value), style = MaterialTheme.typography.bodySmall,
        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Spacing.l),
    )
}
