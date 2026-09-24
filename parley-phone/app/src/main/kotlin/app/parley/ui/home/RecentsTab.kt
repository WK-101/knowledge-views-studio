package app.parley.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.RecentFilter
import app.parley.RecentGroup
import app.parley.common.CallType
import app.parley.ui.Avatar
import app.parley.ui.CallColors
import app.parley.ui.EmptyState
import app.parley.ui.MonoAvatar
import app.parley.ui.Routes
import app.parley.ui.avatarSize
import app.parley.ui.common.Format

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecentsTab(vm: AppViewModel, open: (String) -> Unit) {
    val groups by vm.recentGroups.collectAsStateWithLifecycle()
    val filter by vm.recentFilter.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val simLabels = remember(sims) { if (sims.size > 1) sims.associate { it.id to it.label } else emptyMap() }
    var menuFor by remember { mutableStateOf<RecentGroup?>(null) }
    var messageFor by remember { mutableStateOf<String?>(null) }
    menuFor?.let { g -> RecentActionsSheet(vm, g, open, onMessageOn = { messageFor = it }) { menuFor = null } }
    messageFor?.let { n -> app.parley.messaging.MessageOnSheet(n, onDismiss = { messageFor = null }) }
    var daySummary by remember { mutableStateOf<Pair<Long, String>?>(null) }
    daySummary?.let { (day, title) -> app.parley.ui.history.DaySummarySheet(vm, day, title) { daySummary = null } }
    app.parley.ui.history.RecentsExportHost(vm)
    // Blocking: verdict / "Don't call back" badges and multi-select block (B2, B8, B10).
    val badgeFor = app.parley.ui.blocking.rememberRecentBadges(vm)
    val selected by vm.recentSelection.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { vm.recentSelection.value = emptySet() }
    fun toggleSelected(g: RecentGroup) {
        vm.recentSelection.value = selected.let { if (g.key in it) it - g.key else it + g.key }
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        if (selected.isNotEmpty()) stickyHeader(key = "selection") { app.parley.ui.blocking.RecentsSelectionBar(vm, groups.orEmpty()) }
        item(key = "filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecentFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.recentFilter.value = f },
                        label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
                app.parley.ui.history.SavedFilterChips(vm)
            }
        }
        item(key = "archive-notes") { app.parley.ui.history.ArchiveNotices(vm, open) }
        val list = groups
        if (list != null && list.isEmpty()) {
            item(key = "empty") {
                EmptyState(Icons.Rounded.AccessTime, if (filter == RecentFilter.ALL) "No calls yet" else "Nothing here", modifier = Modifier.padding(top = 48.dp))
            }
        }
        var lastHeader: String? = null
        list.orEmpty().forEach { g ->
            val header = Format.dayHeader(context, g.latest.date)
            if (header != lastHeader) {
                lastHeader = header
                item(key = "h" + g.key) {
                    Text(
                        header, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Day summary") { daySummary = g.latest.date to header }
                            .padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            item(key = g.key) {
                RecentRow(
                    g, vm.countryIso, simLabels.takeIf { settings.showSimLabels }.orEmpty(),
                    onLongClick = { if (selected.isNotEmpty()) toggleSelected(g) else menuFor = g },
                    badge = badgeFor(g),
                    selected = g.key in selected,
                    onOpen = {
                        val ct = g.contact
                        when {
                            selected.isNotEmpty() -> toggleSelected(g)
                            g.vaultId != null -> open(Routes.vault(g.vaultId))
                            ct != null -> open(Routes.contact(ct.id))
                            !g.hidden -> open(Routes.history(g.number))
                        }
                    },
                    onCall = { vm.requestCall(g.number, g.contact?.displayName) },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecentRow(
    g: RecentGroup, countryIso: String, simLabels: Map<String, String>, onLongClick: (() -> Unit)? = null,
    badge: app.parley.ui.blocking.RecentBadge? = null, selected: Boolean = false, onOpen: () -> Unit, onCall: () -> Unit,
) {
    val context = LocalContext.current
    val e = g.latest
    val (icon, tint) = callTypeIcon(e.type)
    val missed = e.type == CallType.MISSED || e.type == CallType.REJECTED
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongClick, onLongClickLabel = "More actions")
            .semantics { this.selected = selected },
        colors = if (selected) androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else androidx.compose.material3.ListItemDefaults.colors(),
        leadingContent = {
            if (g.hidden) MonoAvatar(avatarSize()) else Avatar(g.title, g.contact?.photoUri, avatarSize())
        },
        headlineContent = {
            Text(
                (if (g.vaultId != null) "🔒 " else "") + g.title + if (g.calls.size > 1) " (${g.calls.size})" else "",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
          androidx.compose.foundation.layout.Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                val location = if (g.contact == null && g.vaultId == null && !g.hidden) remember(g.number) { app.parley.data.NumberInfo.location(g.number, countryIso) } else null
                val parts = listOfNotNull(
                    location,
                    if (g.contact != null) g.contact.phones.firstOrNull { p -> app.parley.common.PhoneNumbers.matchKey(p.number) == app.parley.common.PhoneNumbers.matchKey(e.number) }
                        ?.let { p -> Format.phoneType(context.resources, p.type, p.label) } else if (!g.hidden && g.contact == null && g.cachedName != null) Format.number(e.number, countryIso) else null,
                    e.accountId?.let { simLabels[it] },
                    Format.shortWhen(context, e.date),
                )
                Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null) {
                Text(
                    badge.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                    color = if (badge.warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
          }
        },
        trailingContent = {
            if (!g.hidden && g.number.isNotBlank()) {
                IconButton(onClick = onCall) { Icon(Icons.Rounded.Call, "Call ${g.title}", tint = MaterialTheme.colorScheme.primary) }
            }
        },
    )
}

@Composable
fun callTypeIcon(type: CallType): Pair<ImageVector, Color> = when (type) {
    CallType.INCOMING, CallType.ANSWERED_EXTERNALLY -> Icons.AutoMirrored.Rounded.CallReceived to CallColors.Accept
    CallType.OUTGOING -> Icons.AutoMirrored.Rounded.CallMade to MaterialTheme.colorScheme.primary
    CallType.MISSED -> Icons.AutoMirrored.Rounded.CallMissed to CallColors.Decline
    CallType.REJECTED -> Icons.Rounded.CallEnd to CallColors.Decline
    CallType.BLOCKED -> Icons.Rounded.Block to MaterialTheme.colorScheme.onSurfaceVariant
    CallType.VOICEMAIL -> Icons.Rounded.Voicemail to MaterialTheme.colorScheme.tertiary
    CallType.UNKNOWN -> Icons.Rounded.Call to MaterialTheme.colorScheme.onSurfaceVariant
}


/** Long-press actions for a Recents row. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RecentActionsSheet(vm: AppViewModel, g: RecentGroup, open: (String) -> Unit, onMessageOn: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun act(block: () -> Unit) { onDismiss(); block() }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(g.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        val hasNumber = !g.hidden && g.number.isNotBlank()
        @Composable
        fun row(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
            if (enabled) ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable(onClick = onClick))
        }
        row("Call", Icons.Rounded.Call, hasNumber) { act { vm.requestCall(g.number, g.contact?.displayName) } }
        row("Send message", Icons.AutoMirrored.Rounded.Message, hasNumber) { act { app.parley.ui.common.Intents.sms(context, g.number) } }
        row("Message on…", Icons.AutoMirrored.Rounded.Chat, hasNumber) { act { onMessageOn(g.number) } }
        row("Edit number before calling", Icons.Rounded.Dialpad, hasNumber) {
            act { vm.navigate(app.parley.NavEvent.Tab(app.parley.common.StartTab.KEYPAD, dial = g.number)) }
        }
        row("Copy number", Icons.Rounded.ContentCopy, hasNumber) { act { app.parley.ui.common.Intents.copy(context, g.number) } }
        row("Create contact", Icons.Rounded.PersonAdd, hasNumber && g.contact == null && g.vaultId == null) { act { open(Routes.edit(phone = g.number)) } }
        row("Add to a contact", Icons.Rounded.PersonAdd, hasNumber && g.contact == null && g.vaultId == null) { act { open(Routes.pick(g.number)) } }
        row("Block number", Icons.Rounded.Block, hasNumber) { act { vm.blockNumber(g.number) } }
        row("Select", Icons.Rounded.Block, true) { act { vm.recentSelection.value = setOf(g.key) } }
        if (hasNumber) app.parley.ui.blocking.RecentBlockingActions(vm, g.number, g.contact?.displayName, g.latest.type == CallType.BLOCKED, onDismiss)
        row("Delete from history", Icons.Rounded.Delete) {
            act {
                scope.launch {
                    vm.c.history.delete(g.calls.filter { it.id > 0 })
                    g.calls.filter { it.id < 0 }.forEach { vm.c.vault.deletePrivateCall(-it.id) }
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
    }
}
