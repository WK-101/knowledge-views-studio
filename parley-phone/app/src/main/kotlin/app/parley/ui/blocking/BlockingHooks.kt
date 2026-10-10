package app.parley.ui.blocking

import app.parley.ui.Bidi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalResources
import app.parley.common.LabelRefs
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.activityViewModel
import app.parley.common.PhoneIdentity
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import app.parley.blocking.BlockingText
import app.parley.common.TraceCodec
import app.parley.ui.common.Format
import app.parley.ui.home.RecentsViewModel
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.bidiLtrIfNumber
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.parley.ui.ConfirmDialog
import androidx.compose.material.icons.rounded.Storefront
import app.parley.data.PhoneEnv
import app.parley.data.VerdictSummary
import app.parley.telecom.R as TR

/*
 * Small entry points other screens drop in with one line. Each opens a dialog through [BlockingDialogs], so
 * the calling screen needs no state of its own.
 */

/** Long-press rows for a Recents entry ("Why did this ring?"). Renders nothing for hidden callers. */
@Composable
fun RecentBlockingActions(vm: AppViewModel, number: String, contactName: String?, blocked: Boolean, dismiss: () -> Unit) {
    if (number.isBlank()) return
    val context = LocalContext.current
    val res = LocalResources.current

    @Composable
    fun row(label: String, icon: ImageVector, onClick: () -> Unit) =
        ParleyListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable { dismiss(); onClick() })
    row(
        stringResource(if (blocked) R.string.blk_why_blocked else R.string.blk_why_rang), Icons.AutoMirrored.Rounded.HelpOutline,
    ) { BlockingDialogs.show(BlockingDialog.Why(number)) }
    row(stringResource(R.string.blk_why_test), Icons.Rounded.Science) { BlockingDialogs.show(BlockingDialog.Test(number)) }
    // Only when your calls say it looks like a sales line.
    val salesLine = rememberReputation(vm, number, isContact = contactName != null) != null
    if (salesLine) {
        row(stringResource(R.string.blk_rep_menu), Icons.Rounded.Storefront) { BlockingDialogs.show(BlockingDialog.Reputation(number)) }
    }
    if (contactName == null) {
        row(stringResource(R.string.blk_always_allow), Icons.Rounded.VerifiedUser) {
            allowWithUndo(vm, number, hours = null, res.getString(R.string.blk_always_allow_toast))
        }
        row(stringResource(R.string.blk_allow_24h), Icons.Rounded.HourglassTop) {
            allowWithUndo(vm, number, hours = 24, res.getString(R.string.blk_allow_24h_toast))
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
    val groups by activityViewModel<RecentsViewModel>().groups.collectAsStateWithLifecycle()
    // The quiet "Looks like a sales line (your calls)" when there's no verdict to show.
    val repVersion by vm.c.reputation.version.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val learn = settings.screening.learnFromCalls
    val context = LocalContext.current
    val res = LocalResources.current
    val badges by produceState(emptyMap<String, RecentBadge>(), groups, verdicts, rings, repVersion, learn) {
        value = withContext(Dispatchers.Default) {
            val iso = vm.countryIso
            val out = HashMap<String, RecentBadge>()
            for (g in groups.orEmpty()) {
                if (g.hidden || g.number.isBlank() || g.contact != null) continue
                val e = g.latest
                val badge = if (vm.c.dialGuard.isWangiri(e.type, g.number, e.date, rings, iso)) {
                    RecentBadge(res.getString(R.string.blk_dont_call_back), warn = true)
                } else {
                    verdictBadge(context, verdicts[vm.c.blocks.verdictKey(g.number, e.accountId)], e.date) ?: salesBadge(vm, g, res, learn)
                }
                if (badge != null) out[g.key] = badge
            }
            out
        }
    }
    return remember(badges) { { g -> badges[g.key] } }
}

/** The screening verdict for a row's latest call (stored within 10 minutes of it, or later). */
private fun verdictBadge(context: android.content.Context, v: VerdictSummary?, date: Long): RecentBadge? =
    v?.takeIf { abs(it.time - date) < 10 * 60_000L || it.time > date }
        ?.let { RecentBadge(BlockingText.verdict(context, it.text) ?: it.text, warn = it.blocked || it.kind == "LIKELY_SPAM" || it.kind == "REPORTED") }

/** "Looks like a sales line (your calls)", quiet (never a warning), for an unknown number your calls tagged. */
private fun salesBadge(vm: AppViewModel, g: RecentGroup, res: android.content.res.Resources, learn: Boolean): RecentBadge? {
    if (!learn || g.vaultId != null) return null
    return runCatching { vm.c.reputation.lookup(g.number, PhoneEnv.countryIso(vm.c.appContext, g.latest.accountId)) }.getOrNull()
        ?.let { RecentBadge(res.getString(TR.string.rep_tag), warn = false) }
}

/**
 * Bar shown while Recents rows are selected: block the unknown numbers in one go, after a confirmation that
 * lists them. Contacts and private (vault) contacts are never blocked from here: they're left out and named, to
 * be blocked from their own page if that's really meant. With one call selected, ⋮ opens its actions ([onActions]):
 * a long-press selects, as in every list.
 */
@Composable
@Suppress("CyclomaticComplexMethod") // The bar, its confirmation and the one-call ⋮ read best together.
fun RecentsSelectionBar(vm: AppViewModel, groups: List<RecentGroup>, onActions: (RecentGroup) -> Unit) {
    val recents: RecentsViewModel = activityViewModel()
    val selected by recents.selection.collectAsStateWithLifecycle()
    if (selected.isEmpty()) return
    var confirming by remember { mutableStateOf(false) }
    val chosen = groups.filter { it.key in selected }
    val people = chosen.filter { it.contact != null || it.vaultId != null }
    val unknown = chosen.filter { it.contact == null && it.vaultId == null && !it.hidden && it.number.isNotBlank() }.distinctBy {
        PhoneIdentity.key(it.number, vm.countryIso)
    }
    val numbers = unknown.map { it.number }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ recents.clearSelection() }) { Icon(Icons.Rounded.Close, stringResource(R.string.blk_clear_selection)) }
            Text(
                pluralStringResource(R.plurals.blk_selected, selected.size, selected.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton({ confirming = true }, enabled = numbers.isNotEmpty()) {
                Icon(Icons.Rounded.Block, null)
                Text(" " + stringResource(R.string.blk_block_n, numbers.size))
            }
            chosen.singleOrNull()?.let { g ->
                IconButton({ onActions(g) }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more_actions)) }
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.blk_block_numbers_q, numbers.size, numbers.size),
            text = null,
            confirmLabel = stringResource(R.string.blk_block),
            onConfirm = {
                confirming = false
                // The same block as everywhere (Android's list, or a rule without the phone-app role), with Undo.
                blockWithUndo(vm, numbers)
                recents.clearSelection()
            },
            onDismiss = { confirming = false },
            dismissLabel = stringResource(R.string.set_cancel),
            content = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // A name the network sent says so: it isn't one you saved.
                    val fromNetwork = stringResource(R.string.main_separator) + stringResource(R.string.network_name_tag)
                    unknown.take(MAX_LISTED).forEach { g ->
                        val name = if (g.fromNetwork) Bidi.isolate(g.title) else bidiLtrIfNumber(g.title)
                        Text("• " + name + (if (g.title != g.number) " (${bidiLtr(g.number)})" else "") + (if (g.fromNetwork) fromNetwork else ""))
                    }
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
        )
    }
}

