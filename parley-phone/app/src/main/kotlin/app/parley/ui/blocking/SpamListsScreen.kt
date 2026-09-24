package app.parley.ui.blocking

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.BlockingText
import app.parley.common.ListMode
import app.parley.common.spam.BuiltInPacks
import app.parley.common.spam.PackOrigin
import app.parley.common.spam.PackState
import app.parley.common.spam.ParsedPack
import app.parley.common.spam.SignatureStatus
import app.parley.data.DryRun
import app.parley.data.SpamListStore
import app.parley.ui.common.Format
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.launch

/**
 * Spam lists (B4, B5, B7, B13): add a `.parleylist` file or subscribe to a folder (Syncthing, Nextcloud,
 * Downloads); each list warns by default, blocking is opt-in with a score threshold. Before adding a file
 * you see what it would have caught last week.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamListsScreen(vm: AppViewModel, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by vm.c.lists.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<ParsedPack?>(null) }
    var pendingDry by remember { mutableStateOf<DryRun?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val parsed = vm.c.lists.parse(uri)
                pending = parsed
                pendingDry = runCatching { vm.c.screener.dryRun(vm.c.callLog.calls.value.orEmpty(), 7, candidatePack = parsed) }.getOrNull()
            } catch (e: Exception) {
                error = e.message?.let { BlockingText.installFailure(context, it) } ?: context.getString(R.string.blk_not_valid_list)
            }
            busy = false
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            vm.c.lists.subscribe(uri)
            busy = false
            vm.toast(context.getString(R.string.blk_folder_subscribed))
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(settingTitle("spam_lists")) },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) } },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                val sum = vm.c.lists.summarize(state, now)
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (sum.lists == 0) stringResource(R.string.blk_no_lists) else
                                listOfNotNull(
                                    pluralStringResource(R.plurals.blk_lists_count, sum.lists, sum.lists),
                                    pluralStringResource(R.plurals.blk_numbers_count, sum.numbers.toPluralCount(), "%,d".format(sum.numbers)),
                                    sum.updatedAt?.let { stringResource(R.string.blk_updated_ago, ago(it, now)) },
                                ).joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.blk_lists_intro),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ pickFile.launch(arrayOf("*/*")) }, enabled = !busy) { Icon(Icons.Rounded.Add, null); Text(" " + stringResource(R.string.blk_add_list_file)) }
                            OutlinedButton({ pickFolder.launch(null) }, enabled = !busy) { Icon(Icons.Rounded.Folder, null); Text(" " + stringResource(R.string.blk_folder)) }
                        }
                    }
                }
            }
            item {
                val folder = state.folderUri
                if (folder != null) {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.Folder, null) },
                        headlineContent = { Text(stringResource(R.string.blk_subscribed_folder)) },
                        supportingContent = {
                            Text(
                                (Uri.parse(folder).lastPathSegment ?: folder) + " · " +
                                    stringResource(R.string.blk_checked_ago, if (state.folderCheckedAt > 0) ago(state.folderCheckedAt, now) else stringResource(R.string.blk_never)) +
                                    (state.folderError?.let { "\n" + BlockingText.installFailure(context, it) } ?: "") + "\n" + stringResource(R.string.blk_folder_help),
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton({
                                    scope.launch {
                                        busy = true
                                        val n = vm.c.lists.refreshFolder()
                                        busy = false
                                        vm.toast(if (n == 0) context.getString(R.string.blk_no_new_lists) else context.getString(R.string.blk_updated_n, n))
                                    }
                                }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.blk_check_now)) }
                                TextButton({ scope.launch { vm.c.lists.unsubscribe() } }) { Text(stringResource(R.string.ct_remove)) }
                            }
                        },
                    )
                }
            }
            item(key = "updater") { ListsUpdaterSection(vm) }
            val suggested = BuiltInPacks.all.filter { b -> state.packs.none { it.id == b.id } }
            if (suggested.isNotEmpty()) {
                item { app.parley.ui.contact.Section(stringResource(R.string.blk_built_in_section)) }
                items(suggested, key = { "b" + it.id }) { b ->
                    ListItem(
                        headlineContent = {
                            val n = BlockingText.packName(context, b.id, b.name)
                            Text(if (b.country.equals(vm.countryIso, true)) stringResource(R.string.blk_for_your_sim, n) else n)
                        },
                        supportingContent = { Text(BlockingText.packDescription(context, b.id, b.description)) },
                        trailingContent = { TextButton({ scope.launch { vm.c.lists.installBuiltIn(b); vm.toast(context.getString(R.string.blk_added_toast)) } }) { Text(stringResource(R.string.blk_add)) } },
                    )
                }
            }
            if (state.packs.isNotEmpty()) item { app.parley.ui.contact.Section(stringResource(R.string.blk_your_lists)) }
            items(state.packs, key = { "p" + it.id }) { pk -> PackCard(vm, pk, now) }
        }
    }

    pending?.let { pk ->
        val m = pk.manifest
        val existing = state.packs.firstOrNull { it.id == m.id }
        AlertDialog(
            onDismissRequest = { pending = null; pendingDry = null },
            title = { Text(stringResource(if (existing != null) R.string.blk_update_list_q else R.string.blk_add_list_q, m.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val count = pk.numbers.size / 10
                    Text(
                        listOf(
                            pluralStringResource(R.plurals.blk_numbers_count, count, "%,d".format(count)),
                            pluralStringResource(R.plurals.blk_ranges_count, pk.ranges.size, pk.ranges.size),
                            stringResource(R.string.blk_version, m.version.toString()),
                        ).joinToString(" · "),
                    )
                    if (m.publisher.isNotBlank()) Text(stringResource(R.string.blk_from, m.publisher) + (m.licence.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
                    if (pk.signature == SignatureStatus.SIGNED) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Verified, null); Text("  " + stringResource(R.string.blk_signed_key, pk.fingerprint.orEmpty())) }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Text("  " + stringResource(R.string.blk_unsigned_warning), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    pendingDry?.let { d ->
                        val added = d.added
                        Text(
                            pluralStringResource(R.plurals.blk_list_dry_result, d.current.unknown.size, added.size, d.current.unknown.size) +
                                if (added.isNotEmpty()) " (" + added.take(3).joinToString { bidiLtr(Format.number(it.call.number, vm.countryIso)) } + if (added.size > 3) "…)" else ")" else "",
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(stringResource(R.string.blk_new_lists_warn), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        when (val r = vm.c.lists.install(pk, PackOrigin.FILE)) {
                            is SpamListStore.InstallResult.Installed -> vm.toast(context.getString(if (r.replaced) R.string.blk_updated_toast else R.string.blk_added_toast))
                            is SpamListStore.InstallResult.Older -> vm.toast(context.getString(R.string.blk_list_newer_version, r.installed.toString()))
                            is SpamListStore.InstallResult.Failed -> error = BlockingText.installFailure(context, r.reason)
                        }
                    }
                    pending = null
                    pendingDry = null
                }) { Text(stringResource(if (existing != null) R.string.blk_update else R.string.blk_add)) }
            },
            dismissButton = { TextButton({ pending = null; pendingDry = null }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
    error?.let { e ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.blk_cant_add_list)) },
            text = { Text(e) },
            confirmButton = { TextButton({ error = null }) { Text(stringResource(R.string.set_ok)) } },
        )
    }
}

@Composable
private fun PackCard(vm: AppViewModel, pk: PackState, now: Long) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val stale = pk.isStale(now)
    val name = BlockingText.packName(context, pk.id, pk.name)
    Column {
        ListItem(
            modifier = Modifier.padding(0.dp),
            headlineContent = { Text(name) },
            supportingContent = {
                Column {
                    Text(
                        listOfNotNull(
                            pluralStringResource(R.plurals.blk_numbers_count, pk.entries, "%,d".format(pk.entries)),
                            if (pk.ranges > 0) pluralStringResource(R.plurals.blk_ranges_count, pk.ranges, pk.ranges) else null,
                            if (pk.mode == ListMode.BLOCK) stringResource(R.string.blk_blocks_at_score, pk.threshold) else stringResource(R.string.blk_warns),
                            stringResource(if (pk.signed) R.string.blk_signed else if (pk.origin == PackOrigin.BUILTIN) R.string.blk_built_in else R.string.blk_unsigned),
                            stringResource(R.string.blk_from_parley_lists).takeIf { pk.origin == PackOrigin.UPDATER },
                            stringResource(R.string.blk_updated_ago, ago(pk.installedAt, now)),
                        ).joinToString(" · "),
                    )
                    if (stale) Text(pluralStringResource(R.plurals.blk_out_of_date_days, pk.ttlDays, pk.ttlDays), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            trailingContent = { Switch(pk.enabled, { v -> scope.launch { vm.c.lists.setPack(pk.id) { it.copy(enabled = v) } } }) },
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ open = !open }) { Text(stringResource(if (open) R.string.blk_less else R.string.blk_options)) }
        }
        if (open) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pk.publisher.isNotBlank() || pk.licence.isNotBlank()) Text(listOf(pk.publisher, pk.licence).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                pk.fingerprint?.let { Text(stringResource(R.string.blk_publisher_key, it), style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(pk.mode == ListMode.WARN, { scope.launch { vm.c.lists.setPack(pk.id) { it.copy(mode = ListMode.WARN) } } }, label = { Text(stringResource(R.string.blk_warn_only)) })
                    FilterChip(pk.mode == ListMode.BLOCK, { scope.launch { vm.c.lists.setPack(pk.id) { it.copy(mode = ListMode.BLOCK) } } }, label = { Text(stringResource(R.string.blk_block)) })
                }
                if (pk.mode == ListMode.BLOCK) {
                    var t by remember(pk.threshold) { mutableFloatStateOf(pk.threshold.toFloat()) }
                    Text(stringResource(R.string.blk_score_threshold, t.toInt()))
                    Slider(t, { t = it }, valueRange = 0f..100f, steps = 19, onValueChangeFinished = { scope.launch { vm.c.lists.setPack(pk.id) { it.copy(threshold = t.toInt()) } } })
                    ActionChoice(pk.action, { a -> scope.launch { vm.c.lists.setPack(pk.id) { it.copy(action = a) } } })
                }
                if (pk.ranges > 0) ToggleRow(stringResource(R.string.blk_match_ranges), stringResource(R.string.blk_match_ranges_help), pk.useRanges) { v -> scope.launch { vm.c.lists.setPack(pk.id) { it.copy(useRanges = v) } } }
                Text(stringResource(R.string.blk_notify), style = MaterialTheme.typography.labelLarge)
                NotifyChoice(pk.notify, allowDefault = true) { n -> scope.launch { vm.c.lists.setPack(pk.id) { it.copy(notify = n) } } }
                if (pk.suppressed.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.blk_marked_not_spam_count, pk.suppressed.size), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton({ scope.launch { vm.c.lists.setPack(pk.id) { it.copy(suppressed = emptyList()) } } }) { Text(stringResource(R.string.set_clear)) }
                    }
                }
                if (pk.source.isNotBlank()) Text(stringResource(R.string.blk_source, pk.source), style = MaterialTheme.typography.bodySmall)
                TextButton({ confirmRemove = true }) { Text(stringResource(R.string.blk_remove_list), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.blk_remove_list_q, name)) },
            confirmButton = {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                TextButton({
                    scope.launch {
                        // A list from Parley Lists would come back tomorrow: stop its updates too.
                        if (pk.origin == PackOrigin.UPDATER) app.parley.blocking.ListsUpdaterClient.unsubscribe(ctx, vm.c.lists, pk.id) else vm.c.lists.remove(pk.id)
                    }
                    confirmRemove = false
                }) { Text(stringResource(R.string.ct_remove)) }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}
