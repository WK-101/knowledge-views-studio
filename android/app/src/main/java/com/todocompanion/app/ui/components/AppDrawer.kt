package com.todocompanion.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.domain.SmartVis
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.ui.AppViewModel

private fun smartIcon(k: SmartKind): ImageVector = when (k) {
    SmartKind.INBOX -> Icons.Filled.Inbox
    SmartKind.TODAY -> Icons.Filled.Today
    SmartKind.TOMORROW -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    SmartKind.NEXT7 -> Icons.Filled.CalendarMonth
    SmartKind.DO_NEXT -> Icons.Filled.Bolt
    SmartKind.SCHEDULED -> Icons.Filled.EventAvailable
    SmartKind.FLAGGED -> Icons.Filled.Star
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
    onOpenSettings: () -> Unit,
) {
    val folders by vm.folders.collectAsState()
    val lists by vm.lists.collectAsState()
    val tags by vm.tags.collectAsState()
    val contexts by vm.contexts.collectAsState()
    val counts by vm.smartCounts.collectAsState()
    val current by vm.currentView.collectAsState()
    val settings by vm.settings.collectAsState()

    ModalDrawerSheet {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(Modifier.padding(20.dp, 22.dp, 16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("ToDo Companion", fontWeight = FontWeight.SemiBold)
                    Text("Offline · private · free", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val collapsed = remember { mutableStateMapOf<String, Boolean>() }
            fun open(k: String) = collapsed[k] != true
            fun toggle(k: String) { collapsed[k] = open(k) }

            SectionHeader("Smart lists", open = open("smart"), onToggle = { toggle("smart") })
            if (open("smart")) listOf(
                SmartKind.INBOX, SmartKind.TODAY, SmartKind.TOMORROW, SmartKind.NEXT7, SmartKind.DO_NEXT,
                SmartKind.SCHEDULED, SmartKind.FLAGGED, SmartKind.ALL, SmartKind.COMPLETED, SmartKind.WONT_DO, SmartKind.TRASH,
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
                    FolderNode(f, 0, folders, lists, current, vm, onSelect, onNewList, onNewFolder, onManageList, onManageFolder, onMoveList, onMoveFolder)
                }
                lists.filter { it.folderId == null && it.id != ListEntity.INBOX_ID && !it.archived }.sortedBy { it.sortOrder }.forEach { l ->
                    ListRow(l, 0, current, vm, onSelect, onManageList, onMoveList)
                }
            }

            SectionHeader("Tags", open = open("tags"), onToggle = { toggle("tags") }, onAdd = { onNewTag(null) })
            if (open("tags")) tags.filter { it.parentId == null }.sortedWith(compareBy({ it.sortOrder }, { it.name })).forEach { t ->
                TagNode(t, 0, tags, current, onSelect, onNewTag, onManageTag, onMoveTag)
            }

            SectionHeader("Contexts", open = open("contexts"), onToggle = { toggle("contexts") }, onAdd = { onNewContext(null) })
            if (open("contexts")) contexts.filter { it.parentId == null }.sortedWith(compareBy({ it.name })).forEach { c ->
                ContextNode(c, 0, contexts, current, onSelect, onNewContext, onManageContext, onMoveContext)
            }

            SectionHeader("")
            DrawerRow(Icons.Filled.Settings, "Settings", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun FolderNode(
    folder: FolderEntity, depth: Int, folders: List<FolderEntity>, lists: List<ListEntity>, current: ViewRef, vm: AppViewModel,
    onSelect: (ViewRef) -> Unit, onNewList: (String?) -> Unit, onNewFolder: (String?) -> Unit,
    onManageList: (ListEntity) -> Unit, onManageFolder: (FolderEntity) -> Unit, onMoveList: (ListEntity) -> Unit, onMoveFolder: (FolderEntity) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
            .clickable { vm.toggleFolder(folder) }.padding(start = (10 + depth * 16).dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (folder.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(3.dp))
        if (folder.icon != null) Text(folder.icon!!, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.size(20.dp).wrapContentSize(Alignment.Center))
        else Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(folder.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Box {
            Icon(Icons.Filled.MoreVert, "Folder menu", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
            FolderNode(child, depth + 1, folders, lists, current, vm, onSelect, onNewList, onNewFolder, onManageList, onManageFolder, onMoveList, onMoveFolder)
        }
        lists.filter { it.folderId == folder.id && !it.archived }.sortedBy { it.sortOrder }.forEach { l ->
            ListRow(l, depth + 1, current, vm, onSelect, onManageList, onMoveList)
        }
    }
}

@Composable
private fun ListRow(list: ListEntity, depth: Int, current: ViewRef, vm: AppViewModel, onSelect: (ViewRef) -> Unit, onManageList: (ListEntity) -> Unit, onMoveList: (ListEntity) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val selected = (current as? ViewRef.ListView)?.listId == list.id
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onSelect(ViewRef.ListView(list.id)) }
            .padding(start = (14 + depth * 16).dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(list.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(11.dp))
        Text(list.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Box {
            Icon(Icons.Filled.MoreVert, "List menu", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(Icons.Filled.KeyboardArrowUp, "Move up") { vm.moveListOrder(list, -1); menu = false }
                MenuItem(Icons.Filled.KeyboardArrowDown, "Move down") { vm.moveListOrder(list, +1); menu = false }
                MenuItem(Icons.Filled.DriveFileMove, "Move to folder…") { onMoveList(list); menu = false }
                MenuItem(Icons.Filled.Folder, "Convert to folder") { vm.convertListToFolder(list); menu = false }
                MenuItem(Icons.Filled.Edit, "Rename / colour / delete") { onManageList(list); menu = false }
            }
        }
    }
}

@Composable
private fun TagNode(
    tag: com.todocompanion.app.data.entity.TagEntity, depth: Int, allTags: List<com.todocompanion.app.data.entity.TagEntity>, current: ViewRef,
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
                MenuItem(Icons.Filled.Add, "New sub-tag") { onNewTag(tag.id); menu = false }
                MenuItem(Icons.Filled.DriveFileMove, "Move to…") { onMoveTag(tag); menu = false }
                MenuItem(Icons.Filled.Edit, "Rename / colour / delete") { onManageTag(tag); menu = false }
            }
        }
    }
    children.forEach { TagNode(it, depth + 1, allTags, current, onSelect, onNewTag, onManageTag, onMoveTag) }
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
    ctx: com.todocompanion.app.data.entity.ContextEntity, depth: Int, all: List<com.todocompanion.app.data.entity.ContextEntity>, current: ViewRef,
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
                MenuItem(Icons.Filled.Add, "New sub-context") { onNew(ctx.id); menu = false }
                MenuItem(Icons.Filled.DriveFileMove, "Move to…") { onMove(ctx); menu = false }
                MenuItem(Icons.Filled.Edit, "Rename / colour / delete") { onManage(ctx); menu = false }
            }
        }
    }
    children.forEach { ContextNode(it, depth + 1, all, current, onSelect, onNew, onManage, onMove) }
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