private const val MAX_LISTED = 12

/** Number-history section: every screening decision stored for this number, with its trace (§3.3). */
@Composable
fun ScreeningHistorySection(vm: AppViewModel, number: String, contactName: String?) {
    val screened by vm.c.blocks.screenedCalls.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val mine = remember(screened, number) { screened.filter { it.number != null && PhoneIdentity.same(it.number, number, vm.countryIso) }.take(10) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                { BlockingDialogs.show(BlockingDialog.Test(number)) },
                { Text(stringResource(R.string.blk_why_test)) },
                leadingIcon = { Icon(Icons.Rounded.Science, null) },
            )
            AssistChip(
                { BlockingDialogs.show(BlockingDialog.WebSearch(number, contactName)) },
                { Text(stringResource(R.string.blk_search_web)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
            )
            if (contactName == null) AssistChip(
                { BlockingDialogs.show(BlockingDialog.Report(number)) },
                { Text(stringResource(R.string.blk_report)) },
                leadingIcon = { Icon(Icons.Rounded.Flag, null) },
            )
        }
        if (mine.isNotEmpty()) {
            Section(stringResource(R.string.blk_screening))
            mine.forEach { e ->
                ParleyListItem(
                    modifier = Modifier.clickable { BlockingDialogs.show(BlockingDialog.Why(number)) },
                    leadingContent = { Icon(if (e.allowed) Icons.Rounded.Shield else Icons.Rounded.Block, null) },
                    headlineContent = { Text((if (e.failedOpen) "! " else "") + (BlockingText.verdict(context, e.verdict) ?: stringResource(if (e.allowed) R.string.blk_rang else R.string.blk_blocked))) },
                    supportingContent = {
                        Text(Format.fullDate(context, e.time) + " · " + BlockingText.oneLine(context, TraceCodec.decode(e.trace)), maxLines = 2)
                    },
                )
            }
        }
    }
}

/** Label page overflow item. Put it inside the label page's DropdownMenu. */
@Composable
fun LabelBlockingMenuItem(title: String, closeMenu: () -> Unit) {
    DropdownMenuItem(
        { Text(stringResource(R.string.blk_label_menu)) },
        leadingIcon = { Icon(Icons.Rounded.Shield, null) },
        onClick = { closeMenu(); BlockingDialogs.show(BlockingDialog.LabelRule(LabelRefs.key(title))) },
    )
}
