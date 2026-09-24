package app.parley.ui.blocking

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
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.RecentGroup
import app.parley.blocking.BlockingActions
import app.parley.blocking.BlockingText
import app.parley.container
import app.parley.common.PhoneNumbers
import app.parley.common.TraceCodec
import app.parley.ui.common.Format
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.bidiLtrIfNumber
import kotlinx.coroutines.launch

/*
 * Small entry points other screens drop in with one line. Each opens a dialog through [BlockingDialogs], so
 * the calling screen needs no state of its own.
 */

/** Long-press rows for a Recents entry (B1, B8, B13, B25, "Why did this ring?"). Renders nothing for hidden callers. */
@Composable
fun RecentBlockingActions(vm: AppViewModel, number: String, contactName: String?, blocked: Boolean, dismiss: () -> Unit) {
    if (number.isBlank()) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    @Composable
    fun row(label: String, icon: ImageVector, onClick: () -> Unit) =
        ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable { dismiss(); onClick() })
    row(stringResource(if (blocked) R.string.blk_why_blocked else R.string.blk_why_rang), Icons.AutoMirrored.Rounded.HelpOutline) { BlockingDialogs.show(BlockingDialog.Why(number)) }
    row(stringResource(R.string.blk_why_test), Icons.Rounded.Science) { BlockingDialogs.show(BlockingDialog.Test(number)) }
    if (contactName == null) {
        row(stringResource(R.string.blk_always_allow), Icons.Rounded.VerifiedUser) {
            scope.launch { BlockingActions.allowNumber(vm.c, number); vm.toast(context.getString(R.string.blk_always_allow_toast)) }
        }
        row(stringResource(R.string.blk_allow_24h), Icons.Rounded.HourglassTop) {
            scope.launch { BlockingActions.allowNumber(vm.c, number, hours = 24); vm.toast(context.getString(R.string.blk_allow_24h_toast)) }
        }
        row(stringResource(R.string.blk_report), Icons.Rounded.Flag) { BlockingDialogs.show(BlockingDialog.Report(number)) }
    }
    row(stringResource(R.string.blk_search_web_long), Icons.Rounded.Search) { BlockingDialogs.show(BlockingDialog.WebSearch(number, contactName)) }
}

/** Second-line badge for a Recents row (B2 verdict, B10 "Don't call back"). Null when there's nothing to say. */
data class RecentBadge(val text: String, val warn: Boolean)

/**
 * Badges for every Recents row, worked out off the main thread (number parsing per row is too slow for
 * composition) whenever the rows, verdicts or ring records change. Rows show no badge until it's ready.
 */
@Composable
fun rememberRecentBadges(vm: AppViewModel): (RecentGroup) -> RecentBadge? {
    val verdicts by vm.c.blocks.verdictIndex.collectAsStateWithLifecycle()
    val rings by vm.c.blocks.rings.collectAsStateWithLifecycle()
    val groups by vm.recentGroups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val badges by androidx.compose.runtime.produceState(emptyMap<String, RecentBadge>(), groups, verdicts, rings) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val iso = vm.countryIso
            val out = HashMap<String, RecentBadge>()
            for (g in groups.orEmpty()) {
                if (g.hidden || g.number.isBlank() || g.contact != null) continue
                val e = g.latest
                val badge = if (vm.c.dialGuard.isWangiri(e.type, g.number, e.date, rings, iso)) {
                    RecentBadge(context.getString(R.string.blk_dont_call_back), warn = true)
                } else {
                    verdicts[vm.c.blocks.verdictKey(g.number, e.accountId)]?.takeIf { kotlin.math.abs(it.time - e.date) < 10 * 60_000L || it.time > e.date }
                        ?.let { v -> RecentBadge(BlockingText.verdict(context, v.text) ?: v.text, warn = v.blocked || v.kind == "LIKELY_SPAM" || v.kind == "REPORTED") }
                }
                if (badge != null) out[g.key] = badge
            }
            out
        }
    }
    return remember(badges) { { g -> badges[g.key] } }
}

/**
 * Bar shown while Recents rows are selected (B8): block the unknown numbers in one go, after a confirmation that
 * lists them. Contacts and private (vault) contacts are never blocked from here: they're left out and named, to
 * be blocked from their own page if that's really meant.
 */
