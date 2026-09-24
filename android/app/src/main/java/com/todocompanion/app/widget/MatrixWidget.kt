package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/**
 * Eisenhower matrix on the home screen — a real 2×2 board. Each quadrant is its own scrolling list of
 * the tasks that fall in it (fills the space, scrolls for more), using the app's own quadrant names and
 * honouring every in-app Matrix setting (thresholds, date range, list/folder filter, duration cap,
 * overdue-only, show-completed, sort). Tapping a task opens it. Offline; active workspace only.
 */
class MatrixWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        render(context, manager, intArrayOf(id))

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val counts = runCatching { MatrixData.load(app).map { it.size } }.getOrDefault(listOf(0, 0, 0, 0))
                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_matrix)
                    WidgetStyle.applyListCard(views, R.id.mx_card, context, id)
                    val labelColors = intArrayOf(style.danger, style.warning, style.info, style.teal)
                    val listIds = intArrayOf(R.id.mx_l0, R.id.mx_l1, R.id.mx_l2, R.id.mx_l3)
                    val emptyIds = intArrayOf(R.id.mx_e0, R.id.mx_e1, R.id.mx_e2, R.id.mx_e3)
                    val lblIds = intArrayOf(R.id.mx_lbl0, R.id.mx_lbl1, R.id.mx_lbl2, R.id.mx_lbl3)
                    val cntIds = intArrayOf(R.id.mx_cnt0, R.id.mx_cnt1, R.id.mx_cnt2, R.id.mx_cnt3)
                    val quadRoots = intArrayOf(R.id.mx_q0, R.id.mx_q1, R.id.mx_q2, R.id.mx_q3)
                    for (q in 0 until 4) {
                        views.setTextViewText(lblIds[q], MatrixData.NAMES[q])
                        views.setTextColor(lblIds[q], labelColors[q])
                        views.setTextColor(cntIds[q], labelColors[q])
                        views.setTextViewText(cntIds[q], counts.getOrElse(q) { 0 }.toString())
                        views.setTextColor(emptyIds[q], style.textTertiary)
                        val svc = Intent(context, MatrixWidgetService::class.java).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            putExtra(EXTRA_QUAD, q)
                            data = android.net.Uri.parse("matrix://$id/$q")
                        }
                        views.setRemoteAdapter(listIds[q], svc)
                        views.setEmptyView(listIds[q], emptyIds[q])
                        // Broadcast template: a row's title opens the task, its check ticks it off in place.
                        views.setPendingIntentTemplate(listIds[q], TaskWidgetReceiver.template(context, id * 8 + q + 3000))
                        views.setOnClickPendingIntent(quadRoots[q], openMatrix(context, id, q))
                    }
                    manager.updateAppWidget(id, views)
                    listIds.forEach { manager.notifyAppWidgetViewDataChanged(id, it) }
                }
            } finally { pending.finish() }
        }
    }

    private fun openMatrix(context: Context, widgetId: Int, q: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_matrix")
        }
        return PendingIntent.getActivity(context, ("mx:$widgetId:q$q").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val EXTRA_QUAD = "quad"
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

/** Shared Matrix computation: the exact same filter/sort/quadrant logic the in-app Matrix screen uses,
 *  so the widget always agrees with the app. Returns four lists (one per quadrant), fully sorted. */
object MatrixData {
    // The app's quadrant names (MatrixScreen QUAD), in PriorityEngine.quadrant() index order.
    val NAMES = arrayOf("Urgent & Important", "Not Urgent & Important", "Urgent & Unimportant", "Not Urgent & Unimportant")

    // Short-lived cache so the 5 loads that make up one Matrix update reuse a single DB read set.
    @Volatile private var cache: Array<List<TaskEntity>>? = null
    @Volatile private var cacheAt = 0L

    private fun expandFolderIds(seed: Set<String>, folders: List<FolderEntity>): Set<String> {
        if (seed.isEmpty()) return emptySet()
        val out = seed.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            folders.forEach { if (it.parentId in out && it.id !in out) { out.add(it.id); changed = true } }
        }
        return out
    }

    private fun dateEnd(filter: String): Long? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun end(d: LocalDate) = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sunday = today.plusDays((7 - today.dayOfWeek.value).toLong())
        return when (filter) {
            "today" -> end(today)
            "today_tom" -> end(today.plusDays(1))
            "this_week" -> end(sunday)
            "week_next" -> end(sunday.plusWeeks(1))
            "this_month" -> end(today.withDayOfMonth(today.lengthOfMonth()))
            "month_next" -> { val n = today.plusMonths(1); end(n.withDayOfMonth(n.lengthOfMonth())) }
            else -> null
        }
    }

    private fun sorter(mode: String): Comparator<TaskEntity> = when (mode) {
        "due" -> compareBy(nullsLast<Long>()) { it.dueDate }
        "created" -> compareBy { it.createdAt }
        "alpha" -> compareBy { it.title.lowercase() }
        "manual" -> compareBy<TaskEntity>({ it.sortOrder }, { it.createdAt })
        else -> compareByDescending<TaskEntity> { maxOf(it.importance, it.urgency) }.thenByDescending { it.star }
    }

    fun load(app: App): Array<List<TaskEntity>> {
        // One Matrix update calls this 5× (provider counts + 4 quadrant factories). Cache the result for
        // a short window so the burst collapses to a single set of DB reads instead of ~20.
        val cacheNow = android.os.SystemClock.elapsedRealtime()
        synchronized(this) { cache?.let { if (cacheNow - cacheAt < 1500L) return it } }
        val s = runBlocking { app.repository.settingsSnapshot() }
        val tasks = runBlocking { app.repository.wsTasksOnce() }
        val lists = runBlocking { app.repository.allListsOnce() }
        val folders = runBlocking { app.repository.allFolders.first() }
        val now = System.currentTimeMillis()
        val listFolderById = lists.associate { it.id to it.folderId }
        val selectedFolders = expandFolderIds(s.matrixFolderFilter, folders)
        fun inContainers(t: TaskEntity): Boolean {
            if (s.matrixListFilter.isEmpty() && s.matrixFolderFilter.isEmpty()) return true
            if (t.listId in s.matrixListFilter) return true
            val fid = t.folderId ?: listFolderById[t.listId]
            return fid != null && fid in selectedFolders
        }
        val end = dateEnd(s.matrixDateFilter)
        val visible = tasks.filter {
            !it.trashed && !it.abandoned && (s.matrixShowCompleted || !it.completed) &&
                inContainers(it) &&
                (s.matrixMaxDuration == 0 || ((it.estimateMin ?: it.estimateMax ?: it.durationMin)?.let { d -> d <= s.matrixMaxDuration } ?: true)) &&
                (!s.matrixOverdueOnly || (it.dueDate != null && it.dueDate!! < now && !it.completed)) &&
                (end == null || (it.dueDate != null && it.dueDate!! < end))
        }
        val cmp = sorter(s.matrixSort)
        val byQuad = visible.groupBy { PriorityEngine.quadrant(it, s.matrixImportanceThreshold, s.matrixUrgencyThreshold) }
        val result = Array(4) { q -> (byQuad[q] ?: emptyList()).sortedWith(cmp) }
        synchronized(this) { cache = result; cacheAt = android.os.SystemClock.elapsedRealtime() }
        return result
    }
}

class MatrixWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val quad = intent.getIntExtra(MatrixWidget.EXTRA_QUAD, 0)
        return MatrixQuadFactory(applicationContext, id, quad)
    }
}

private class MatrixQuadFactory(private val context: Context, private val widgetId: Int, private val quad: Int) : RemoteViewsService.RemoteViewsFactory {
    private data class Row(val id: String, val title: String, val done: Boolean,
                           val priColor: Int = 0, val priTint: Float = 0f)
    private var rows: List<Row> = emptyList()
    private var checkPx: Int = 0

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    override fun onDataSetChanged() {
        val app = context.applicationContext as App
        checkPx = WidgetBitmaps.dp(context, 20f).toInt()
        val cap = WidgetPrefs.matrixRows(context, widgetId).let { if (it <= 0) 30 else it }
        rows = runCatching {
            MatrixData.load(app).getOrElse(quad) { emptyList() }
                .take(cap)
                .map {
                    val (pc, pt) = WidgetBitmaps.priorityColorAndTint(it.importance, it.urgency)
                    Row(it.id, it.title.ifBlank { "Untitled" }, it.completed, priColor = pc, priTint = pt)
                }
        }.getOrDefault(emptyList())
    }

    override fun getViewAt(position: Int): RemoteViews {
        val r = rows[position]
        val style = WidgetStyle.resolve(context, widgetId)
        return RemoteViews(context.packageName, R.layout.widget_matrix_item).apply {
            // The app's priority checkbox (rounded square, priority-coloured + rest tint), matching the
            // in-app row and the Agenda/Day/Do-Next widgets. Tapping it ticks the task off in place;
            // tapping the title opens it.
            setImageViewBitmap(R.id.mx_i_check, WidgetBitmaps.priorityCheckbox(checkPx, r.priColor, r.done, r.priTint))
            setTextViewText(R.id.mx_i_title, r.title)
            setTextColor(R.id.mx_i_title, if (r.done) style.textTertiary else style.textPrimary)
            if (r.done) {
                // Already done — the whole row just opens the task (no re-complete).
                setOnClickFillInIntent(R.id.mx_i_root, TaskWidgetReceiver.openFill("open_task:${r.id}"))
            } else {
                setOnClickFillInIntent(R.id.mx_i_check, TaskWidgetReceiver.completeFill(r.id))
                setOnClickFillInIntent(R.id.mx_i_title, TaskWidgetReceiver.openFill("open_task:${r.id}"))
                setOnClickFillInIntent(R.id.mx_i_root, TaskWidgetReceiver.openFill("open_task:${r.id}"))
            }
        }
    }
}
