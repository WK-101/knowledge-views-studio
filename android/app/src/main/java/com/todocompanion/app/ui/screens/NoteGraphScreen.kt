package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.EmptyState
import com.todocompanion.app.ui.components.KairoScreenScaffold
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wave E (moonshot) + L15 — the on-device Life Graph, now interactive: notes and the tasks / habits /
 * events they link to, drawn as one force-directed picture you can pan, pinch-zoom and tap. Edges come in
 * four flavours you can toggle — [[wiki-links]] (solid), shared tags, shared contexts, and same-day time
 * threads (dashed) — so the graph reveals structure that no link alone shows. No notes app can draw this,
 * because none has the rest of the app to connect to. Fully local (materialized note_links edges + the
 * note↔tag / note↔context cross-refs already in memory); the layout runs a small Fruchterman–Reingold
 * simulation that cools to rest, and tapping a note node opens it.
 */
private const val LINK = 0
private const val TAG = 1
private const val CTX = 2
private const val TIME = 3
private const val MAX_NODES = 220   // cap the simulation so a huge vault stays smooth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteGraphScreen(vm: AppViewModel, onOpenNote: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val notes by vm.notes.collectAsState()
    val tagRefs by vm.noteTagRefs.collectAsState()
    val ctxRefs by vm.noteContextRefs.collectAsState()
    val links by produceState(initialValue = emptyList<com.todocompanion.app.data.entity.NoteLinkEntity>(), notes) {
        value = runCatching { vm.noteLinksSnapshot() }.getOrDefault(emptyList())
    }

    data class Node(val key: String, val label: String, val type: String, val noteId: String?)

    // Node set: the most-connected / most-recent notes (capped), plus the resolved non-note targets they
    // link to. Capping keeps the O(n²) layout smooth on a large collection.
    val outCount = remember(links) { links.groupingBy { it.noteId }.eachCount() }
    val nodes = remember(notes, links, outCount) {
        val chosen = notes.sortedWith(
            compareByDescending<com.todocompanion.app.data.entity.NoteEntity> { outCount[it.id] ?: 0 }
                .thenByDescending { it.updatedAt },
        ).take(MAX_NODES)
        val chosenIds = chosen.map { it.id }.toHashSet()
        val ns = LinkedHashMap<String, Node>()
        chosen.forEach { ns["note:${it.id}"] = Node("note:${it.id}", it.title.ifBlank { "Untitled" }, "note", it.id) }
        links.forEach { l ->
            if (l.targetId.isNotBlank() && l.targetType != "note" && l.noteId in chosenIds) {
                val k = "${l.targetType}:${l.targetId}"
                ns.getOrPut(k) { Node(k, l.targetTitle, l.targetType, null) }
            }
        }
        ns.values.toList()
    }
    val indexOf = remember(nodes) { nodes.mapIndexed { i, n -> n.key to i }.toMap() }

    // Edges, tagged by kind. Tag/context clusters connect as a star from the first member (cheap, and it
    // still pulls the cluster together under the force layout). Time threads link notes created within a
    // day of each other in creation order.
    data class E(val a: Int, val b: Int, val kind: Int)
    val edges = remember(nodes, links, tagRefs, ctxRefs, notes, indexOf) {
        val es = ArrayList<E>()
        val seen = HashSet<Long>()
        fun add(a: Int, b: Int, kind: Int) {
            if (a == b || a < 0 || b < 0) return
            val lo = min(a, b); val hi = max(a, b)
            val key = (lo.toLong() shl 34) or (hi.toLong() shl 2) or kind.toLong()
            if (seen.add(key)) es.add(E(lo, hi, kind))
        }
        // Links (solid).
        links.forEach { l ->
            val from = indexOf["note:${l.noteId}"] ?: return@forEach
            val to = if (l.targetType == "note") indexOf["note:${l.targetId}"] else indexOf["${l.targetType}:${l.targetId}"]
            if (l.targetId.isNotBlank() && to != null) add(from, to, LINK)
        }
        val visibleNoteIds = nodes.mapNotNull { it.noteId }.toHashSet()
        // Tag stars.
        tagRefs.groupBy { it.tagId }.forEach { (_, refs) ->
            val members = refs.map { it.noteId }.filter { it in visibleNoteIds }.distinct()
            val hub = members.firstOrNull() ?: return@forEach
            members.drop(1).forEach { m -> add(indexOf["note:$hub"] ?: -1, indexOf["note:$m"] ?: -1, TAG) }
        }
        // Context stars.
        ctxRefs.groupBy { it.contextId }.forEach { (_, refs) ->
            val members = refs.map { it.noteId }.filter { it in visibleNoteIds }.distinct()
            val hub = members.firstOrNull() ?: return@forEach
            members.drop(1).forEach { m -> add(indexOf["note:$hub"] ?: -1, indexOf["note:$m"] ?: -1, CTX) }
        }
        // Time threads: consecutive notes created within 24h.
        val day = 24L * 60 * 60 * 1000
        notes.filter { it.id in visibleNoteIds && it.createdAt > 0 }.sortedBy { it.createdAt }
            .zipWithNext().forEach { (a, b) ->
                if (b.createdAt - a.createdAt in 0..day) add(indexOf["note:${a.id}"] ?: -1, indexOf["note:${b.id}"] ?: -1, TIME)
            }
        es
    }

    var showLinks by remember { mutableStateOf(true) }
    var showTags by remember { mutableStateOf(false) }
    var showCtx by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    fun kindOn(k: Int) = when (k) { LINK -> showLinks; TAG -> showTags; CTX -> showCtx; else -> showTime }

    val notePrimary = MaterialTheme.colorScheme.primary
    val taskColor = Color(0xFF12A594); val habitColor = Color(0xFFF59E0B); val eventColor = Color(0xFF3E7BFA)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    fun colorFor(type: String) = when (type) { "task" -> taskColor; "habit" -> habitColor; "event" -> eventColor; else -> notePrimary }
    fun edgeColor(k: Int) = when (k) {
        LINK -> onSurface.copy(alpha = 0.20f); TAG -> secondary.copy(alpha = 0.35f)
        CTX -> tertiary.copy(alpha = 0.35f); else -> outline.copy(alpha = 0.30f)
    }

    // ---- force-directed layout state ----
    val n = nodes.size
    val px = remember(nodes) { FloatArray(n) { i -> (500 + 320 * cos(2.0 * Math.PI * i / max(1, n))).toFloat() } }
    val py = remember(nodes) { FloatArray(n) { i -> (500 + 320 * sin(2.0 * Math.PI * i / max(1, n))).toFloat() } }
    var frame by remember(nodes) { mutableIntStateOf(0) }
    var scale by remember(nodes) { mutableStateOf(1f) }
    var pan by remember(nodes) { mutableStateOf(Offset.Zero) }
    var canvas by remember { mutableStateOf(Size.Zero) }

    androidx.compose.runtime.LaunchedEffect(nodes, edges) {
        if (n <= 1) return@LaunchedEffect
        val area = 1000f * 1000f
        val k = (0.75f * sqrt(area / n)).coerceAtLeast(24f)
        var temp = 260f
        val activeEdges = edges  // attraction runs on ALL structural edges regardless of toggles, so the
        // resting layout is stable while the user flips which edges are drawn.
        repeat(200) { iter ->
            val dx = FloatArray(n); val dy = FloatArray(n)
            // Repulsion (all pairs).
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var ex = px[i] - px[j]; var ey = py[i] - py[j]
                    var d = sqrt(ex * ex + ey * ey)
                    if (d < 0.01f) { ex = (i - j).toFloat() + 0.1f; ey = 0.1f; d = sqrt(ex * ex + ey * ey) }
                    val f = k * k / d
                    val ux = ex / d * f; val uy = ey / d * f
                    dx[i] += ux; dy[i] += uy; dx[j] -= ux; dy[j] -= uy
                }
            }
            // Attraction (edges).
            activeEdges.forEach { e ->
                var ex = px[e.a] - px[e.b]; var ey = py[e.a] - py[e.b]
                val d = sqrt(ex * ex + ey * ey).coerceAtLeast(0.01f)
                val f = d * d / k
                val ux = ex / d * f; val uy = ey / d * f
                dx[e.a] -= ux; dy[e.a] -= uy; dx[e.b] += ux; dy[e.b] += uy
            }
            // Gentle gravity toward centre keeps disconnected nodes from drifting off.
            for (i in 0 until n) { dx[i] += (500f - px[i]) * 0.012f; dy[i] += (500f - py[i]) * 0.012f }
            // Apply, capped by temperature.
            for (i in 0 until n) {
                val dl = sqrt(dx[i] * dx[i] + dy[i] * dy[i]).coerceAtLeast(0.01f)
                px[i] += dx[i] / dl * min(dl, temp)
                py[i] += dy[i] / dl * min(dl, temp)
            }
            temp = max(1.5f, temp * 0.975f)
            frame++
            kotlinx.coroutines.delay(16)
        }
    }

    // Map a graph point → screen, auto-fitting the current bounds then applying user pan/zoom.
    fun fit(): Triple<Float, Offset, Offset> {   // fitScale, graphCenter, base(canvasCenter+pan)
        if (n == 0 || canvas == Size.Zero) return Triple(1f, Offset(500f, 500f), Offset.Zero)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { minX = min(minX, px[i]); minY = min(minY, py[i]); maxX = max(maxX, px[i]); maxY = max(maxY, py[i]) }
        val bw = (maxX - minX).coerceAtLeast(1f); val bh = (maxY - minY).coerceAtLeast(1f)
        val fitScale = min(canvas.width / bw, canvas.height / bh) * 0.82f * scale
        return Triple(fitScale, Offset(minX + bw / 2, minY + bh / 2), Offset(canvas.width / 2 + pan.x, canvas.height / 2 + pan.y))
    }
    fun toScreen(i: Int, f: Triple<Float, Offset, Offset>): Offset =
        Offset((px[i] - f.second.x) * f.first + f.third.x, (py[i] - f.second.y) * f.first + f.third.y)

    KairoScreenScaffold(
        title = "Life graph",
        onBack = onClose,
        actions = { IconButton(onClick = { scale = 1f; pan = Offset.Zero }) { Icon(Icons.Filled.CenterFocusStrong, "Reset view") } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (nodes.isEmpty()) {
                EmptyState(emoji = "🕸️", title = "No connections yet", body = "Link notes with [[wiki-links]], or share tags and contexts across notes — they'll appear here as a graph you can explore.")
                return@Column
            }
            // Edge-type toggles.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = showLinks, onClick = { showLinks = !showLinks }, label = { Text("Links") })
                FilterChip(selected = showTags, onClick = { showTags = !showTags }, label = { Text("Tags") })
                FilterChip(selected = showCtx, onClick = { showCtx = !showCtx }, label = { Text("Contexts") })
                FilterChip(selected = showTime, onClick = { showTime = !showTime }, label = { Text("Time") })
            }
            Spacer(Modifier.size(6.dp))
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .onSizeChanged { canvas = Size(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(nodes) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            scale = (scale * zoomChange).coerceIn(0.3f, 6f)
                            pan += panChange
                        }
                    }
                    .pointerInput(nodes, showLinks, showTags, showCtx, showTime) {
                        detectTapGestures { tap ->
                            val f = fit()
                            var best = -1; var bestD = 40f * 40f
                            for (i in 0 until n) {
                                val s = toScreen(i, f)
                                val dd = (s.x - tap.x) * (s.x - tap.x) + (s.y - tap.y) * (s.y - tap.y)
                                if (dd < bestD) { bestD = dd; best = i }
                            }
                            val node = best.takeIf { it >= 0 }?.let { nodes[it] }
                            if (node?.noteId != null) onOpenNote(node.noteId)
                        }
                    },
            ) {
                val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER } }
                Canvas(Modifier.fillMaxSize()) {
                    frame // read → redraw as the simulation ticks
                    if (canvas == Size.Zero) return@Canvas
                    val f = fit()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    // edges first
                    edges.forEach { e ->
                        if (!kindOn(e.kind)) return@forEach
                        val sa = toScreen(e.a, f); val sb = toScreen(e.b, f)
                        drawLine(
                            color = edgeColor(e.kind), start = sa, end = sb,
                            strokeWidth = if (e.kind == LINK) 2.2f else 1.6f,
                            pathEffect = if (e.kind == LINK) null else dash,
                        )
                    }
                    // nodes
                    val showLabels = n <= 45 || (f.first > 0.9f)
                    labelPaint.color = onSurface.toArgb()
                    labelPaint.textSize = (11.dp.toPx()) * min(1.4f, max(0.8f, f.first))
                    nodes.forEachIndexed { i, node ->
                        val s = toScreen(i, f)
                        val deg = (outCount[node.noteId] ?: 0)
                        val r = when { node.type != "note" -> 7f; else -> (7f + min(deg, 8) * 1.1f) }
                        drawCircle(colorFor(node.type), radius = r, center = s)
                        if (showLabels && node.type == "note") {
                            val lbl = node.label.let { if (it.length > 22) it.take(21) + "…" else it }
                            drawContext.canvas.nativeCanvas.drawText(lbl, s.x, s.y - r - 6f, labelPaint)
                        }
                    }
                }
            }
            // Legend.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot(notePrimary, "Notes"); LegendDot(taskColor, "Tasks"); LegendDot(habitColor, "Habits"); LegendDot(eventColor, "Events")
            }
            Text(
                if (nodes.size >= MAX_NODES) "Showing your ${MAX_NODES} most-connected notes · pinch to zoom, drag to pan, tap a note to open"
                else "Pinch to zoom · drag to pan · tap a note to open",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
