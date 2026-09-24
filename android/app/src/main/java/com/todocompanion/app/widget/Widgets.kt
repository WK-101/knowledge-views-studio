package com.todocompanion.app.widget

import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shared widget base classes + the goAsync/IO wrapper. The stateless helpers (broadcastUpdate,
 * appIntent, captureIntent, minHeightDp) live on the [Widgets] object in WidgetRefresh.kt.
 */

/** Run [block] on IO while holding the receiver's async lifetime (goAsync + finish). Works for both
 *  providers (an AppWidgetProvider IS a BroadcastReceiver) and standalone receivers. */
inline fun BroadcastReceiver.goAsyncIO(crossinline block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try { block() } finally { pending.finish() }
    }
}

/**
 * Base provider: standardises prefs cleanup on delete so a launcher-recycled widget id never inherits
 * a removed widget's stale scope/theme/habit config. Subclasses keep their own onUpdate; one that also
 * needs onDeleted work should call super.onDeleted(context, ids).
 */
abstract class BaseWidgetProvider : AppWidgetProvider() {
    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetPrefs.clear(context, it) }
    }
}

/**
 * Base RemoteViewsFactory: removes the identical scaffold (onCreate/onDestroy/getCount/getViewTypeCount/
 * getItemId/hasStableIds/getLoadingView + the rows/style fields + the resolve-then-load onDataSetChanged)
 * that every list widget's factory repeated. Subclasses implement [load] (runs on the binder worker).
 */
abstract class BaseRowFactory<T>(
    protected val context: Context,
    protected val widgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {
    protected var rows: List<T> = emptyList()
    protected var style: WidgetStyle = WidgetStyle.resolve(context)

    final override fun onCreate() {}
    final override fun onDestroy() { rows = emptyList() }
    final override fun getCount() = rows.size
    final override fun getViewTypeCount() = 1
    final override fun getItemId(position: Int) = position.toLong()
    final override fun hasStableIds() = false
    final override fun getLoadingView(): RemoteViews? = null
    final override fun onDataSetChanged() {
        style = WidgetStyle.resolve(context, widgetId)
        rows = load()
    }

    /** Compute this factory's rows (blocking DB reads are fine here — it runs off the main thread). */
    protected abstract fun load(): List<T>
}
