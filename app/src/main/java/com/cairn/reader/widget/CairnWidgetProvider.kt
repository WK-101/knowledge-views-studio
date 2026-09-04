package com.cairn.reader.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

/** A small home-screen widget: unread count + the latest headline, tap to open Cairn.
 *  Reads the database directly through a Hilt entry point (widgets aren't Hilt-injected). */
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
                val latest = runCatching { dao.latestInboxTitle() }.getOrNull()
                appWidgetIds.forEach { id -> render(context, appWidgetManager, id, unread, latest) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int, unread: Int, latest: String?) {
        val views = RemoteViews(context.packageName, R.layout.widget_cairn)
        views.setTextViewText(R.id.widget_unread, if (unread == 1) "1 unread" else "$unread unread")
        views.setTextViewText(R.id.widget_latest, latest?.takeIf { it.isNotBlank() } ?: "You're all caught up")
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, widgetId, launch, flags))
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
