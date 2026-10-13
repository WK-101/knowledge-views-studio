package app.parley.situations

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.parley.IntentRoutes
import app.parley.R
import app.parley.common.catching
import app.parley.common.situations.Situation
import app.parley.common.situations.SituationWidgetPlan
import app.parley.common.widgets.WidgetShape
import app.parley.container
import app.parley.data.StatePrefs
import app.parley.data.situations.SituationsController
import app.parley.shortcuts.WidgetSizing
import app.parley.shortcuts.WidgetTaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Situations widget: the Situation on now ("Night is on", or "No Situation") and a tap to turn each one on or off
 * ([SituationWidgetPlan]). A tap goes through [SituationsController.turnOn]/[SituationsController.turnOff], the switch
 * the app and the Quick Settings tile use, so the snapshot, the "Set by …" banners and putting things back all work
 * as from there; like the tile, it sets no end ("Until I turn it off"). Like the tile, it shows the Situations' names:
 * nothing about them is private (no duress or app-lock rule covers them), and it never shows Rescue call. It changes
 * nothing while the phone is locked. Redrawn whenever a Situation changes, whatever changed it ([SituationTriggers]).
 */
class SituationsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redrawAsync(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redrawAsync(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) return super.onReceive(context, intent)
        val id = intent.getStringExtra(EXTRA_SITUATION) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        val c = app.container
        c.scope.launch(Dispatchers.IO) {
            try {
                // A Situation changes who may ring: never from a locked phone (the tile asks for the unlock first).
                val locked = app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
                if (!locked) catching { toggle(c.situations, id) }
                redraw(app)
            } finally {
                pending.finish()
            }
        }
    }

    private fun redrawAsync(context: Context) {
        val app = context.applicationContext
        val pending = goAsync()
        app.container.scope.launch(Dispatchers.IO) {
            try {
                redraw(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal const val ACTION_TOGGLE = "app.parley.action.SITUATION_WIDGET_TOGGLE"
        private const val EXTRA_SITUATION = "situation"
        private const val K_LAST = "last_on"
        private const val USED = "used."

        private fun prefs(context: Context) = StatePrefs.open(context, "situation_widgets")

        /** The one on goes off (what was set before it comes back); any other goes on, the one on going off first. */
        internal suspend fun toggle(sit: SituationsController, id: String) {
            if (sit.state.value.activeId == id) sit.turnOff() else sit.turnOn(id, until = null)
        }

        /** A Situation changed (from anywhere): counted for the single toggle, and every widget drawn again. */
        fun update(context: Context) {
            val app = context.applicationContext
            val c = app.container
            c.scope.launch(Dispatchers.IO) { catching { redraw(app) } }
        }

        private fun ids(context: Context): IntArray = catching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, SituationsWidget::class.java))
        }.getOrNull() ?: IntArray(0)

        /** Counts a Situation newly on, then redraws every widget (none placed: only the count). */
        internal suspend fun redraw(context: Context) {
            val sit = withContext(Dispatchers.IO) { context.container.situations }
            val list = sit.list.value
            val activeId = sit.state.value.activeId
            val p = prefs(context)
            val used = list.associate { it.id to p.getInt(USED + it.id, 0) }
            val last = p.getString(K_LAST, null)
            val counts = SituationWidgetPlan.counted(used, last, activeId) ?: used
            if (activeId != last) {
                val e = p.edit().putString(K_LAST, activeId)
                if (counts !== used) counts.forEach { (k, n) -> e.putInt(USED + k, n) }
                e.apply()
            }
            val ids = ids(context)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { id ->
                catching { manager.updateAppWidget(id, views(context, manager, id, list, activeId, counts)) }
            }
        }

        @Suppress("LongParameterList") // RemoteViews are built from plain values: no state object to pass instead.
        internal fun views(
            ctx: Context, manager: AppWidgetManager, id: Int, list: List<Situation>, activeId: String?, used: Map<String, Int>,
        ): RemoteViews = WidgetSizing.responsive(manager, id) { size ->
            val fit = SituationWidgetPlan.fit(size.widthDp, size.heightDp)
            if (fit == null) single(ctx, id, list, activeId, used, size.heightDp) else chips(ctx, id, list, activeId, used, fit)
        }

        private fun openSituations(ctx: Context, id: Int): PendingIntent = WidgetTaps.activity(
            ctx, WidgetTaps.Kind.SITUATIONS_APP, id, 0,
            IntentRoutes.own(ctx).setAction(IntentRoutes.ACTION_OPEN_SITUATIONS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        /** A tap on [s], the [place]th of the list (the same in every size, so each size's tap says the same). */
        internal fun toggleTap(ctx: Context, id: Int, place: Int, s: Situation): PendingIntent = WidgetTaps.broadcast(
            ctx, WidgetTaps.Kind.SITUATION_TOGGLE, id,
            Intent(ctx, SituationsWidget::class.java).setAction(ACTION_TOGGLE).putExtra(EXTRA_SITUATION, s.id), place,
        )

        private fun tapText(ctx: Context, name: String, on: Boolean): String =
            ctx.getString(if (on) R.string.sit_widget_on_cd else R.string.sit_widget_off_cd, name)

        /** One cell: the one on, else the one turned on most; the icon shows whether it's on. */
        @Suppress("LongParameterList") // RemoteViews are built from plain values: no state object to pass instead.
        private fun single(ctx: Context, id: Int, list: List<Situation>, activeId: String?, used: Map<String, Int>, heightDp: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_compact)
            v.setViewVisibility(R.id.compact_label, if (WidgetShape.labelFits(heightDp)) View.VISIBLE else View.GONE)
            val pickId = SituationWidgetPlan.favourite(list.map { it.id }, activeId, used)
            val pick = list.firstOrNull { it.id == pickId }
            if (pick == null) {
                v.setImageViewResource(R.id.compact_icon, R.drawable.ic_widget_situation_off)
                v.setTextViewText(R.id.compact_label, ctx.getString(R.string.sit_widget_none))
                v.setContentDescription(R.id.compact_icon, ctx.getString(R.string.sit_widget_none))
                val open = openSituations(ctx, id)
                v.setOnClickPendingIntent(R.id.compact_root, open)
                v.setOnClickPendingIntent(R.id.compact_icon, open)
                return v
            }
            val on = pick.id == activeId
            val name = SituationTriggers.name(ctx, pick)
            v.setImageViewResource(R.id.compact_icon, if (on) R.drawable.ic_widget_situation else R.drawable.ic_widget_situation_off)
            v.setTextViewText(R.id.compact_label, name)
            v.setContentDescription(R.id.compact_icon, tapText(ctx, name, on))
            val tap = toggleTap(ctx, id, list.indexOf(pick), pick)
            v.setOnClickPendingIntent(R.id.compact_root, tap)
            v.setOnClickPendingIntent(R.id.compact_icon, tap)
            v.setOnClickPendingIntent(R.id.compact_label, tap)
            return v
        }

        /** Wider: chips (the one on filled), and two cells high the line above them, which opens Situations. */
        @Suppress("LongParameterList") // RemoteViews are built from plain values: no state object to pass instead.
        private fun chips(
            ctx: Context, id: Int, list: List<Situation>, activeId: String?, used: Map<String, Int>, fit: SituationWidgetPlan.Fit,
        ): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_situations)
            v.removeAllViews(R.id.sit_rows)
            val active = list.firstOrNull { it.id == activeId }
            v.setViewVisibility(R.id.sit_title, if (fit.title) View.VISIBLE else View.GONE)
            v.setTextViewText(
                R.id.sit_title,
                active?.let { ctx.getString(R.string.sit_widget_on, SituationTriggers.name(ctx, it)) } ?: ctx.getString(R.string.sit_widget_none),
            )
            v.setOnClickPendingIntent(R.id.sit_title, openSituations(ctx, id))
            val shown = SituationWidgetPlan.chips(list.map { it.id }, activeId, used, fit.capacity).mapNotNull { sid -> list.firstOrNull { it.id == sid } }
            if (shown.isEmpty()) {
                v.setViewVisibility(R.id.sit_title, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.sit_root, openSituations(ctx, id))
                return v
            }
            shown.chunked(fit.perRow).forEach { row ->
                val line = RemoteViews(ctx.packageName, R.layout.widget_situation_row)
                row.forEach { s ->
                    val on = s.id == activeId
                    val chip = RemoteViews(ctx.packageName, if (on) R.layout.widget_situation_chip_on else R.layout.widget_situation_chip_off)
                    val name = SituationTriggers.name(ctx, s)
                    chip.setTextViewText(R.id.sit_chip, name)
                    chip.setContentDescription(R.id.sit_chip, tapText(ctx, name, on))
                    chip.setOnClickPendingIntent(R.id.sit_chip, toggleTap(ctx, id, list.indexOf(s), s))
                    line.addView(R.id.sit_row, chip)
                }
                // Empty places keep the chips lined up on the last row.
                repeat(fit.perRow - row.size) { line.addView(R.id.sit_row, RemoteViews(ctx.packageName, R.layout.widget_situation_gap)) }
                v.addView(R.id.sit_rows, line)
            }
            return v
        }
    }
}
