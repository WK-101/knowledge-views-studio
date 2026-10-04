package app.parley.ui.home

import app.parley.ui.Clipboard
import app.parley.ui.Destination
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.platform.LocalResources
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
import app.parley.common.ux.ListSections
import app.parley.ui.segmentShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
import app.parley.ui.blocking.askToBlock
import app.parley.ui.calls.RemindToCallSheet
import app.parley.ui.blocking.rememberBlocked
import app.parley.ui.blocking.unblockWithUndo
import app.parley.ui.blocking.rememberRecentBadges
import app.parley.ui.calls.ToCallStrip
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
import androidx.compose.ui.semantics.heading

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentsTab(vm: AppViewModel, open: (Destination) -> Unit, bottomPadding: Dp = 0.dp) {
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
    val style = settings.recentsStyle
    val rich = style.rich
    // Cards: each day's calls in one segmented card; the four corner sets are made once, not per row.
    val cardShapes = remember { ListSections.Place.entries.map { cardShape(it) } }
    val toReturn = unreturned.size
    RecentsLegendHost()

    // What a tap on a call does (Settings › Appearance › Layout, in every layout).
    val tapCalls = settings.surfaces.recentTap == RecentTap.CALL
    // Swipes don't start while this list is still flinging.
    val listState = rememberLazyListState()
    LazyColumn(Modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
        if (selected.isNotEmpty()) stickyHeader(key = "selection") { RecentsSelectionBar(vm, groups.orEmpty()) }
        item(key = "filters") {
            RecentsFilterRow(
                vm, filter, onFilter = { recents.setFilter(it) }, voicemailChip = isDefault,
                unheardVoicemail = voicemail.unheard, toReturn = toReturn, rich = rich,
            )
        }
        if (filter == RecentFilter.VOICEMAIL) {
            item(key = "voicemail") { VoicemailInbox(vm, query) }
            return@LazyColumn
        }
        // I9: the calls you owe, as one quiet strip that opens the To call list.
        item(key = "to-call") { ToCallStrip(open) }
        // Unknown: a quiet "3 unknown callers today" under the chips.
        if (filter == RecentFilter.UNKNOWN) item(key = "unknown-today") { UnknownCallersHeader(recents.unknownToday.collectAsStateWithLifecycle().value) }
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
                        Icons.Rounded.AccessTime,
                        stringResource(R.string.recents_nothing_here),
                        stringResource(R.string.ux_empty_calls_filter),
                        Modifier.padding(top = 48.dp),
                        action = stringResource(R.string.ux_empty_show_all_calls),
                        onAction = recents::showAll,
                    )
                    else -> EmptyState(
                        Icons.Rounded.AccessTime,
                        stringResource(R.string.recents_empty),
                        stringResource(R.string.ux_empty_calls_none),
                        Modifier.padding(top = 48.dp),
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
                        modifier = Modifier.fillMaxWidth()
                            .clickable(onClickLabel = stringResource(R.string.recents_day_summary)) { daySummary = row.date to header },
                    )
                }
                is RecentsRow.Call -> RecentCard(if (style.cards) cardShapes[row.place.ordinal] else null, row.place) {
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
                          SwipeAction.MESSAGE_ON -> g.contact?.let { quick.message(it, g.number, ask = true) } ?: run {
                              messageFor = g.number to g.latest.accountId
                          }
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
    val style = LocalRecentsStyle.current
    val rich = style.rich
    val cls = CallClass.of(e)
    val hue = CallTypeColors.of(cls.hue)
    val sequence = if (rich) CallGlance.sequence(g.calls) else emptyList()
    // Every mark the row draws comes from here, so "What do the colours mean?" explains each of them.
    val marks = RecentsMark.onRow(
        RecentRowFacts(
            calls = g.calls.size, cls = cls, missed = missed, sequence = sequence.isNotEmpty(), unreturned = unreturned, hidden = g.hidden,
            video = e.video, private = g.vaultId != null, screening = badge != null, callButton = !tapCalls && !g.hidden && g.number.isNotBlank(),
        ),
        style,
    )
    val attention = RecentsMark.NOT_RETURNED in marks
    val lock = if (RecentsMark.PRIVATE in marks) "$PRIVATE_MARK " else ""
    // In the Cards style the row takes its day card's colour.
    val base = if (style.cards) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
    ParleyListItem(
        modifier = Modifier.combinedClickable(
            onClick = if (tapCalls) onCall else onOpen, onLongClick = onLongClick,
            onClickLabel = if (tapCalls) stringResource(R.string.main_call) else null,
            onLongClickLabel = stringResource(R.string.main_more_actions),
        )
            .then(if (RecentsMark.ACCENT in marks) Modifier.callAccent(hue) else Modifier)
            .semantics { this.selected = selected },
        colors = when {
            selected -> ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            attention -> ListItemDefaults.colors(containerColor = hue.copy(alpha = 0.08f).compositeOver(base))
            else -> ListItemDefaults.colors(containerColor = base)
        },
        leadingContent = {
            if (g.hidden) MonoAvatar(avatarSize()) else Avatar(g.title, g.contact?.photoUri, avatarSize())
        },
        headlineContent = {
            if (rich) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        lock + g.shownTitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (attention) FontWeight.Bold else null,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (RecentsMark.COUNT in marks) {
                        Spacer(Modifier.width(6.dp))
                        CallCountChip(g.calls.size, cls)
                    }
                }
            } else {
                val counted = if (RecentsMark.COUNT_TEXT in marks) stringResource(R.string.missed_name_count, g.shownTitle, g.calls.size) else g.shownTitle
                Text(
                    lock + counted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (RecentsMark.MISSED_NAME in marks) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        supportingContent = {
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rich) {
                    // TalkBack reads the type in words (and that the call still waits for a call back).
                    val words = stringResource(callClassLabel(cls)) + if (attention) stringResource(
                        R.string.main_separator,
                    ) + stringResource(R.string.recents_not_returned) else ""
                    CallClassBadge(cls, size = 20.dp, contentDescription = words)
                    if (RecentsMark.SEQUENCE in marks) {
                        Spacer(Modifier.width(6.dp))
                        val spoken = sequenceDescription(g.calls.size, sequence)
                        CallSequenceDots(sequence, Modifier.semantics { contentDescription = spoken })
                    }
                } else {
                    CallTypeIcon(e.type, size = 20.dp)
                }
                // Android logged it as a video call (Parley answered it as voice).
                if (RecentsMark.VIDEO in marks) {
                    Spacer(Modifier.width(4.dp))
                    VideoCallMark(contentDescription = stringResource(R.string.recents_video_call))
                }
                Spacer(Modifier.width(6.dp))
                val location = rememberNumberLocation(g.number, countryIso, enabled = g.contact == null && g.vaultId == null && !g.hidden)
                val shownNumber = remember(e.number, countryIso) { Bidi.ltr(Format.number(e.number, countryIso)) }
                val parts = listOfNotNull(
                    location,
                    if (g.contact != null) g.contact.phones.firstOrNull { p -> PhoneIdentity.same(p.number, e.number, countryIso) }
                        ?.let { p ->
                            Format.phoneType(context.resources, p.type, p.label)
                        } else if (!g.hidden && g.contact == null && g.cachedName != null) shownNumber else null,
                    e.accountId?.let { simLabels[it] },
                    // An outgoing call nobody answered says so.
                    if (rich && cls == CallClass.NO_ANSWER) stringResource(R.string.recents_class_no_answer) else null,
                    Format.shortWhen(context, e.date),
                )
                Text(
                    parts.joinToString(stringResource(R.string.main_separator)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // How long you talked, as a small bar (the length in words for TalkBack).
                if (RecentsMark.DURATION in marks) {
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
                if (RecentsMark.CALL_BACK in marks) {
                    CallBackPill(g.title, onCall)
                } else {
                    IconButton(onClick = onCall) {
                        Icon(Icons.Rounded.Call, stringResource(R.string.main_call_who, g.title), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
    )
}

/** The corners of a call row in a day card: large at the card's top and bottom, small between rows. */
private fun cardShape(place: ListSections.Place): Shape = when (place) {
    ListSections.Place.ONLY -> segmentShape(0, 1)
    ListSections.Place.FIRST -> segmentShape(0, CARD_ROWS)
    ListSections.Place.MIDDLE -> segmentShape(1, CARD_ROWS)
    ListSections.Place.LAST -> segmentShape(CARD_ROWS - 1, CARD_ROWS)
}

private const val CARD_ROWS = 3

/**
 * A call row as one segment of its day's card ([shape]; null draws the row as it is): inset from the screen's edges,
 * with the same 2 dp gap between rows as the Settings groups. The swipe and its coloured background stay inside the
 * card's corners.
 */
@Composable
private fun RecentCard(shape: Shape?, place: ListSections.Place, content: @Composable () -> Unit) {
    if (shape == null) {
        content()
        return
    }
    Box(
        Modifier
            .padding(horizontal = Spacing.listInset)
            .padding(top = if (place.first) 0.dp else Spacing.xxs, bottom = if (place.last) Spacing.xs else 0.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

/** The row's title; a number (no name) stays left to right in right-to-left languages. */
private val RecentGroup.shownTitle: String
    get() = if (contact == null && cachedName.isNullOrBlank() && number.isNotBlank()) Bidi.ltr(title) else title

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
private fun RecentActionsSheet(vm: AppViewModel, recents: RecentsViewModel, g: RecentGroup, open: (Destination) -> Unit, onMessageOn: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    fun act(block: () -> Unit) { onDismiss(); block() }
    // "Remind me to call" takes this sheet's place with the fixed times.
    var remind by remember { mutableStateOf(false) }
    if (remind) {
        RemindToCallSheet(vm, g.number, g.contact?.displayName, g.latest.accountId, onDismiss)
        return
    }
    ParleySheet(onDismissRequest = onDismiss) {
        Text(
            g.shownTitle,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
        )
        val hasNumber = !g.hidden && g.number.isNotBlank()

        @Composable
        fun row(label: Int, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
            if (enabled) ListItem(
                headlineContent = { Text(stringResource(label)) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable(onClick = onClick),
            )
        }
        row(R.string.main_call, Icons.Rounded.Call, hasNumber) { act { vm.requestCall(g.number, g.contact?.displayName) } }
        row(R.string.recents_send_message, Icons.AutoMirrored.Rounded.Message, hasNumber) { act { Intents.sms(context, g.number) } }
        row(R.string.reach_message_or_call_on, Icons.AutoMirrored.Rounded.Chat, hasNumber) { act { onMessageOn(g.number) } }
        row(R.string.to_call_remind_me_to_call, Icons.Rounded.AlarmAdd, hasNumber) { remind = true }
        row(R.string.recents_edit_before_call, Icons.Rounded.Dialpad, hasNumber) {
            act { vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = g.number)) }
        }
        row(R.string.recents_copy_number, Icons.Rounded.ContentCopy, hasNumber) { act { Clipboard.copy(context, g.number) } }
        row(
            R.string.home_create_contact, Icons.Rounded.PersonAdd, hasNumber && g.contact == null && g.vaultId == null,
        ) { act { open(Routes.edit(phone = g.number)) } }
        row(
            R.string.recents_add_to_contact, Icons.Rounded.PersonAdd, hasNumber && g.contact == null && g.vaultId == null,
        ) { act { open(Routes.pick(g.number)) } }
        // The same Block as everywhere (a question, then Undo), and Unblock once it is blocked.
        val blocked = hasNumber && rememberBlocked(vm, listOf(g.number))
        if (blocked) {
            row(R.string.recents_unblock_number, Icons.Rounded.RemoveModerator) { act { unblockWithUndo(vm, listOf(g.number), g.contact?.displayName) } }
        } else {
            row(R.string.recents_block_number, Icons.Rounded.Block, hasNumber) { act { askToBlock(listOf(g.number), g.contact?.displayName) } }
        }
        row(R.string.recents_select, Icons.Rounded.CheckCircle, true) { act { recents.selection.value = setOf(g.key) } }
        if (hasNumber) RecentBlockingActions(vm, g.number, g.contact?.displayName, g.latest.type == CallType.BLOCKED, onDismiss)
        row(R.string.recents_delete_from_history, Icons.Rounded.Delete) {
            act { recents.delete(g) { n, undo -> vm.offerUndo(res.getQuantityString(R.plurals.vm_calls_deleted, n, n), undo) } }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}
