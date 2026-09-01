package com.todocompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * R58 — ONE color picker for the whole app. Every place that used to show its own small, divergent swatch
 * row (habits, calendars, occasions, folders, lists, tags, contexts, flags, time activities, core values…)
 * now opens this. It offers three things the old pickers never had, together:
 *   • a rich, well-sorted PALETTE — rows are hue families, columns are shades (light→deep) + a neutral row,
 *   • a shared RECENT-colours row (remembered across every picker, via Settings),
 *   • a CUSTOM tab — an HSV spectrum (saturation/value panel + hue bar) with an editable HEX field,
 * so a color is either a tasteful preset or any exact value the user wants. Fully offline, on-device.
 */
object AppPalette {
    private fun argb(h: Float, s: Float, v: Float): Long =
        (Color.hsv(h, s, v).toArgb().toLong() and 0xFFFFFFFFL)

    // 15 hue families sweeping the wheel; 6 shades each (light → deep). A clean, sorted spectrum grid.
    private val HUES = listOf(0f, 16f, 32f, 45f, 62f, 96f, 140f, 168f, 190f, 210f, 232f, 262f, 286f, 320f, 344f)
    val hueRows: List<List<Long>> = HUES.map { h ->
        listOf(
            argb(h, .30f, .98f), argb(h, .48f, .95f), argb(h, .64f, .88f),
            argb(h, .78f, .78f), argb(h, .86f, .62f), argb(h, .90f, .45f),
        )
    }
    // Neutral row (white → black) so greys/ink are first-class, not an afterthought.
    val neutralRow: List<Long> = listOf(0xFFFFFFFF, 0xFFD1D5DB, 0xFF9CA3AF, 0xFF6B7280, 0xFF374151, 0xFF111827)
    val allRows: List<List<Long>> = hueRows + listOf(neutralRow)
}

/**
 * Shared recent-colours host, provided once at the app root so every [AppColorPicker] gets the app-wide
 * recents + a recorder without threading the ViewModel through every dialog.
 */
class ColorPickerHost(val recents: List<Long>, val record: (Long) -> Unit)
val LocalColorPickerHost = androidx.compose.runtime.staticCompositionLocalOf { ColorPickerHost(emptyList()) {} }

/**
 * The one-line entry every screen uses: a swatch that opens the unified picker and shares the app-wide
 * recent-colours list automatically (via [LocalColorPickerHost]). Replaces every old per-screen swatch row.
 */
@Composable
fun AppColorPicker(
    current: Long?,
    onPick: (Long?) -> Unit,
    allowNone: Boolean = false,
    size: Int = 30,
    modifier: Modifier = Modifier,
    presets: List<Long> = emptyList(),
    noneLabel: String = "No colour (use default)",
) {
    val host = LocalColorPickerHost.current
    ColorPickerButton(current, onPick, host.recents, host.record, allowNone, size, modifier, presets, noneLabel)
}

