package com.cairn.reader.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.cairn.reader.MainActivity
import com.cairn.reader.R
import com.cairn.reader.data.db.ItemDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A home-screen widget: an unread count plus a scrollable list of the latest unread headlines,
 *  each tappable to open that article. Reads the database directly through a Hilt entry point
 *  (widgets aren't Hilt-injected). */
class CairnWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun itemDao(): ItemDao
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).itemDao()
                val unread = runCatching { dao.unreadCountOnce() }.getOrDefault(0)
                appWidgetIds.forEach { id -> render(context, appWidgetManager, id, unread) }
                // Tell the list to reload its rows.
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list)
            } finally {
                pending.finish()
            }
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int, unread: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_cairn)
        views.setTextViewText(R.id.widget_unread, if (unread == 1) "1 unread" else "$unread unread")

        // The scrollable list is fed by CairnWidgetService; a unique data URI keeps each widget's
        // adapter distinct so multiple placements don't share state.
        val serviceIntent = Intent(context, CairnWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra(CairnWidgetService.EXTRA_SCOPE, "INBOX")
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widget_list, serviceIntent)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        // A template intent → MainActivity; each row fills in its item id.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val template = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        views.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(context, widgetId, template, flags),
        )
        // Tapping the header (not a row) just opens the app.
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        views.setOnClickPendingIntent(
            R.id.widget_unread,
            PendingIntent.getActivity(context, widgetId + 100000, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        manager.updateAppWidget(widgetId, views)
    }

    companion object {
        /** Push a refresh to every placed widget — called after a sync. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CairnWidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, CairnWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
