package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Eisenhower matrix on the home screen — a real 2×2 board, not just four numbers: each quadrant lists
 * its top open tasks (soonest-due first), colour-coded, with a header count. Tapping a task opens it;
 * tapping a quadrant opens the in-app Matrix. Offline; reads the local DB, scoped to the active workspace.
 */
class MatrixWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        render(context, manager, intArrayOf(id))

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = app.repository.settingsSnapshot()
                val impT = s.matrixImportanceThreshold
                val urgT = s.matrixUrgencyThreshold
                val open = app.repository.wsTasksOnce().filter { !it.completed && !it.trashed && !it.abandoned }
                    // Soonest-due first (undated last), then most important — the order you'd triage in.
                    .sortedWith(compareBy({ it.dueDate ?: Long.MAX_VALUE }, { -it.importance }))
                // q0 urgent+important, q1 important, q2 urgent, q3 neither → tiles Do first / Schedule / Delegate / Later.
                val quads = Array(4) { mutableListOf<Pair<String, String>>() }
                open.forEach { t ->
                    val imp = t.importance >= impT; val urg = t.urgency >= urgT
                    val qi = if (imp && urg) 0 else if (imp) 1 else if (urg) 2 else 3
                    quads[qi].add(t.id to t.title.ifBlank { "Untitled" })
                }

                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, R.layout.widget_matrix)
                    val style = WidgetStyle.resolve(context, id)
                    WidgetStyle.applyListCard(views, R.id.mx_card, context, id)
                    val labelColors = intArrayOf(style.danger, style.warning, style.info, style.teal)
                    val quadRoots = intArrayOf(R.id.mx_q1, R.id.mx_q2, R.id.mx_q3, R.id.mx_q4)
                    val labelIds = intArrayOf(R.id.mx_q1_label, R.id.mx_q2_label, R.id.mx_q3_label, R.id.mx_q4_label)
                    val countIds = intArrayOf(R.id.mx_q1_count, R.id.mx_q2_count, R.id.mx_q3_count, R.id.mx_q4_count)
                    val emptyIds = intArrayOf(R.id.mx_q1_empty, R.id.mx_q2_empty, R.id.mx_q3_empty, R.id.mx_q4_empty)
                    val titleIds = arrayOf(
                        intArrayOf(R.id.mx_q1_t0, R.id.mx_q1_t1, R.id.mx_q1_t2),
                        intArrayOf(R.id.mx_q2_t0, R.id.mx_q2_t1, R.id.mx_q2_t2),
                        intArrayOf(R.id.mx_q3_t0, R.id.mx_q3_t1, R.id.mx_q3_t2),
                        intArrayOf(R.id.mx_q4_t0, R.id.mx_q4_t1, R.id.mx_q4_t2),
                    )
                    for (qi in 0 until 4) {
                        views.setTextColor(labelIds[qi], labelColors[qi])
                        views.setTextColor(countIds[qi], labelColors[qi])
                        views.setTextViewText(countIds[qi], quads[qi].size.toString())
                        views.setTextColor(emptyIds[qi], style.textTertiary)
                        views.setViewVisibility(emptyIds[qi], if (quads[qi].isEmpty()) View.VISIBLE else View.GONE)
                        // Tap the quadrant background → open the Matrix.
                        views.setOnClickPendingIntent(quadRoots[qi], openMatrix(context, id, qi))
                        val slots = titleIds[qi]
                        for (ti in slots.indices) {
                            val task = quads[qi].getOrNull(ti)
                            if (task == null) {
                                views.setViewVisibility(slots[ti], View.GONE)
                            } else {
                                views.setViewVisibility(slots[ti], View.VISIBLE)
                                views.setTextColor(slots[ti], if (ti < 2) style.textPrimary else style.textSecondary)
                                // Last visible slot shows "+N more" when the quadrant overflows.
                                val overflow = ti == slots.size - 1 && quads[qi].size > slots.size
                                if (overflow) {
                                    views.setTextViewText(slots[ti], "+${quads[qi].size - (slots.size - 1)} more")
                                    views.setTextColor(slots[ti], labelColors[qi])
                                    views.setOnClickPendingIntent(slots[ti], openMatrix(context, id, qi))
                                } else {
                                    views.setTextViewText(slots[ti], "•  ${task.second}")
                                    views.setOnClickPendingIntent(slots[ti], openTask(context, id, qi, ti, task.first))
                                }
                            }
                        }
                    }
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }

    private fun openMatrix(context: Context, widgetId: Int, qi: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_matrix")
        }
        return PendingIntent.getActivity(context, ("mx:$widgetId:q$qi").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openTask(context: Context, widgetId: Int, qi: Int, ti: Int, taskId: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_task:$taskId")
        }
        return PendingIntent.getActivity(context, ("mx:$widgetId:q$qi:t$ti").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, MatrixWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, MatrixWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
