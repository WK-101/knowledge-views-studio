package app.parley.ui.home

import androidx.compose.foundation.clickable
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

@Composable
fun RecentsTab(vm: AppViewModel, open: (String) -> Unit) {
    val groups by vm.recentGroups.collectAsStateWithLifecycle()
    val filter by vm.recentFilter.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val simLabels = remember(sims) { if (sims.size > 1) sims.associate { it.id to it.label } else emptyMap() }

    LazyColumn(Modifier.fillMaxWidth()) {
        item(key = "filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecentFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.recentFilter.value = f },
                        label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }
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
                    Text(header, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                }
            }
            item(key = g.key) {
                RecentRow(
                    g, vm.countryIso, simLabels.takeIf { settings.showSimLabels }.orEmpty(),
                    onOpen = {
                        val ct = g.contact
                        if (ct != null) open(Routes.contact(ct.id)) else if (!g.hidden) open(Routes.history(g.number))
                    },
                    onCall = { vm.requestCall(g.number, g.contact?.displayName) },
                )
            }
        }
    }
}

@Composable
fun RecentRow(g: RecentGroup, countryIso: String, simLabels: Map<String, String>, onOpen: () -> Unit, onCall: () -> Unit) {
    val context = LocalContext.current
    val e = g.latest
    val (icon, tint) = callTypeIcon(e.type)
    val missed = e.type == CallType.MISSED || e.type == CallType.REJECTED
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            if (g.hidden) MonoAvatar(avatarSize()) else Avatar(g.title, g.contact?.photoUri, avatarSize())
        },
        headlineContent = {
            Text(
                g.title + if (g.calls.size > 1) " (${g.calls.size})" else "",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                val parts = listOfNotNull(
                    if (g.contact != null) g.contact.phones.firstOrNull { p -> app.parley.common.PhoneNumbers.matchKey(p.number) == app.parley.common.PhoneNumbers.matchKey(e.number) }
                        ?.let { p -> Format.phoneType(context.resources, p.type, p.label) } else if (!g.hidden && g.contact == null && g.cachedName != null) Format.number(e.number, countryIso) else null,
                    e.accountId?.let { simLabels[it] },
                    Format.shortWhen(context, e.date),
                )
                Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
