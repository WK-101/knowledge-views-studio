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
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parley.common.Initials
import app.parley.common.people.AvatarStyle
import app.parley.common.people.AvatarText
import app.parley.common.ux.AvatarPalette
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

fun avatarColor(seed: String): Color = Color(AvatarPalette.colorFor(seed))

/** The initials' colour on [avatarColor] (white or a dark ink, whichever reaches 4.5:1). */
fun avatarInk(seed: String): Color = Color(AvatarPalette.inkFor(AvatarPalette.colorFor(seed)))

/** Grapheme-aware (see [app.parley.common.Initials]). */
fun initialsOf(name: String): String = Initials.of(name)

/** How avatars without a photo look (Settings › Appearance › Avatars), provided at the app's root. */
val LocalAvatarStyle = staticCompositionLocalOf { AvatarStyle.COLOURFUL }

@Composable
fun Avatar(name: String, photoUri: String?, size: Dp = 44.dp, modifier: Modifier = Modifier, isCompany: Boolean = false) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val initial: ImageBitmap? = remember(photoUri, px) { photoUri?.let { PhotoCache.peek("$it@$px")?.asImageBitmap() } }
    val image by produceState(initial, photoUri, px) {
        if (value == null && photoUri != null) value = PhotoCache.load(ctx, photoUri, px)?.asImageBitmap()
    }
    val grey = LocalAvatarStyle.current == AvatarStyle.GREY
    val emoji = remember(name) { AvatarText.leadingEmoji(name) }
    // The grey monogram is a soft vertical gradient in the theme's neutral tones.
    val greyTop = MaterialTheme.colorScheme.surfaceContainerHighest
    val greyBottom = MaterialTheme.colorScheme.outlineVariant
    val background = when {
        image != null -> Modifier.background(Color.Transparent)
        emoji != null -> Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
        grey -> Modifier.background(Brush.verticalGradient(listOf(greyTop, greyBottom)))
        else -> Modifier.background(avatarColor(name))
    }
    val ink = if (grey && image == null && emoji == null) MaterialTheme.colorScheme.onSurfaceVariant else avatarInk(name)
    // Initials follow the circle, not the font size: at 200 % text they would no longer fit in it.
    val density = LocalDensity.current
    val initialsSize = with(density) { (size * 0.38f).toSp() }
    val emojiSize = with(density) { (size * 0.5f).toSp() }
    Box(
        modifier.size(size).clip(CircleShape).then(background),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else if (emoji != null && !isCompany) {
            // "🐶 Rex" shows the dog.
            Text(emoji, fontSize = emojiSize)
        } else {
            val initials = if (isCompany) "" else initialsOf(name)
            if (isCompany) {
                // A contact that is only a company gets a building, not the company's initials.
                Icon(Icons.Rounded.Business, null, tint = ink, modifier = Modifier.size(size * 0.55f))
            } else if (initials.isNotEmpty()) {
                Text(initials, color = ink, fontWeight = FontWeight.SemiBold, fontSize = initialsSize)
            } else {
                Icon(Icons.Rounded.Person, null, tint = ink, modifier = Modifier.size(size * 0.6f))
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
