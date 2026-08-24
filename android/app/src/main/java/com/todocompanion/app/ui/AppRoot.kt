package com.todocompanion.app.ui

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    MATRIX("Matrix", Icons.Filled.GridView),
    SEARCH("Search", Icons.Filled.Search),
    SETTINGS("Settings", Icons.Filled.Settings),
}

private data class NewReq(val isFolder: Boolean, val parentId: String?)

private val CAL_MODES = listOf("list" to "List", "day" to "Day", "3day" to "3-Day", "week" to "Week", "month" to "Month", "year" to "Year")

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val vm: AppViewModel = viewModel()
    val settings by vm.settings.collectAsState()

    AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
        val scope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        var tab by remember { mutableStateOf(Tab.TASKS) }
        var editing by remember { mutableStateOf<String?>(null) }
        var showQuickAdd by remember { mutableStateOf(false) }
        var quickAddDue by remember { mutableStateOf<Long?>(null) }
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
        var menu by remember { mutableStateOf(false) }
        // Hoisted per-tab controls, surfaced in the shared top bar to free screen space.
        var calMode by remember { mutableStateOf(settings.calendarDefaultMode) }
        var calMenu by remember { mutableStateOf(false) }
        var matrixSettings by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }

        val currentView by vm.currentView.collectAsState()
        val lists by vm.lists.collectAsState()
        val folders by vm.folders.collectAsState()
        val tags by vm.tags.collectAsState()
        val contexts by vm.contexts.collectAsState()
        val outlineMode by vm.outlineMode.collectAsState()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) { perm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        val context = LocalContext.current
        LaunchedEffect(settings.dailySummaryEnabled, settings.dailySummaryHour, settings.dailySummaryMinute) {
            if (settings.dailySummaryEnabled) AlarmScheduler.scheduleDailySummary(context, settings.dailySummaryHour, settings.dailySummaryMinute)
            else AlarmScheduler.cancelDailySummary(context)
        }

        val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
        LaunchedEffect(Unit) {
            vm.undoEvents.collect { e ->
                val res = snackbar.showSnackbar(e.message, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
                if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undo(e)
            }
        }

        fun openTask(id: String) { editing = id }
        fun goTasks() { tab = Tab.TASKS }
        fun openQuickAdd(due: Long?) { quickAddDue = due; showQuickAdd = true }

        // Back from a secondary tab returns to Tasks instead of exiting.
        BackHandler(enabled = tab != Tab.TASKS && editing == null && !showQuickAdd) { tab = Tab.TASKS }

        val title = when (tab) {
            Tab.TASKS -> when (val v = currentView) {
                is ViewRef.Smart -> v.kind.title
                is ViewRef.ListView -> lists.firstOrNull { it.id == v.listId }?.name ?: "List"
                is ViewRef.TagView -> "#" + (tags.firstOrNull { it.id == v.tagId }?.name ?: "")
                is ViewRef.ContextView -> "@" + (contexts.firstOrNull { it.id == v.contextId }?.name ?: "")
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
                    onOpenSettings = { tab = Tab.SETTINGS; scope.launch { drawerState.close() } },
                )
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        windowInsets = androidx.compose.material3.TopAppBarDefaults.windowInsets,
                        title = {
                            if (tab == Tab.SEARCH) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Box(Modifier.weight(1f)) {
                                        if (searchQuery.isEmpty()) Text("Search tasks, #tags, @contexts…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
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
                            if (canOutline) IconButton(onClick = { vm.outlineMode.value = !outlineMode }) {
                                Icon(if (outlineMode) Icons.AutoMirrored.Filled.FormatListBulleted else Icons.Filled.AccountTree, if (outlineMode) "List view" else "Outline view")
                            }
                            when (tab) {
                                Tab.TASKS -> {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Sort & group") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        Text("Group by", Modifier.padding(12.dp, 8.dp, 12.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
                                        listOf("None" to GroupMode.NONE, "Date" to GroupMode.DATE, "Priority" to GroupMode.PRIORITY).forEach { (l, m) ->
                                            DropdownMenuItem(text = { Text(l) }, onClick = { vm.groupMode.value = m; menu = false })
                                        }
                                        Text("Sort by", Modifier.padding(12.dp, 8.dp, 12.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
                                        listOf("Manual" to SortMode.MANUAL, "Priority" to SortMode.PRIORITY, "Due" to SortMode.DUE, "Title" to SortMode.TITLE).forEach { (l, m) ->
                                            DropdownMenuItem(text = { Text(l) }, onClick = { vm.sortMode.value = m; menu = false })
                                        }
                                    }
                                }
                                Tab.CALENDAR -> {
                                    IconButton(onClick = { calMenu = true }) { Icon(Icons.Filled.CalendarViewMonth, "Calendar view") }
                                    DropdownMenu(expanded = calMenu, onDismissRequest = { calMenu = false }) {
                                        CAL_MODES.forEach { (k, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                leadingIcon = { if (calMode == k) Icon(Icons.Filled.Check, null) else Spacer(Modifier.width(24.dp)) },
                                                onClick = { calMode = k; calMenu = false },
                                            )
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
                snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
                floatingActionButton = {
                    if (tab == Tab.TASKS || tab == Tab.CALENDAR || tab == Tab.MATRIX) {
                        FloatingActionButton(onClick = { openQuickAdd(null) }) { Icon(Icons.Filled.Add, "Add task") }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    Crossfade(targetState = tab, animationSpec = tween(180), label = "tab") { t ->
                        when (t) {
                            Tab.TASKS -> TasksScreen(vm, ::openTask)
                            Tab.SEARCH -> SearchScreen(vm, ::openTask, searchQuery)
                            Tab.SETTINGS -> SettingsScreen(vm)
                            Tab.CALENDAR -> CalendarScreen(vm, ::openTask, calMode, { calMode = it }, onAddOnDate = { d ->
                                openQuickAdd(d.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                            })
                            Tab.MATRIX -> MatrixScreen(vm, ::openTask, matrixSettings, { matrixSettings = false })
                        }
                    }
                }
            }
        }

        editing?.let { id -> TaskDetailScreen(vm, id, onBack = { editing = null }) }
        if (showQuickAdd) QuickAddSheet(vm, initialDue = quickAddDue, onDismiss = { showQuickAdd = false; quickAddDue = null })

        newReq?.let { req ->
            NewContainerDialog(req, folders, onDismiss = { newReq = null }) { name, isFolder, parentId ->
                if (isFolder) vm.createFolder(name, parentId) else vm.createList(name, parentId, null)
                newReq = null
            }
        }
        manageList?.let { l ->
            ManageListDialog(l, onDismiss = { manageList = null },
                onRename = { vm.saveList(l.copy(name = it)); manageList = null },
                onColor = { vm.saveList(l.copy(colorArgb = it)) },
                onDelete = { vm.deleteList(l.id); if (currentView == ViewRef.ListView(l.id)) vm.select(ViewRef.Smart(SmartKind.TODAY)); manageList = null })
        }
        manageFolder?.let { f ->
            ManageFolderDialog(f, onDismiss = { manageFolder = null },
                onRename = { vm.renameFolder(f, it); manageFolder = null },
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
                onDelete = { vm.deleteContext(c); manageCtx = null })
        }
        moveCtx?.let { c ->
            ContextPickerDialog("Move context to", contexts, exclude = ctxDescendantsOf(c.id, contexts) + c.id, onDismiss = { moveCtx = null }) { target ->
                vm.moveContextToParent(c.id, target); moveCtx = null
            }
        }
    }
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
private fun ManageListDialog(list: ListEntity, onDismiss: () -> Unit, onRename: (String) -> Unit, onColor: (Long) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(list.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name.trim()) }) { Text("Save") } },
        dismissButton = {
            if (list.id != ListEntity.INBOX_ID) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("List") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.forEach { c -> Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c)).clickable { onColor(c) }) }
                }
            }
        },
    )
}

private val FOLDER_EMOJIS = listOf("📁", "📂", "🗂️", "📥", "⭐", "🎯", "💼", "🏠", "🛒", "✈️", "📚", "💡", "❤️", "🔥", "✅", "🧠", "💪", "🎨", "🎵", "🍽️")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ManageFolderDialog(folder: FolderEntity, onDismiss: () -> Unit, onRename: (String) -> Unit, onIcon: (String?) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        title = { Text("Folder") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(6.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (folder.icon == null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant).clickable { onIcon(null) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Folder, "No icon", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FOLDER_EMOJIS.forEach { e ->
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (folder.icon == e) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant).clickable { onIcon(e) }, contentAlignment = Alignment.Center) {
                            Text(e, style = MaterialTheme.typography.titleMedium)
                        }
                    }
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

@Composable
private fun ManageContextDialog(ctx: com.todocompanion.app.data.entity.ContextEntity, onDismiss: () -> Unit, onRename: (String) -> Unit, onColor: (Long?) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(ctx.name) }
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
