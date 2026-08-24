package com.todocompanion.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.domain.view.GroupMode
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.SortMode
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.ui.components.AppDrawer
import com.todocompanion.app.ui.screens.QuickAddSheet
import com.todocompanion.app.ui.screens.SearchScreen
import com.todocompanion.app.ui.screens.SettingsScreen
import com.todocompanion.app.ui.screens.TaskDetailScreen
import com.todocompanion.app.ui.screens.TasksScreen
import com.todocompanion.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    TASKS("Tasks", Icons.AutoMirrored.Filled.FormatListBulleted),
    CALENDAR("Calendar", Icons.Filled.CalendarMonth),
    MATRIX("Matrix", Icons.Filled.GridView),
    SEARCH("Search", Icons.Filled.Search),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val vm: AppViewModel = viewModel()
    val settings by vm.settings.collectAsState()

    AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        val scope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        var tab by remember { mutableStateOf(Tab.TASKS) }
        var editing by remember { mutableStateOf<String?>(null) }
        var showQuickAdd by remember { mutableStateOf(false) }
        var showNew by remember { mutableStateOf(false) }
        var manageList by remember { mutableStateOf<ListEntity?>(null) }
        var menu by remember { mutableStateOf(false) }

        val currentView by vm.currentView.collectAsState()
        val lists by vm.lists.collectAsState()
        val tags by vm.tags.collectAsState()
        val contexts by vm.contexts.collectAsState()
        val outlineMode by vm.outlineMode.collectAsState()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) { perm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }

        fun openTask(id: String) { editing = id }
        fun goTasks() { tab = Tab.TASKS }

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
                    onNewList = { showNew = true },
                    onNewFolder = { showNew = true },
                    onManageList = { manageList = it },
                    onOpenSettings = { tab = Tab.SETTINGS; scope.launch { drawerState.close() } },
                )
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title, maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "Menu") }
                        },
                        actions = {
                            if (canOutline) {
                                IconButton(onClick = { vm.outlineMode.value = !outlineMode }) {
                                    Icon(if (outlineMode) Icons.AutoMirrored.Filled.FormatListBulleted else Icons.Filled.AccountTree,
                                        if (outlineMode) "List view" else "Outline view")
                                }
                            }
                            if (tab == Tab.TASKS) {
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
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                },
                floatingActionButton = {
                    if (tab == Tab.TASKS) {
                        FloatingActionButton(onClick = { showQuickAdd = true }) { Icon(Icons.Filled.Add, "Add task") }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (tab) {
                        Tab.TASKS -> TasksScreen(vm, ::openTask)
                        Tab.SEARCH -> SearchScreen(vm, ::openTask)
                        Tab.SETTINGS -> SettingsScreen(vm)
                        Tab.CALENDAR -> Placeholder("Calendar", "Month · Week · Day · Agenda — arriving in Phase 1b")
                        Tab.MATRIX -> Placeholder("Matrix", "The Eisenhower matrix — arriving in Phase 1b")
                    }
                }
            }
        }

        editing?.let { id ->
            TaskDetailScreen(vm, id, onBack = { editing = null })
        }
        if (showQuickAdd) QuickAddSheet(vm, onDismiss = { showQuickAdd = false })
        if (showNew) NewContainerDialog(onDismiss = { showNew = false }) { name, isFolder ->
            if (isFolder) vm.createFolder(name) else vm.createList(name, null, null); showNew = false
        }
        manageList?.let { l ->
            ManageListDialog(l, onDismiss = { manageList = null },
                onRename = { vm.saveList(l.copy(name = it)); manageList = null },
                onColor = { vm.saveList(l.copy(colorArgb = it)) },
                onDelete = { vm.deleteList(l.id); if (currentView == ViewRef.ListView(l.id)) vm.select(ViewRef.Smart(SmartKind.TODAY)); manageList = null })
        }
    }
}

@Composable
private fun Placeholder(title: String, sub: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewContainerDialog(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var isFolder by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim(), isFolder) }) { Text("Create") } },
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
            }
        },
    )
}

private val SWATCHES = listOf(0xFFE5484D, 0xFFF59E0B, 0xFF12A594, 0xFF3E7BFA, 0xFF8B5CF6, 0xFFEC4899, 0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
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
                    SWATCHES.forEach { c ->
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c)).clickable { onColor(c) })
                    }
                }
            }
        },
    )
}
