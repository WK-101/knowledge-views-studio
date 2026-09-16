package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R

/**
 * A home-screen Notes widget with two tap targets — a new blank note and today's daily (journal) note.
 * Both deep-link into the app (todocompanion://note / todocompanion://daily), which MainActivity resolves
 * to a launchAction consumed in AppRoot. Fully offline; no capture popup because notes are longer-form
 * and belong in the editor, unlike the task quick-capture widget.
 */
class NoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        fun deepLink(host: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("todocompanion://$host")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val views = RemoteViews(context.packageName, R.layout.widget_note).apply {
            setOnClickPendingIntent(R.id.note_new, deepLink("note", 0))
            setOnClickPendingIntent(R.id.note_today, deepLink("daily", 1))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
