package com.todocompanion.app.ui

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import com.todocompanion.app.domain.Modules
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.domain.view.GroupMode
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.SortMode
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.reminders.AlarmScheduler
import com.todocompanion.app.ui.components.AppDrawer
import com.todocompanion.app.ui.components.HourStepper
import com.todocompanion.app.domain.OmegaCommand
import com.todocompanion.app.ui.screens.CalendarScreen
import com.todocompanion.app.ui.screens.CommandPaletteDialog
import com.todocompanion.app.ui.screens.MatrixScreen
import com.todocompanion.app.ui.screens.QuickAddSheet
import com.todocompanion.app.ui.screens.RecapScreen
import com.todocompanion.app.ui.screens.SearchScreen
import com.todocompanion.app.ui.screens.SettingsScreen
import com.todocompanion.app.ui.screens.TaskDetailScreen
import com.todocompanion.app.ui.screens.TasksScreen
import com.todocompanion.app.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.time.ZoneId

private enum class Tab(val label: String, val icon: ImageVector) {
    TASKS("Tasks", Icons.AutoMirrored.Filled.FormatListBulleted),
    CALENDAR("Calendar", Icons.Filled.CalendarMonth),
    TIMELINE("Timeline", Icons.Filled.ViewTimeline),
    MATRIX("Matrix", Icons.Filled.GridView),
    HABITS("Habits", Icons.Filled.LocalFireDepartment),
    TIME("Time", Icons.Filled.Schedule),
    FOCUS("Focus", Icons.Filled.Timer),
    SEARCH("Search", Icons.Filled.Search),
    SETTINGS("Settings", Icons.Filled.Settings),
}

private data class NewReq(val isFolder: Boolean, val parentId: String?)

/** Compact, icon-only bottom navigation (TickTick-style) — shorter than the Material NavigationBar. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun CompactBottomBar(
    tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit,
    onReselect: (Tab) -> Unit = {}, onLongPressPrimary: () -> Unit = {},
) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { idx, t ->
                val selected = t == current
                // Tapping the active tab re-triggers it (Calendar → jump to today). Long-pressing the FIRST
                // tab jumps to the configured home shortcut (default Inbox).
                Box(
                    Modifier.weight(1f).fillMaxHeight().combinedClickable(
                        onClick = { if (selected) onReselect(t) else onSelect(t) },
                        onLongClick = if (idx == 0) ({ onLongPressPrimary() }) else null,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(t.icon, t.label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(25.dp))
                }
            }
        }
    }
}

/** A slim, persistent bar shown above the bottom nav on every tab while a timer runs (or is paused) —
 *  the running activity, a live-ticking clock, tap to open Time, plus reassign / pause / stop. This is
 *  the single global timer surface (the Time screen no longer duplicates it with an in-screen card). */
