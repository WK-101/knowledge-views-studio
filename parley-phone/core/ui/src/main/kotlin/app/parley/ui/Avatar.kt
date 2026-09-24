package app.parley.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tiny in-process photo loader for content:// contact photos. No network, no extra libraries. */
object PhotoCache {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun peek(key: String): Bitmap? = cache.get(key)

    suspend fun load(context: Context, uri: String, sizePx: Int): Bitmap? {
        val key = "$uri@$sizePx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                val u = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }?.also { cache.put(key, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
}

private val avatarPalette = listOf(
    Color(0xFF3F51B5), Color(0xFF00897B), Color(0xFF8E24AA), Color(0xFFD81B60), Color(0xFFF4511E),
    Color(0xFF6D4C41), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFF5E35B1), Color(0xFFC0CA33),
)

fun avatarColor(seed: String): Color = avatarPalette[(seed.hashCode() and 0x7fffffff) % avatarPalette.size]

fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it[0].isLetter() }
    return when {
        words.isEmpty() -> ""
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

@Composable
fun Avatar(name: String, photoUri: String?, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val initial: ImageBitmap? = remember(photoUri, px) { photoUri?.let { PhotoCache.peek("$it@$px")?.asImageBitmap() } }
    val image by produceState(initial, photoUri, px) {
        if (value == null && photoUri != null) value = PhotoCache.load(ctx, photoUri, px)?.asImageBitmap()
    }
    Box(
        modifier.size(size).clip(CircleShape).background(if (image == null) avatarColor(name) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            val initials = initialsOf(name)
            if (initials.isNotEmpty()) {
                Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
            } else {
                Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
        }
    }
}

@Composable
fun MonoAvatar(size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.6f))
    }
}
