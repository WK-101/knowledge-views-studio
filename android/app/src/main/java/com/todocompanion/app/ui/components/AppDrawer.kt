package com.todocompanion.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.domain.SmartVis
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.ui.AppViewModel
import kotlin.math.roundToInt

private fun smartIcon(k: SmartKind): ImageVector = when (k) {
    SmartKind.INBOX -> Icons.Filled.Inbox
    SmartKind.TODAY -> Icons.Filled.Today
    SmartKind.TOMORROW -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    SmartKind.NEXT7 -> Icons.Filled.CalendarMonth
    SmartKind.DO_NEXT -> Icons.Filled.Bolt
    SmartKind.SCHEDULED -> Icons.Filled.EventAvailable
    SmartKind.FLAGGED -> Icons.Filled.Star
    SmartKind.GOALS -> Icons.Filled.EmojiEvents
    SmartKind.ALL -> Icons.Filled.AllInbox
    SmartKind.COMPLETED -> Icons.Filled.CheckCircle
    SmartKind.WONT_DO -> Icons.Filled.Cancel
    SmartKind.TRASH -> Icons.Filled.DeleteOutline
}

@Composable
fun AppDrawer(
    vm: AppViewModel,
    onSelect: (ViewRef) -> Unit,
    onSearch: () -> Unit,
    onNewList: (String?) -> Unit,
    onNewFolder: (String?) -> Unit,
    onManageList: (ListEntity) -> Unit,
    onManageFolder: (FolderEntity) -> Unit,
    onMoveList: (ListEntity) -> Unit,
    onMoveFolder: (FolderEntity) -> Unit,
    onNewTag: (String?) -> Unit,
    onManageTag: (com.todocompanion.app.data.entity.TagEntity) -> Unit,
    onMoveTag: (com.todocompanion.app.data.entity.TagEntity) -> Unit,
    onNewContext: (String?) -> Unit,
    onManageContext: (com.todocompanion.app.data.entity.ContextEntity) -> Unit,
    onMoveContext: (com.todocompanion.app.data.entity.ContextEntity) -> Unit,
    onNewWorkspace: () -> Unit,
    onManageWorkspace: (com.todocompanion.app.data.entity.WorkspaceEntity) -> Unit,
    onEditFilter: (com.todocompanion.app.data.entity.FilterEntity?) -> Unit,
    onOpenStats: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val folders by vm.folders.collectAsState()
    val lists by vm.lists.collectAsState()
    val tags by vm.tags.collectAsState()
    val contexts by vm.contexts.collectAsState()
    val counts by vm.smartCounts.collectAsState()
    val current by vm.currentView.collectAsState()
    val settings by vm.settings.collectAsState()

    val workspaces by vm.workspaces.collectAsState()
    val activeWsId = settings.activeWorkspaceId
    val activeWs = workspaces.firstOrNull { it.id == activeWsId } ?: workspaces.firstOrNull()

    ModalDrawerSheet {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            var wsMenu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.fillMaxWidth().clickable { wsMenu = true }.padding(20.dp, 22.dp, 12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(activeWs?.name ?: "ToDo Companion", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Workspace · offline · free", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.KeyboardArrowDown, "Workspaces", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = wsMenu, onDismissRequest = { wsMenu = false }) {
                    Text("WORKSPACES", Modifier.padding(14.dp, 8.dp, 14.dp, 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    workspaces.forEach { w ->
                        DropdownMenuItem(
                            text = { Text(w.name) },
                            leadingIcon = { if (w.id == activeWsId) Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp)) },
                            trailingIcon = { Icon(Icons.Filled.MoreVert, "Manage", modifier = Modifier.size(18.dp).clickable { wsMenu = false; onManageWorkspace(w) }) },
                            onClick = { vm.switchWorkspace(w.id); wsMenu = false },
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(text = { Text("New workspace") }, leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)) }, onClick = { wsMenu = false; onNewWorkspace() })
                }
            }

            val collapsed = remember { mutableStateMapOf<String, Boolean>() }
            fun open(k: String) = collapsed[k] != true
            fun toggle(k: String) { collapsed[k] = open(k) }
            // Expand state for nested lists (default expanded).
            val listExpand = remember { mutableStateMapOf<String, Boolean>() }

            PinnedFavourites(settings.pinnedRefs, lists, folders, tags, contexts, vm, current, onSelect)

            SectionHeader("Smart lists", open = open("smart"), onToggle = { toggle("smart") })
            if (open("smart")) listOf(
                SmartKind.INBOX, SmartKind.TODAY, SmartKind.TOMORROW, SmartKind.NEXT7, SmartKind.DO_NEXT,
                SmartKind.SCHEDULED, SmartKind.FLAGGED, SmartKind.GOALS, SmartKind.ALL, SmartKind.COMPLETED, SmartKind.WONT_DO, SmartKind.TRASH,
            ).filter { k ->
                when (settings.smartListVis[k] ?: SmartVis.SHOW) {
                    SmartVis.SHOW -> true
                    SmartVis.HIDE -> (current as? ViewRef.Smart)?.kind == k   // keep visible if it's the active view
                    SmartVis.AUTO -> (counts[k] ?: 0) > 0 || (current as? ViewRef.Smart)?.kind == k
                }
            }.forEach { k ->
                DrawerRow(smartIcon(k), k.title, count = counts[k]?.takeIf { it > 0 },
                    selected = (current as? ViewRef.Smart)?.kind == k, onClick = { onSelect(ViewRef.Smart(k)) })
            }

            SectionHeader("Lists", open = open("lists"), onToggle = { toggle("lists") }, onAdd = { onNewList(null) })
            if (open("lists")) {
                folders.filter { it.parentId == null }.sortedBy { it.sortOrder }.forEach { f ->
                    FolderNode(f, 0, folders, lists, listExpand, current, vm, onSelect, onNewList, onNewFolder, onManageList, onManageFolder, onMoveList, onMoveFolder)
                }
                ReorderableListGroup(lists.filter { it.folderId == null && it.parentListId == null && it.id != ListEntity.INBOX_ID && !it.archived }.sortedBy { it.sortOrder },
                    lists, listExpand, 0, current, vm, onSelect, onManageList, onMoveList)
            }

            SectionHeader("Tags", open = open("tags"), onToggle = { toggle("tags") }, onAdd = { onNewTag(null) })
            if (open("tags")) tags.filter { it.parentId == null }.sortedWith(compareBy({ it.sortOrder }, { it.name })).forEach { t ->
                TagNode(t, 0, tags, current, vm, onSelect, onNewTag, onManageTag, onMoveTag)
            }

            val filters by vm.filters.collectAsState()
            if (filters.isNotEmpty() || open("filters")) {
                SectionHeader("Filters", open = open("filters"), onToggle = { toggle("filters") }, onAdd = { onEditFilter(null) })
                if (open("filters")) filters.sortedBy { it.sortOrder }.forEach { f ->
                    var menu by remember(f.id) { mutableStateOf(false) }
                    val selected = (current as? ViewRef.FilterView)?.filterId == f.id
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onSelect(ViewRef.FilterView(f.id)) }.padding(start = 12.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.FilterList, null, tint = f.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(11.dp))
                        Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        Box {
                            Icon(Icons.Filled.MoreVert, "Filter menu", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { menu = true })
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                val pinRef = "filter:${f.id}"
                                MenuItem(if (vm.isPinned(pinRef)) Icons.Filled.PushPin else Icons.Filled.PushPin, if (vm.isPinned(pinRef)) "Unpin from top" else "Pin to top") { vm.togglePinnedRef(pinRef); menu = false }
                                MenuItem(Icons.Filled.Edit, "Edit filter") { onEditFilter(f); menu = false }
                            }
                        }
                    }
                }
            }

            SectionHeader("Contexts", open = open("contexts"), onToggle = { toggle("contexts") }, onAdd = { onNewContext(null) })
            if (open("contexts")) contexts.filter { it.parentId == null }.sortedWith(compareBy({ it.name })).forEach { c ->
                ContextNode(c, 0, contexts, current, vm, onSelect, onNewContext, onManageContext, onMoveContext)
            }

            SectionHeader("")
            DrawerRow(Icons.Filled.BarChart, "Statistics", onClick = onOpenStats)
            DrawerRow(Icons.Filled.ChecklistRtl, "Weekly review", onClick = onOpenReview)
            DrawerRow(Icons.Filled.Settings, "Settings", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun FolderNode(
    folder: FolderEntity, depth: Int, folders: List<FolderEntity>, lists: List<ListEntity>, listExpand: MutableMap<String, Boolean>, current: ViewRef, vm: AppViewModel,
    onSelect: (ViewRef) -> Unit, onNewList: (String?) -> Unit, onNewFolder: (String?) -> Unit,
    onManageList: (ListEntity) -> Unit, onManageFolder: (FolderEntity) -> Unit, onMoveList: (ListEntity) -> Unit, onMoveFolder: (FolderEntity) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val selected = (current as? ViewRef.FolderView)?.folderId == folder.id
    val pinRef = "folder:${folder.id}"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(start = (10 + depth * 16).dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron toggles collapse; the rest of the row opens the folder (all its tasks).
        Icon(if (folder.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, if (folder.collapsed) "Expand" else "Collapse",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).clip(CircleShape).clickable { vm.toggleFolder(folder) })
        Spacer(Modifier.width(3.dp))
        if (folder.icon != null) Text(folder.icon!!, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.size(20.dp).wrapContentSize(Alignment.Center))
        else Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(folder.name, Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { onSelect(ViewRef.FolderView(folder.id)) },
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Box {
            Icon(Icons.Filled.MoreVert, "Folder menu", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(if (vm.isPinned(pinRef)) Icons.Filled.PushPin else Icons.Filled.PushPin, if (vm.isPinned(pinRef)) "Unpin from top" else "Pin to top") { vm.togglePinnedRef(pinRef); menu = false }
                MenuItem(Icons.Filled.Add, "New list here") { onNewList(folder.id); menu = false }
                MenuItem(Icons.Filled.Folder, "New folder here") { onNewFolder(folder.id); menu = false }
                MenuItem(Icons.Filled.KeyboardArrowUp, "Move up") { vm.moveFolderOrder(folder, -1); menu = false }
                MenuItem(Icons.Filled.KeyboardArrowDown, "Move down") { vm.moveFolderOrder(folder, +1); menu = false }
                MenuItem(Icons.Filled.DriveFileMove, "Move to…") { onMoveFolder(folder); menu = false }
                MenuItem(Icons.AutoMirrored.Filled.FormatListBulleted, "Convert to list") {
                    if (!vm.convertFolderToList(folder)) Toast.makeText(ctx, "Empty the folder first", Toast.LENGTH_SHORT).show()
                    menu = false
                }
                MenuItem(Icons.Filled.Edit, "Rename / icon / delete") { onManageFolder(folder); menu = false }
            }
        }
    }
    if (!folder.collapsed) {
        folders.filter { it.parentId == folder.id }.sortedBy { it.sortOrder }.forEach { child ->
            FolderNode(child, depth + 1, folders, lists, listExpand, current, vm, onSelect, onNewList, onNewFolder, onManageList, onManageFolder, onMoveList, onMoveFolder)
        }
        ReorderableListGroup(lists.filter { it.folderId == folder.id && it.parentListId == null && !it.archived }.sortedBy { it.sortOrder },
            lists, listExpand, depth + 1, current, vm, onSelect, onManageList, onMoveList)
    }
}

@Composable
private fun ReorderableListGroup(
    siblings: List<ListEntity>, allLists: List<ListEntity>, expand: MutableMap<String, Boolean>,
    depth: Int, current: ViewRef, vm: AppViewModel,
    onSelect: (ViewRef) -> Unit, onManageList: (ListEntity) -> Unit, onMoveList: (ListEntity) -> Unit,
) {
    var dragId by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf(siblings) }
    // Resync from upstream (rename, colour, add/remove, reorder) except mid-drag.
    androidx.compose.runtime.LaunchedEffect(siblings) { if (dragId == null) items = siblings }
    var delta by remember { mutableFloatStateOf(0f) }
    var rowH by remember { mutableFloatStateOf(0f) }
    Column {
        items.forEach { l ->
            key(l.id) {
                val children = allLists.filter { it.parentListId == l.id && !it.archived }.sortedBy { it.sortOrder }
                val expanded = expand[l.id] != false
                val dragging = l.id == dragId
                Column {
                    // Only the row itself carries the drag gesture + height measurement, so nested
                    // children rendered below don't distort the sibling reorder math.
                    Box(
                        Modifier
                            .onSizeChanged { if (rowH == 0f) rowH = it.height.toFloat() }
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) delta else 0f }
                            .pointerInput(l.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragId = l.id; delta = 0f },
                                    onDragEnd = { if (dragId != null) vm.setListOrder(items.map { it.id }); dragId = null; delta = 0f },
                                    onDragCancel = { dragId = null; delta = 0f },
                                    onDrag = { ch, d ->
                                        ch.consume(); delta += d.y
                                        val from = items.indexOfFirst { it.id == dragId }
                                        if (from >= 0 && rowH > 0f) {
                                            val target = (from + (delta / rowH).roundToInt()).coerceIn(0, items.size - 1)
                                            if (target != from) { items = items.toMutableList().also { it.add(target, it.removeAt(from)) }; delta -= (target - from) * rowH }
                                        }
                                    },
                                )
                            },
                    ) { ListRow(l, depth, children.isNotEmpty(), expanded, { expand[l.id] = !expanded }, current, vm, onSelect, onManageList, onMoveList) }
                    if (children.isNotEmpty() && expanded) {
                        ReorderableListGroup(children, allLists, expand, depth + 1, current, vm, onSelect, onManageList, onMoveList)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(
    list: ListEntity, depth: Int, hasChildren: Boolean, expanded: Boolean, onToggleExpand: () -> Unit,
    current: ViewRef, vm: AppViewModel, onSelect: (ViewRef) -> Unit, onManageList: (ListEntity) -> Unit, onMoveList: (ListEntity) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val selected = (current as? ViewRef.ListView)?.listId == list.id
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onSelect(ViewRef.ListView(list.id)) }
            .padding(start = (10 + depth * 16).dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onToggleExpand() },
            )
            Spacer(Modifier.width(3.dp))
        } else {
            Spacer(Modifier.width(21.dp))
        }
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(list.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(11.dp))
        Text(list.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Box {
            Icon(Icons.Filled.MoreVert, "List menu", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val pinRef = "list:${list.id}"
                MenuItem(if (vm.isPinned(pinRef)) Icons.Filled.PushPin else Icons.Filled.PushPin, if (vm.isPinned(pinRef)) "Unpin from top" else "Pin to top") { vm.togglePinnedRef(pinRef); menu = false }
                MenuItem(Icons.Filled.Add, "New sub-list here") { vm.createSubList(list); menu = false }
                MenuItem(Icons.Filled.KeyboardArrowUp, "Move up") { vm.moveListOrder(list, -1); menu = false }
                MenuItem(Icons.Filled.KeyboardArrowDown, "Move down") { vm.moveListOrder(list, +1); menu = false }
                MenuItem(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nest under list above") { vm.indentList(list); menu = false }
                if (list.parentListId != null) MenuItem(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Un-nest") { vm.outdentList(list); menu = false }
                MenuItem(Icons.Filled.DriveFileMove, "Move to folder…") { onMoveList(list); menu = false }
                MenuItem(Icons.Filled.Folder, "Convert to folder") { vm.convertListToFolder(list); menu = false }
                MenuItem(Icons.Filled.Edit, "Rename / colour / delete") { onManageList(list); menu = false }
            }
        }
    }
}

@Composable
private fun TagNode(
    tag: com.todocompanion.app.data.entity.TagEntity, depth: Int, allTags: List<com.todocompanion.app.data.entity.TagEntity>, current: ViewRef, vm: AppViewModel,
    onSelect: (ViewRef) -> Unit, onNewTag: (String?) -> Unit, onManageTag: (com.todocompanion.app.data.entity.TagEntity) -> Unit, onMoveTag: (com.todocompanion.app.data.entity.TagEntity) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val selected = (current as? ViewRef.TagView)?.tagId == tag.id
    val children = allTags.filter { it.parentId == tag.id }.sortedWith(compareBy({ it.sortOrder }, { it.name }))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onSelect(ViewRef.TagView(tag.id)) }
            .padding(start = (12 + depth * 16).dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Label, null, tint = tag.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(11.dp))
        Text("#" + tag.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Box {
            Icon(Icons.Filled.MoreVert, "Tag menu", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val pinRef = "tag:${tag.id}"
                MenuItem(if (vm.isPinned(pinRef)) Icons.Filled.PushPin else Icons.Filled.PushPin, if (vm.isPinned(pinRef)) "Unpin from top" else "Pin to top") { vm.togglePinnedRef(pinRef); menu = false }
                MenuItem(Icons.Filled.Add, "New sub-tag") { onNewTag(tag.id); menu = false }
                MenuItem(Icons.Filled.DriveFileMove, "Move to…") { onMoveTag(tag); menu = false }
                MenuItem(Icons.Filled.Edit, "Rename / colour / delete") { onManageTag(tag); menu = false }
            }
        }
    }
    children.forEach { TagNode(it, depth + 1, allTags, current, vm, onSelect, onNewTag, onManageTag, onMoveTag) }
}

/** MLO-style favourites pinned to the top as big tiles, 1–4 per row (dynamically sharing the
 *  width). Tap opens; long-press reveals an unpin badge so the control never steals row space. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PinnedFavourites(
    refs: List<String>,
    lists: List<ListEntity>, folders: List<FolderEntity>,
    tags: List<com.todocompanion.app.data.entity.TagEntity>, contexts: List<com.todocompanion.app.data.entity.ContextEntity>,
    vm: AppViewModel, current: ViewRef, onSelect: (ViewRef) -> Unit,
) {
    val filters by vm.filters.collectAsState()
    data class Pin(val icon: ImageVector, val emoji: String?, val label: String, val color: Color?, val view: ViewRef, val ref: String)
    val resolved = refs.mapNotNull { ref ->
        val id = ref.substringAfter(':')
        when (ref.substringBefore(':')) {
            "list" -> lists.firstOrNull { it.id == id }?.let { Pin(Icons.AutoMirrored.Filled.FormatListBulleted, it.emoji, it.name, it.colorArgb?.let(::Color), ViewRef.ListView(id), ref) }
            "folder" -> folders.firstOrNull { it.id == id }?.let { Pin(Icons.Filled.Folder, it.icon, it.name, null, ViewRef.FolderView(id), ref) }
            "tag" -> tags.firstOrNull { it.id == id }?.let { Pin(Icons.Filled.Label, null, "#" + it.name, it.colorArgb?.let(::Color), ViewRef.TagView(id), ref) }
            "context" -> contexts.firstOrNull { it.id == id }?.let { Pin(Icons.Filled.Place, null, "@" + it.name, it.colorArgb?.let(::Color), ViewRef.ContextView(id), ref) }
            "filter" -> filters.firstOrNull { it.id == id }?.let { Pin(Icons.Filled.FilterList, null, it.name, it.colorArgb?.let(::Color), ViewRef.FilterView(id), ref) }
            else -> null
        }
    }
    if (resolved.isEmpty()) return
    var unpinTarget by remember { mutableStateOf<String?>(null) }
    SectionHeader("Favourites")
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        resolved.chunked(4).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { p ->
                    val selected = current == p.view
                    val accent = p.color ?: MaterialTheme.colorScheme.primary
                    Box(Modifier.weight(1f)) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                                .combinedClickable(
                                    onClick = { if (unpinTarget != null) unpinTarget = null else onSelect(p.view) },
                                    onLongClick = { unpinTarget = if (unpinTarget == p.ref) null else p.ref },
                                )
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                if (p.emoji != null) Text(p.emoji, style = MaterialTheme.typography.titleLarge)
                                else Icon(p.icon, null, tint = accent, modifier = Modifier.size(24.dp))
                            }
                            Text(p.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 15.sp,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                        }
                        if (unpinTarget == p.ref) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error).clickable { vm.togglePinnedRef(p.ref); unpinTarget = null },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Close, "Unpin", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null, modifier = Modifier.size(20.dp)) }, onClick = onClick)
}

@Composable
private fun SectionHeader(text: String, open: Boolean = true, onToggle: (() -> Unit)? = null, onAdd: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).let { if (onToggle != null) it.clickable { onToggle() } else it }
            .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggle != null) {
            Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        if (onAdd != null) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Add, "Add", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).clickable { onAdd() })
        }
    }
}

@Composable
private fun ContextNode(
    ctx: com.todocompanion.app.data.entity.ContextEntity, depth: Int, all: List<com.todocompanion.app.data.entity.ContextEntity>, current: ViewRef, vm: AppViewModel,
    onSelect: (ViewRef) -> Unit, onNew: (String?) -> Unit, onManage: (com.todocompanion.app.data.entity.ContextEntity) -> Unit, onMove: (com.todocompanion.app.data.entity.ContextEntity) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val selected = (current as? ViewRef.ContextView)?.contextId == ctx.id
    val children = all.filter { it.parentId == ctx.id }.sortedWith(compareBy({ it.name }))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onSelect(ViewRef.ContextView(ctx.id)) }
            .padding(start = (12 + depth * 16).dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Place, null, tint = ctx.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(11.dp))
        Text("@" + ctx.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Box {
            Icon(Icons.Filled.MoreVert, "Context menu", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val pinRef = "context:${ctx.id}"
                MenuItem(if (vm.isPinned(pinRef)) Icons.Filled.PushPin else Icons.Filled.PushPin, if (vm.isPinned(pinRef)) "Unpin from top" else "Pin to top") { vm.togglePinnedRef(pinRef); menu = false }
                MenuItem(Icons.Filled.Add, "New sub-context") { onNew(ctx.id); menu = false }
                MenuItem(Icons.Filled.DriveFileMove, "Move to…") { onMove(ctx); menu = false }
                MenuItem(Icons.Filled.Edit, "Rename / colour / delete") { onManage(ctx); menu = false }
            }
        }
    }
    children.forEach { ContextNode(it, depth + 1, all, current, vm, onSelect, onNew, onManage, onMove) }
}

@Composable
private fun DrawerRow(icon: ImageVector, label: String, count: Int? = null, selected: Boolean = false, muted: Boolean = false, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onClick() }.padding(start = 12.dp, top = 9.dp, bottom = 9.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        if (count != null) Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
