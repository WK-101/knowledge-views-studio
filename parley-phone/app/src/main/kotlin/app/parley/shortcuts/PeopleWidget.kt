package app.parley.shortcuts

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.core.content.ContextCompat
import app.parley.common.catching
import app.parley.common.people.FavoriteSort
import app.parley.common.people.FavoritesWidgetPlan
import app.parley.common.people.PeopleWidgetPlan
import app.parley.common.people.PeopleWidgetPlan.Mode
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.StatePrefs
import app.parley.security.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home-screen widgets' shared life: placed, resized, revealed and removed. Two providers stay registered, because a
 * placed widget belongs to its provider for good: [FavoritesWidget] is the People widget in the widget list (Favourites
 * or Circle, chosen when placed), and [CircleWidget] keeps drawing the Circle widgets placed before there was one.
 */
abstract class PeopleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isNotEmpty()) PeopleWidget.placed(context)
        refreshAsync(context)
    }

    override fun onDisabled(context: Context) = PeopleWidget.placed(context)

    override fun onDeleted(context: Context, ids: IntArray) = PeopleWidget.forget(context, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refreshAsync(context)

    override fun onReceive(context: Context, intent: Intent) {
        // "Tap to show" on the locked drawing: drawn again, with names only if the phone is unlocked now. The old
        // actions still come from drawings made before the widgets were one.
        if (intent.action in PeopleWidget.REVEAL_ACTIONS) refreshAsync(context) else super.onReceive(context, intent)
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        context.container.scope.launch {
            try {
                PeopleWidget.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The People widget (its component name is the Favourites widget's, so the ones already placed keep working). */
class FavoritesWidget : PeopleWidgetProvider()

/** Circle widgets placed before the People widget; their entry leaves the widget list once none is placed. */
class CircleWidget : PeopleWidgetProvider()

/**
 * Draws every People widget in its mode ([PeopleWidgetPlan]) and keeps them current. Plain RemoteViews (no Glance):
 * Favourites is a grid ([FavouritesDrawing]), the Circle its digest's people and dates ([CircleDrawing]).
 *
 * - Only phone contacts are ever read: private contacts never are in a widget, whoever is allowed to see them in Parley.
 * - With the app lock on, a locked phone (or Parley) shows only counts, never names or photos; names come back with
 *   USER_PRESENT while Parley runs, when Parley unlocks ([AppLock.locked]), or with a tap.
 * - The app redraws them when what they show changes: for Favourites the contacts, their order and the lock; for the
 *   Circle also the Circle, interactions, the call history and To call, and daily from the reminders worker.
 */
object PeopleWidget {
    const val ACTION_REVEAL = "app.parley.action.PEOPLE_WIDGET_REVEAL"
    val REVEAL_ACTIONS = setOf(ACTION_REVEAL, "app.parley.action.FAVORITES_WIDGET_REVEAL", "app.parley.action.CIRCLE_WIDGET_REVEAL")

    /** The modes of the widgets placed now; null until first asked. */
    private val modes = MutableStateFlow<Set<Mode>?>(null)

    /** Whether the last drawing was the locked one; null: unknown (e.g. a new process). */
    @Volatile
    private var shownLocked: Boolean? = null

    private fun prefs(context: Context) = StatePrefs.open(context, "favorites_widgets")

    private fun tapKey(id: Int) = "$id.tap"

    private fun modeKey(id: Int) = "$id.mode"

    /** What a tap on a favourite does in widget [id] (calls, unless chosen otherwise). */
    fun tap(context: Context, id: Int): FavoritesWidgetPlan.Tap =
        catching { FavoritesWidgetPlan.Tap.valueOf(prefs(context).getString(tapKey(id), null).orEmpty()) }.getOrDefault(FavoritesWidgetPlan.Tap.CALL)

    /** What widget [id] shows. */
    fun mode(context: Context, id: Int): Mode =
        PeopleWidgetPlan.mode(prefs(context).getString(modeKey(id), null), legacyCircle = id in ids(context, CircleWidget::class.java))

    /** The People widget's settings, chosen when placed or later (Done). */
    fun choose(context: Context, id: Int, mode: Mode, tap: FavoritesWidgetPlan.Tap) {
        prefs(context).edit().putString(modeKey(id), mode.name).putString(tapKey(id), tap.name).apply()
        placed(context)
    }

    internal fun forget(context: Context, ids: IntArray) {
        val p = prefs(context).edit()
        ids.forEach { p.remove(tapKey(it)).remove(modeKey(it)) }
        p.apply()
    }

    private fun ids(context: Context, provider: Class<*>): IntArray =
        catching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider)) }.getOrDefault(IntArray(0))

    /** Every placed widget with its mode and the provider it belongs to. */
    private fun placedNow(context: Context): List<Triple<Int, Mode, Class<*>>> =
        ids(context, FavoritesWidget::class.java).map { Triple(it, mode(context, it), FavoritesWidget::class.java) } +
            ids(context, CircleWidget::class.java).map { Triple(it, Mode.CIRCLE, CircleWidget::class.java) }

    /** Widgets were placed or removed: what the app follows changes, and the old Circle entry may leave the list. */
    internal fun placed(context: Context) {
        val ctx = context.applicationContext
        modes.value = placedNow(ctx).map { it.second }.toSet()
        keepCircleEntry(ctx)
    }

    /**
     * Takes the old Circle widget out of the widget list once none is placed (a Circle widget is a People widget now).
     * Never while one is placed: disabling a provider removes its widgets from the home screen.
     */
    private fun keepCircleEntry(ctx: Context) {
        // Unknown (the widget service didn't answer) counts as placed.
        val placed = catching { AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, CircleWidget::class.java)).size }.getOrNull() ?: return
        if (PeopleWidgetPlan.keepCircleEntry(placed)) return
        catching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, CircleWidget::class.java), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
        }
    }

    /** Parley came to the front (so the phone is unlocked): brings names back if a widget still hides them. */
    suspend fun refreshIfShownLocked(context: Context) {
        if (shownLocked != false) refresh(context)
    }

    /** Re-draws every People widget (no-op without any). */
    suspend fun refresh(context: Context) {
        val ctx = context.applicationContext
        val placed = placedNow(ctx)
        if (placed.isEmpty()) return
        val c = ctx.container
        val shown = placed.map { it.second }.toSet()
        // Each mode's people are read once per refresh, and only when a widget shows them.
        val favourites = if (Mode.FAVOURITES in shown) {
            catching { withContext(Dispatchers.IO) { FavouritesDrawing.load(ctx, c) } }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val circle = if (Mode.CIRCLE in shown) catching { withContext(Dispatchers.IO) { CircleDrawing.load(ctx, c) } }.getOrNull() else null
        // With the app lock on, names show only while both the phone and Parley are unlocked.
        val s = c.settings.current()
        val deviceLocked = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
        val locked = s.appLock && (deviceLocked || AppLock.lockedFor(s))
        val manager = AppWidgetManager.getInstance(ctx)
        // Photos are decoded once per refresh, whichever widgets show them.
        val photos = HashMap<Long, Bitmap>()
        placed.forEach { (id, mode, provider) ->
            val look = Look(locked, !deviceLocked, provider)
            catching {
                val views = when (mode) {
                    Mode.FAVOURITES -> FavouritesDrawing.views(ctx, id, manager, favourites, look, photos)
                    Mode.CIRCLE -> CircleDrawing.views(ctx, id, manager, circle ?: CircleDrawing.Content(emptyList(), emptyList()), look)
                }
                manager.updateAppWidget(id, views)
            }
        }
        shownLocked = locked
    }

    /**
     * How a widget is drawn now: [locked] (counts only); [unlockInParley]: only Parley is locked, so a tap opens it to
     * unlock; [provider]: the widget's own provider, which a "tap to show" goes to.
     */
    internal class Look(val locked: Boolean, val unlockInParley: Boolean, val provider: Class<*>)

    internal fun reveal(ctx: Context, kind: WidgetTaps.Kind, id: Int, look: Look): PendingIntent =
        WidgetTaps.broadcast(ctx, kind, id, Intent(ctx, look.provider).setAction(ACTION_REVEAL))

    /**
     * Keeps the widgets current while Parley runs, following only what the placed widgets show, and redraws them when
     * the screen turns off (names hide) or the phone is unlocked (names return). Called once from the Application.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observe(context: Context, c: DataContainer) {
        val ctx = context.applicationContext
        c.scope.launch {
            c.fullStart.await()
            if (modes.value == null) placed(ctx)
            // Following the history index keeps the call log, the archive and the contacts loaded, which a process
            // started for a call must not pay for: only while a Circle widget is placed.
            modes.flatMapLatest { shown ->
                if (shown.isNullOrEmpty()) {
                    emptyFlow()
                } else {
                    combine(
                        if (Mode.FAVOURITES in shown) favouriteChanges(c) else flowOf(null),
                        if (Mode.CIRCLE in shown) circleChanges(c) else flowOf(null),
                        combine(c.settings.settings.map { it.appLock }, AppLock.locked) { on, engaged -> on to engaged },
                    ) { a, b, lock -> listOf(a, b, lock) }
                        .drop(1)
                        .debounce(2_000)
                }
            }.collect { if (placedNow(ctx).isNotEmpty()) catching { refresh(ctx) } }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (modes.value.isNullOrEmpty() || !c.settings.settings.value.appLock) return
                c.scope.launch { catching { refresh(ctx) } }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // System broadcasts only; not exported to other apps.
        catching { ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    /** Stars, names, photos and the number a tap calls; the favourites' order (and, by most called, the calls). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun favouriteChanges(c: DataContainer): Flow<Any?> = combine(
        c.contacts.contacts.map { list ->
            list?.filter { it.starred }?.map { listOf(it.id, it.displayName, it.photoUri, FavoritesWidgetPlan.numberOf(it)) }
        },
        c.people.prefs.settings.flatMapLatest { p ->
            val calls = if (p.favoriteSort == FavoriteSort.MOST_CALLED) c.history.calls.map { it?.size } else flowOf(null)
            calls.map { listOf(p.favoriteSort, p.favoriteOrder, it) }
        },
    ) { a, b -> listOf(a, b) }

    /** The Circle (contact_meta), interactions, To call, the call history and the contacts. */
    private fun circleChanges(c: DataContainer): Flow<Any?> = combine(
        c.meta.allMeta().map { rows -> rows.map { Triple(it.lookupKey, it.reachOutDays, it.rhythm) } },
        combine(c.circle.interactions.changes, c.toCall.state) { changes, toCall -> changes to toCall },
        c.history.index,
        c.contacts.contacts,
    ) { a, b, idx, contacts -> listOf(a, b, idx?.calls?.size, contacts?.size) }
}
