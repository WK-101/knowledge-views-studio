package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.EmptyState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wave E (moonshot) — the on-device Life Graph: notes and the tasks / habits / events they link to,
 * drawn as one picture. No notes app can show this, because none has the rest of the app to connect to.
 * Fully local (reads the materialized note_links edges); a static radial layout keeps it robust, and the
 * list below gives reliable tap-to-open navigation.
 */
@Composable
fun NoteGraphScreen(vm: AppViewModel, onOpenNote: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val notes by vm.notes.collectAsState()
    val links by produceState(initialValue = emptyList<com.todocompanion.app.data.entity.NoteLinkEntity>(), notes) {
        value = runCatching { vm.noteLinksSnapshot() }.getOrDefault(emptyList())
    }

    // Node set: every non-trashed note, plus the distinct resolved targets its links point at.
    val noteById = remember(notes) { notes.associateBy { it.id } }
    data class Node(val key: String, val label: String, val type: String, val noteId: String?)
    val nodes = remember(notes, links) {
        val ns = LinkedHashMap<String, Node>()
        notes.forEach { ns["note:${it.id}"] = Node("note:${it.id}", it.title.ifBlank { "Untitled" }, "note", it.id) }
        links.forEach { l ->
            if (l.targetId.isNotBlank() && l.targetType != "note") {
                val k = "${l.targetType}:${l.targetId}"
                ns.getOrPut(k) { Node(k, l.targetTitle, l.targetType, null) }
            }
        }
        ns.values.toList()
    }
    val edges = remember(nodes, links) {
        val keys = nodes.map { it.key }.toHashSet()
        links.mapNotNull { l ->
            val from = "note:${l.noteId}"
            val to = if (l.targetType == "note") "note:${l.targetId}" else "${l.targetType}:${l.targetId}"
            if (l.targetId.isNotBlank() && from in keys && to in keys) from to to else null
        }
    }
    val outCount = remember(links) { links.groupingBy { it.noteId }.eachCount() }

    val notePrimary = MaterialTheme.colorScheme.primary
    val taskColor = Color(0xFF12A594); val habitColor = Color(0xFFF59E0B); val eventColor = Color(0xFF3E7BFA)
    val edgeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    fun colorFor(type: String) = when (type) { "task" -> taskColor; "habit" -> habitColor; "event" -> eventColor; else -> notePrimary }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                Text("Life graph", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (nodes.isEmpty()) {
                EmptyState(emoji = "🕸️", title = "No connections yet", body = "Link notes with [[wiki-links]] — to other notes, tasks, habits or events — and they'll appear here as a graph of your work.")
                return@Column
            }
            // Radial layout: place every node on a circle; draw edges then nodes.
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp)) {
                val index = remember(nodes) { nodes.mapIndexed { i, n -> n.key to i }.toMap() }
                Canvas(Modifier.fillMaxSize()) {
                    val n = nodes.size
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val r = minOf(cx, cy) * 0.82f
                    fun pos(i: Int): Offset {
                        val a = 2.0 * Math.PI * i / n - Math.PI / 2
                        return Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
                    }
                    edges.forEach { (from, to) ->
                        val a = index[from]; val b = index[to]
                        if (a != null && b != null) drawLine(edgeColor, pos(a), pos(b), strokeWidth = 2f)
                    }
                    nodes.forEachIndexed { i, node ->
                        drawCircle(colorFor(node.type), radius = if (node.type == "note") 9f else 6f, center = pos(i))
                    }
                }
            }
            // Legend.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot(notePrimary, "Notes"); LegendDot(taskColor, "Tasks"); LegendDot(habitColor, "Habits"); LegendDot(eventColor, "Events")
            }
            Spacer(Modifier.height(8.dp))
            // Most-connected notes — reliable tap-to-open navigation.
            val ranked = remember(notes, outCount) { notes.sortedByDescending { outCount[it.id] ?: 0 } }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(ranked, key = { it.id }) { note ->
                    val c = outCount[note.id] ?: 0
                    Row(
                        Modifier.fillMaxWidth().clip(CircleShape).clickable { onOpenNote(note.id) }.padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(notePrimary))
                        Spacer(Modifier.width(12.dp))
                        Text(note.title.ifBlank { "Untitled" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                        if (c > 0) Text("$c link${if (c == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
