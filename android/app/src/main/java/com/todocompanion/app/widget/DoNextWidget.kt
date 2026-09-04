package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The Do-Next widget: the computed-priority top of your list, right on the home screen — the one
 * thing MLO never surfaces and TickTick can't compute. Two tappable chips filter by how much
 * ENERGY you have and how much TIME ("I have 15 minutes"), so what shows always fits the moment.
 * Tapping a task opens it; the header quick-adds. Offline — reads the local Room DB only.
 */
class DoNextWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(context, manager, id) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetPrefs.clear(context, it) }
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_donext)
        val svc = Intent(context, DoNextWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.dn_list, svc)
        views.setEmptyView(R.id.dn_list, R.id.dn_empty)
        views.setOnClickPendingIntent(R.id.dn_add, activity(context, id * 10 + 1, MainActivity.ACTION_QUICK_ADD))
        views.setOnClickPendingIntent(R.id.dn_title, activity(context, id * 10 + 2, "open_donext"))
        views.setPendingIntentTemplate(R.id.dn_list, TaskWidgetReceiver.template(context, 4202))

        views.setTextViewText(R.id.dn_energy, energyLabel(WidgetPrefs.energy(context, id)))
        views.setTextViewText(R.id.dn_time, timeLabel(WidgetPrefs.time(context, id)))
        views.setOnClickPendingIntent(R.id.dn_energy, filterIntent(context, id, DoNextFilterReceiver.WHICH_ENERGY))
        views.setOnClickPendingIntent(R.id.dn_time, filterIntent(context, id, DoNextFilterReceiver.WHICH_TIME))

        manager.updateAppWidget(id, views)
        manager.notifyAppWidgetViewDataChanged(id, R.id.dn_list)
    }

    private fun activity(context: Context, code: Int, action: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (action != null) putExtra(MainActivity.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun filterIntent(context: Context, id: Int, which: Int): PendingIntent {
        val i = Intent(context, DoNextFilterReceiver::class.java).setAction(DoNextFilterReceiver.ACTION_FILTER).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(DoNextFilterReceiver.EXTRA_WHICH, which)
        }
        return PendingIntent.getBroadcast(context, id * 10 + 4 + which, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun energyLabel(v: Int) = when (v) { 1 -> "⚡ Low"; 2 -> "⚡ Medium"; 3 -> "⚡ High"; else -> "⚡ Any energy" }
        fun timeLabel(v: Int) = when (v) { 15 -> "⏱ ≤15m"; 30 -> "⏱ ≤30m"; 60 -> "⏱ ≤1h"; else -> "⏱ Any time" }

        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, DoNextWidget::class.java))
            if (ids.isEmpty()) return
            m.notifyAppWidgetViewDataChanged(ids, R.id.dn_list)
            context.sendBroadcast(Intent(context, DoNextWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }

        fun updateOne(context: Context, id: Int) {
            val m = AppWidgetManager.getInstance(context) ?: return
            DoNextWidget().render(context, m, id)
        }
    }
}

/** Advances one of the two Do-Next filter chips, then re-renders that widget. */
class DoNextFilterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FILTER) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        when (intent.getIntExtra(EXTRA_WHICH, WHICH_ENERGY)) {
            WHICH_TIME -> WidgetPrefs.cycleTime(context, id)
            else -> WidgetPrefs.cycleEnergy(context, id)
        }
        DoNextWidget.updateOne(context, id)
    }

    companion object {
        const val ACTION_FILTER = "com.todocompanion.app.action.DONEXT_FILTER"
        const val EXTRA_WHICH = "which"
        const val WHICH_ENERGY = 0
        const val WHICH_TIME = 1
    }
}

class DoNextWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return DoNextFactory(applicationContext, id)
    }
}

private class DoNextFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private data class Row(val id: String, val rank: Int, val title: String, val sub: String)
    private var rows: List<Row> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    private fun estimateOf(t: TaskEntity): Int? = t.estimateMin ?: t.estimateMax ?: t.durationMin

    override fun onDataSetChanged() {
        val app = context.applicationContext as App
        val now = System.currentTimeMillis()
        val energy = WidgetPrefs.energy(context, widgetId)   // 0 any, 1..3
        val timeCap = WidgetPrefs.time(context, widgetId)     // 0 any, else minutes

        val tasks = runBlocking { app.repository.wsTasksOnce() }
        val settings = runBlocking { app.repository.settingsSnapshot() }
        val cfg = PriorityEngine.Config(
            mode = when (settings.priorityMode) { "importance" -> PriorityEngine.Mode.IMPORTANCE; "urgency" -> PriorityEngine.Mode.URGENCY; else -> PriorityEngine.Mode.BOTH },
            dueWeight = settings.priorityDueWeight, startWeight = settings.priorityStartWeight, goalWeight = settings.priorityGoalWeight,
            overdueBoost = settings.priorityOverdueBoost, starBoost = settings.priorityStarBoost, curveBase = settings.priorityCurveBase, computed = settings.priorityComputed,
        )
        val byId = tasks.associateBy { it.id }
        val hasChild = tasks.filter { it.parentId != null }.map { it.parentId }.toHashSet()

        rows = tasks.asSequence()
            .filter { !it.completed && !it.trashed && !it.abandoned }
            .filter { it.id !in hasChild }                                   // leaf actionables only
            .filter { !(it.hideInTodoUntilStart && it.startDate != null && it.startDate!! > now) }
            .filter { energy == 0 || it.energy == null || it.energy!! <= energy }
            .filter { timeCap == 0 || (estimateOf(it)?.let { e -> e <= timeCap } ?: true) }
            .sortedByDescending { PriorityEngine.score(it, now, byId, cfg) }
            .take(25)
            .mapIndexed { i, t ->
                val e = t.energy?.let { listOf("", "Low", "Med", "High").getOrNull(it) }
                val est = estimateOf(t)?.let { "${it}m" }
                val sub = listOfNotNull(e, est).joinToString(" · ")
                Row(t.id, i + 1, t.title.ifBlank { "Untitled" }, sub)
            }
            .toList()
    }

    override fun getViewAt(position: Int): RemoteViews {
        val r = rows[position]
        return RemoteViews(context.packageName, R.layout.widget_donext_item).apply {
            setTextViewText(R.id.dni_title, r.title)
            setTextViewText(R.id.dni_sub, r.sub)
            // R104 — tap the circle to tick it off in place; the rest of the row opens the task.
            setOnClickFillInIntent(R.id.dni_check, TaskWidgetReceiver.completeFill(r.id))
            setOnClickFillInIntent(R.id.dni_root, TaskWidgetReceiver.openFill("open_task:${r.id}"))
        }
    }
}