/** A single circular swatch that opens the unified picker. Drop-in for every old swatch row. */
@Composable
fun ColorPickerButton(
    current: Long?,
    onPick: (Long?) -> Unit,
    recents: List<Long> = emptyList(),
    onRecent: (Long) -> Unit = {},
    allowNone: Boolean = false,
    size: Int = 30,
    modifier: Modifier = Modifier,
    presets: List<Long> = emptyList(),
    noneLabel: String = "No colour (use default)",
) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(current?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        if (current == null) Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        ColorPickerSheet(
            initial = current, allowNone = allowNone, recents = recents,
            onDismiss = { open = false },
            onPick = { c -> if (c != null) onRecent(c); onPick(c); open = false },
            presets = presets, noneLabel = noneLabel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(
    initial: Long?,
    allowNone: Boolean,
    recents: List<Long>,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
    presets: List<Long> = emptyList(),
    noneLabel: String = "No colour (use default)",
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(0) }   // 0 = palette, 1 = custom
    val hsv0 = remember(initial) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV((initial ?: 0xFF3E7BFA).toInt(), out); out
    }
    var hue by remember { mutableStateOf(hsv0[0]) }
    var sat by remember { mutableStateOf(hsv0[1]) }
    var value by remember { mutableStateOf(hsv0[2]) }
    var hexText by remember { mutableStateOf("") }
    val customArgb = (android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)).toLong() and 0xFFFFFFFFL) or 0xFF000000L

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp).verticalScroll(rememberScrollState())) {
            Text("Choose colour", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Palette") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Custom") })
            }
            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                if (allowNone) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp).clickable { onPick(null) }) {
                        Box(Modifier.size(30.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                            Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(noneLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                // R61 — a screen's curated presets (e.g. the app's accent set) surface as a "Suggested" row at
                // the top of the palette, so those one-tap picks live inside the one unified picker too.
                if (presets.isNotEmpty()) {
                    Text("Suggested", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        presets.forEach { c -> Swatch(c, c == initial, Modifier.width(40.dp)) { onPick(c) } }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (recents.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        recents.forEach { c -> Swatch(c, c == initial, Modifier.width(40.dp)) { onPick(c) } }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                AppPalette.allRows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        row.forEach { c -> Swatch(c, c == initial, Modifier.weight(1f)) { onPick(c) } }
                    }
                }
            } else {
                SatValPanel(hue = hue, sat = sat, value = value) { s, v -> sat = s; value = v; hexText = "" }
                Spacer(Modifier.height(12.dp))
                HueBar(hue = hue) { h -> hue = h; hexText = "" }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(customArgb)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(12.dp))
                    val shown = hexText.ifBlank { "%06X".format(customArgb.toInt() and 0xFFFFFF) }
                    AppTextField(
                        value = shown,
                        onValueChange = { raw ->
                            val h = raw.trim().removePrefix("#").filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(6)
                            hexText = h
                            if (h.length == 6) {
                                val rgb = h.toLong(16)
                                val out = FloatArray(3)
                                android.graphics.Color.colorToHSV((0xFF000000L or rgb).toInt(), out)
                                hue = out[0]; sat = out[1]; value = out[2]
                            }
                        },
                        singleLine = true,
                        leadingIcon = { Text("#", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        label = { Text("Hex") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onPick(customArgb) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Check, null); Spacer(Modifier.width(8.dp)); Text("Use this colour")
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Long, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(9.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** Saturation (x) × Value (y) panel for the current hue, with a draggable thumb. */
@Composable
private fun SatValPanel(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    val hueColor = Color.hsv(hue, 1f, 1f)
    var w by remember { mutableStateOf(1f) }
    var h by remember { mutableStateOf(1f) }
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
            .onSizeChanged { w = it.width.toFloat(); h = it.height.toFloat() }
            .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            .pointerInput(hue) { detectTapGestures { o -> onChange((o.x / w).coerceIn(0f, 1f), 1f - (o.y / h).coerceIn(0f, 1f)) } }
            .pointerInput(hue) { detectDragGestures { c, _ -> onChange((c.position.x / w).coerceIn(0f, 1f), 1f - (c.position.y / h).coerceIn(0f, 1f)) } },
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
        val density = LocalDensity.current
        val xdp = with(density) { (sat * w).toDp() } - 9.dp
        val ydp = with(density) { ((1f - value) * h).toDp() } - 9.dp
        Box(
            Modifier.offset(x = xdp.coerceAtLeast(0.dp), y = ydp.coerceAtLeast(0.dp)).size(18.dp).clip(CircleShape)
                .background(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))).border(3.dp, Color.White, CircleShape),
        )
    }
}

/** Horizontal hue spectrum 0..360 with a draggable thumb. */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    var w by remember { mutableStateOf(1f) }
    val stops = (0..360 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) }
    Box(
        Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))
            .onSizeChanged { w = it.width.toFloat() }
            .background(Brush.horizontalGradient(stops))
            .pointerInput(Unit) { detectTapGestures { o -> onChange((o.x / w).coerceIn(0f, 1f) * 360f) } }
            .pointerInput(Unit) { detectDragGestures { c, _ -> onChange((c.position.x / w).coerceIn(0f, 1f) * 360f) } },
    ) {
        val density = LocalDensity.current
        val xdp = with(density) { ((hue / 360f) * w).toDp() } - 9.dp
        Box(
            Modifier.offset(x = xdp.coerceAtLeast(0.dp), y = 4.dp).size(18.dp).clip(CircleShape)
                .background(Color.hsv(hue, 1f, 1f)).border(3.dp, Color.White, CircleShape),
        )
    }
}
