package app.parley.shortcuts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import app.parley.MainActivity
import app.parley.common.ContactSummary
import app.parley.ui.avatarColor
import app.parley.ui.initialsOf
import androidx.compose.ui.graphics.toArgb

/** Home-screen shortcuts: pinned per-contact ones and dynamic ones for top favourites. */
object Shortcuts {
    enum class Kind { CALL, MESSAGE, OPEN }

    fun intent(context: Context, kind: Kind, number: String?, contactId: Long?): Intent =
        Intent(context, ShortcutActivity::class.java)
            .setAction(ShortcutActivity.ACTION)
            .putExtra(ShortcutActivity.EXTRA_KIND, kind.name)
            .putExtra(ShortcutActivity.EXTRA_NUMBER, number)
            .putExtra(ShortcutActivity.EXTRA_CONTACT, contactId ?: -1L)

    fun icon(context: Context, name: String, photoUri: String?): IconCompat {
        val bmp = photoUri?.let { runCatching { context.contentResolver.openInputStream(Uri.parse(it))?.use { s -> BitmapFactory.decodeStream(s) } }.getOrNull() }
        return IconCompat.createWithAdaptiveBitmap(bmp?.let { square(it) } ?: monogram(name))
    }

    private fun square(src: Bitmap): Bitmap {
        val size = 216
        val out = createBitmap(size, size)
        val side = minOf(src.width, src.height)
        val sx = (src.width - side) / 2
        val sy = (src.height - side) / 2
        Canvas(out).drawBitmap(src, android.graphics.Rect(sx, sy, sx + side, sy + side), android.graphics.Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun monogram(name: String, size: Int = 216): Bitmap {
        val out = createBitmap(size, size)
        val c = Canvas(out)
        c.drawColor(avatarColor(name).toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = size * 0.32f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        c.drawText(initialsOf(name).ifEmpty { "#" }, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return out
    }

    /** [lookupKey] makes an "open contact" shortcut survive contact re-aggregation (ids change, lookup keys don't). */
    fun pin(context: Context, kind: Kind, name: String, number: String?, contactId: Long?, photoUri: String?, lookupKey: String? = null): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val label = when (kind) {
            Kind.CALL -> name
            Kind.MESSAGE -> context.getString(app.parley.R.string.shortcut_text_name, name)
            Kind.OPEN -> name
        }
        val info = ShortcutInfoCompat.Builder(context, "pin-${kind.name}-${contactId ?: number}")
            .setShortLabel(label.take(24))
            .setLongLabel(label)
            .setIcon(icon(context, name, photoUri))
            .setIntent(
                if (kind == Kind.OPEN && contactId != null && !lookupKey.isNullOrEmpty()) {
                    // Opens the contact page through its lookup URI, which MainActivity resolves to the current id.
                    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                        .setData(android.provider.ContactsContract.Contacts.getLookupUri(contactId, lookupKey))
                } else {
                    intent(context, kind, number, contactId)
                },
            )
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** Keeps launcher long-press shortcuts in sync with favourites. */
    fun updateDynamic(context: Context, favorites: List<ContactSummary>) {
        runCatching {
            val list = ArrayList<ShortcutInfoCompat>()
            list += ShortcutInfoCompat.Builder(context, "new-contact")
                .setShortLabel(context.getString(app.parley.R.string.shortcut_new_contact))
                .setIcon(IconCompat.createWithResource(context, app.parley.R.drawable.ic_shortcut_add))
                .setIntent(Intent(context, MainActivity::class.java).setAction(android.content.Intent.ACTION_INSERT).setType("vnd.android.cursor.dir/contact"))
                .build()
            favorites.filter { it.phones.isNotEmpty() }.take(3).forEachIndexed { i, c ->
                list += ShortcutInfoCompat.Builder(context, "fav-${c.id}")
                    .setShortLabel(c.displayName.take(24))
                    .setIcon(icon(context, c.displayName, c.photoUri))
                    .setIntent(intent(context, Kind.CALL, c.phones.first().number, c.id))
                    .setRank(i + 1)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, list.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)))
        }
    }
}
