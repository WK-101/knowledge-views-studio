package app.parley.shortcuts

import app.parley.security.LockedActivity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.container
import app.parley.picker.PickKind
import app.parley.picker.PickerScreen
import app.parley.security.AppLock
import app.parley.security.LockScreen
import app.parley.ui.ParleyTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1×1 direct-dial widget: tap to call one person. */
class DialWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Photo decoding reads storage: keep it off the main thread.
        val pending = goAsync()
        Thread {
            try {
                ids.forEach { update(context, manager, it) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val p = prefs(context).edit()
        ids.forEach { p.remove("$it.name").remove("$it.number").remove("$it.photo").remove("$it.contact") }
        p.apply()
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("dial_widgets", Context.MODE_PRIVATE)

        fun save(context: Context, id: Int, name: String, number: String, photo: String?, contactId: Long) {
            prefs(context).edit().putString("$id.name", name).putString("$id.number", number).putString("$id.photo", photo).putLong("$id.contact", contactId).apply()
        }

        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val p = prefs(context)
            val name = p.getString("$id.name", null) ?: return
            val number = p.getString("$id.number", null) ?: return
            val photo = p.getString("$id.photo", null)
            val views = RemoteViews(context.packageName, R.layout.widget_dial)
            views.setTextViewText(R.id.widget_name, name.substringBefore(' '))
            val icon = photo?.let { runCatching { context.contentResolver.openInputStream(Uri.parse(it))?.use { s -> BitmapFactory.decodeStream(s) } }.getOrNull() }
                ?: Shortcuts.monogram(name, 160)
            views.setImageViewBitmap(R.id.widget_photo, circle(icon))
            views.setContentDescription(R.id.widget_root, context.getString(R.string.widget_call_name, name))
            val pi = WidgetTaps.activity(
                context, WidgetTaps.Kind.DIAL, id, 0, Shortcuts.intent(context, Shortcuts.Kind.CALL, number, p.getLong("$id.contact", -1), name),
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(id, views)
        }

        /** [src]'s centre square as a circle, at most [max] px across (also the Favourites widget's photos). */
        internal fun circle(src: Bitmap, max: Int = 256): Bitmap {
            val side = minOf(src.width, src.height)
            val size = side.coerceAtMost(max)
            // The centre square (a rectangular photo was stretched into the circle before).
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            val out = createBitmap(size, size)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
            return out
        }
    }
}

/** Chooses the phone number for a new direct-dial widget. */
class DialWidgetConfigActivity : LockedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Exported for the launcher: only configure widgets that really are ours.
        if (AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.packageName != packageName) {
            finish()
            return
        }
        setContent {
            val s by container.settings.settings.collectAsStateWithLifecycle()
            val loaded by container.settings.loaded.collectAsStateWithLifecycle()
            val locked by AppLock.locked.collectAsStateWithLifecycle()
            ParleyTheme(s.themeMode, s.amoledBlack, s.dynamicColor, s.density) {
                if (!loaded) return@ParleyTheme
                if (locked && s.appLock) {
                    LockScreen { AppLock.authenticate(this@DialWidgetConfigActivity) }
                    return@ParleyTheme
                }
                PickerScreen(
                    kind = PickKind.PHONE, multiple = false, title = getString(R.string.widget_pick_title), excludeContactId = null,
                    onCancel = { finish() },
                    onPicked = { picks ->
                        val pick = picks.firstOrNull() ?: return@PickerScreen finish()
                        val number = pick.subtitle?.substringAfter(" · ") ?: return@PickerScreen finish()
                        DialWidget.save(this, id, pick.title, number, pick.photoUri, pick.contactId)
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { DialWidget.update(this@DialWidgetConfigActivity, AppWidgetManager.getInstance(this@DialWidgetConfigActivity), id) }
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        }
                    },
                )
            }
        }
    }
}
