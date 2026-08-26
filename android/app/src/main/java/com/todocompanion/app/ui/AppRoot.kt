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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.AlertDialog
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
import com.todocompanion.app.ui.screens.CalendarScreen
import com.todocompanion.app.ui.screens.MatrixScreen
import com.todocompanion.app.ui.screens.QuickAddSheet
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
    FOCUS("Focus", Icons.Filled.Timer),
    SEARCH("Search", Icons.Filled.Search),
    SETTINGS("Settings", Icons.Filled.Settings),
}

private data class NewReq(val isFolder: Boolean, val parentId: String?)

/** Compact, icon-only bottom navigation (TickTick-style) — shorter than the Material NavigationBar. */
@Composable
private fun CompactBottomBar(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { t ->
                val selected = t == current
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t) }, contentAlignment = Alignment.Center) {
                    Icon(t.icon, t.label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(25.dp))
                }
            }
        }
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
      AppLockGate(enabled = settings.appLockEnabled) {
        val scope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        var tab by remember { mutableStateOf(Tab.TASKS) }
        var editing by remember { mutableStateOf<String?>(null) }
        var showQuickAdd by remember { mutableStateOf(false) }
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
        // E9: a backup file handed in by the file manager ("Open with"), awaiting a restore confirm.
        var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
        var importResult by remember { mutableStateOf<String?>(null) }
        var saveTab by remember { mutableStateOf(false) }
        var templatePicker by remember { mutableStateOf(false) }
        var showAttachments by remember { mutableStateOf(false) }
        var showCountdowns by remember { mutableStateOf(false) }
        var showPlan by remember { mutableStateOf(false) }
        // G4 interactive time-blocking: which (day, minute) slot the user tapped on the calendar.
        var blockAt by remember { mutableStateOf<Pair<java.time.LocalDate, Int>?>(null) }
        var menu by remember { mutableStateOf(false) }
        // Hoisted per-tab controls, surfaced in the shared top bar to free screen space.
        var calMode by remember { mutableStateOf(settings.calendarDefaultMode) }
        // Calendar navigation state, hoisted so the combined header can live in the app-bar slot.
        var calAnchor by remember { mutableStateOf(java.time.LocalDate.now()) }
        var calSelected by remember { mutableStateOf(java.time.LocalDate.now()) }
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
            val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) { perm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        val context = LocalContext.current
        LaunchedEffect(settings.dailySummaryEnabled, settings.dailySummaryHour, settings.dailySummaryMinute) {
            if (settings.dailySummaryEnabled) AlarmScheduler.scheduleDailySummary(context, settings.dailySummaryHour, settings.dailySummaryMinute)
            else AlarmScheduler.cancelDailySummary(context)
        }
        LaunchedEffect(settings.eveningReviewEnabled, settings.eveningReviewHour) {
            if (settings.eveningReviewEnabled) AlarmScheduler.scheduleEveningReview(context, settings.eveningReviewHour)
            else AlarmScheduler.cancelEveningReview(context)
        }
        LaunchedEffect(settings.autoBackupEnabled, settings.autoBackupHour, settings.autoBackupFolder) {
            if (settings.autoBackupEnabled && settings.autoBackupFolder.isNotBlank()) AlarmScheduler.scheduleAutoBackup(context, settings.autoBackupHour)
            else AlarmScheduler.cancelAutoBackup(context)
        }
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
                a == "open_focus" -> { tab = Tab.FOCUS; launchAction.value = null }
                a == "open_habits" -> { tab = Tab.HABITS; launchAction.value = null }
                a == "open_countdowns" -> { showCountdowns = true; launchAction.value = null }
                a == "open_matrix" -> { tab = Tab.MATRIX; launchAction.value = null }
                a == "open_donext" -> { vm.select(ViewRef.Smart(SmartKind.DO_NEXT)); tab = Tab.TASKS; launchAction.value = null }
                a == "open_next7" -> { vm.select(ViewRef.Smart(SmartKind.NEXT7)); tab = Tab.TASKS; launchAction.value = null }
                a == "open_plan" -> { showPlan = true; launchAction.value = null }
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

        val title = when (tab) {
            Tab.TASKS -> when (val v = currentView) {
                is ViewRef.Smart -> v.kind.title
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
                    onSelect = { v -> vm.select(v); goTasks(); scope.launch { drawerState.close() } },
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
                    onOpenMomentum = { showMomentum = true; scope.launch { drawerState.close() } },
                )
            },
        ) {
          val appBg = appBackgroundBrush(settings.appBackground)
          Box(Modifier.fillMaxSize().then(if (appBg != null) Modifier.background(appBg) else Modifier)) {
            Scaffold(
                containerColor = if (appBg != null) Color.Transparent else MaterialTheme.colorScheme.background,
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
                                        listOf("Manual" to SortMode.MANUAL, "Priority" to SortMode.PRIORITY, "Due" to SortMode.DUE, "Title" to SortMode.TITLE, "Flag" to SortMode.FLAG).forEach { (l, m) ->
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
                                Tab.SEARCH -> if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Close, "Clear") }
                                else -> {}
                            }
                        },
                    )
                },
                bottomBar = {
                    val visibleTabs = Tab.entries.filter { it == Tab.TASKS || it.name !in settings.bottomTabsHidden }
                    CompactBottomBar(visibleTabs, tab) { tab = it }
                },
                snackbarHost = {
                    androidx.compose.material3.SnackbarHost(snackbar) { data ->
                        // A rounded card that matches the app's own surface language — same shape, colour
                        // and accent as every other card/sheet, with a subtle outline so it reads as ours.
                        androidx.compose.material3.Surface(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
                            tonalElevation = 3.dp, shadowElevation = 8.dp,
                        ) {
                            Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(data.visuals.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                data.visuals.actionLabel?.let { label ->
                                    TextButton(onClick = { data.performAction() }) {
                                        Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                if (data.visuals.withDismissAction) IconButton(onClick = { data.dismiss() }) {
                                    Icon(Icons.Filled.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                },
                floatingActionButtonPosition = when (settings.fabPosition) {
                    "center" -> androidx.compose.material3.FabPosition.Center
                    "start" -> androidx.compose.material3.FabPosition.Start
                    else -> androidx.compose.material3.FabPosition.End
                },
                floatingActionButton = {
                    val selecting by vm.selectionActive.collectAsState()
                    if ((tab == Tab.TASKS || tab == Tab.CALENDAR || tab == Tab.MATRIX) && !(tab == Tab.TASKS && selecting)) {
                        var fabMenu by remember { mutableStateOf(false) }
                        Box {
                            // Tap adds; long-press opens quick actions (C1).
                            FloatingActionButton(onClick = { openQuickAdd(null) }, modifier = Modifier.combinedClickable(
                                onClick = { openQuickAdd(null) }, onLongClick = { fabMenu = true })) {
                                Icon(Icons.Filled.Add, "Add task")
                            }
                            DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                                DropdownMenuItem(text = { Text("New task") }, leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)) }, onClick = { fabMenu = false; openQuickAdd(null) })
                                DropdownMenuItem(text = { Text("Plan my day") }, leadingIcon = { Icon(Icons.Filled.Bolt, null, modifier = Modifier.size(18.dp)) }, onClick = { fabMenu = false; showPlan = true })
                                DropdownMenuItem(text = { Text("Focus") }, leadingIcon = { Icon(Icons.Filled.Timer, null, modifier = Modifier.size(18.dp)) }, onClick = { fabMenu = false; tab = Tab.FOCUS })
                                DropdownMenuItem(text = { Text("Weekly review") }, leadingIcon = { Icon(Icons.Filled.EventRepeat, null, modifier = Modifier.size(18.dp)) }, onClick = { fabMenu = false; showReview = true })
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    Crossfade(targetState = tab, animationSpec = tween(180), label = "tab") { t ->
                        when (t) {
                            Tab.TASKS -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                                ViewTabStrip(vm)
                                Box(Modifier.weight(1f)) {
                                    ListBackgroundLayer(vm)
                                    if (boardMode) com.todocompanion.app.ui.screens.KanbanScreen(vm, ::openTask) else TasksScreen(vm, ::openTask)
                                }
                            }
                            Tab.SEARCH -> SearchScreen(vm, ::openTask, searchQuery, onOpenHabit = { hid -> vm.habitDetailId.value = hid; tab = Tab.HABITS })
                            Tab.SETTINGS -> SettingsScreen(vm)
                            Tab.CALENDAR -> CalendarScreen(vm, ::openTask, calMode, { calMode = it },
                                calAnchor, calSelected, { calAnchor = it }, { calSelected = it },
                                onAddOnDate = { d ->
                                    openQuickAdd(d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                                }, onAddAt = { d, minute -> blockAt = d to minute })
                            Tab.TIMELINE -> com.todocompanion.app.ui.screens.TimelineScreen(vm, ::openTask, selectedLists = timelineLists, showDone = timelineShowDone)
                            Tab.MATRIX -> MatrixScreen(vm, ::openTask, matrixSettings, { matrixSettings = false })
                            Tab.HABITS -> com.todocompanion.app.ui.screens.HabitsScreen(vm, onFocusHabit = { hid -> vm.pendingFocusHabitId.value = hid; tab = Tab.FOCUS })
                            Tab.FOCUS -> com.todocompanion.app.ui.screens.FocusScreen(vm, onOpenStats = { showStats = true })
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
        if (showStats) com.todocompanion.app.ui.screens.StatisticsScreen(vm, onBack = { showStats = false })
        if (showAttachments) com.todocompanion.app.ui.screens.AttachmentsScreen(vm, onOpenTask = { showAttachments = false; openTask(it) }, onBack = { showAttachments = false })
        if (showCountdowns) com.todocompanion.app.ui.screens.CountdownScreen(vm, onBack = { showCountdowns = false })
        if (showPlan) com.todocompanion.app.ui.screens.PlanYourDayScreen(vm, onOpenTask = { showPlan = false; openTask(it) }, onBack = { showPlan = false })
        if (showReview) com.todocompanion.app.ui.screens.ReviewScreen(vm, onOpenTask = { showReview = false; openTask(it) }, onBack = { showReview = false })
        if (showMomentum) com.todocompanion.app.ui.screens.MomentumScreen(vm, onBack = { showMomentum = false })
        if (saveTab) {
            var tabName by remember { mutableStateOf(vm.currentTitle()) }
            AlertDialog(
                onDismissRequest = { saveTab = false },
                confirmButton = { TextButton(onClick = { vm.saveCurrentAsTab(tabName); saveTab = false }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { saveTab = false }) { Text("Cancel") } },
                title = { Text("Save view as tab") },
                text = { OutlinedTextField(tabName, { tabName = it }, singleLine = true, label = { Text("Tab name") }, modifier = Modifier.fillMaxWidth()) },
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
                    text = { OutlinedTextField(nm, { nm = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
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
                onEmoji = { vm.saveList((lists.firstOrNull { it.id == l.id } ?: l).copy(emoji = it)) })
        }
        manageFolder?.let { stale ->
            val f = folders.firstOrNull { it.id == stale.id } ?: stale
            ManageFolderDialog(f, onDismiss = { manageFolder = null },
                onSave = { n, d -> vm.saveFolder((folders.firstOrNull { it.id == f.id } ?: f).copy(name = n.trim(), description = d)); manageFolder = null },
                onIcon = { vm.setFolderIcon(f, it) },
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
            CalendarFilterDialog(lists.filter { !it.archived }, settings.calendarListFilter, onDismiss = { calFilter = false }) { sel ->
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
            FilterBuilderDialog(f, lists.filter { !it.archived }, tags, contexts, flagsList,
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBuilderDialog(
    filter: com.todocompanion.app.data.entity.FilterEntity,
    lists: List<ListEntity>, tags: List<com.todocompanion.app.data.entity.TagEntity>, contexts: List<com.todocompanion.app.data.entity.ContextEntity>,
    flags: List<com.todocompanion.app.data.entity.FlagEntity>,
    onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (com.todocompanion.app.data.entity.FilterEntity) -> Unit,
) {
    val q0 = com.todocompanion.app.domain.view.Filters.parse(filter.queryJson)
    var name by remember { mutableStateOf(filter.name) }
    var matchAll by remember { mutableStateOf(q0.matchAll) }
    var listIds by remember { mutableStateOf(q0.listIds) }
    var tagIds by remember { mutableStateOf(q0.tagIds) }
    var ctxIds by remember { mutableStateOf(q0.contextIds) }
    var levels by remember { mutableStateOf(q0.levels) }
    var flagged by remember { mutableStateOf(q0.flaggedOnly) }
    var starred by remember { mutableStateOf(q0.starredOnly) }
    var flagIds by remember { mutableStateOf(q0.flagIds) }
    var dueWithin by remember { mutableStateOf(q0.dueWithinDays) }
    var maxDur by remember { mutableStateOf(q0.maxDurationMin) }
    var inclChildren by remember { mutableStateOf(q0.includeChildren) }

    fun save() {
        val q = com.todocompanion.app.domain.view.FilterQuery(
            matchAll = matchAll, listIds = listIds, tagIds = tagIds, contextIds = ctxIds, levels = levels,
            flaggedOnly = flagged, starredOnly = starred, flagIds = flagIds,
            dueWithinDays = dueWithin, maxDurationMin = maxDur, includeCompleted = false, includeChildren = inclChildren,
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
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Match", Modifier.padding(end = 8.dp))
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(selected = matchAll, onClick = { matchAll = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("All") }
                        SegmentedButton(selected = !matchAll, onClick = { matchAll = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Any") }
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
            text = { OutlinedTextField(nm, { nm = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
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
private fun CalendarFilterDialog(lists: List<ListEntity>, selected: Set<String>, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onApply(emptySet()) }) { Text("Show all") } },
        title = { Text("Filter calendar") },
        text = {
            // Compact colour-coded chips instead of a loose checkbox list.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.FilterChip(selected = selected.isEmpty(), onClick = { onApply(emptySet()) }, label = { Text("All lists") })
                lists.forEach { l ->
                    val on = l.id in selected
                    androidx.compose.material3.FilterChip(
                        selected = on,
                        onClick = { onApply(if (on) selected - l.id else selected + l.id) },
                        label = { Text(l.name, maxLines = 1) },
                        leadingIcon = l.colorArgb?.let { { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(it))) } },
                    )
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
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                OutlinedTextField(name, { name = it }, placeholder = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
    onPickBackground: (android.net.Uri) -> Unit, onClearBackground: () -> Unit, onEmoji: (String?) -> Unit,
) {
    var name by remember { mutableStateOf(list.name) }
    var description by remember { mutableStateOf(list.description) }
    var confirmDelete by remember { mutableStateOf(false) }
    val bgPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPickBackground(uri)
    }
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
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name") })
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2)
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.forEach { c -> Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c)).clickable { onColor(c) }) }
                }
                Spacer(Modifier.size(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(6.dp))
                EmojiPicker(current = list.emoji, onPick = onEmoji)
                Spacer(Modifier.size(12.dp))
                Text("Background image", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val bgCtx = androidx.compose.ui.platform.LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { try { bgPicker.launch("image/*") } catch (e: Exception) { android.widget.Toast.makeText(bgCtx, "No file manager is available on this device.", android.widget.Toast.LENGTH_LONG).show() } }) { Text(if (list.backgroundBase64 == null) "Set image" else "Change image") }
                    if (list.backgroundBase64 != null) TextButton(onClick = onClearBackground) { Text("Remove", color = MaterialTheme.colorScheme.error) }
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
private fun ManageFolderDialog(folder: FolderEntity, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onIcon: (String?) -> Unit, onDelete: () -> Unit) {
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
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name") })
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2)
                Spacer(Modifier.size(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(6.dp))
                EmojiPicker(current = folder.icon, onPick = onIcon)
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
        text = { OutlinedTextField(name, { name = it }, placeholder = { Text(placeholder) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
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
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onColor(null) }, contentAlignment = Alignment.Center) {
                        Text("–", style = MaterialTheme.typography.labelMedium)
                    }
                    SWATCHES.forEach { c -> Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c)).clickable { onColor(c) }) }
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
    var startH by remember { mutableStateOf((oh0?.startMin ?: 540) / 60) }
    var endH by remember { mutableStateOf((oh0?.endMin ?: 1020) / 60) }
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
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onColor(null) }, contentAlignment = Alignment.Center) {
                        Text("–", style = MaterialTheme.typography.labelMedium)
                    }
                    SWATCHES.forEach { c -> Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c)).clickable { onColor(c) }) }
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
private fun HourStepper(hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(hour - 1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) { Text("−") }
        Text("%02d:00".format(hour), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange(hour + 1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) { Text("+") }
    }
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
