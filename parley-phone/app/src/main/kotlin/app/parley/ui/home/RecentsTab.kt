package app.parley.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.parley.NavEvent
import app.parley.common.PhoneIdentity
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
import androidx.compose.material.icons.rounded.Info
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
import app.parley.common.RecentTap
import app.parley.common.SettingsCategory
import app.parley.common.StartTab
import app.parley.common.people.SwipeAction
import app.parley.common.ux.CallHue
import app.parley.common.ux.RecentsStyle
import app.parley.common.ux.Tips
import app.parley.messaging.ReachSheet
import app.parley.messaging.ReachTarget
import app.parley.ui.Bidi
import app.parley.ui.CallClassBadge
import app.parley.ui.CallDurationBar
import app.parley.ui.CallSequenceDots
import app.parley.ui.CallTypeBadge
import app.parley.ui.CallTypeColors
import app.parley.ui.blocking.RecentBadge
import app.parley.ui.blocking.RecentBlockingActions
import app.parley.ui.blocking.RecentsSelectionBar
import app.parley.ui.blocking.rememberRecentBadges
import app.parley.ui.calls.VoicemailInbox
import app.parley.ui.common.CoachMark
import app.parley.ui.common.Intents
import app.parley.ui.common.rememberNumberLocation
import app.parley.ui.contact.rememberQuickMessenger
import app.parley.ui.history.ArchiveNotices
import app.parley.ui.history.DaySummarySheet
import app.parley.ui.history.HistoryText
import app.parley.ui.history.RecentsExportHost
import app.parley.ui.history.RecentsMenuDialogs
import app.parley.ui.history.SavedFilterChips
import app.parley.ui.people.SwipeActionRow
import app.parley.ui.people.blockWithUndo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.RecentFilter
import app.parley.RecentGroup
import app.parley.common.CallType
import app.parley.common.ux.CallClass
import app.parley.common.ux.CallGlance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.MonoAvatar
import app.parley.ui.Routes
import app.parley.ui.activityViewModel
import app.parley.ui.avatarSize
import app.parley.ui.common.Format
import app.parley.ui.ParleySheet
import app.parley.ui.ListSectionHeader
import app.parley.ui.Spacing
import app.parley.ui.ParleyListItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentsTab(vm: AppViewModel, open: (String) -> Unit, bottomPadding: Dp = 0.dp) {
    // The list, its day headers and the chips' state come from RecentsViewModel; this only draws them.
    val recents: RecentsViewModel = activityViewModel()
    val model by recents.list.collectAsStateWithLifecycle()
    val groups = model?.groups
    val filter by recents.filter.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val simLabels = remember(sims) { if (sims.size > 1) sims.associate { it.id to it.label } else emptyMap() }
    var menuFor by remember { mutableStateOf<RecentGroup?>(null) }
    // The number with the SIM of its latest call, so a national number is read with that SIM's country.
    var messageFor by remember { mutableStateOf<Pair<String, String?>?>(null) }
    menuFor?.let { g -> RecentActionsSheet(vm, recents, g, open, onMessageOn = { messageFor = it to g.latest.accountId }) { menuFor = null } }
    messageFor?.let { (n, account) -> ReachSheet(ReachTarget.Number(n, account), onDismiss = { messageFor = null }, onCall = { num -> vm.requestCall(num) }) }
    var daySummary by remember { mutableStateOf<Pair<Long, String>?>(null) }
    daySummary?.let { (day, title) -> DaySummarySheet(vm, day, title) { daySummary = null } }
    RecentsExportHost(vm)
    RecentsMenuDialogs(vm, open)
    // Blocking: verdict / "Don't call back" badges and multi-select block.
    val badgeFor = rememberRecentBadges(vm)
    val selected by recents.selection.collectAsStateWithLifecycle()
    // Opt-in swipe actions; M7: "Message" uses a contact's usual way to message.
    val swipe = vm.people.settings.collectAsStateWithLifecycle().value.swipe
    val (quick, quickHost) = rememberQuickMessenger(vm)
    quickHost()
    BackHandler(enabled = selected.isNotEmpty()) { recents.clearSelection() }
    fun toggleSelected(g: RecentGroup) = recents.toggleSelected(g)
    // Opening Recents (or coming back to it) clears Telecom's missed-call count and stops the re-alert.
    LifecycleResumeEffect(Unit) {
        vm.onRecentsShown()
        onPauseOrDispose { }
    }
    // The Voicemail chip, with the number of unheard voicemails, while Parley can read them (default phone app).
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val voicemail by recents.voicemail.collectAsStateWithLifecycle()
    val query by recents.query.collectAsStateWithLifecycle()
    // Missed calls not returned yet (tint, Call back pill, the Missed chip's count) and the legend.
    val unreturned by recents.unreturnedMissed.collectAsStateWithLifecycle()
    val rich = settings.recentsStyle == RecentsStyle.RICH
    val toReturn = unreturned.size
    RecentsLegendHost()

    // What a tap on a call does (Settings › Appearance › Layout, in every layout).
    val tapCalls = settings.surfaces.recentTap == RecentTap.CALL
    // Swipes don't start while this list is still flinging.
    val listState = rememberLazyListState()
    LazyColumn(Modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
        if (selected.isNotEmpty()) stickyHeader(key = "selection") { RecentsSelectionBar(vm, groups.orEmpty()) }
        item(key = "filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecentFilter.entries.forEach { f ->
                    if (f == RecentFilter.VOICEMAIL && !isDefault && filter != f) return@forEach
                    FilterChip(
                        selected = filter == f,
                        onClick = { recents.filter.value = f },
                        label = {
                            Text(stringResource(f.labelRes))
                            // The Missed chip counts the people still to call back.
                            if (f == RecentFilter.MISSED && rich && toReturn > 0) {
                                Spacer(Modifier.width(6.dp))
                                Badge { Text(toReturn.toString()) }
                            }
                            if (f == RecentFilter.VOICEMAIL && voicemail.unheard > 0) {
                                Spacer(Modifier.width(6.dp))
                                Badge { Text(voicemail.unheard.toString()) }
                            }
                        },
                        modifier = if (f == RecentFilter.VOICEMAIL && voicemail.unheard > 0) {
                            val spoken = pluralStringResource(R.plurals.recents_voicemail_new, voicemail.unheard, voicemail.unheard)
                            Modifier.semantics { contentDescription = spoken }
                        } else if (f == RecentFilter.MISSED && rich && toReturn > 0) {
                            val spoken = stringResource(f.labelRes) + stringResource(R.string.main_separator) + pluralStringResource(R.plurals.recents_to_call_back, toReturn, toReturn)
                            Modifier.semantics { contentDescription = spoken }
                        } else {
                            Modifier
                        },
                    )
                }
                SavedFilterChips(vm)
            }
        }
        if (filter == RecentFilter.VOICEMAIL) {
            item(key = "voicemail") { VoicemailInbox(vm, query) }
            return@LazyColumn
        }
        item(key = "archive-notes") { ArchiveNotices(vm, open) }
        val list = groups
        val rows = model?.rows.orEmpty()
        // Swipe and long-press actions on calls, told once (only when there are calls to try them on).
        if (!list.isNullOrEmpty()) item(key = "tip") {
            CoachMark(
                Tips.RECENTS_SWIPE,
                stringResource(if (swipe.enabled) R.string.ux_tip_recents_swipe else R.string.ux_tip_recents_long_press),
                action = if (swipe.enabled) null else stringResource(R.string.ux_tip_turn_on),
                onAction = { open(Routes.settingsPage(SettingsCategory.LAYOUT, "swipe_actions")) },
            )
        }
        if (list != null && list.isEmpty()) {
            item(key = "empty") {
                // "no matches" (clear the search), a filter that shows nothing (show all), or no calls yet (keypad).
                val activeSaved by recents.historyFilter.collectAsStateWithLifecycle()
                when {
                    query.isNotBlank() -> EmptyState(
                        Icons.Rounded.AccessTime, stringResource(R.string.ux_empty_calls_no_match, query), modifier = Modifier.padding(top = 48.dp),
                        action = stringResource(R.string.ux_empty_clear_search), onAction = { recents.query.value = "" },
                    )
                    filter != RecentFilter.ALL || !activeSaved.isEmpty -> EmptyState(
                        Icons.Rounded.AccessTime, stringResource(R.string.recents_nothing_here), stringResource(R.string.ux_empty_calls_filter), Modifier.padding(top = 48.dp),
                        action = stringResource(R.string.ux_empty_show_all_calls),
                        onAction = recents::showAll,
                    )
                    else -> EmptyState(
                        Icons.Rounded.AccessTime, stringResource(R.string.recents_empty), stringResource(R.string.ux_empty_calls_none), Modifier.padding(top = 48.dp),
                        action = stringResource(R.string.ux_empty_open_keypad), onAction = { vm.navigate(NavEvent.Tab(StartTab.KEYPAD)) },
                    )
                }
            }
        }
        // The day headers are worked out once per list change in the view model; a header's text is formatted
        // only when it is on screen.
        items(rows, key = { it.key }, contentType = { if (it is RecentsRow.Day) RecentsRow.TYPE_DAY else RecentsRow.TYPE_CALL }) { row ->
            when (row) {
                is RecentsRow.Day -> {
                    val header = remember(row.date, row.today, context) { Format.dayHeader(context, row.date) }
                    ListSectionHeader(
                        header, inset = 20.dp, top = Spacing.m,
                        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.recents_day_summary)) { daySummary = row.date to header },
                    )
                }
                is RecentsRow.Call -> {
                    val g = row.group
              val hasNumber = !g.hidden && g.number.isNotBlank()
              SwipeActionRow(
                  if (selected.isEmpty()) swipe else swipe.copy(enabled = false), hasNumber = hasNumber,
                  // Private calls live in Parley's encrypted history, which has no undo: no swipe delete there.
                  canDelete = g.vaultId == null && g.calls.all { it.id > 0 },
                  listState = listState,
                  onAction = { a ->
                      when (a) {
                          SwipeAction.CALL -> vm.requestCall(g.number, g.contact?.displayName)
                          SwipeAction.MESSAGE -> g.contact?.let { quick.message(it, g.number) } ?: Intents.sms(context, g.number)
                          SwipeAction.MESSAGE_ON -> g.contact?.let { quick.message(it, g.number, ask = true) } ?: run { messageFor = g.number to g.latest.accountId }
                          SwipeAction.BLOCK -> blockWithUndo(vm, listOf(g.number))
                          SwipeAction.DELETE -> vm.deleteCallsWithUndo(g.calls)
                          SwipeAction.NONE -> Unit
                      }
                  },
              ) {
                RecentRow(
                    g, vm.countryIso, simLabels.takeIf { settings.showSimLabels }.orEmpty(),
                    onLongClick = { if (selected.isNotEmpty()) toggleSelected(g) else menuFor = g },
                    badge = badgeFor(g),
                    selected = g.key in selected,
                    unreturned = g.latest.id in unreturned,
                    tapCalls = tapCalls && selected.isEmpty() && hasNumber,
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentRow(
    g: RecentGroup, countryIso: String, simLabels: Map<String, String>, onLongClick: (() -> Unit)? = null,
    badge: RecentBadge? = null, selected: Boolean = false, unreturned: Boolean = false,
    /** A tap calls back; the trailing button then opens the details instead. */
    tapCalls: Boolean = false,
    onOpen: () -> Unit, onCall: () -> Unit,
) {
    val context = LocalContext.current
    val e = g.latest
    val missed = e.type == CallType.MISSED || e.type == CallType.REJECTED
    // The rich look (shape-coded badge, accent bar, tint and Call back pill for unreturned missed calls,
    // count chip and sequence dots, duration bar); Simple keeps the U3 row.
    val rich = richCalls()
    val cls = CallClass.of(e)
    val hue = CallTypeColors.of(cls.hue)
    val attention = rich && unreturned && !g.hidden
    val sequence = if (rich) CallGlance.sequence(g.calls) else emptyList()
    ParleyListItem(
        modifier = Modifier.combinedClickable(
            onClick = if (tapCalls) onCall else onOpen, onLongClick = onLongClick,
            onClickLabel = if (tapCalls) stringResource(R.string.main_call) else null,
            onLongClickLabel = stringResource(R.string.main_more_actions),
        )
            .then(if (rich) Modifier.callAccent(hue) else Modifier)
            .semantics { this.selected = selected },
        colors = when {
            selected -> ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            attention -> ListItemDefaults.colors(containerColor = hue.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface))
            else -> ListItemDefaults.colors()
        },
        leadingContent = {
            if (g.hidden) MonoAvatar(avatarSize()) else Avatar(g.title, g.contact?.photoUri, avatarSize())
        },
        headlineContent = {
            if (rich) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (g.vaultId != null) "🔒 " else "") + g.shownTitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (attention) FontWeight.Bold else null,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (g.calls.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        CallCountChip(g.calls.size, cls)
                    }
                }
            } else {
                Text(
                    (if (g.vaultId != null) "🔒 " else "") + (if (g.calls.size > 1) stringResource(R.string.missed_name_count, g.shownTitle, g.calls.size) else g.shownTitle),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        supportingContent = {
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rich) {
                    // TalkBack reads the type in words (and that the call still waits for a call back).
                    val words = stringResource(callClassLabel(cls)) + if (attention) stringResource(R.string.main_separator) + stringResource(R.string.recents_not_returned) else ""
                    CallClassBadge(cls, size = 20.dp, contentDescription = words)
                    if (sequence.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        val spoken = sequenceDescription(g.calls.size, sequence)
                        CallSequenceDots(sequence, Modifier.semantics { contentDescription = spoken })
                    }
                } else {
                    CallTypeIcon(e.type, size = 20.dp)
                }
                Spacer(Modifier.width(6.dp))
                val location = rememberNumberLocation(g.number, countryIso, enabled = g.contact == null && g.vaultId == null && !g.hidden)
                val parts = listOfNotNull(
                    location,
                    if (g.contact != null) g.contact.phones.firstOrNull { p -> PhoneIdentity.same(p.number, e.number, countryIso) }
                        ?.let { p -> Format.phoneType(context.resources, p.type, p.label) } else if (!g.hidden && g.contact == null && g.cachedName != null) Bidi.ltr(Format.number(e.number, countryIso)) else null,
                    e.accountId?.let { simLabels[it] },
                    // An outgoing call nobody answered says so.
                    if (rich && cls == CallClass.NO_ANSWER) stringResource(R.string.recents_class_no_answer) else null,
                    Format.shortWhen(context, e.date),
                )
                Text(parts.joinToString(stringResource(R.string.main_separator)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // How long you talked, as a small bar (the length in words for TalkBack).
                if (rich && cls.answered) {
                    Spacer(Modifier.width(8.dp))
                    val length = Format.duration(e.durationSec)
                    CallDurationBar(CallGlance.durationFraction(e.durationSec), cls, Modifier.semantics { contentDescription = length })
                }
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
            if (tapCalls) {
                IconButton(onClick = onOpen) { Icon(Icons.Rounded.Info, stringResource(R.string.home_recent_details, g.title)) }
            } else if (!g.hidden && g.number.isNotBlank()) {
                if (attention) {
                    CallBackPill(g.title, onCall)
                } else {
                    IconButton(onClick = onCall) { Icon(Icons.Rounded.Call, stringResource(R.string.main_call_who, g.title), tint = MaterialTheme.colorScheme.primary) }
                }
            }
        },
    )
}

/** The row's title; a number (no name) stays left to right in right-to-left languages. */
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

/** The icon of a call type, in its fixed call colour (never the wallpaper colours). */
@Composable
fun callTypeIcon(type: CallType): Pair<ImageVector, Color> = callTypeVector(type) to CallTypeColors.of(CallHue.of(type))

private fun callTypeVector(type: CallType): ImageVector = when (type) {
    CallType.INCOMING, CallType.ANSWERED_EXTERNALLY -> Icons.AutoMirrored.Rounded.CallReceived
    CallType.OUTGOING -> Icons.AutoMirrored.Rounded.CallMade
    CallType.MISSED -> Icons.AutoMirrored.Rounded.CallMissed
    CallType.REJECTED -> Icons.Rounded.CallEnd
    CallType.BLOCKED -> Icons.Rounded.Block
    CallType.VOICEMAIL -> Icons.Rounded.Voicemail
    CallType.UNKNOWN -> Icons.Rounded.Call
}

/**
 * A call type's icon on its tinted circle, the same in Recents, history, the contact page and insights. R4:
 * in the Rich style it is the shape-coded [app.parley.ui.CallClassBadge] ([durationSec] tells "No answer" apart).
 */
@Composable
fun CallTypeIcon(type: CallType, modifier: Modifier = Modifier, size: Dp = 32.dp, describe: Boolean = true, durationSec: Long? = null) {
    if (richCalls()) {
        val cls = CallClass.of(type, durationSec ?: 1)
        CallClassBadge(cls, modifier, size, contentDescription = if (describe) stringResource(callClassLabel(cls)) else null)
        return
    }
    CallTypeBadge(
        callTypeVector(type), CallHue.of(type), modifier, size,
        // Rows that already say the type in words pass describe = false, so it isn't read twice.
        contentDescription = if (describe) stringResource(HistoryText.callType(type)) else null,
    )
}


/** Long-press actions for a Recents row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentActionsSheet(vm: AppViewModel, recents: RecentsViewModel, g: RecentGroup, open: (String) -> Unit, onMessageOn: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun act(block: () -> Unit) { onDismiss(); block() }
    ParleySheet(onDismissRequest = onDismiss) {
        Text(g.shownTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        val hasNumber = !g.hidden && g.number.isNotBlank()
        @Composable
        fun row(label: Int, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
            if (enabled) ListItem(headlineContent = { Text(stringResource(label)) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable(onClick = onClick))
        }
        row(R.string.main_call, Icons.Rounded.Call, hasNumber) { act { vm.requestCall(g.number, g.contact?.displayName) } }
        row(R.string.recents_send_message, Icons.AutoMirrored.Rounded.Message, hasNumber) { act { Intents.sms(context, g.number) } }
        row(R.string.reach_message_or_call_on, Icons.AutoMirrored.Rounded.Chat, hasNumber) { act { onMessageOn(g.number) } }
        row(R.string.recents_edit_before_call, Icons.Rounded.Dialpad, hasNumber) {
            act { vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = g.number)) }
        }
        row(R.string.recents_copy_number, Icons.Rounded.ContentCopy, hasNumber) { act { Intents.copy(context, g.number) } }
        row(R.string.home_create_contact, Icons.Rounded.PersonAdd, hasNumber && g.contact == null && g.vaultId == null) { act { open(Routes.edit(phone = g.number)) } }
        row(R.string.recents_add_to_contact, Icons.Rounded.PersonAdd, hasNumber && g.contact == null && g.vaultId == null) { act { open(Routes.pick(g.number)) } }
        row(R.string.recents_block_number, Icons.Rounded.Block, hasNumber) { act { vm.blockNumber(g.number) } }
        row(R.string.recents_select, Icons.Rounded.Block, true) { act { recents.selection.value = setOf(g.key) } }
        if (hasNumber) RecentBlockingActions(vm, g.number, g.contact?.displayName, g.latest.type == CallType.BLOCKED, onDismiss)
        row(R.string.recents_delete_from_history, Icons.Rounded.Delete) {
            act { recents.delete(g) }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}
