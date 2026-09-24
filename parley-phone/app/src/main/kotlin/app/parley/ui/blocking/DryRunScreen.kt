package app.parley.ui.blocking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.BlockingText
import app.parley.data.DryRun
import app.parley.ui.common.Format
import androidx.compose.foundation.layout.Arrangement
import app.parley.ui.settings.bidiLtr

/**
 * Coverage replay (B13): "current rules would have blocked 14 of 22 unknown calls", with each past call's
 * would-be decision. Reads only; nothing is blocked, logged, counted or notified.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DryRunScreen(vm: AppViewModel, back: () -> Unit) {
    val calls by vm.c.callLog.calls.collectAsStateWithLifecycle()
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var days by remember { mutableIntStateOf(7) }
    var result by remember { mutableStateOf<DryRun?>(null) }
    var loading by remember { mutableStateOf(true) }
    val context = LocalContext.current
    LaunchedEffect(days, calls, rules, settings.screening) {
        loading = true
        result = runCatching { vm.c.screener.dryRun(calls.orEmpty(), days) }.getOrNull()
        loading = false
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.blk_dry_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 7, 30).forEach { d -> FilterChip(days == d, { days = d }, label = { Text(if (d == 1) stringResource(R.string.blk_dry_today) else pluralStringResource(R.plurals.set_days, d, d)) }) }
                }
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            when {
                                loading -> stringResource(R.string.blk_dry_loading)
                                result == null -> stringResource(R.string.blk_dry_failed)
                                result!!.current.unknown.isEmpty() -> stringResource(R.string.blk_dry_none)
                                else -> result!!.current.let { c -> pluralStringResource(R.plurals.blk_dry_summary, c.unknown.size, c.blocked, c.unknown.size) }
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.blk_dry_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val rows = result?.current?.unknown.orEmpty().sortedByDescending { it.call.time }
            items(rows, key = { "${it.call.time}|${it.call.number}" }) { r ->
                var open by remember { mutableStateOf(false) }
                Column {
                    ListItem(
                        modifier = Modifier.clickable { open = !open },
                        leadingContent = { Icon(if (r.result.blocked) Icons.Rounded.Block else Icons.Rounded.Call, stringResource(if (r.result.blocked) R.string.blk_would_block else R.string.blk_would_ring)) },
                        headlineContent = { Text(if (r.call.hidden) stringResource(R.string.blk_private_number) else bidiLtr(Format.number(r.call.number, vm.countryIso))) },
                        supportingContent = {
                            Text(
                                Format.fullDate(context, r.call.time) + " · " +
                                    (BlockingText.verdict(context, r.result.verdict?.text) ?: stringResource(if (r.result.blocked) R.string.blk_would_block else R.string.blk_would_ring)),
                            )
                        },
                    )
                    if (open) TraceList(r.result.trace, Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp))
                }
            }
        }
    }
}

/** Blocking sub-screens. Registered in the app's NavHost. */
object BlockingRoutes {
    const val LISTS = "blocking/lists"
    const val TRANSFER = "blocking/transfer"
    const val DRY_RUN = "blocking/dryrun"
    const val TEMPLATES = "blocking/templates"
    const val RULE = "blocking/rule/{id}?kind={kind}&type={type}&pattern={pattern}"

    fun rule(id: Long, kind: app.parley.common.RuleKind = app.parley.common.RuleKind.BLOCK, type: app.parley.common.RuleType = app.parley.common.RuleType.PREFIX, pattern: String = "") =
        "blocking/rule/$id?kind=${kind.name}&type=${type.name}&pattern=${android.net.Uri.encode(pattern)}"

    /** Adds every blocking destination to a NavGraph. */
    fun register(builder: androidx.navigation.NavGraphBuilder, vm: AppViewModel, back: () -> Unit) {
        with(builder) {
            composable(LISTS) { SpamListsScreen(vm, back) }
            composable(TRANSFER) { TransferScreen(vm, back) }
            composable(DRY_RUN) { DryRunScreen(vm, back) }
            composable(TEMPLATES) { TemplatesScreen(vm, back) }
            composable(
                RULE,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("kind") { defaultValue = "BLOCK" },
                    navArgument("type") { defaultValue = "PREFIX" },
                    navArgument("pattern") { defaultValue = "" },
                ),
            ) {
                val a = it.arguments!!
                val kind = runCatching { app.parley.common.RuleKind.valueOf(a.getString("kind").orEmpty()) }.getOrDefault(app.parley.common.RuleKind.BLOCK)
                val type = runCatching { app.parley.common.RuleType.valueOf(a.getString("type").orEmpty()) }.getOrDefault(app.parley.common.RuleType.PREFIX)
                RuleEditorScreen(vm, a.getLong("id"), newRule(kind, type, android.net.Uri.decode(a.getString("pattern").orEmpty())), back)
            }
        }
    }
}
