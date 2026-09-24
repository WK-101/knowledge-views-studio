package app.parley.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.graphics.createBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.container
import app.parley.picker.PickKind
import app.parley.picker.PickerScreen
import app.parley.ui.ParleyTheme

/** 1×1 direct-dial widget: tap to call one person. */
class DialWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
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
            val icon = photo?.let { runCatching { context.contentResolver.openInputStream(android.net.Uri.parse(it))?.use { s -> android.graphics.BitmapFactory.decodeStream(s) } }.getOrNull() }
                ?: Shortcuts.monogram(name, 160)
            views.setImageViewBitmap(R.id.widget_photo, circle(icon))
            views.setContentDescription(R.id.widget_root, "Call $name")
            val pi = PendingIntent.getActivity(
                context, id, Shortcuts.intent(context, Shortcuts.Kind.CALL, number, p.getLong("$id.contact", -1)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(id, views)
        }

        private fun circle(src: Bitmap): Bitmap {
            val size = minOf(src.width, src.height).coerceAtMost(256)
            val scaled = Bitmap.createScaledBitmap(src, size, size, true)
            val out = createBitmap(size, size)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
            return out
        }
    }
}

/** Chooses the phone number for a new direct-dial widget. */
class DialWidgetConfigActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            val s by container.settings.settings.collectAsStateWithLifecycle()
            ParleyTheme(s.themeMode, s.amoledBlack, s.dynamicColor, s.density) {
                PickerScreen(
                    kind = PickKind.PHONE, multiple = false, title = "Direct-dial widget", excludeContactId = null,
                    onCancel = { finish() },
                    onPicked = { picks ->
                        val pick = picks.firstOrNull() ?: return@PickerScreen finish()
                        val number = pick.subtitle?.substringAfter(" · ") ?: return@PickerScreen finish()
                        DialWidget.save(this, id, pick.title, number, pick.photoUri, pick.contactId)
                        DialWidget.update(this, AppWidgetManager.getInstance(this), id)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    },
                )
            }
        }
    }
}
