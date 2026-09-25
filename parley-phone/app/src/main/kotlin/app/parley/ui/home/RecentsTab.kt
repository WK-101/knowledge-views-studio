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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.parley.R
import app.parley.ui.Bidi
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
    // F19: the number with the SIM of its latest call, so a national number is read with that SIM's country.
    var messageFor by remember { mutableStateOf<Pair<String, String?>?>(null) }
    menuFor?.let { g -> RecentActionsSheet(vm, g, open, onMessageOn = { messageFor = it to g.latest.accountId }) { menuFor = null } }
    messageFor?.let { (n, account) -> app.parley.messaging.MessageOnSheet(n, onDismiss = { messageFor = null }, accountId = account) }
    var daySummary by remember { mutableStateOf<Pair<Long, String>?>(null) }
    daySummary?.let { (day, title) -> app.parley.ui.history.DaySummarySheet(vm, day, title) { daySummary = null } }
    app.parley.ui.history.RecentsExportHost(vm)
    // Blocking: verdict / "Don't call back" badges and multi-select block (B2, B8, B10).
    val badgeFor = app.parley.ui.blocking.rememberRecentBadges(vm)
    val selected by vm.recentSelection.collectAsStateWithLifecycle()
    // U4: opt-in swipe actions; M7: "Message" uses a contact's usual way to message.
    val swipe = vm.people.settings.collectAsStateWithLifecycle().value.swipe
    val (quick, quickHost) = app.parley.ui.contact.rememberQuickMessenger(vm)
    quickHost()
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { vm.recentSelection.value = emptySet() }
    fun toggleSelected(g: RecentGroup) {
        vm.recentSelection.value = selected.let { if (g.key in it) it - g.key else it + g.key }
    }
    // V11: opening Recents (or coming back to it) clears Telecom's missed-call count and stops the re-alert.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.onRecentsShown()
        onPauseOrDispose { }
    }
    // V1: the Voicemail chip, with the number of unheard voicemails, while Parley can read them (default phone app).
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val voicemail by vm.c.voicemail.state.collectAsStateWithLifecycle()
    val query by vm.recentQuery.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxWidth()) {
        if (selected.isNotEmpty()) stickyHeader(key = "selection") { app.parley.ui.blocking.RecentsSelectionBar(vm, groups.orEmpty()) }
        item(key = "filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecentFilter.entries.forEach { f ->
                    if (f == RecentFilter.VOICEMAIL && !isDefault && filter != f) return@forEach
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.recentFilter.value = f },
                        label = {
                            Text(stringResource(f.labelRes))
                            if (f == RecentFilter.VOICEMAIL && voicemail.unheard > 0) {
                                Spacer(Modifier.width(6.dp))
                                androidx.compose.material3.Badge { Text(voicemail.unheard.toString()) }
                            }
                        },
                        modifier = if (f == RecentFilter.VOICEMAIL && voicemail.unheard > 0) {
                            val spoken = pluralStringResource(R.plurals.recents_voicemail_new, voicemail.unheard, voicemail.unheard)
                            Modifier.semantics { contentDescription = spoken }
                        } else {
                            Modifier
                        },
                    )
                }
                app.parley.ui.history.SavedFilterChips(vm)
            }
        }
        if (filter == RecentFilter.VOICEMAIL) {
            item(key = "voicemail") { app.parley.ui.calls.VoicemailInbox(vm, query) }
            return@LazyColumn
        }
        item(key = "archive-notes") { app.parley.ui.history.ArchiveNotices(vm, open) }
        val list = groups
        if (list != null && list.isEmpty()) {
            item(key = "empty") {
                EmptyState(Icons.Rounded.AccessTime, stringResource(if (filter == RecentFilter.ALL) R.string.recents_empty else R.string.recents_nothing_here), modifier = Modifier.padding(top = 48.dp))
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
                        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.recents_day_summary)) { daySummary = g.latest.date to header }
                            .padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            item(key = g.key) {
              val hasNumber = !g.hidden && g.number.isNotBlank()
              app.parley.ui.people.SwipeActionRow(
                  if (selected.isEmpty()) swipe else swipe.copy(enabled = false), hasNumber = hasNumber,
                  // Private calls live in Parley's encrypted history, which has no undo: no swipe delete there.
                  canDelete = g.vaultId == null && g.calls.all { it.id > 0 },
                  onAction = { a ->
                      when (a) {
                          app.parley.common.people.SwipeAction.CALL -> vm.requestCall(g.number, g.contact?.displayName)
                          app.parley.common.people.SwipeAction.MESSAGE -> g.contact?.let { quick.message(it, g.number) } ?: app.parley.ui.common.Intents.sms(context, g.number)
                          app.parley.common.people.SwipeAction.MESSAGE_ON -> g.contact?.let { quick.message(it, g.number, ask = true) } ?: run { messageFor = g.number to g.latest.accountId }
                          app.parley.common.people.SwipeAction.BLOCK -> vm.blockNumber(g.number)
                          app.parley.common.people.SwipeAction.DELETE -> vm.deleteCallsWithUndo(g.calls)
                          app.parley.common.people.SwipeAction.NONE -> Unit
                      }
                  },
              ) {
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
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecentRow(
    g: RecentGroup, countryIso: String, simLabels: Map<String, String>, onLongClick: (() -> Unit)? = null,
    badge: app.parley.ui.blocking.RecentBadge? = null, selected: Boolean = false, onOpen: () -> Unit, onCall: () -> Unit,
) {
    val context = LocalContext.current
    val e = g.latest
    val missed = e.type == CallType.MISSED || e.type == CallType.REJECTED
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.main_more_actions))
            .semantics { this.selected = selected },
        colors = if (selected) androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else androidx.compose.material3.ListItemDefaults.colors(),
        leadingContent = {
            if (g.hidden) MonoAvatar(avatarSize()) else Avatar(g.title, g.contact?.photoUri, avatarSize())
        },
        headlineContent = {
            Text(
                (if (g.vaultId != null) "🔒 " else "") + (if (g.calls.size > 1) stringResource(R.string.missed_name_count, g.shownTitle, g.calls.size) else g.shownTitle),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
          androidx.compose.foundation.layout.Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CallTypeIcon(e.type, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                val location = if (g.contact == null && g.vaultId == null && !g.hidden) remember(g.number) { app.parley.data.NumberInfo.location(g.number, countryIso) } else null
                val parts = listOfNotNull(
                    location,
                    if (g.contact != null) g.contact.phones.firstOrNull { p -> app.parley.common.PhoneNumbers.matchKey(p.number) == app.parley.common.PhoneNumbers.matchKey(e.number) }
                        ?.let { p -> Format.phoneType(context.resources, p.type, p.label) } else if (!g.hidden && g.contact == null && g.cachedName != null) Bidi.ltr(Format.number(e.number, countryIso)) else null,
                    e.accountId?.let { simLabels[it] },
                    Format.shortWhen(context, e.date),
                )
                Text(parts.joinToString(stringResource(R.string.main_separator)), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                IconButton(onClick = onCall) { Icon(Icons.Rounded.Call, stringResource(R.string.main_call_who, g.title), tint = MaterialTheme.colorScheme.primary) }
            }
        },
    )
}

/** The row's title; a number (no name) stays left to right in right-to-left languages (L3). */
private val RecentGroup.shownTitle: String
    get() = if (contact == null && cachedName.isNullOrBlank() && number.isNotBlank()) Bidi.ltr(title) else title

/** Chip text of a Recents filter. */
private val RecentFilter.labelRes: Int
    get() = when (this) {
        RecentFilter.ALL -> R.string.recents_filter_all
        RecentFilter.MISSED -> R.string.recents_filter_missed
        RecentFilter.INCOMING -> R.string.recents_filter_incoming
        RecentFilter.OUTGOING -> R.string.recents_filter_outgoing
        RecentFilter.BLOCKED -> R.string.recents_filter_blocked
        RecentFilter.VOICEMAIL -> R.string.recents_filter_voicemail
    }

/** U3: the icon of a call type, in its fixed call colour (never the wallpaper colours). */
@Composable
fun callTypeIcon(type: CallType): Pair<ImageVector, Color> = callTypeVector(type) to app.parley.ui.CallTypeColors.of(app.parley.common.ux.CallHue.of(type))

private fun callTypeVector(type: CallType): ImageVector = when (type) {
    CallType.INCOMING, CallType.ANSWERED_EXTERNALLY -> Icons.AutoMirrored.Rounded.CallReceived
    CallType.OUTGOING -> Icons.AutoMirrored.Rounded.CallMade
    CallType.MISSED -> Icons.AutoMirrored.Rounded.CallMissed
    CallType.REJECTED -> Icons.Rounded.CallEnd
    CallType.BLOCKED -> Icons.Rounded.Block
    CallType.VOICEMAIL -> Icons.Rounded.Voicemail
    CallType.UNKNOWN -> Icons.Rounded.Call
}

/** U3: a call type's icon on its tinted circle, the same in Recents, history, the contact page and insights. */
@Composable
fun CallTypeIcon(type: CallType, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 32.dp, describe: Boolean = true) {
    app.parley.ui.CallTypeBadge(
        callTypeVector(type), app.parley.common.ux.CallHue.of(type), modifier, size,
        // Rows that already say the type in words pass describe = false, so it isn't read twice.
        contentDescription = if (describe) stringResource(app.parley.ui.history.HistoryText.callType(type)) else null,
    )
}


/** Long-press actions for a Recents row. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RecentActionsSheet(vm: AppViewModel, g: RecentGroup, open: (String) -> Unit, onMessageOn: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun act(block: () -> Unit) { onDismiss(); block() }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(g.shownTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        val hasNumber = !g.hidden && g.number.isNotBlank()
        @Composable
        fun row(label: Int, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
            if (enabled) ListItem(headlineContent = { Text(stringResource(label)) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable(onClick = onClick))
        }
        row(R.string.main_call, Icons.Rounded.Call, hasNumber) { act { vm.requestCall(g.number, g.contact?.displayName) } }
        row(R.string.recents_send_message, Icons.AutoMirrored.Rounded.Message, hasNumber) { act { app.parley.ui.common.Intents.sms(context, g.number) } }
        row(R.string.missed_message_on, Icons.AutoMirrored.Rounded.Chat, hasNumber) { act { onMessageOn(g.number) } }
        row(R.string.recents_edit_before_call, Icons.Rounded.Dialpad, hasNumber) {
            act { vm.navigate(app.parley.NavEvent.Tab(app.parley.common.StartTab.KEYPAD, dial = g.number)) }
        }
        row(R.string.recents_copy_number, Icons.Rounded.ContentCopy, hasNumber) { act { app.parley.ui.common.Intents.copy(context, g.number) } }
        row(R.string.home_create_contact, Icons.Rounded.PersonAdd, hasNumber && g.contact == null && g.vaultId == null) { act { open(Routes.edit(phone = g.number)) } }
        row(R.string.recents_add_to_contact, Icons.Rounded.PersonAdd, hasNumber && g.contact == null && g.vaultId == null) { act { open(Routes.pick(g.number)) } }
        row(R.string.recents_block_number, Icons.Rounded.Block, hasNumber) { act { vm.blockNumber(g.number) } }
        row(R.string.recents_select, Icons.Rounded.Block, true) { act { vm.recentSelection.value = setOf(g.key) } }
        if (hasNumber) app.parley.ui.blocking.RecentBlockingActions(vm, g.number, g.contact?.displayName, g.latest.type == CallType.BLOCKED, onDismiss)
        row(R.string.recents_delete_from_history, Icons.Rounded.Delete) {
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
