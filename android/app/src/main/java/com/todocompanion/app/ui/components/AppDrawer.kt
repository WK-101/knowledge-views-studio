package com.todocompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    onNewList: () -> Unit,
    onNewFolder: () -> Unit,
    onManageList: (ListEntity) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val folders by vm.folders.collectAsState()
    val lists by vm.lists.collectAsState()
    val tags by vm.tags.collectAsState()
    val contexts by vm.contexts.collectAsState()
    val counts by vm.smartCounts.collectAsState()
    val current by vm.currentView.collectAsState()

    ModalDrawerSheet {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
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

            DrawerRow(Icons.Filled.Search, "Search everything…", muted = true, onClick = onSearch)

            SectionHeader("Smart lists")
            listOf(
                SmartKind.INBOX, SmartKind.TODAY, SmartKind.TOMORROW, SmartKind.NEXT7, SmartKind.DO_NEXT,
                SmartKind.SCHEDULED, SmartKind.FLAGGED, SmartKind.ALL, SmartKind.COMPLETED, SmartKind.WONT_DO, SmartKind.TRASH,
            ).forEach { k ->
                DrawerRow(
                    smartIcon(k), k.title,
                    count = counts[k]?.takeIf { it > 0 },
                    selected = (current as? ViewRef.Smart)?.kind == k,
                    onClick = { onSelect(ViewRef.Smart(k)) },
                )
            }

            SectionHeader("Lists", onAdd = onNewList)
            // root folders, then root lists (excluding Inbox which lives in Smart Lists)
            folders.filter { it.parentId == null }.sortedBy { it.sortOrder }.forEach { f ->
                FolderNode(f, 0, folders, lists, current, onSelect, onManageList, vm)
            }
            lists.filter { it.folderId == null && it.id != ListEntity.INBOX_ID && !it.archived }
                .sortedBy { it.sortOrder }.forEach { l -> ListRow(l, 0, current, onSelect, onManageList) }
            DrawerRow(Icons.Filled.Add, "New list or folder", muted = true, indent = 0, onClick = onNewList, onLongClick = onNewFolder)

            if (tags.isNotEmpty()) {
                SectionHeader("Tags")
                tags.forEach { t ->
                    DrawerRow(Icons.Filled.Label, "#" + t.name, selected = (current as? ViewRef.TagView)?.tagId == t.id, onClick = { onSelect(ViewRef.TagView(t.id)) })
                }
            }
            if (contexts.isNotEmpty()) {
                SectionHeader("Contexts")
                contexts.forEach { c ->
                    DrawerRow(Icons.Filled.Place, "@" + c.name, selected = (current as? ViewRef.ContextView)?.contextId == c.id, onClick = { onSelect(ViewRef.ContextView(c.id)) })
                }
            }

            SectionHeader("")
            DrawerRow(Icons.Filled.Settings, "Settings", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun FolderNode(
    folder: FolderEntity,
    depth: Int,
    folders: List<FolderEntity>,
    lists: List<ListEntity>,
    current: ViewRef,
    onSelect: (ViewRef) -> Unit,
    onManageList: (ListEntity) -> Unit,
    vm: AppViewModel,
) {
    DrawerRow(
        icon = Icons.Filled.Folder,
        label = folder.name,
        indent = depth,
        chevron = if (folder.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
        onChevron = { vm.toggleFolder(folder) },
        onClick = { vm.toggleFolder(folder) },
    )
    if (!folder.collapsed) {
        folders.filter { it.parentId == folder.id }.sortedBy { it.sortOrder }.forEach { child ->
            FolderNode(child, depth + 1, folders, lists, current, onSelect, onManageList, vm)
        }
        lists.filter { it.folderId == folder.id && !it.archived }.sortedBy { it.sortOrder }.forEach { l ->
            ListRow(l, depth + 1, current, onSelect, onManageList)
        }
    }
}

@Composable
private fun ListRow(
    list: ListEntity,
    depth: Int,
    current: ViewRef,
    onSelect: (ViewRef) -> Unit,
    onManageList: (ListEntity) -> Unit,
) {
    val selected = (current as? ViewRef.ListView)?.listId == list.id
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onSelect(ViewRef.ListView(list.id)) }
            .padding(start = (14 + depth * 16).dp, top = 9.dp, bottom = 9.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(list.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(11.dp))
        Text(list.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Filled.Settings, "Manage list", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).clickable { onManageList(list) })
    }
}

@Composable
private fun SectionHeader(text: String, onAdd: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        if (onAdd != null) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Add, "Add", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).clickable { onAdd() })
        }
    }
}

@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    count: Int? = null,
    selected: Boolean = false,
    muted: Boolean = false,
    indent: Int = 0,
    chevron: ImageVector? = null,
    onChevron: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onClick() }
            .padding(start = (12 + indent * 16).dp, top = 9.dp, bottom = 9.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chevron != null) {
            Icon(chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).clickable { (onChevron ?: onClick)() })
            Spacer(Modifier.width(4.dp))
        }
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (count != null) Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