@Composable
fun RecentsSelectionBar(vm: AppViewModel, groups: List<RecentGroup>) {
    val selected by vm.recentSelection.collectAsStateWithLifecycle()
    if (selected.isEmpty()) return
    val scope = rememberCoroutineScope()
    var confirming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val chosen = groups.filter { it.key in selected }
    val people = chosen.filter { it.contact != null || it.vaultId != null }
    val unknown = chosen.filter { it.contact == null && it.vaultId == null && !it.hidden && it.number.isNotBlank() }.distinctBy { PhoneNumbers.matchKey(it.number) }
    val numbers = unknown.map { it.number }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.recentSelection.value = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.blk_clear_selection)) }
            Text(stringResource(R.string.blk_selected, selected.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({ confirming = true }, enabled = numbers.isNotEmpty()) {
                Icon(Icons.Rounded.Block, null)
                Text(" " + stringResource(R.string.blk_block_n, numbers.size))
            }
        }
    }
    if (confirming) {
        val context = LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(pluralStringResource(R.plurals.blk_block_numbers_q, numbers.size, numbers.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    unknown.take(MAX_LISTED).forEach { g -> Text("• " + bidiLtrIfNumber(g.title) + if (g.title != g.number) " (${bidiLtr(g.number)})" else "") }
                    if (unknown.size > MAX_LISTED) (unknown.size - MAX_LISTED).let { Text(pluralStringResource(R.plurals.set_and_more, it, it)) }
                    if (people.isNotEmpty()) {
                        Text(
                            stringResource(R.string.blk_not_blocked_people, people.joinToString(", ") { it.title }),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton({
                    confirming = false
                    scope.launch {
                        // Without the phone-app role the system list is unavailable: rules do the job instead.
                        numbers.forEach { n -> if (!vm.c.blocks.blockNumber(n)) BlockingActions.blockNumberRule(vm.c, n) }
                        vm.toast(context.resources.getQuantityString(R.plurals.blk_blocked_numbers, numbers.size, numbers.size))
                        vm.recentSelection.value = emptySet()
                    }
                }) { Text(stringResource(R.string.blk_block)) }
            },
            dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}

private const val MAX_LISTED = 12

/** Number-history section: every screening decision stored for this number, with its trace (§3.3). */
@Composable
fun ScreeningHistorySection(vm: AppViewModel, number: String, contactName: String?) {
    val screened by vm.c.blocks.screenedCalls.collectAsStateWithLifecycle(emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current
    val mine = remember(screened, number) { screened.filter { it.number != null && PhoneNumbers.same(it.number, number, vm.countryIso) }.take(10) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip({ BlockingDialogs.show(BlockingDialog.Test(number)) }, { Text(stringResource(R.string.blk_why_test)) }, leadingIcon = { Icon(Icons.Rounded.Science, null) })
            AssistChip({ BlockingDialogs.show(BlockingDialog.WebSearch(number, contactName)) }, { Text(stringResource(R.string.blk_search_web)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
            if (contactName == null) AssistChip({ BlockingDialogs.show(BlockingDialog.Report(number)) }, { Text(stringResource(R.string.blk_report)) }, leadingIcon = { Icon(Icons.Rounded.Flag, null) })
        }
        if (mine.isNotEmpty()) {
            app.parley.ui.contact.Section(stringResource(R.string.blk_screening))
            mine.forEach { e ->
                ListItem(
                    modifier = Modifier.clickable { BlockingDialogs.show(BlockingDialog.Why(number)) },
                    leadingContent = { Icon(if (e.allowed) Icons.Rounded.Shield else Icons.Rounded.Block, null) },
                    headlineContent = { Text((if (e.failedOpen) "! " else "") + (BlockingText.verdict(context, e.verdict) ?: stringResource(if (e.allowed) R.string.blk_rang else R.string.blk_blocked))) },
                    supportingContent = { Text(Format.fullDate(context, e.time) + " · " + BlockingText.oneLine(context, TraceCodec.decode(e.trace)), maxLines = 2) },
                )
            }
        }
    }
}

/** Contact overflow item (B22). Put it inside the contact page's DropdownMenu. */
@Composable
fun ContactPrefixAllowMenuItem(name: String?, numbers: List<String>, closeMenu: () -> Unit) {
    if (numbers.isEmpty()) return
    DropdownMenuItem(
        { Text(stringResource(R.string.blk_prefix_title)) },
        leadingIcon = { Icon(Icons.Rounded.Business, null) },
        onClick = { closeMenu(); BlockingDialogs.show(BlockingDialog.PrefixAllow(name, numbers)) },
    )
}

/** Label page overflow item (B18/B24). Put it inside the label page's DropdownMenu. */
@Composable
fun LabelBlockingMenuItem(title: String, closeMenu: () -> Unit) {
    DropdownMenuItem(
        { Text(stringResource(R.string.blk_label_menu)) },
        leadingIcon = { Icon(Icons.Rounded.Shield, null) },
        onClick = { closeMenu(); BlockingDialogs.show(BlockingDialog.LabelRule(app.parley.common.LabelRefs.key(title))) },
    )
}

/** Keypad or home overflow item (B21). */
@Composable
fun ExpectingCallMenuItem(closeMenu: () -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current.container
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val active = s.screening.snoozeActive(System.currentTimeMillis())
    val scope = rememberCoroutineScope()
    DropdownMenuItem(
        { Text(stringResource(if (active) R.string.blk_snooze_stop else R.string.blk_snooze_menu)) },
        leadingIcon = { Icon(Icons.Rounded.HourglassTop, null) },
        onClick = {
            closeMenu()
            if (active) scope.launch { BlockingActions.snooze(c, 0) } else BlockingDialogs.show(BlockingDialog.Snooze)
        },
    )
}
