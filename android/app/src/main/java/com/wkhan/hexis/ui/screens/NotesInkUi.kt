package com.wkhan.hexis.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

/**
 * L14 — a permission-free handwriting/ink pad. Draw with a finger or stylus on a paper-white board; on
 * save the strokes are rasterised to a PNG ([onSave]) that the editor stores as a note image attachment.
 * Ink is rendered near-black on white so it reads on any theme (like a scanned page). No camera, no mic,
 * no storage permission — the bytes go straight into the note.
 */
@Composable
fun InkPadDialog(onSave: (png: ByteArray, caption: String) -> Unit, onDismiss: () -> Unit) {
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var tick by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var caption by remember { mutableStateOf("") }   // L14/Wave2 — a caption keeps handwriting FTS-searchable
    val strokeWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }
    val paper = Color(0xFFFCFCF9)
    val inkColor = Color(0xFF20242B)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = strokes.any { it.size > 1 },
                onClick = {
                    val w = size.width.coerceAtLeast(1); val h = size.height.coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(bmp)
                    c.drawColor(android.graphics.Color.rgb(252, 252, 249))
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true; color = android.graphics.Color.rgb(32, 36, 43)
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = strokeWidthPx
                        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    strokes.forEach { s ->
                        if (s.size == 1) { c.drawPoint(s[0].x, s[0].y, paint.apply { style = android.graphics.Paint.Style.FILL }); paint.style = android.graphics.Paint.Style.STROKE }
                        else if (s.size > 1) {
                            val p = android.graphics.Path().apply {
                                moveTo(s[0].x, s[0].y); for (i in 1 until s.size) lineTo(s[i].x, s[i].y)
                            }
                            c.drawPath(p, paint)
                        }
                    }
                    val out = ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    bmp.recycle()
                    onSave(out.toByteArray(), caption.trim())
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = strokes.isNotEmpty(), onClick = { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex); tick++ } }) { Text("Undo") }
                TextButton(enabled = strokes.isNotEmpty(), onClick = { strokes.clear(); tick++ }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        title = { Text("Handwrite") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Draw with your finger or stylus.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier.fillMaxWidth().padding(top = 10.dp).aspectRatio(1.3f)
                        .clip(RoundedCornerShape(12.dp)).background(paper)
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { off -> strokes.add(mutableListOf(off)); tick++ },
                                onDrag = { change, _ -> strokes.lastOrNull()?.add(change.position); change.consume(); tick++ },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1.3f)) {
                        tick // read → redraw as strokes grow
                        strokes.forEach { s ->
                            if (s.size == 1) drawCircle(inkColor, radius = strokeWidthPx / 2f, center = s[0])
                            else if (s.size > 1) {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(s[0].x, s[0].y); for (i in 1 until s.size) lineTo(s[i].x, s[i].y)
                                }
                                drawPath(path, inkColor, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                com.wkhan.hexis.ui.components.AppTextField(
                    value = caption, onValueChange = { caption = it.take(80) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Caption (keeps this handwriting searchable)") },
                )
            }
        },
    )
}