@Composable
private fun RunningTimerBar(vm: AppViewModel, onOpen: () -> Unit) {
    val entries by vm.timeEntries.collectAsState()
    val activities by vm.timeActivities.collectAsState()
    val paused by vm.pausedTrack.collectAsState()
    // Show EVERY running timer, not just the first — when overlapping timers are enabled each gets its
    // own row with its own live clock and stop button, so several parallel activities are all visible.
    val running = entries.filter { it.running }
    val paused0 = paused
    if (running.isEmpty() && paused0 == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running.size) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    var reassignFor by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        running.forEachIndexed { i, r ->
            val act = activities.firstOrNull { it.id == r.activityId }
            val c = act?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
            val secs = ((now - r.startMillis) / 1000).coerceAtLeast(0)
            androidx.compose.material3.Surface(color = c.copy(alpha = .16f), onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(10.dp))
                    // Tap the activity name to reassign — "start first, pick the activity later".
                    Box(Modifier.weight(1f)) {
                        Row(Modifier.clickable { reassignFor = r.id }, verticalAlignment = Alignment.CenterVertically) {
                            Text((act?.emoji?.plus(" ") ?: "") + (act?.name ?: "Tap to pick activity"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                            Icon(Icons.Filled.ArrowDropDown, "Reassign activity", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = reassignFor == r.id, onDismissRequest = { reassignFor = null }) {
                            activities.filter { !it.archived }.forEach { a ->
                                DropdownMenuItem(text = { Text((a.emoji?.plus(" ") ?: "") + a.name) }, onClick = { reassignFor = null; vm.reassignTimeEntry(r.id, a.id) })
                            }
                        }
                    }
                    Text("%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c)
                    // Pause is only unambiguous with a single running timer; with several overlapping,
                    // just offer stop per row.
                    if (running.size == 1) IconButton(onClick = { vm.pauseTracking() }) { Icon(Icons.Filled.Pause, "Pause", tint = c) }
                    IconButton(onClick = { vm.stopTimeEntry(r.id) }) { Icon(Icons.Filled.Stop, "Stop ${act?.name ?: "timer"}", tint = c) }
                }
            }
            if (i < running.lastIndex) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        }
        // Paused: no interval is running, but a resume is one tap away (the gap in between is a real,
        // honest untracked gap).
        if (running.isEmpty() && paused0 != null) {
            val pAct = activities.firstOrNull { it.id == paused0.first }
            val c = pAct?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Pause, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                    Text("Paused · " + (pAct?.emoji?.plus(" ") ?: "") + (pAct?.name ?: "activity"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    IconButton(onClick = { vm.clearPaused() }) { Icon(Icons.Filled.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { vm.resumeTracking() }) { Icon(Icons.Filled.PlayArrow, "Resume", tint = c) }
                }
            }
        }
    }
}

/**
 * A FAB that reliably supports both a tap and a long-press. A real [FloatingActionButton] wires its
 * own inner clickable Surface, which sits on top of any outer `combinedClickable` and swallows the
 * gesture — so `onLongClick` never fires (this was the "hold to add a past entry doesn't work" bug).
 * Here the FAB-styled Surface has NO onClick of its own; `combinedClickable` is the only gesture
 * handler, so tap and hold are both delivered.
 */
/** The compact undo pill (rounded, wrap-content) shown for the SnackbarHost's current message. */
@Composable
private fun UndoPill(data: androidx.compose.material3.SnackbarData) {
    androidx.compose.material3.Surface(
        Modifier.widthIn(max = 320.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
        tonalElevation = 3.dp, shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                    Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            if (data.visuals.withDismissAction) IconButton(onClick = { data.dismiss() }) {
                Icon(Icons.Filled.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DualFab(icon: ImageVector, contentDescription: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        tonalElevation = 6.dp,
        modifier = Modifier
            .size(56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, contentDescription) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppRoot(
    launchAction: MutableState<String?> = mutableStateOf(null),
    importUri: MutableState<android.net.Uri?> = mutableStateOf(null),
) {
    val vm: AppViewModel = viewModel()
    val settings by vm.settings.collectAsState()

    AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
      // R58 — provide the app-wide recent-colours host so every unified colour picker shares recents.
      val colorRecents = remember(settings.recentColors) { settings.recentColors.split(",").mapNotNull { it.trim().toLongOrNull() } }
      androidx.compose.runtime.CompositionLocalProvider(
        com.todocompanion.app.ui.components.LocalColorPickerHost provides com.todocompanion.app.ui.components.ColorPickerHost(colorRecents, vm::rememberRecentColor)
      ) {
      AppLockGate(enabled = settings.appLockEnabled) {
        val scope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        var tab by remember { mutableStateOf(Tab.TASKS) }
        // Focus is now a MODE of the Time hub, not a separate view — both are just "time". This flag
        // flips the Time tab between Track (activity timers) and Focus (the Pomodoro ring).
        var timeFocus by remember { mutableStateOf(false) }
        // Any legacy "go to Focus" navigation (FAB, habit focus, just-start, deep links) lands on the
        // Time hub in Focus mode, so there's one destination for time — never a stranded Focus tab.
        LaunchedEffect(tab) { if (tab == Tab.FOCUS) { timeFocus = true; tab = Tab.TIME } }
        // T0: land on the primary module's home once settings load (unless a default view / resume is set),
        // and never leave the user stranded on a disabled module's tab.
        var landedInitial by remember { mutableStateOf(false) }
        LaunchedEffect(settings.primaryModule, settings.disabledModules) {
            val primaryHomeTab = when (Modules.primary(settings)) {
                Modules.HABITS -> Tab.HABITS; Modules.TIME -> Tab.TIME; else -> Tab.TASKS
            }
            if (!landedInitial) {
                landedInitial = true
                if (settings.defaultViewRef.isBlank() && !settings.resumeLastView) tab = primaryHomeTab
            }
            val m = Modules.moduleOfTab(tab.name)
            if (m != null && !Modules.isEnabled(settings, m)) tab = primaryHomeTab
        }
        var editing by remember { mutableStateOf<String?>(null) }
        var showQuickAdd by remember { mutableStateOf(false) }
        // Where to return when Back is pressed inside an archive view (Trash / Completed / Won't-do): the
        // (tab, view) you opened it from — so Back goes back there instead of exiting the app (R19).
        var navReturn by remember { mutableStateOf<Pair<Tab, ViewRef>?>(null) }
        var quickAddDue by remember { mutableStateOf<Long?>(null) }
        var quickAddWithTime by remember { mutableStateOf(false) }
        var quickAddText by remember { mutableStateOf("") }
        var newReq by remember { mutableStateOf<NewReq?>(null) }
        var manageList by remember { mutableStateOf<ListEntity?>(null) }
        var manageFolder by remember { mutableStateOf<FolderEntity?>(null) }
        var moveList by remember { mutableStateOf<ListEntity?>(null) }
        var moveFolder by remember { mutableStateOf<FolderEntity?>(null) }
        var newTag by remember { mutableStateOf<NewTagReq?>(null) }
        var manageTag by remember { mutableStateOf<com.todocompanion.app.data.entity.TagEntity?>(null) }
        var moveTag by remember { mutableStateOf<com.todocompanion.app.data.entity.TagEntity?>(null) }
        var newCtx by remember { mutableStateOf<NewTagReq?>(null) }
        var manageCtx by remember { mutableStateOf<com.todocompanion.app.data.entity.ContextEntity?>(null) }
        var moveCtx by remember { mutableStateOf<com.todocompanion.app.data.entity.ContextEntity?>(null) }
        var newWs by remember { mutableStateOf(false) }
        var manageWs by remember { mutableStateOf<com.todocompanion.app.data.entity.WorkspaceEntity?>(null) }
        var filterEdit by remember { mutableStateOf<com.todocompanion.app.data.entity.FilterEntity?>(null) }
        var showStats by remember { mutableStateOf(false) }
        var showReview by remember { mutableStateOf(false) }
        var showMomentum by remember { mutableStateOf(false) }   // Q1
        var showTimeTracking by remember { mutableStateOf(false) }   // Tier S
        // E9: a backup file handed in by the file manager ("Open with"), awaiting a restore confirm.
        var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
        var importResult by remember { mutableStateOf<String?>(null) }
        var saveTab by remember { mutableStateOf(false) }
        var templatePicker by remember { mutableStateOf(false) }
        var showAttachments by remember { mutableStateOf(false) }
        var showCountdowns by remember { mutableStateOf(false) }
        // R48 — deep-link into Occasions (optionally opening a specific entry) from the calendar / lists.
        var countdownOpenId by remember { mutableStateOf<String?>(null) }
        val openOccasion: (String?) -> Unit = { id -> countdownOpenId = id; showCountdowns = true }
        var showDone by remember { mutableStateOf(false) }   // R27 The Done Record
        var showPlan by remember { mutableStateOf(false) }
        var showDayReview by remember { mutableStateOf<Long?>(null) }   // R66 end-of-day review (holds the epoch-day, null = closed)
        // Phase F — when opened via the "Close your day" shortcut / evening nudge, land straight in the close flow.
        var dayReviewStartClose by remember { mutableStateOf(false) }
        // Tier Ω: the command palette, the any-period recap overlay, and the annual-report picker.
        var showPalette by remember { mutableStateOf(false) }
        var recapRange by remember { mutableStateOf<Triple<Long, Long, String>?>(null) }
        var showAnnual by remember { mutableStateOf(false) }
        var showTimeStats by remember { mutableStateOf(false) }   // Time tab → Statistics overlay
        // G4 interactive time-blocking: which (day, minute) slot the user tapped on the calendar.
        var blockAt by remember { mutableStateOf<Pair<java.time.LocalDate, Int>?>(null) }
        var menu by remember { mutableStateOf(false) }
        // Hoisted per-tab controls, surfaced in the shared top bar to free screen space.
        var calMode by remember { mutableStateOf(settings.calendarDefaultMode) }
        // Calendar navigation state, hoisted so the combined header can live in the app-bar slot.
        var calAnchor by remember { mutableStateOf(java.time.LocalDate.now()) }
        var calSelected by remember { mutableStateOf(java.time.LocalDate.now()) }
        // R39 — the calendar header's events menu passes its choice to CalendarScreen, which owns the dialogs.
        var calEventAction by remember { mutableStateOf<String?>(null) }
        var matrixSettings by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var calFilter by remember { mutableStateOf(false) }
        // Timeline filter, surfaced as a compact top-bar dropdown (no space-hungry chip row).
        var timelineLists by remember { mutableStateOf(setOf<String>()) }
        var timelineShowDone by remember { mutableStateOf(false) }
        var timelineMenu by remember { mutableStateOf(false) }

        val currentView by vm.currentView.collectAsState()
        val lists by vm.lists.collectAsState()
        val folders by vm.folders.collectAsState()
        val tags by vm.tags.collectAsState()
        val contexts by vm.contexts.collectAsState()
        val flagsList by vm.flags.collectAsState()
        val filtersList by vm.filters.collectAsState()
        val outlineMode by vm.outlineMode.collectAsState()
        val boardModeTransient by vm.boardMode.collectAsState()
        // Per-list layout (A3): a real list remembers its Board/List choice; other views use the
        // transient toggle. The current list id, when the active view is a plain list.
        val currentListId = (currentView as? ViewRef.ListView)?.listId
        val boardMode = if (currentListId != null) currentListId in settings.boardLists else boardModeTransient

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // R81 — never ask for notifications at first launch. A brand-new user who wants no reminders is
            // never nagged. We request POST_NOTIFICATIONS only once the user turns on something that
            // actually needs it: a task reminder, the daily/evening/morning brief, an occasion notification,
            // a habit reminder time, or a calendar-event alert. (R71: the launch stays runCatching-guarded so
            // a launcher/registry hiccup can never take the app down.)
            val notifCtx = LocalContext.current
            val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val taskReminders by vm.reminders.collectAsState()
            val habitsForNotif by vm.habits.collectAsState()
            val eventsForNotif by vm.events.collectAsState()
            val needsNotif = settings.dailySummaryEnabled || settings.eveningReviewEnabled ||
                settings.morningBriefEnabled || settings.occasionLiveNotif || settings.occasionNudge ||
                taskReminders.isNotEmpty() ||
                habitsForNotif.any { it.reminderTimes.isNotBlank() } ||
                eventsForNotif.any { it.alertsMinutes.isNotBlank() }
            val askedNotif = remember { mutableStateOf(false) }
            LaunchedEffect(needsNotif) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    notifCtx, android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (needsNotif && !askedNotif.value && !granted) {
                    askedNotif.value = true
                    runCatching { perm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                }
            }
        }
        val context = LocalContext.current
        // R81 — keep the reminder notification channel in sync with the chosen sound (foreground path;
        // App.onCreate seeds it for background receivers).
        LaunchedEffect(settings.reminderSound) {
            com.todocompanion.app.reminders.Notifications.reminderSoundSpec = settings.reminderSound
            com.todocompanion.app.reminders.Notifications.ensureChannel(context)
        }
        // R37 · Port 5 — when "time reminders to my peak" is on, aim the daily brief at the learned
        // receptive hour instead of the fixed time.
        val receptiveHour by vm.receptiveHour.collectAsState()
        LaunchedEffect(settings.dailySummaryEnabled, settings.dailySummaryHour, settings.dailySummaryMinute, settings.receptivityTiming, receptiveHour) {
            if (settings.dailySummaryEnabled) {
                val hour = if (settings.receptivityTiming && receptiveHour != null) receptiveHour!! else settings.dailySummaryHour
                AlarmScheduler.scheduleDailySummary(context, hour, settings.dailySummaryMinute)
            } else AlarmScheduler.cancelDailySummary(context)
        }
        LaunchedEffect(settings.eveningReviewEnabled, settings.eveningReviewHour, settings.eveningReviewAdaptive) {
            // Phase F — route through the smart scheduler (skip-if-done + adaptive time); it cancels when off.
            if (settings.eveningReviewEnabled) vm.rescheduleEveningReview()
            else AlarmScheduler.cancelEveningReview(context)
        }
        // R46 Occasions — schedule/cancel the daily reflective nudge, and (re)post the ongoing "next
        // occasion" notification whenever the toggle or the occasions data changes (on-demand, no worker).
        LaunchedEffect(settings.occasionNudge, settings.occasionNudgeHour) {
            if (settings.occasionNudge) AlarmScheduler.scheduleOccasionNudge(context, settings.occasionNudgeHour)
            else AlarmScheduler.cancelOccasionNudge(context)
        }
        val occasionsForNotif by vm.countdowns.collectAsState()
        LaunchedEffect(settings.occasionLiveNotif, occasionsForNotif) { vm.refreshOccasionNotification() }
        LaunchedEffect(settings.autoBackupEnabled, settings.autoBackupHour, settings.autoBackupFolder) {
            if (settings.autoBackupEnabled && settings.autoBackupFolder.isNotBlank()) AlarmScheduler.scheduleAutoBackup(context, settings.autoBackupHour)
            else AlarmScheduler.cancelAutoBackup(context)
        }
        // U2: (re)schedule today's timebox → track prompts whenever the toggle is on.
        LaunchedEffect(settings.autoTrackPrompt) { vm.rescheduleTrackPrompts() }
        // U13: keep the per-activity launcher shortcuts fresh.
        LaunchedEffect(Unit) { vm.refreshTrackShortcuts() }
        // Account-free folder sync: reconcile once on launch when a sync folder is configured.
        LaunchedEffect(settings.syncEnabled, settings.syncFolder) {
            if (settings.syncEnabled && settings.syncFolder.isNotBlank()) vm.runSyncNow { _, _ -> }
        }

        val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
        LaunchedEffect(Unit) {
            vm.undoEvents.collect { e ->
                // Drop any prior snackbar so a fresh completion always re-shows Undo immediately,
                // and use a Long duration so the Undo action is easy to notice and hit.
                snackbar.currentSnackbarData?.dismiss()
                val res = snackbar.showSnackbar(e.message, actionLabel = "Undo", withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long)
                if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undo(e)
            }
        }

        fun openTask(id: String) { editing = id }
        fun goTasks() { tab = Tab.TASKS }
        fun openQuickAdd(due: Long?, withTime: Boolean = false) { quickAddDue = due; quickAddWithTime = withTime; quickAddText = ""; showQuickAdd = true }

        // One-shot launch action from the home-screen widget's "＋ Add" button.
        LaunchedEffect(launchAction.value) {
            val a = launchAction.value
            when {
                a == com.todocompanion.app.MainActivity.ACTION_QUICK_ADD -> { openQuickAdd(null); launchAction.value = null }
                a != null && a.startsWith(com.todocompanion.app.MainActivity.ACTION_QUICK_ADD_TEXT) -> {
                    quickAddText = a.removePrefix(com.todocompanion.app.MainActivity.ACTION_QUICK_ADD_TEXT)
                    quickAddDue = null; quickAddWithTime = false; showQuickAdd = true; goTasks(); launchAction.value = null
                }
                a != null && a.startsWith("open_task:") -> { openTask(a.removePrefix("open_task:")); launchAction.value = null }
                // R59 (Wave 2) — permission-free place reminder: an NFC/QR/shortcut arrival fires armed reminders.
                a != null && a.startsWith("arrive:") -> { vm.fireArrivalReminders(a.removePrefix("arrive:")); launchAction.value = null }
                a == "open_focus" -> { tab = Tab.FOCUS; launchAction.value = null }
                a == "open_habits" -> { tab = Tab.HABITS; launchAction.value = null }
                a == "open_countdowns" -> { showCountdowns = true; launchAction.value = null }
                a == "open_matrix" -> { tab = Tab.MATRIX; launchAction.value = null }
                a == "open_today" -> { vm.select(ViewRef.Smart(SmartKind.TODAY)); tab = Tab.TASKS; launchAction.value = null }
                a == "open_donext" -> { vm.select(ViewRef.Smart(SmartKind.DO_NEXT)); tab = Tab.TASKS; launchAction.value = null }
                a == "open_next7" -> { vm.select(ViewRef.Smart(SmartKind.NEXT7)); tab = Tab.TASKS; launchAction.value = null }
                a == "open_plan" -> { showPlan = true; launchAction.value = null }
                a == "open_momentum" -> { showMomentum = true; launchAction.value = null }
                a == "open_record" -> { showDone = true; launchAction.value = null }
                a == "open_dayreview" -> { dayReviewStartClose = false; showDayReview = java.time.LocalDate.now().toEpochDay(); launchAction.value = null }
                // Phase F — the "Close your day" shortcut / evening nudge opens today's review in the close flow.
                a == "open_close_day" -> { dayReviewStartClose = true; showDayReview = java.time.LocalDate.now().toEpochDay(); launchAction.value = null }
                a == "open_time" -> { showTimeTracking = true; launchAction.value = null }
                a == "open_calendar" -> { tab = Tab.CALENDAR; launchAction.value = null }
                a != null && a.startsWith(com.todocompanion.app.MainActivity.ACTION_TRACK_ACTIVITY) -> {
                    val id = a.removePrefix(com.todocompanion.app.MainActivity.ACTION_TRACK_ACTIVITY)
                    vm.startTimeTracking(id); showTimeTracking = true; launchAction.value = null
                }
                a != null && a.startsWith(com.todocompanion.app.MainActivity.ACTION_TRACK_NAME) -> {
                    val nm = a.removePrefix(com.todocompanion.app.MainActivity.ACTION_TRACK_NAME)
                    vm.startTimeTrackingByName(nm); showTimeTracking = true; launchAction.value = null
                }
                a != null && a.startsWith(com.todocompanion.app.MainActivity.ACTION_RUN_ROUTINE) -> {
                    val nm = a.removePrefix(com.todocompanion.app.MainActivity.ACTION_RUN_ROUTINE)
                    vm.runRoutineByName(nm); showTimeTracking = true; launchAction.value = null
                }
                a != null && a.startsWith("open_context:") -> { vm.select(ViewRef.ContextView(a.removePrefix("open_context:"))); tab = Tab.TASKS; launchAction.value = null }
            }
        }

        // E9: a backup opened from a file manager ("Open with → ToDo Companion") — confirm, then restore.
        LaunchedEffect(importUri.value) {
            importUri.value?.let { pendingImport = it; importUri.value = null }
        }
        pendingImport?.let { uri ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingImport = null },
                // O4: Merge combines this file with your data (keep-newest); Replace wipes and restores.
                confirmButton = { TextButton(onClick = {
                    val u = uri; pendingImport = null
                    vm.importFromIntent(u, merge = true) { _, msg -> importResult = msg }
                }) { Text("Merge") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
                        TextButton(onClick = {
                            val u = uri; pendingImport = null
                            vm.importFromIntent(u, merge = false) { _, msg -> importResult = msg }
                        }) { Text("Replace") }
                    }
                },
                title = { Text("Import backup") },
                text = { Text("Import from this file?\n\n• Merge — combine it with your current data, keeping the newer of any duplicates (great for moving between phones).\n• Replace — wipe everything and restore exactly this file.\n\nCSV/OPML files are always merged in.") },
            )
        }
        importResult?.let { msg ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { importResult = null },
                confirmButton = { TextButton(onClick = { importResult = null }) { Text("OK") } },
                title = { Text("Import") },
                text = { Text(msg) },
            )
        }

        // Back from a secondary tab returns to Tasks instead of exiting.
        BackHandler(enabled = tab != Tab.TASKS && editing == null && !showQuickAdd) { tab = Tab.TASKS }
        // Back from an archive view (Trash / Completed / Won't-do) returns to where you opened it from
        // (the last list / smart list / habits / time / search) instead of exiting. Composed after the
        // handler above so it wins while an archive view is showing.
        val inArchiveView = currentView.let { it is ViewRef.Smart && (it.kind == SmartKind.TRASH || it.kind == SmartKind.COMPLETED || it.kind == SmartKind.WONT_DO) }
        BackHandler(enabled = tab == Tab.TASKS && navReturn != null && inArchiveView && editing == null && !showQuickAdd) {
            val (t, v) = navReturn!!
            navReturn = null
            vm.select(v)
            tab = t
        }

        val title = when (tab) {
            Tab.TASKS -> when (val v = currentView) {
                is ViewRef.Smart -> com.todocompanion.app.domain.smartTitle(settings, v.kind)
                is ViewRef.ListView -> lists.firstOrNull { it.id == v.listId }?.name ?: "List"
                is ViewRef.FolderView -> folders.firstOrNull { it.id == v.folderId }?.name ?: "Folder"
                is ViewRef.TagView -> "#" + (tags.firstOrNull { it.id == v.tagId }?.name ?: "")
                is ViewRef.ContextView -> "@" + (contexts.firstOrNull { it.id == v.contextId }?.name ?: "")
                is ViewRef.FilterView -> filtersList.firstOrNull { it.id == v.filterId }?.name ?: "Filter"
            }
            else -> tab.label
        }
        val canOutline = tab == Tab.TASKS && currentView is ViewRef.ListView

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawer(
                    vm = vm,
                    onSelect = { v ->
                        // Opening an archive view (Trash / Completed / Won't-do)? Remember where we came
                        // from so Back returns there rather than dropping out of the app.
                        val archive = v is ViewRef.Smart && (v.kind == SmartKind.TRASH || v.kind == SmartKind.COMPLETED || v.kind == SmartKind.WONT_DO)
                        val alreadyThere = currentView.let { it is ViewRef.Smart && v is ViewRef.Smart && it.kind == v.kind }
                        navReturn = if (archive && !alreadyThere) (tab to currentView) else null
                        vm.select(v); goTasks(); scope.launch { drawerState.close() }
                    },
                    onSearch = { tab = Tab.SEARCH; scope.launch { drawerState.close() } },
                    onNewList = { parent -> newReq = NewReq(false, parent) },
                    onNewFolder = { parent -> newReq = NewReq(true, parent) },
                    onNewTaskInFolder = { fid -> vm.select(ViewRef.FolderView(fid)); goTasks(); scope.launch { drawerState.close() }; openQuickAdd(null) },
                    onManageList = { manageList = it },
                    onManageFolder = { manageFolder = it },
                    onMoveList = { moveList = it },
                    onMoveFolder = { moveFolder = it },
                    onNewTag = { parent -> newTag = NewTagReq(parent) },
                    onManageTag = { manageTag = it },
                    onMoveTag = { moveTag = it },
                    onNewContext = { parent -> newCtx = NewTagReq(parent) },
                    onManageContext = { manageCtx = it },
                    onMoveContext = { moveCtx = it },
                    onNewWorkspace = { newWs = true },
                    onManageWorkspace = { manageWs = it },
                    onEditFilter = { f -> filterEdit = f ?: com.todocompanion.app.data.entity.FilterEntity(id = java.util.UUID.randomUUID().toString(), name = "New filter", workspaceId = settings.activeWorkspaceId) },
                    onOpenStats = { showStats = true; scope.launch { drawerState.close() } },
                    onOpenReview = { showReview = true; scope.launch { drawerState.close() } },
                    onOpenSettings = { tab = Tab.SETTINGS; scope.launch { drawerState.close() } },
                    onOpenTab = { name -> runCatching { Tab.valueOf(name) }.getOrNull()?.let { tab = it }; scope.launch { drawerState.close() } },
                    onOpenTemplates = { templatePicker = true; scope.launch { drawerState.close() } },
                    onOpenAttachments = { showAttachments = true; scope.launch { drawerState.close() } },
                    onOpenCountdowns = { showCountdowns = true; scope.launch { drawerState.close() } },
                    onOpenDone = { showDone = true; scope.launch { drawerState.close() } },
                    onOpenMomentum = { showMomentum = true; scope.launch { drawerState.close() } },
                    onOpenTime = { showTimeTracking = true; scope.launch { drawerState.close() } },
                    onOpenRecap = { val t = java.time.LocalDate.now(); val ws = com.todocompanion.app.ui.screens.weekStartOf(t, settings.weekStart); recapRange = Triple(ws.toEpochDay(), t.toEpochDay(), "This week"); scope.launch { drawerState.close() } },
                    onOpenAnnual = { showAnnual = true; scope.launch { drawerState.close() } },
                )
            },
        ) {
          val appBg = appBackgroundBrush(settings.appBackground)
          Box(Modifier.fillMaxSize().then(if (appBg != null) Modifier.background(appBg) else Modifier)) {
            Scaffold(
                containerColor = if (appBg != null) Color.Transparent else MaterialTheme.colorScheme.background,
                // R31 #6 — when an app-background gradient makes the container transparent, Material can't
                // derive a content colour and LocalContentColor collapses to black, blacking out every
                // uncoloured Text/Icon in the tab body (worst on AMOLED). Pin it to onBackground so the
                // whole app stays legible under any theme + background combination.
                contentColor = if (appBg != null) MaterialTheme.colorScheme.onBackground
                    else androidx.compose.material3.contentColorFor(MaterialTheme.colorScheme.background),
                topBar = {
                    // The calendar's combined header (menu · period ▾ · today · type · filter) is
                    // rendered right here in the app-bar slot, so its insets, height and button
                    // placement match every other screen and the tab switch never shifts layout.
                    if (tab == Tab.CALENDAR) {
                        val firstDow = if (settings.weekStart in 1..7) java.time.DayOfWeek.of(settings.weekStart)
                            else java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek
                        com.todocompanion.app.ui.screens.CalHeader(
                            label = com.todocompanion.app.ui.screens.calLabel(calMode, calAnchor, firstDow),
                            anchor = calAnchor, showNav = calMode != "list",
                            onPrev = { calAnchor = com.todocompanion.app.ui.screens.calStep(calMode, calAnchor, -1) },
                            onNext = { calAnchor = com.todocompanion.app.ui.screens.calStep(calMode, calAnchor, 1) },
                            onToday = { calAnchor = java.time.LocalDate.now(); calSelected = java.time.LocalDate.now() },
                            onPickDate = { d -> calAnchor = d; calSelected = d },
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            mode = calMode, onModeChange = { calMode = it },
                            onOpenFilter = { calFilter = true }, filterActive = settings.calendarListFilter.isNotEmpty(),
                            showCompleted = settings.calendarShowCompleted,
                            onToggleShowCompleted = { vm.saveSettings(settings.copy(calendarShowCompleted = !settings.calendarShowCompleted)) },
                            onEventAction = { calEventAction = it },
                        )
                    } else if (tab == Tab.HABITS) {
                        // The Habits tab renders its actions here so it shows one header like the rest.
                        com.todocompanion.app.ui.screens.HabitsHeader(vm, onOpenDrawer = { scope.launch { drawerState.open() } })
                    } else TopAppBar(
                        windowInsets = androidx.compose.material3.TopAppBarDefaults.windowInsets,
                        expandedHeight = 52.dp,   // denser than the 64dp default, TickTick-like
                        title = {
                            if (tab == Tab.SEARCH) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Box(Modifier.weight(1f)) {
                                        if (searchQuery.isEmpty()) Text("Search tasks, habits, #tags, @contexts…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = searchQuery, onValueChange = { searchQuery = it }, singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            } else Text(title, maxLines = 1)
                        },
                        navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "Menu") } },
                        actions = {
                            // Ω1 — the command palette: one line to capture, navigate, act or ask. Always here.
                            IconButton(onClick = { showPalette = true }) { Icon(Icons.Filled.AutoAwesome, "Command palette") }
                            if (tab == Tab.TASKS) IconButton(onClick = { showPlan = true }) { Icon(Icons.Filled.Bolt, "Plan your day") }
                            if (tab == Tab.TASKS) IconButton(onClick = {
                                // On a real list, remember the choice for that list; elsewhere flip the transient toggle.
                                if (currentListId != null) vm.setBoardList(currentListId, !boardMode) else vm.boardMode.value = !boardMode
                            }) {
                                Icon(Icons.Filled.ViewColumn, if (boardMode) "List view" else "Board view", tint = if (boardMode) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            }
                            if (canOutline && !boardMode) IconButton(onClick = { vm.toggleOutline() }) {
                                Icon(if (outlineMode) Icons.AutoMirrored.Filled.FormatListBulleted else Icons.Filled.AccountTree, if (outlineMode) "List view" else "Outline view")
                            }
                            // Hierarchy-preserving output for filter/tag/context views (MLO outline filtering).
                            val canHierarchy = tab == Tab.TASKS && (currentView is ViewRef.FilterView || currentView is ViewRef.TagView || currentView is ViewRef.ContextView)
                            if (canHierarchy && !boardMode) {
                                val hier by vm.filterHierarchy.collectAsState()
                                IconButton(onClick = { vm.filterHierarchy.value = !hier }) {
                                    Icon(if (hier) Icons.AutoMirrored.Filled.FormatListBulleted else Icons.Filled.AccountTree, if (hier) "Flat list" else "Show in outline",
                                        tint = if (hier) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                }
                            }
                            // "Time available" planner — only on the Do-Next list.
                            if (tab == Tab.TASKS && (currentView as? ViewRef.Smart)?.kind == SmartKind.DO_NEXT && !boardMode) {
                                val avail by vm.timeAvailableMin.collectAsState()
                                var timeMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { timeMenu = true }) {
                                        Icon(Icons.Filled.Timer, "Time available", tint = if (avail != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                    }
                                    DropdownMenu(expanded = timeMenu, onDismissRequest = { timeMenu = false }) {
                                        Text("I HAVE…", Modifier.padding(14.dp, 8.dp, 14.dp, 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        listOf<Pair<Int?, String>>(null to "Any amount of time", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", 120 to "2 hours").forEach { (m, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                leadingIcon = { if (avail == m) Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp)) },
                                                onClick = { vm.timeAvailableMin.value = m; timeMenu = false },
                                            )
                                        }
                                    }
                                }
                                // "Energy right now" planner — pairs with time-available on the Do-Next list.
                                val energy by vm.energyAvailable.collectAsState()
                                var energyMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { energyMenu = true }) {
                                        Icon(Icons.Filled.BatteryChargingFull, "Energy right now", tint = if (energy != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                    }
                                    DropdownMenu(expanded = energyMenu, onDismissRequest = { energyMenu = false }) {
                                        Text("ENERGY RIGHT NOW", Modifier.padding(14.dp, 8.dp, 14.dp, 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        listOf<Pair<Int?, String>>(null to "Any energy", 1 to "Low — easy wins", 2 to "Medium", 3 to "High — deep work").forEach { (e, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                leadingIcon = { if (energy == e) Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp)) },
                                                onClick = { vm.energyAvailable.value = e; energyMenu = false },
                                            )
                                        }
                                    }
                                }
                            }
                            when (tab) {
                                Tab.TASKS -> {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Sort & group") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        Text("Group by", Modifier.padding(12.dp, 8.dp, 12.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
                                        listOf("None" to GroupMode.NONE, "Date" to GroupMode.DATE, "Priority" to GroupMode.PRIORITY, "Context" to GroupMode.CONTEXT, "Flag" to GroupMode.FLAG).forEach { (l, m) ->
                                            DropdownMenuItem(text = { Text(l) }, onClick = { vm.groupMode.value = m; menu = false })
                                        }
                                        Text("Sort by", Modifier.padding(12.dp, 8.dp, 12.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
                                        // R28 #2 — "Completed date" is offered in the Completed / Won't-Do views (sort finished
                                        // work by when it was actually done).
                                        val curView by vm.currentView.collectAsState()
                                        val doneView = (curView as? com.todocompanion.app.domain.view.ViewRef.Smart)?.kind.let {
                                            it == com.todocompanion.app.domain.view.SmartKind.COMPLETED || it == com.todocompanion.app.domain.view.SmartKind.WONT_DO
                                        }
                                        val sortOptions = buildList {
                                            add("Manual" to SortMode.MANUAL); add("Priority" to SortMode.PRIORITY)
                                            add("Due" to SortMode.DUE); add("Title" to SortMode.TITLE); add("Flag" to SortMode.FLAG)
                                            if (doneView) add("Completed date" to SortMode.COMPLETED)
                                        }
                                        sortOptions.forEach { (l, m) ->
                                            DropdownMenuItem(text = { Text(l) }, onClick = { vm.sortMode.value = m; menu = false })
                                        }
                                        androidx.compose.material3.HorizontalDivider()
                                        DropdownMenuItem(text = { Text("Save current view as tab") }, leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp)) }, onClick = { menu = false; saveTab = true })
                                        DropdownMenuItem(text = { Text("New from template…") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(20.dp)) }, onClick = { menu = false; templatePicker = true })
                                    }
                                }
                                Tab.TIMELINE -> {
                                    Box {
                                        IconButton(onClick = { timelineMenu = true }) {
                                            Icon(Icons.Filled.FilterList, "Filter timeline", tint = if (timelineLists.isEmpty() && !timelineShowDone) LocalContentColor.current else MaterialTheme.colorScheme.primary)
                                        }
                                        DropdownMenu(expanded = timelineMenu, onDismissRequest = { timelineMenu = false }) {
                                            Text("SHOW LISTS", Modifier.padding(14.dp, 8.dp, 14.dp, 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            DropdownMenuItem(text = { Text("All lists") }, leadingIcon = { if (timelineLists.isEmpty()) Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp)) }, onClick = { timelineLists = emptySet() })
                                            lists.filter { !it.archived }.forEach { l ->
                                                DropdownMenuItem(
                                                    text = { Text((l.emoji?.plus(" ") ?: "") + l.name) },
                                                    leadingIcon = { if (l.id in timelineLists) Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp)) },
                                                    onClick = { timelineLists = if (l.id in timelineLists) timelineLists - l.id else timelineLists + l.id },
                                                )
                                            }
                                            androidx.compose.material3.HorizontalDivider()
                                            DropdownMenuItem(text = { Text("Show completed") }, leadingIcon = { if (timelineShowDone) Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp)) }, onClick = { timelineShowDone = !timelineShowDone })
                                        }
                                    }
                                }
                                Tab.MATRIX -> IconButton(onClick = { matrixSettings = true }) { Icon(Icons.Filled.Tune, "Matrix settings") }
                                Tab.TIME -> IconButton(onClick = { showTimeStats = true }) { Icon(Icons.Filled.BarChart, "Statistics") }
                                Tab.SEARCH -> if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Close, "Clear") }
                                else -> {}
                            }
                        },
                    )
                },
                bottomBar = {
                    // While multi-selecting tasks, drop the nav bar entirely so the selection action bar can
                    // sit at the very bottom and cover that space (TickTick-style), rather than stacking above it.
                    val selecting by vm.selectionActive.collectAsState()
                    if (!(selecting && tab == Tab.TASKS)) {
                        // T0: gate tabs by module. A tab shows only if its module is enabled; the primary
                        // module's home tab is always shown (relaxing the old "Tasks always shown"); the rest
                        // still honour bottomTabsHidden.
                        val primaryHomeTab = when (Modules.primary(settings)) {
                            Modules.HABITS -> Tab.HABITS; Modules.TIME -> Tab.TIME; else -> Tab.TASKS
                        }
                        val visibleTabs = Tab.entries.filter { t ->
                            val m = Modules.moduleOfTab(t.name)
                            val moduleOk = m == null || Modules.isEnabled(settings, m)
                            // Focus folded into the Time hub — no longer its own bottom-bar destination.
                            t != Tab.FOCUS && moduleOk && (t == primaryHomeTab || t.name !in settings.bottomTabsHidden)
                        }
                        Column {
                            // Persistent running-timer bar — visible on every tab while a timer runs.
                            if (Modules.isEnabled(settings, Modules.TIME)) RunningTimerBar(vm, onOpen = { tab = Tab.TIME })
                            CompactBottomBar(
                                visibleTabs, tab, onSelect = { tab = it },
                                onReselect = { t ->
                                    // Re-tap the active Calendar tab → jump to today (mirrors the top-bar Today).
                                    if (t == Tab.CALENDAR) { calAnchor = java.time.LocalDate.now(); calSelected = java.time.LocalDate.now() }
                                },
                                onLongPressPrimary = {
                                    val ref = settings.navShortcutRef.ifBlank { "smart:INBOX" }
                                    com.todocompanion.app.domain.view.ViewTabs.viewOf(ref)?.let { vm.select(it); tab = Tab.TASKS }
                                },
                            )
                        }
                    }
                },
                // The undo pill is rendered inside the content overlay (below) so it can sit at the FAB's
                // level beside it, rather than in the Scaffold slot that floats it above the FAB.
                snackbarHost = {},
                floatingActionButtonPosition = when (settings.fabPosition) {
                    "center" -> androidx.compose.material3.FabPosition.Center
                    "start" -> androidx.compose.material3.FabPosition.Start
                    else -> androidx.compose.material3.FabPosition.End
                },
                floatingActionButton = {
                    val selecting by vm.selectionActive.collectAsState()
                    if ((tab == Tab.TASKS || tab == Tab.CALENDAR || tab == Tab.MATRIX) && !(tab == Tab.TASKS && selecting)) {
                        var fabMenu by remember { mutableStateOf(false) }
                        // On the calendar, a quick-add inherits the day you have selected (so a task added
                        // while looking at, say, the 14th is due the 14th, not undated).
                        val fabDue = { if (tab == Tab.CALENDAR) calSelected.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() else null }
                        Box {
                            // Tap adds; long-press opens quick actions (C1). DualFab so the long-press fires.
                            DualFab(
                                icon = Icons.Filled.Add,
                                contentDescription = "Add task",
                                onClick = { openQuickAdd(fabDue()) },
                                onLongClick = { fabMenu = true },
                            )
                            DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                                DropdownMenuItem(text = { Text("New task") }, leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)) }, onClick = { fabMenu = false; openQuickAdd(null) })
                                DropdownMenuItem(text = { Text("Plan my day") }, leadingIcon = { Icon(Icons.Filled.Bolt, null, modifier = Modifier.size(18.dp)) }, onClick = { fabMenu = false; showPlan = true })
                                DropdownMenuItem(text = { Text("Focus") }, leadingIcon = { Icon(Icons.Filled.Timer, null, modifier = Modifier.size(18.dp)) }, onClick = { fabMenu = false; tab = Tab.FOCUS })
                                DropdownMenuItem(text = { Text("Weekly review") }, leadingIcon = { Icon(Icons.Filled.EventRepeat, null, modifier = Modifier.size(18.dp)) }, onClick = { fabMenu = false; showReview = true })
                                DropdownMenuItem(text = { Text("Day review") }, leadingIcon = { Icon(Icons.Filled.WbSunny, null, modifier = Modifier.size(18.dp)) }, onClick = { fabMenu = false; showDayReview = java.time.LocalDate.now().toEpochDay() })
                            }
                        }
                    } else if (tab == Tab.HABITS) {
                        // Habits get the same quick-add FAB as tasks (the header add button is gone), so the
                        // "add" affordance sits in the same place across the app.
                        FloatingActionButton(onClick = { vm.habitQuickAddOpen.value = true }) {
                            Icon(Icons.Filled.Add, "New habit")
                        }
                    } else if (tab == Tab.TIME && !timeFocus) {
                        // Double-action FAB (R18/R19): a single tap starts a new timer straight away (smart
                        // pick); press-and-hold opens the "add a past entry" dialog. The common action
                        // (start now) is one tap, and back-dating is the deliberate long-press. Uses
                        // [DualFab] so the long-press actually fires (a real FAB's inner click swallowed it).
                        DualFab(
                            icon = Icons.Filled.Add,
                            contentDescription = "Start timer (hold to add a past entry)",
                            onClick = { if (!vm.startTimeTrackingSmart()) vm.addTimeEntryRequests.value++ },
                            onLongClick = { vm.addTimeEntryRequests.value++ },
                        )
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    // Undo pill: bottom of the content, on the side OPPOSITE the FAB and at its level, drawn
                    // on top of the tab content (zIndex) — so it sits beside the FAB, not floating above it.
                    androidx.compose.material3.SnackbarHost(
                        snackbar,
                        modifier = Modifier
                            .align(if (settings.fabPosition == "start") Alignment.BottomEnd else Alignment.BottomStart)
                            .zIndex(10f).padding(horizontal = 12.dp, vertical = 16.dp),
                    ) { data -> UndoPill(data) }
                    Crossfade(targetState = tab, animationSpec = tween(180), label = "tab") { t ->
                        when (t) {
                            Tab.TASKS -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                                ViewTabStrip(vm)
                                Box(Modifier.weight(1f)) {
                                    ListBackgroundLayer(vm)
                                    if (boardMode) com.todocompanion.app.ui.screens.KanbanScreen(vm, ::openTask) else TasksScreen(vm, ::openTask, onOpenOccasion = openOccasion)
                                }
                            }
                            Tab.SEARCH -> SearchScreen(vm, ::openTask, searchQuery,
                                onOpenHabit = { hid -> vm.habitDetailId.value = hid; tab = Tab.HABITS },
                                onOpenEvent = { eid -> calEventAction = "open:$eid"; tab = Tab.CALENDAR },
                                onOpenOccasion = openOccasion)
                            Tab.SETTINGS -> SettingsScreen(vm)
                            Tab.CALENDAR -> CalendarScreen(vm, ::openTask, calMode, { calMode = it },
                                calAnchor, calSelected, { calAnchor = it }, { calSelected = it },
                                onAddOnDate = { d ->
                                    openQuickAdd(d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                                }, onAddAt = { d, minute -> blockAt = d to minute },
                                eventAction = calEventAction, onEventActionConsumed = { calEventAction = null },
                                onOpenOccasion = openOccasion)
                            Tab.TIMELINE -> com.todocompanion.app.ui.screens.TimelineScreen(vm, ::openTask, selectedLists = timelineLists, showDone = timelineShowDone)
                            Tab.MATRIX -> MatrixScreen(vm, ::openTask, matrixSettings, { matrixSettings = false })
                            Tab.HABITS -> com.todocompanion.app.ui.screens.HabitsScreen(vm, onFocusHabit = { hid -> vm.pendingFocusHabitId.value = hid; timeFocus = true; tab = Tab.TIME })
                            Tab.TIME, Tab.FOCUS -> Column(Modifier.fillMaxSize()) {
                                // One Time hub, two modes — Track (activity timers) and Focus (the ring).
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
                                    listOf(false to "Track", true to "Focus").forEach { (isFocus, label) ->
                                        val sel = timeFocus == isFocus
                                        Box(
                                            Modifier.clip(RoundedCornerShape(20.dp))
                                                .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else Color.Transparent)
                                                .clickable { timeFocus = isFocus }.padding(horizontal = 20.dp, vertical = 7.dp),
                                        ) { Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                                if (timeFocus) com.todocompanion.app.ui.screens.FocusScreen(vm, onOpenStats = { showTimeStats = true })
                                else com.todocompanion.app.ui.screens.TimeTrackingScreen(vm, onBack = {}, embedded = true)
                            }
                        }
                    }
                }
            }
          }
        }

        editing?.let { id -> TaskDetailScreen(vm, id, onBack = { editing = null },
            onJustStart = { tid -> vm.pendingFocusTaskId.value = tid; editing = null; tab = Tab.FOCUS }) }

        // Habit analytics + editor: full-screen overlays (like the task editor) so each shows a single
        // top bar and Back returns to the Habits list, never the inbox.
        val habitDetail by vm.habitDetailId.collectAsState()
        habitDetail?.let { hid ->
            com.todocompanion.app.ui.screens.HabitDetailScreen(vm, hid,
                onBack = { vm.habitDetailId.value = null },
                onEdit = { h -> vm.habitEditor.value = HabitEditRequest(h); vm.habitDetailId.value = null })
        }
        val habitEdit by vm.habitEditor.collectAsState()
        habitEdit?.let { req ->
            com.todocompanion.app.ui.screens.HabitEditorScreen(vm, req.habit, onClose = { vm.habitEditor.value = null })
        }
        val habitTrends by vm.habitTrendsOpen.collectAsState()
        if (habitTrends) com.todocompanion.app.ui.screens.HabitTrendsScreen(vm, onBack = { vm.habitTrendsOpen.value = false })
        // R34 — the Life-Systems hub + its screens (values, scorecard, correlations, reviews, ledger, buddies).
        val lifeRoute by vm.lifeSystemsRoute.collectAsState()
        lifeRoute?.let { route ->
            com.todocompanion.app.ui.screens.LifeSystemsScreen(vm, route,
                onBack = { if (route == "hub") vm.lifeSystemsRoute.value = null else vm.lifeSystemsRoute.value = "hub" },
                onOpenHabit = { hid -> vm.lifeSystemsRoute.value = null; vm.habitDetailId.value = hid })
        }
        if (showStats) com.todocompanion.app.ui.screens.StatisticsScreen(vm, onBack = { showStats = false })
        if (showAttachments) com.todocompanion.app.ui.screens.AttachmentsScreen(vm, onOpenTask = { showAttachments = false; openTask(it) }, onBack = { showAttachments = false })
        if (showCountdowns) com.todocompanion.app.ui.screens.CountdownScreen(vm, onBack = { showCountdowns = false; countdownOpenId = null }, initialOpenId = countdownOpenId)
        if (showDone) com.todocompanion.app.ui.screens.DoneScreen(vm, onOpenTask = { showDone = false; openTask(it) }, onBack = { showDone = false })
        if (showPlan) com.todocompanion.app.ui.screens.PlanYourDayScreen(vm, onOpenTask = { showPlan = false; openTask(it) }, onBack = { showPlan = false })
        if (showReview) com.todocompanion.app.ui.screens.ReviewScreen(vm, onOpenTask = { showReview = false; openTask(it) }, onBack = { showReview = false })
        showDayReview?.let { d -> com.todocompanion.app.ui.screens.DayReviewScreen(vm, d, startInClose = dayReviewStartClose, onOpenTask = { showDayReview = null; dayReviewStartClose = false; openTask(it) }, onBack = { showDayReview = null; dayReviewStartClose = false }) }
        if (showMomentum) com.todocompanion.app.ui.screens.MomentumScreen(vm, onBack = { showMomentum = false })
        if (showTimeTracking) com.todocompanion.app.ui.screens.TimeTrackingScreen(vm, onBack = { showTimeTracking = false })
        if (showTimeStats) com.todocompanion.app.ui.screens.TimeStatsScreen(vm, onBack = { showTimeStats = false })


        // ── Tier Ω · command palette, recap overlay, annual-report picker ──────────────────────────
        if (showPalette) CommandPaletteDialog(vm, onDismiss = { showPalette = false }) { cmd ->
            val now = java.time.LocalDate.now()
            val td = now.toEpochDay()
            when (cmd) {
                is OmegaCommand.Command.Track -> {
                    vm.startTimeTrackingByName(cmd.activity)
                    if (Modules.isEnabled(settings, Modules.TIME)) tab = Tab.TIME
                    android.widget.Toast.makeText(context, "Tracking ${cmd.activity}", android.widget.Toast.LENGTH_SHORT).show()
                }
                is OmegaCommand.Command.Act -> when (cmd.action) {
                    OmegaCommand.Action.PLAN -> showPlan = true
                    OmegaCommand.Action.WEEKLY_REVIEW -> showReview = true
                    OmegaCommand.Action.MOMENTUM -> showMomentum = true
                    OmegaCommand.Action.STATS -> showStats = true
                    OmegaCommand.Action.ANNUAL_REPORT -> showAnnual = true
                    OmegaCommand.Action.RECAP_WEEK -> { val ws = com.todocompanion.app.ui.screens.weekStartOf(now, settings.weekStart); recapRange = Triple(ws.toEpochDay(), td, "This week") }
                    OmegaCommand.Action.RECAP_LAST_WEEK -> { val ws = com.todocompanion.app.ui.screens.weekStartOf(now, settings.weekStart); recapRange = Triple(ws.minusWeeks(1).toEpochDay(), ws.minusDays(1).toEpochDay(), "Last week") }
                    OmegaCommand.Action.RECAP_MONTH -> recapRange = Triple(now.withDayOfMonth(1).toEpochDay(), td, "This month")
                }
                is OmegaCommand.Command.Goto -> {
                    val t = cmd.target.trim()
                    val q = t.lowercase()
                    // R28 #5 — "setting <query>" jumps to Settings and pre-fills its search box.
                    if (q == "settings" || q.startsWith("settings:")) {
                        tab = Tab.SETTINGS
                        vm.settingsSearchQuery.value = t.substringAfter(':', "").trim()
                        return@CommandPaletteDialog
                    }
                    val tabByName = mapOf(
                        "tasks" to Tab.TASKS, "today" to Tab.TASKS, "calendar" to Tab.CALENDAR, "matrix" to Tab.MATRIX,
                        "timeline" to Tab.TIMELINE, "habits" to Tab.HABITS, "time" to Tab.TIME, "focus" to Tab.FOCUS,
                        "search" to Tab.SEARCH, "settings" to Tab.SETTINGS,
                    )
                    val smartByName = mapOf(
                        "do next" to SmartKind.DO_NEXT, "donext" to SmartKind.DO_NEXT, "next 7" to SmartKind.NEXT7,
                        "next7" to SmartKind.NEXT7, "next 7 days" to SmartKind.NEXT7, "today list" to SmartKind.TODAY,
                        "inbox" to SmartKind.INBOX, "scheduled" to SmartKind.SCHEDULED, "flagged" to SmartKind.FLAGGED,
                        "completed" to SmartKind.COMPLETED, "done list" to SmartKind.COMPLETED, "trash" to SmartKind.TRASH,
                        "goals" to SmartKind.GOALS, "waiting" to SmartKind.WAITING,
                    )
                    // R28 #5 — every hub/overlay screen is reachable from the palette, not just the bottom tabs.
                    val overlayByName: Map<String, () -> Unit> = mapOf(
                        "the record" to { showDone = true }, "record" to { showDone = true }, "done" to { showDone = true },
                        "countdowns" to { showCountdowns = true }, "countdown" to { showCountdowns = true },
                        "attachments" to { showAttachments = true }, "files" to { showAttachments = true },
                        "momentum" to { showMomentum = true }, "statistics" to { showStats = true }, "stats" to { showStats = true },
                        "weekly review" to { showReview = true }, "review" to { showReview = true },
                        "day review" to { showDayReview = java.time.LocalDate.now().toEpochDay() }, "day" to { showDayReview = java.time.LocalDate.now().toEpochDay() }, "today review" to { showDayReview = java.time.LocalDate.now().toEpochDay() },
                        "plan" to { showPlan = true }, "plan my day" to { showPlan = true },
                        "time stats" to { showTimeStats = true }, "time tracking" to { showTimeTracking = true },
                        // R41 — the calendar's own planner surfaces (auto-schedule, time-audit) from the palette.
                        "auto-schedule" to { tab = Tab.CALENDAR; calEventAction = "plan" }, "auto schedule" to { tab = Tab.CALENDAR; calEventAction = "plan" },
                        "calendar plan" to { tab = Tab.CALENDAR; calEventAction = "plan" }, "schedule my day" to { tab = Tab.CALENDAR; calEventAction = "plan" },
                        "time audit" to { tab = Tab.CALENDAR; calEventAction = "review" }, "calendar review" to { tab = Tab.CALENDAR; calEventAction = "review" },
                        "new event" to { tab = Tab.CALENDAR; calEventAction = "new" }, "add event" to { tab = Tab.CALENDAR; calEventAction = "new" },
                        "find a gap" to { tab = Tab.CALENDAR; calEventAction = "gap" }, "gap" to { tab = Tab.CALENDAR; calEventAction = "gap" },
                        "calendar" to { tab = Tab.CALENDAR },
                    )
                    val listMatch = lists.firstOrNull { !it.archived && it.name.equals(t, true) }
                    val tagMatch = tags.firstOrNull { it.name.equals(t, true) }
                    val ctxMatch = contexts.firstOrNull { it.name.equals(t, true) }
                    when {
                        tabByName.containsKey(q) -> { tab = tabByName.getValue(q); if (q == "today") vm.select(ViewRef.Smart(SmartKind.TODAY)) }
                        overlayByName.containsKey(q) -> overlayByName.getValue(q).invoke()
                        smartByName.containsKey(q) -> { vm.select(ViewRef.Smart(smartByName.getValue(q))); tab = Tab.TASKS }
                        listMatch != null -> { vm.select(ViewRef.ListView(listMatch.id)); tab = Tab.TASKS }
                        tagMatch != null -> { vm.select(ViewRef.TagView(tagMatch.id)); tab = Tab.TASKS }
                        ctxMatch != null -> { vm.select(ViewRef.ContextView(ctxMatch.id)); tab = Tab.TASKS }
                        else -> android.widget.Toast.makeText(context, "Couldn't find “$t”", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                is OmegaCommand.Command.Capture -> if (cmd.text.isNotBlank()) {
                    vm.smartCapture(cmd.text) { android.widget.Toast.makeText(context, "Added", android.widget.Toast.LENGTH_SHORT).show() }
                }
                is OmegaCommand.Command.Ask -> {}   // answered inline in the palette
            }
        }
        recapRange?.let { (s, e, t) -> RecapScreen(vm, s, e, t, onBack = { recapRange = null }) }
        if (showAnnual) {
            val yr = java.time.LocalDate.now().year
            AlertDialog(
                onDismissRequest = { showAnnual = false },
                title = { Text("Your year in review") },
                text = { Text("Build a private, on-device recap across tasks, habits and time — a self-contained page you can keep or share. Nothing leaves your phone.") },
                confirmButton = { TextButton(onClick = { showAnnual = false; vm.shareAnnualReport(yr) }) { Text("This year ($yr)") } },
                dismissButton = { TextButton(onClick = { showAnnual = false; vm.shareAnnualReport(yr - 1) }) { Text("Last year (${yr - 1})") } },
            )
        }
        // T0: one-time "what's your main use?" picker sets the primary module. All modules stay on.
        // R28 #4: wait for real settings to load first, else it flashes for a frame on every launch.
        val settingsLoaded by vm.settingsLoaded.collectAsState()
        if (settingsLoaded && !settings.onboardedModules) com.todocompanion.app.ui.screens.ModulePickerDialog(
            // CU2: start with only the chosen modules — the rest stay off until the user wants them.
            onPick = { primary, enabled -> vm.applyModulePreset(primary, enabled - primary) },
            onSkip = { vm.markModulesOnboarded() },
        )
        if (saveTab) {
            var tabName by remember { mutableStateOf(vm.currentTitle()) }
            AlertDialog(
                onDismissRequest = { saveTab = false },
                confirmButton = { TextButton(onClick = { vm.saveCurrentAsTab(tabName); saveTab = false }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { saveTab = false }) { Text("Cancel") } },
                title = { Text("Save view as tab") },
                text = { com.todocompanion.app.ui.components.AppTextField(tabName, { tabName = it }, singleLine = true, label = { Text("Tab name") }, modifier = Modifier.fillMaxWidth()) },
            )
        }
        if (templatePicker) {
            val templates by vm.templates.collectAsState()
            var renaming by remember { mutableStateOf<com.todocompanion.app.data.entity.TemplateEntity?>(null) }
            AlertDialog(
                onDismissRequest = { templatePicker = false },
                confirmButton = { TextButton(onClick = { templatePicker = false }) { Text("Close") } },
                title = { Text("Templates") },
                text = {
                    if (templates.isEmpty()) {
                        Text("No templates yet. Open a task and choose “Save as template” to create one.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                            Text("Tap a template to drop it into the current list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            templates.forEach { t ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(t.name, Modifier.weight(1f).clickable {
                                        templatePicker = false
                                        tab = Tab.TASKS
                                        vm.insertTemplateHere(t.id) { newId -> newId?.let { openTask(it) } }
                                    }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                    IconButton(onClick = { renaming = t }) { Icon(Icons.Filled.Edit, "Rename template") }
                                    IconButton(onClick = { vm.deleteTemplate(t.id) }) { Icon(Icons.Filled.Delete, "Delete template", tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                },
            )
            renaming?.let { t ->
                var nm by remember(t.id) { mutableStateOf(t.name) }
                AlertDialog(
                    onDismissRequest = { renaming = null },
                    confirmButton = { TextButton(onClick = { vm.renameTemplate(t.id, nm.trim()); renaming = null }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
                    title = { Text("Rename template") },
                    text = { com.todocompanion.app.ui.components.AppTextField(nm, { nm = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                )
            }
        }
        if (showQuickAdd) QuickAddSheet(vm, initialDue = quickAddDue, initialHasTime = quickAddWithTime, initialText = quickAddText, onDismiss = { showQuickAdd = false; quickAddDue = null; quickAddWithTime = false; quickAddText = "" })

        newReq?.let { req ->
            NewContainerDialog(req, folders, onDismiss = { newReq = null }) { name, isFolder, parentId ->
                if (isFolder) vm.createFolder(name, parentId) else vm.createList(name, parentId, null)
                newReq = null
            }
        }
        manageList?.let { stale ->
            // Resolve the freshest row from the live flow so incremental icon/colour/background
            // saves aren't clobbered when the final "Save" fires with a stale snapshot.
            val l = lists.firstOrNull { it.id == stale.id } ?: stale
            ManageListDialog(l, onDismiss = { manageList = null },
                onSave = { n, d -> vm.saveList((lists.firstOrNull { it.id == l.id } ?: l).copy(name = n, description = d)); manageList = null },
                onColor = { vm.saveList((lists.firstOrNull { it.id == l.id } ?: l).copy(colorArgb = it)) },
                onDelete = { vm.deleteList(l.id); if (currentView == ViewRef.ListView(l.id)) vm.select(ViewRef.Smart(SmartKind.TODAY)); manageList = null },
                onPickBackground = { vm.setListBackgroundFromUri(l.id, it) },
                onClearBackground = { vm.clearListBackground(l.id) },
                onArchive = { a -> vm.setListArchived(lists.firstOrNull { it.id == l.id } ?: l, a); if (a && currentView == ViewRef.ListView(l.id)) vm.select(ViewRef.Smart(SmartKind.TODAY)); manageList = null },
                onEmoji = { vm.saveList((lists.firstOrNull { it.id == l.id } ?: l).copy(emoji = it)) })
        }
        manageFolder?.let { stale ->
            val f = folders.firstOrNull { it.id == stale.id } ?: stale
            ManageFolderDialog(f, onDismiss = { manageFolder = null },
                onSave = { n, d -> vm.saveFolder((folders.firstOrNull { it.id == f.id } ?: f).copy(name = n.trim(), description = d)); manageFolder = null },
                onIcon = { vm.setFolderIcon(f, it) },
                onColor = { vm.saveFolder((folders.firstOrNull { it.id == f.id } ?: f).copy(colorArgb = it)) },
                onArchive = { a -> vm.setFolderArchived(folders.firstOrNull { it.id == f.id } ?: f, a); if (a && currentView == ViewRef.FolderView(f.id)) vm.select(ViewRef.Smart(SmartKind.TODAY)); manageFolder = null },
                onDelete = { vm.deleteFolder(f.id); manageFolder = null })
        }
        moveList?.let { l ->
            FolderPickerDialog("Move list to", folders, exclude = emptySet(), onDismiss = { moveList = null }) { target ->
                vm.moveListToFolder(l.id, target); moveList = null
            }
        }
        moveFolder?.let { f ->
            FolderPickerDialog("Move folder to", folders, exclude = descendantsOf(f.id, folders) + f.id, onDismiss = { moveFolder = null }) { target ->
                vm.moveFolderToParent(f.id, target); moveFolder = null
            }
        }
        newTag?.let { req ->
            TextEntryDialog(title = if (req.parentId == null) "New tag" else "New sub-tag", placeholder = "Tag name", onDismiss = { newTag = null }) { name ->
                vm.createTag(name, req.parentId); newTag = null
            }
        }
        manageTag?.let { t ->
            ManageTagDialog(t, onDismiss = { manageTag = null },
                onRename = { vm.renameTag(t, it); manageTag = null },
                onColor = { vm.setTagColor(t, it) },
                onDelete = { vm.deleteTag(t); manageTag = null })
        }
        moveTag?.let { t ->
            TagPickerDialog("Move tag to", tags, exclude = tagDescendantsOf(t.id, tags) + t.id, onDismiss = { moveTag = null }) { target ->
                vm.moveTagToParent(t.id, target); moveTag = null
            }
        }
        newCtx?.let { req ->
            TextEntryDialog(title = if (req.parentId == null) "New context" else "New sub-context", placeholder = "Context name", onDismiss = { newCtx = null }) { name ->
                vm.createContext(name, req.parentId); newCtx = null
            }
        }
        manageCtx?.let { c ->
            ManageContextDialog(c, onDismiss = { manageCtx = null },
                onRename = { vm.renameContext(c, it); manageCtx = null },
                onColor = { vm.setContextColor(c, it) },
                onActive = { vm.setContextActive(c, it) },
                onHours = { vm.setContextHours(c, it) },
                onDelete = { vm.deleteContext(c); manageCtx = null })
        }
        moveCtx?.let { c ->
            ContextPickerDialog("Move context to", contexts, exclude = ctxDescendantsOf(c.id, contexts) + c.id, onDismiss = { moveCtx = null }) { target ->
                vm.moveContextToParent(c.id, target); moveCtx = null
            }
        }
        if (calFilter) {
            CalendarFilterDialog(lists.filter { !it.archived }, folders, settings.calendarListFilter, onDismiss = { calFilter = false }) { sel ->
                vm.saveSettings(settings.copy(calendarListFilter = sel))
            }
        }
        if (newWs) {
            TextEntryDialog(title = "New workspace", placeholder = "Workspace name", onDismiss = { newWs = false }) { name ->
                vm.createWorkspace(name); newWs = false
            }
        }
        manageWs?.let { w ->
            ManageWorkspaceDialog(w, onDismiss = { manageWs = null },
                onRename = { vm.renameWorkspace(w, it); manageWs = null },
                onDelete = { vm.deleteWorkspace(w.id); manageWs = null })
        }
        filterEdit?.let { f ->
            FilterBuilderDialog(f, lists.filter { !it.archived }, folders, tags, contexts, flagsList,
                onDismiss = { filterEdit = null },
                onDelete = { vm.deleteFilter(f); filterEdit = null },
                onSave = { updated -> vm.saveFilter(updated); vm.select(ViewRef.FilterView(updated.id)); tab = Tab.TASKS; filterEdit = null })
        }

        // Time-block chooser (G4): tapping an empty calendar slot places a task there.
        blockAt?.let { (day, minute) ->
            val atMillis = day.atStartOfDay(ZoneId.systemDefault()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
            val candidates = remember(blockAt) { vm.unscheduledForBlocking(12) }
            AlertDialog(
                onDismissRequest = { blockAt = null },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { blockAt = null }) { Text("Cancel") } },
                title = { Text("Block ${"%02d:%02d".format(minute / 60, minute % 60)}") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.TextButton(onClick = { blockAt = null; openQuickAdd(atMillis, withTime = true) }) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New task here")
                        }
                        // Time-block a tracking activity right here (R19 #1): logs a 30-min entry at this
                        // slot for the chosen activity (drag/edit it afterwards). Time module only.
                        val timeActs by vm.timeActivities.collectAsState()
                        val blockActs = if (Modules.isEnabled(settings, Modules.TIME)) timeActs.filter { !it.archived } else emptyList()
                        if (blockActs.isNotEmpty()) {
                            androidx.compose.material3.HorizontalDivider()
                            Text("Or track an activity here:", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                blockActs.forEach { a ->
                                    val ac = a.colorArgb?.let { androidx.compose.ui.graphics.Color(it) } ?: MaterialTheme.colorScheme.primary
                                    androidx.compose.material3.AssistChip(
                                        onClick = { vm.addManualTimeEntry(a.id, atMillis, atMillis + 30 * 60_000L); blockAt = null },
                                        label = { Text((a.emoji?.plus(" ") ?: "") + a.name, maxLines = 1) },
                                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(ac)) },
                                    )
                                }
                            }
                        }
                        if (candidates.isNotEmpty()) {
                            androidx.compose.material3.HorizontalDivider()
                            Text("Or schedule an unplanned one:", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                            androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                                candidates.forEach { t ->
                                    Text(t.title.ifBlank { "Untitled" },
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                            .clickable { vm.scheduleTaskAt(t.id, atMillis); blockAt = null }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                },
            )
        }

        // First-run tour (F1) — drawn last so it overlays everything until dismissed.
        if (!settings.onboarded) Onboarding(onDone = { vm.markOnboarded() })
      }
      }  // CompositionLocalProvider (colour-picker host)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBuilderDialog(
    filter: com.todocompanion.app.data.entity.FilterEntity,
    lists: List<ListEntity>, folders: List<FolderEntity>, tags: List<com.todocompanion.app.data.entity.TagEntity>, contexts: List<com.todocompanion.app.data.entity.ContextEntity>,
    flags: List<com.todocompanion.app.data.entity.FlagEntity>,
    onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (com.todocompanion.app.data.entity.FilterEntity) -> Unit,
) {
    val q0 = com.todocompanion.app.domain.view.Filters.parse(filter.queryJson)
    var name by remember { mutableStateOf(filter.name) }
    var matchAll by remember { mutableStateOf(q0.matchAll) }
    var listIds by remember { mutableStateOf(q0.listIds) }
    var folderIds by remember { mutableStateOf(q0.folderIds) }
    var tagIds by remember { mutableStateOf(q0.tagIds) }
    var ctxIds by remember { mutableStateOf(q0.contextIds) }
    var levels by remember { mutableStateOf(q0.levels) }
    var flagged by remember { mutableStateOf(q0.flaggedOnly) }
    var starred by remember { mutableStateOf(q0.starredOnly) }
    var flagIds by remember { mutableStateOf(q0.flagIds) }
    var dueWithin by remember { mutableStateOf(q0.dueWithinDays) }
    var maxDur by remember { mutableStateOf(q0.maxDurationMin) }
    var recurring by remember { mutableStateOf(q0.recurring) }        // null=any, true=recurring, false=one-off
    var recurFreqs by remember { mutableStateOf(q0.recurFreqs) }
    var inclChildren by remember { mutableStateOf(q0.includeChildren) }

    fun save() {
        val q = com.todocompanion.app.domain.view.FilterQuery(
            matchAll = matchAll, listIds = listIds, folderIds = folderIds, tagIds = tagIds, contextIds = ctxIds, levels = levels,
            flaggedOnly = flagged, starredOnly = starred, flagIds = flagIds,
            dueWithinDays = dueWithin, maxDurationMin = maxDur,
            // A frequency choice implies "recurring"; keep them consistent so the two controls never fight.
            recurring = if (recurFreqs.isNotEmpty()) true else recurring, recurFreqs = recurFreqs,
            includeCompleted = false, includeChildren = inclChildren,
        )
        onSave(filter.copy(name = name.trim().ifBlank { "Filter" }, queryJson = com.todocompanion.app.domain.view.Filters.encode(q)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { save() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        title = { Text("Filter") },
        text = {
            androidx.compose.foundation.rememberScrollState().let { sc ->
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(sc)) {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Match", Modifier.padding(end = 8.dp))
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(selected = matchAll, onClick = { matchAll = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("All") }
                        SegmentedButton(selected = !matchAll, onClick = { matchAll = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Any") }
                    }
                }
                if (folders.isNotEmpty()) FilterGroup("Folders") {
                    folders.sortedBy { it.sortOrder }.forEach { fo ->
                        FilterChip(selected = fo.id in folderIds, onClick = { folderIds = if (fo.id in folderIds) folderIds - fo.id else folderIds + fo.id },
                            label = { Text((fo.icon?.plus(" ") ?: "") + fo.name) })
                    }
                }
                FilterGroup("Lists") {
                    lists.forEach { l -> FilterChip(selected = l.id in listIds, onClick = { listIds = if (l.id in listIds) listIds - l.id else listIds + l.id }, label = { Text(l.name) }) }
                }
                if (tags.isNotEmpty()) FilterGroup("Tags") {
                    tags.forEach { t -> FilterChip(selected = t.id in tagIds, onClick = { tagIds = if (t.id in tagIds) tagIds - t.id else tagIds + t.id }, label = { Text("#" + t.name) }) }
                }
                if (contexts.isNotEmpty()) FilterGroup("Contexts") {
                    contexts.forEach { c -> FilterChip(selected = c.id in ctxIds, onClick = { ctxIds = if (c.id in ctxIds) ctxIds - c.id else ctxIds + c.id }, label = { Text("@" + c.name) }) }
                }
                if (flags.isNotEmpty()) FilterGroup("Flags") {
                    flags.forEach { fl -> FilterChip(selected = fl.id in flagIds, onClick = { flagIds = if (fl.id in flagIds) flagIds - fl.id else flagIds + fl.id }, label = { Text(fl.name) }) }
                }
                FilterGroup("Priority") {
                    listOf("HIGH" to "High", "MEDIUM" to "Medium", "LOW" to "Low", "NONE" to "None").forEach { (k, l) ->
                        FilterChip(selected = k in levels, onClick = { levels = if (k in levels) levels - k else levels + k }, label = { Text(l) })
                    }
                }
                FilterGroup("Due within") {
                    listOf<Pair<Int?, String>>(null to "Any", 0 to "Today", 7 to "7 days", 30 to "30 days").forEach { (d, l) ->
                        FilterChip(selected = dueWithin == d, onClick = { dueWithin = d }, label = { Text(l) })
                    }
                }
                FilterGroup("Time available") {
                    listOf<Pair<Int?, String>>(null to "Any", 15 to "≤15 min", 30 to "≤30 min", 60 to "≤1 h").forEach { (m, l) ->
                        FilterChip(selected = maxDur == m, onClick = { maxDur = m }, label = { Text(l) })
                    }
                }
                // Recurrence: first the is-recurring gate, then (when recurring) which frequencies to include.
                FilterGroup("Repeat") {
                    listOf<Pair<Boolean?, String>>(null to "Any", true to "Recurring", false to "One-off").forEach { (r, l) ->
                        FilterChip(selected = recurring == r, onClick = {
                            recurring = r
                            if (r != true) recurFreqs = emptySet()   // frequencies only apply to recurring tasks
                        }, label = { Text(l) })
                    }
                }
                if (recurring != false) FilterGroup("Repeats every") {
                    listOf("DAILY" to "Daily", "WEEKDAYS" to "Weekdays", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "YEARLY" to "Yearly").forEach { (k, l) ->
                        FilterChip(selected = k in recurFreqs, onClick = {
                            recurFreqs = if (k in recurFreqs) recurFreqs - k else recurFreqs + k
                            if (recurFreqs.isNotEmpty()) recurring = true
                        }, label = { Text(l) })
                    }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Has any flag", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = flagged, onCheckedChange = { flagged = it })
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Starred only", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = starred, onCheckedChange = { starred = it })
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Include subtasks of matches", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = inclChildren, onCheckedChange = { inclChildren = it })
                }
            }
            }
        },
    )
}

/** A subtle whole-app background tint (Settings → Appearance). Returns null for "none". The tint
 *  lerps from the theme background, so it adapts to light/dark automatically and stays gentle. */
@Composable
private fun appBackgroundBrush(name: String): androidx.compose.ui.graphics.Brush? {
    val bg = MaterialTheme.colorScheme.background
    val tint = when (name) {
        "warm" -> Color(0xFFF59E0B)
        "cool" -> Color(0xFF3E7BFA)
        "mint" -> Color(0xFF12A594)
        "dusk" -> Color(0xFF8B5CF6)
        "rose" -> Color(0xFFEC4899)
        else -> return null
    }
    return androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(androidx.compose.ui.graphics.lerp(bg, tint, 0.12f), bg, androidx.compose.ui.graphics.lerp(bg, tint, 0.05f)),
    )
}

/** Faint per-list background image, drawn behind the task list when a list with one is open. */
@Composable
private fun ListBackgroundLayer(vm: AppViewModel) {
    val view by vm.currentView.collectAsState()
    val lists by vm.lists.collectAsState()
    val listId = (view as? ViewRef.ListView)?.listId ?: return
    val b64 = lists.firstOrNull { it.id == listId }?.backgroundBase64 ?: return
    val img = remember(b64) {
        runCatching {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    } ?: return
    androidx.compose.foundation.Image(
        bitmap = img, contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        alpha = 0.18f,
    )
}

/** Saved view-tab strip (MLO tabs): tap to restore a whole view state, long-press to manage. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ViewTabStrip(vm: AppViewModel) {
    val tabs by vm.viewTabs.collectAsState()
    val current by vm.currentView.collectAsState()
    if (tabs.isEmpty()) return
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<com.todocompanion.app.domain.view.ViewTab?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        tabs.forEach { t ->
            val active = com.todocompanion.app.domain.view.ViewTabs.viewOf(t.ref) == current
            Box {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.combinedClickable(onClick = { vm.applyTab(t) }, onLongClick = { menuFor = t.id }),
                ) {
                    Text(t.name, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuFor == t.id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { renaming = t; menuFor = null })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { vm.deleteTab(t.id); menuFor = null })
                }
            }
        }
    }
    renaming?.let { t ->
        var nm by remember { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = { TextButton(onClick = { vm.renameTab(t.id, nm); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            title = { Text("Rename tab") },
            text = { com.todocompanion.app.ui.components.AppTextField(nm, { nm = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(label: String, content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    Spacer(Modifier.size(10.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CalendarFilterDialog(lists: List<ListEntity>, folders: List<FolderEntity>, selected: Set<String>, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit) {
    // R23: same folders-then-lists grammar as the sidebar & the move picker — folders (📁) group their
    // lists, ungrouped lists sit below. Selecting a folder shows everything under it. Multi-select.
    @Composable
    fun row(label: String, checked: Boolean, indent: Int, leading: @Composable () -> Unit, onToggle: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onToggle() }
                .padding(start = (8 + indent * 18).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onApply(emptySet()) }) { Text("Show all") } },
        title = { Text("Filter calendar") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                row("All folders & lists", selected.isEmpty(), 0, { Icon(Icons.Filled.FilterList, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }) { onApply(emptySet()) }
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                folders.filter { it.parentId == null }.sortedBy { it.sortOrder }.forEach { f ->
                    row(f.name, f.id in selected, 0, { Text(f.icon ?: "📁", style = MaterialTheme.typography.bodyLarge) }) {
                        onApply(if (f.id in selected) selected - f.id else selected + f.id)
                    }
                    lists.filter { it.folderId == f.id }.sortedBy { it.sortOrder }.forEach { l ->
                        row(l.name, l.id in selected, 1, { Box(Modifier.size(12.dp).clip(CircleShape).background(l.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary)) }) {
                            onApply(if (l.id in selected) selected - l.id else selected + l.id)
                        }
                    }
                }
                lists.filter { it.folderId == null && it.parentListId == null }.sortedBy { it.sortOrder }.forEach { l ->
                    row(l.name, l.id in selected, 0, { Box(Modifier.size(12.dp).clip(CircleShape).background(l.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary)) }) {
                        onApply(if (l.id in selected) selected - l.id else selected + l.id)
                    }
                }
            }
        },
    )
}

@Composable
private fun ManageWorkspaceDialog(w: com.todocompanion.app.data.entity.WorkspaceEntity, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(w.name) }
    val isDefault = w.id == com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name.trim()) }) { Text("Save") } },
        dismissButton = {
            if (!isDefault) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Workspace") },
        text = {
            Column {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!isDefault) Text("Deleting moves its lists & folders back to the default workspace.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
    )
}

private fun ctxDescendantsOf(id: String, all: List<com.todocompanion.app.data.entity.ContextEntity>): Set<String> {
    val out = mutableSetOf<String>()
    var frontier = listOf(id)
    while (frontier.isNotEmpty()) {
        val next = all.filter { it.parentId in frontier }.map { it.id }
        out.addAll(next); frontier = next
    }
    return out
}

private data class NewTagReq(val parentId: String?)

private fun tagDescendantsOf(id: String, tags: List<com.todocompanion.app.data.entity.TagEntity>): Set<String> {
    val out = mutableSetOf<String>()
    var frontier = listOf(id)
    while (frontier.isNotEmpty()) {
        val next = tags.filter { it.parentId in frontier }.map { it.id }
        out.addAll(next); frontier = next
    }
    return out
}

private fun descendantsOf(id: String, folders: List<FolderEntity>): Set<String> {
    val out = mutableSetOf<String>()
    var frontier = listOf(id)
    while (frontier.isNotEmpty()) {
        val next = folders.filter { it.parentId in frontier }.map { it.id }
        out.addAll(next); frontier = next
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewContainerDialog(req: NewReq, folders: List<FolderEntity>, onDismiss: () -> Unit, onCreate: (String, Boolean, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var isFolder by remember { mutableStateOf(req.isFolder) }
    var parentId by remember { mutableStateOf(req.parentId) }
    var pick by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim(), isFolder, parentId) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New " + if (isFolder) "folder" else "list") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !isFolder, onClick = { isFolder = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("List") }
                    SegmentedButton(selected = isFolder, onClick = { isFolder = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Folder") }
                }
                Spacer(Modifier.size(10.dp))
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, placeholder = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(6.dp))
                Box {
                    TextButton(onClick = { pick = true }) { Text("Parent: " + (folders.firstOrNull { it.id == parentId }?.name ?: "Top level")) }
                    DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                        DropdownMenuItem(text = { Text("Top level") }, onClick = { parentId = null; pick = false })
                        folders.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { parentId = f.id; pick = false }) }
                    }
                }
            }
        },
    )
}

private val SWATCHES = listOf(0xFFE5484D, 0xFFF59E0B, 0xFF12A594, 0xFF3E7BFA, 0xFF8B5CF6, 0xFFEC4899, 0xFF64748B)

@Composable
private fun ManageListDialog(
    list: ListEntity, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onColor: (Long) -> Unit, onDelete: () -> Unit,
    onPickBackground: (android.net.Uri) -> Unit, onClearBackground: () -> Unit, onEmoji: (String?) -> Unit, onArchive: (Boolean) -> Unit = {},
) {
    var name by remember { mutableStateOf(list.name) }
    var description by remember { mutableStateOf(list.description) }
    var confirmDelete by remember { mutableStateOf(false) }
    // R45 — image pick via SystemPicker (classic Activity startActivityForResult, gallery ACTION_PICK).
    val bgCtxTop = androidx.compose.ui.platform.LocalContext.current
    if (confirmDelete) ConfirmDeleteDialog("list", list.name, onCancel = { confirmDelete = false }, onConfirm = { confirmDelete = false; onDelete() })
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), description.trim()) }) { Text("Save") } },
        dismissButton = {
            if (list.id != ListEntity.INBOX_ID) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Edit list") },
        text = {
            Column {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name") })
                Spacer(Modifier.size(8.dp))
                com.todocompanion.app.ui.components.AppTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2)
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.todocompanion.app.ui.components.AppColorPicker(current = list.colorArgb, onPick = { it?.let(onColor) }, allowNone = false)
                }
                Spacer(Modifier.size(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(6.dp))
                EmojiPicker(current = list.emoji, onPick = onEmoji)
                Spacer(Modifier.size(12.dp))
                Text("Background image", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val bgCtx = androidx.compose.ui.platform.LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { com.todocompanion.app.util.SystemPicker.galleryOne(onError = { android.widget.Toast.makeText(bgCtxTop, it, android.widget.Toast.LENGTH_LONG).show() }) { onPickBackground(it) } }) { Text(if (list.backgroundBase64 == null) "Set image" else "Change image") }
                    if (list.backgroundBase64 != null) TextButton(onClick = onClearBackground) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                if (list.id != ListEntity.INBOX_ID) {
                    Spacer(Modifier.size(4.dp))
                    TextButton(onClick = { onArchive(!list.archived) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Icon(if (list.archived) Icons.Filled.Unarchive else Icons.Filled.Archive, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp))
                        Text(if (list.archived) "Restore from archive" else "Archive this list")
                    }
                }
            }
        },
    )
}

/** Icon picker: a comprehensive categorised emoji grid plus a free-type field. */
@Composable
private fun EmojiPicker(current: String?, onPick: (String?) -> Unit) {
    com.todocompanion.app.ui.components.EmojiGridPicker(current = current, onPick = onPick)
}

/** A destructive-action confirmation. Deleting a list/folder is not undoable, so always ask first. */
@Composable
private fun ConfirmDeleteDialog(kind: String, name: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text("Delete $kind?") },
        text = { Text("“$name” and its contents will be removed. This can't be undone.") },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ManageFolderDialog(folder: FolderEntity, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onIcon: (String?) -> Unit, onColor: (Long?) -> Unit, onDelete: () -> Unit, onArchive: (Boolean) -> Unit = {}) {
    var name by remember { mutableStateOf(folder.name) }
    var description by remember { mutableStateOf(folder.description) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) ConfirmDeleteDialog("folder", folder.name, onCancel = { confirmDelete = false }, onConfirm = { confirmDelete = false; onDelete() })
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), description.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        title = { Text("Edit folder") },
        text = {
            Column {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name") })
                Spacer(Modifier.size(8.dp))
                com.todocompanion.app.ui.components.AppTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2)
                Spacer(Modifier.size(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(6.dp))
                EmojiPicker(current = folder.icon, onPick = onIcon)
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(10.dp))
                    com.todocompanion.app.ui.components.AppColorPicker(current = folder.colorArgb, onPick = onColor, allowNone = true)
                }
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = { onArchive(!folder.archived) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Icon(if (folder.archived) Icons.Filled.Unarchive else Icons.Filled.Archive, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp))
                    Text(if (folder.archived) "Restore from archive" else "Archive this folder (and its lists)")
                }
            }
        },
    )
}

@Composable
private fun FolderPickerDialog(title: String, folders: List<FolderEntity>, exclude: Set<String>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item { Text("Top level", Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp)) }
                items(folders.filter { it.id !in exclude }, key = { it.id }) { f ->
                    Text(f.name, Modifier.fillMaxWidth().clickable { onPick(f.id) }.padding(vertical = 12.dp))
                }
            }
        },
    )
}

@Composable
private fun TextEntryDialog(title: String, placeholder: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { com.todocompanion.app.ui.components.AppTextField(name, { name = it }, placeholder = { Text(placeholder) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
    )
}

@Composable
private fun ManageTagDialog(tag: com.todocompanion.app.data.entity.TagEntity, onDismiss: () -> Unit, onRename: (String) -> Unit, onColor: (Long?) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(tag.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        title = { Text("Tag") },
        text = {
            Column {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.todocompanion.app.ui.components.AppColorPicker(current = tag.colorArgb, onPick = { onColor(it) }, allowNone = true)
                }
            }
        },
    )
}

@Composable
private fun TagPickerDialog(title: String, tags: List<com.todocompanion.app.data.entity.TagEntity>, exclude: Set<String>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item { Text("Top level", Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp)) }
                items(tags.filter { it.id !in exclude }, key = { it.id }) { t ->
                    Text("#" + t.name, Modifier.fillMaxWidth().clickable { onPick(t.id) }.padding(vertical = 12.dp))
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManageContextDialog(
    ctx: com.todocompanion.app.data.entity.ContextEntity, onDismiss: () -> Unit,
    onRename: (String) -> Unit, onColor: (Long?) -> Unit, onActive: (Boolean) -> Unit, onHours: (String?) -> Unit, onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(ctx.name) }
    val oh0 = com.todocompanion.app.domain.context.ContextAvailability.parse(ctx.openHoursJson)
    var restricted by remember { mutableStateOf(oh0 != null) }
    var days by remember { mutableStateOf(oh0?.days ?: setOf(1, 2, 3, 4, 5)) }
    var startH by remember { mutableIntStateOf((oh0?.startMin ?: 540) / 60) }
    var endH by remember { mutableIntStateOf((oh0?.endMin ?: 1020) / 60) }
    fun persistHours() {
        onHours(if (restricted) com.todocompanion.app.domain.context.ContextAvailability.encode(
            com.todocompanion.app.domain.context.OpenHours(days, startH * 60, endH * 60)) else null)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        title = { Text("Context") },
        text = {
            Column {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.todocompanion.app.ui.components.AppColorPicker(current = ctx.colorArgb, onPick = { onColor(it) }, allowNone = true)
                }
                Spacer(Modifier.size(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Active", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = ctx.active, onCheckedChange = onActive)
                }
                Text("Tasks in an inactive or closed context drop out of Do-Next.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Available only on a schedule", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = restricted, onCheckedChange = { restricted = it; persistHours() })
                }
                if (restricted) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val labels = listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S")
                        labels.forEach { (d, l) ->
                            androidx.compose.material3.FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d; persistHours() }, label = { Text(l) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("From", Modifier.padding(end = 6.dp))
                        HourStepper(startH) { startH = it.coerceIn(0, endH); persistHours() }
                        Spacer(Modifier.size(10.dp))
                        Text("to", Modifier.padding(end = 6.dp))
                        HourStepper(endH) { endH = it.coerceIn(startH, 24); persistHours() }
                    }
                }
            }
        },
    )
}

@Composable
private fun ContextPickerDialog(title: String, all: List<com.todocompanion.app.data.entity.ContextEntity>, exclude: Set<String>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item { Text("Top level", Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp)) }
                items(all.filter { it.id !in exclude }, key = { it.id }) { c ->
                    Text("@" + c.name, Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(vertical = 12.dp))
                }
            }
        },
    )
}
