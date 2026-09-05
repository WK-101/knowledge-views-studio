package com.cairn.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A dependency-free PDF reader built on the platform [PdfRenderer]. The document is opened
 * off the main thread and its page sizes are read once up front; pages then render lazily
 * and one at a time (PdfRenderer allows only one open page), down-scaled to a sensible
 * width, so even a large imported PDF stays within memory and never blocks the UI.
 */
@Composable
fun PdfView(padding: PaddingValues, path: String?, background: Color) {
    val doc by produceState<PdfDoc?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { PdfDoc.open(path) }
    }
    DisposableEffect(doc) { onDispose { doc?.close() } }

    val current = doc
    when {
        current == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        current.pageCount == 0 -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("This PDF couldn't be opened.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Box(Modifier.fillMaxSize().background(background)) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                items(current.pageCount) { index ->
                    val bitmap by produceState<Bitmap?>(initialValue = null, index, current) {
                        value = current.render(index, targetWidth = 1240)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .aspectRatio(current.aspectRatio(index))
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Page ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
            // Floating page indicator that tracks the top-most visible page.
            val pageLabel by remember {
                derivedStateOf { "${listState.firstVisibleItemIndex + 1} / ${current.pageCount}" }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 3.dp,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                Text(
                    pageLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Holds an open [PdfRenderer] and renders pages one at a time. Page dimensions are captured
 *  at open time so layout never has to open a page concurrently with a render. */
private class PdfDoc private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val ratios: FloatArray,
) {
    val pageCount: Int get() = ratios.size
    private val mutex = Mutex()

    fun aspectRatio(index: Int): Float = ratios.getOrElse(index) { 0.7f }

    suspend fun render(index: Int, targetWidth: Int): Bitmap? = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Render defensively: a full-size ARGB_8888 page can exhaust memory and, if the error is
            // swallowed, leave a permanent white page. On OutOfMemoryError, shrink and drop to a
            // lighter bitmap config and retry, rather than failing silently.
            var width = targetWidth.coerceIn(360, 1240)
            var config = Bitmap.Config.ARGB_8888
            var result: Bitmap? = null
            var attempt = 0
            while (attempt < 3 && result == null) {
                try {
                    result = renderer.openPage(index).use { page ->
                        val scale = width.toFloat() / page.width.coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, h, config)
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                } catch (oom: OutOfMemoryError) {
                    width = (width * 2 / 3).coerceAtLeast(320)
                    config = Bitmap.Config.RGB_565
                    @Suppress("ExplicitGarbageCollectionCall") System.gc()
                } catch (e: Exception) {
                    return@withContext null
                }
                attempt++
            }
            result
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        fun open(path: String?): PdfDoc? {
            if (path.isNullOrBlank()) return null
            val file = File(path)
            if (!file.exists()) return null
            return runCatching {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)
                val ratios = FloatArray(renderer.pageCount) { i ->
                    renderer.openPage(i).use { p -> p.width.toFloat() / p.height.coerceAtLeast(1) }
                }
                PdfDoc(fd, renderer, ratios)
            }.getOrNull()
        }
    }
}
