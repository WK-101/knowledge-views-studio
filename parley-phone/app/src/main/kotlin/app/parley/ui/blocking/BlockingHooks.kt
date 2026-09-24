package app.parley.ui.blocking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.RecentGroup
import app.parley.blocking.BlockingActions
import app.parley.container
import app.parley.common.PhoneNumbers
import app.parley.common.TraceCodec
import app.parley.data.GroupInfo
import app.parley.ui.common.Format
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
    @Composable
    fun row(label: String, icon: ImageVector, onClick: () -> Unit) =
        ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable { dismiss(); onClick() })
    row(if (blocked) "Why was this blocked?" else "Why did this ring?", Icons.AutoMirrored.Rounded.HelpOutline) { BlockingDialogs.show(BlockingDialog.Why(number)) }
    row("Test this call", Icons.Rounded.Science) { BlockingDialogs.show(BlockingDialog.Test(number)) }
    if (contactName == null) {
        row("Always allow", Icons.Rounded.VerifiedUser) {
            scope.launch { BlockingActions.allowNumber(vm.c, number); vm.toast("This number will always ring") }
        }
        row("Allow for 24 h", Icons.Rounded.HourglassTop) {
            scope.launch { BlockingActions.allowNumber(vm.c, number, hours = 24); vm.toast("This number rings until this time tomorrow") }
        }
        row("Report", Icons.Rounded.Flag) { BlockingDialogs.show(BlockingDialog.Report(number)) }
    }
    row("Search number on the web", Icons.Rounded.Search) { BlockingDialogs.show(BlockingDialog.WebSearch(number, contactName)) }
}

/** Second-line badge for a Recents row (B2 verdict, B10 "Don't call back"). Null when there's nothing to say. */
data class RecentBadge(val text: String, val warn: Boolean)

@Composable
fun rememberRecentBadges(vm: AppViewModel): (RecentGroup) -> RecentBadge? {
    val verdicts by vm.c.blocks.verdictIndex.collectAsStateWithLifecycle()
    val rings by vm.c.blocks.rings.collectAsStateWithLifecycle()
    return remember(verdicts, rings) {
        { g ->
            if (g.hidden || g.number.isBlank() || g.contact != null) {
                null
            } else {
                val e = g.latest
                if (vm.c.dialGuard.isWangiri(e.type, g.number, e.date, rings, vm.countryIso)) {
                    RecentBadge("Don't call back", warn = true)
                } else {
                    verdicts[PhoneNumbers.matchKey(g.number)]?.takeIf { kotlin.math.abs(it.time - e.date) < 10 * 60_000L || it.time > e.date }
                        ?.let { v -> RecentBadge(v.text, warn = v.blocked || v.kind == "LIKELY_SPAM" || v.kind == "REPORTED") }
                }
            }
        }
    }
}

/** Bar shown while Recents rows are selected (B8): block them all in one go. */
@Composable
fun RecentsSelectionBar(vm: AppViewModel, groups: List<RecentGroup>) {
    val selected by vm.recentSelection.collectAsStateWithLifecycle()
    if (selected.isEmpty()) return
    val scope = rememberCoroutineScope()
    val chosen = groups.filter { it.key in selected }
    val numbers = chosen.filter { !it.hidden && it.number.isNotBlank() }.map { it.number }.distinctBy { PhoneNumbers.matchKey(it) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.recentSelection.value = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
            Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({
                scope.launch {
                    var system = 0
                    numbers.forEach { n -> if (vm.c.blocks.blockNumber(n)) system++ else BlockingActions.blockNumberRule(vm.c, n) }
                    // Without the phone-app role the system list is unavailable: rules do the job instead.
                    vm.toast(if (numbers.size == 1) "Blocked 1 number" else "Blocked ${numbers.size} numbers")
                    vm.recentSelection.value = emptySet()
                }
            }, enabled = numbers.isNotEmpty()) {
                Icon(Icons.Rounded.Block, null)
                Text(" Block ${numbers.size}")
            }
        }
    }
}

/** Number-history section: every screening decision stored for this number, with its trace (§3.3). */
@Composable
fun ScreeningHistorySection(vm: AppViewModel, number: String, contactName: String?) {
    val screened by vm.c.blocks.screenedCalls.collectAsStateWithLifecycle(emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current
    val mine = remember(screened, number) { screened.filter { it.number != null && PhoneNumbers.same(it.number, number, vm.countryIso) }.take(10) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip({ BlockingDialogs.show(BlockingDialog.Test(number)) }, { Text("Test this call") }, leadingIcon = { Icon(Icons.Rounded.Science, null) })
            AssistChip({ BlockingDialogs.show(BlockingDialog.WebSearch(number, contactName)) }, { Text("Search web") }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
            if (contactName == null) AssistChip({ BlockingDialogs.show(BlockingDialog.Report(number)) }, { Text("Report") }, leadingIcon = { Icon(Icons.Rounded.Flag, null) })
        }
        if (mine.isNotEmpty()) {
            app.parley.ui.contact.Section("Screening")
            mine.forEach { e ->
                ListItem(
                    modifier = Modifier.clickable { BlockingDialogs.show(BlockingDialog.Why(number)) },
                    leadingContent = { Icon(if (e.allowed) Icons.Rounded.Shield else Icons.Rounded.Block, null) },
                    headlineContent = { Text((if (e.failedOpen) "! " else "") + (e.verdict ?: if (e.allowed) "Rang" else "Blocked")) },
                    supportingContent = { Text(Format.fullDate(context, e.time) + " · " + TraceCodec.oneLine(TraceCodec.decode(e.trace)), maxLines = 2) },
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
        { Text("Also allow this office's other lines") },
        leadingIcon = { Icon(Icons.Rounded.Business, null) },
        onClick = { closeMenu(); BlockingDialogs.show(BlockingDialog.PrefixAllow(name, numbers)) },
    )
}

/** Label page overflow item (B18/B24). Put it inside the label page's DropdownMenu. */
@Composable
fun LabelBlockingMenuItem(group: GroupInfo, closeMenu: () -> Unit) {
    DropdownMenuItem(
        { Text("Screening for this label…") },
        leadingIcon = { Icon(Icons.Rounded.Shield, null) },
        onClick = { closeMenu(); BlockingDialogs.show(BlockingDialog.LabelRule(group.id, group.title)) },
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
        { Text(if (active) "Stop letting unknown callers ring" else "Expecting a call…") },
        leadingIcon = { Icon(Icons.Rounded.HourglassTop, null) },
        onClick = {
            closeMenu()
            if (active) scope.launch { BlockingActions.snooze(c, 0) } else BlockingDialogs.show(BlockingDialog.Snooze)
        },
    )
}
