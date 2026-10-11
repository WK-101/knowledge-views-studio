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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.catching
import app.parley.common.people.FavoriteOrder
import app.parley.common.people.FavoriteSort
import app.parley.common.people.FavoritesWidgetPlan
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.security.AppLock
import app.parley.security.LockScreen
import app.parley.security.LockedActivity
import app.parley.ui.ChoiceRow
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTheme
import app.parley.ui.ParleyTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Favourites widget: a grid of favourites (photo or monogram, and name) in the Favourites tab's order. Plain
 * RemoteViews like the Circle and direct-dial widgets (no Glance). A tap calls through the shortcut trampoline, so
 * "Confirm before calling" and the pocket guard apply, or opens their page (chosen in the widget's settings,
 * [FavoritesWidgetConfigActivity]).
 *
 * - The launcher draws it, so only phone contacts are in it: private contacts never are ([FavoritesWidgetPlan]).
 * - With the app lock on, a locked phone shows only how many favourites there are; names and photos come back once
 *   the phone is unlocked (as in the Circle widget: USER_PRESENT while Parley runs, opening Parley, or a tap).
 * - Resizable: the grid has as many columns and rows as fit, drawn for each size the widget takes (portrait and
 *   landscape, or the sizes Android 12+ lists). Light and dark follow the system. No network.
 * - Every tap has its own PendingIntent ([WidgetTaps]), so one widget's redraw never changes whom another one calls.
 * - The app refreshes it when contacts, the favourites order or the lock setting change.
 */
class FavoritesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isNotEmpty()) present.value = true
        refreshAsync(context)
    }

    override fun onDisabled(context: Context) {
        present.value = false
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val p = prefs(context).edit()
        ids.forEach { p.remove(tapKey(it)) }
        p.apply()
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refreshAsync(context)

    override fun onReceive(context: Context, intent: Intent) {
        // "Tap to show" on the locked drawing: drawn again, with names only if the phone is unlocked now.
        if (intent.action == ACTION_REVEAL) refreshAsync(context) else super.onReceive(context, intent)
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        context.container.scope.launch {
            try {
                refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REVEAL = "app.parley.action.FAVORITES_WIDGET_REVEAL"

        /** Side of a photo or monogram drawn for the grid, in px (shown at 48 dp). */
        private const val PHOTO_PX = 128

        /** Whether any Favourites widget is placed; null until first asked. */
        private val present = MutableStateFlow<Boolean?>(null)

        /** Whether the last drawing was the locked one; null: unknown (e.g. a new process). */
        @Volatile
        private var shownLocked: Boolean? = null

        private fun prefs(context: Context) = context.getSharedPreferences("favorites_widgets", Context.MODE_PRIVATE)

        private fun tapKey(id: Int) = "$id.tap"

        /** What a tap on a person does in widget [id] (calls, unless chosen otherwise). */
        fun tap(context: Context, id: Int): FavoritesWidgetPlan.Tap =
            runCatching { FavoritesWidgetPlan.Tap.valueOf(prefs(context).getString(tapKey(id), null).orEmpty()) }.getOrDefault(FavoritesWidgetPlan.Tap.CALL)

        fun setTap(context: Context, id: Int, tap: FavoritesWidgetPlan.Tap) {
            prefs(context).edit().putString(tapKey(id), tap.name).apply()
        }

        private fun ids(context: Context): IntArray =
            runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, FavoritesWidget::class.java)) }.getOrDefault(IntArray(0))

        /** Parley came to the front (so the phone is unlocked): brings names back if the widget still hides them. */
        suspend fun refreshIfShownLocked(context: Context) {
            if (shownLocked != false) refresh(context)
        }

        /** Re-draws every Favourites widget (no-op without any). */
        suspend fun refresh(context: Context) {
            val ctx = context.applicationContext
            val ids = ids(ctx)
            if (ids.isEmpty()) return
            val c = ctx.container
            val favourites = runCatching { withContext(Dispatchers.IO) { load(ctx, c) } }.getOrNull().orEmpty()
            // With the app lock on, names show only while both the phone and Parley are unlocked.
            val s = c.settings.current()
            val deviceLocked = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
            val locked = s.appLock && (deviceLocked || AppLock.lockedFor(s))
            val manager = AppWidgetManager.getInstance(ctx)
            // Photos are decoded once per refresh, whichever widgets show them.
            val photos = HashMap<Long, Bitmap>()
            ids.forEach { id ->
                catching { manager.updateAppWidget(id, views(ctx, id, manager, favourites, locked, photos, unlockInParley = !deviceLocked)) }
            }
            shownLocked = locked
        }

        /** Favourites in the Favourites tab's order (phone contacts only: the address book's starred contacts). */
        private suspend fun load(ctx: Context, c: DataContainer): List<ContactSummary> {
            val contacts = withTimeoutOrNull(20_000) { c.contacts.contacts.filterNotNull().first() } ?: return emptyList()
            val favs = contacts.filter { it.starred }
            val prefs = c.people.prefs.current()
            // "Most called" counts the loaded call history, when there is one (a process started for the widget has none).
            val counts = c.history.calls.value?.let { calls ->
                val byLine = PhoneIdentity.LineMap<Long>(PhoneEnv.countryIso(ctx))
                favs.forEach { ct -> ct.phones.forEach { p -> byLine.putIfAbsent(p.number, ct.id) } }
                val n = HashMap<Long, Int>()
                calls.forEach { e -> byLine[e.number]?.let { n[it] = (n[it] ?: 0) + 1 } }
                n
            }.orEmpty()
            return FavoriteOrder.sort(favs, prefs.favoriteSort, prefs.favoriteOrder, counts)
        }

        /**
         * Widget [id] drawn for each size it can take ([FavoritesWidgetPlan.layouts]): the launcher picks the one for the
         * screen as it is, so a rotation needs no redraw and the grid never plans for room it doesn't have.
         */
        @Suppress("LongParameterList") // RemoteViews are built from plain values: no state object to pass instead.
        private fun views(
            ctx: Context, id: Int, manager: AppWidgetManager, favourites: List<ContactSummary>, locked: Boolean, photos: HashMap<Long, Bitmap>,
            unlockInParley: Boolean,
        ): RemoteViews {
            val options = manager.getAppWidgetOptions(id)
            fun dp(key: String) = options?.getInt(key, 0) ?: 0
            val layouts = FavoritesWidgetPlan.layouts(
                dp(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), dp(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                dp(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), dp(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
                listedSizes(options),
            )
            fun at(size: FavoritesWidgetPlan.Size) =
                sized(ctx, id, FavoritesWidgetPlan.grid(size.widthDp, size.heightDp), favourites, locked, photos, unlockInParley)
            return when (layouts) {
                is FavoritesWidgetPlan.Layouts.Listed -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RemoteViews(layouts.sizes.associate { SizeF(it.widthDp.toFloat(), it.heightDp.toFloat()) to at(it) })
                } else {
                    at(layouts.sizes.first())
                }
                is FavoritesWidgetPlan.Layouts.ByOrientation -> RemoteViews(at(layouts.landscape), at(layouts.portrait))
            }
        }

        /** The sizes Android 12 and later list for the widget (null before, or when the launcher gives none). */
        private fun listedSizes(options: Bundle?): List<FavoritesWidgetPlan.Size>? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || options == null) return null
            val sizes = runCatching {
                BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            }.getOrNull() ?: return null
            return sizes.map { FavoritesWidgetPlan.Size(it.width.toInt(), it.height.toInt()) }
        }

        /** One drawing of widget [id] with [grid]. [unlockInParley]: only Parley is locked, so a tap opens it to unlock. */
        @Suppress("LongParameterList") // RemoteViews are built from plain values: no state object to pass instead.
        private fun sized(
            ctx: Context, id: Int, grid: FavoritesWidgetPlan.Grid, favourites: List<ContactSummary>, locked: Boolean, photos: HashMap<Long, Bitmap>,
            unlockInParley: Boolean,
        ): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_favorites)
            v.removeAllViews(R.id.fav_grid)
            v.setViewVisibility(R.id.fav_message, View.GONE)
            v.setOnClickPendingIntent(R.id.fav_title, openApp(ctx, id))
            when (val shown = FavoritesWidgetPlan.shown(favourites, grid, locked)) {
                is FavoritesWidgetPlan.Shown.Locked -> {
                    // App lock on and the phone or Parley locked: a count only, never names or photos.
                    val res = ctx.resources
                    v.setTextViewText(
                        R.id.fav_message,
                        res.getQuantityString(R.plurals.fav_widget_count, shown.count, shown.count) + "\n" + res.getString(R.string.circle_widget_tap_reveal),
                    )
                    v.setViewVisibility(R.id.fav_message, View.VISIBLE)
                    val reveal = if (unlockInParley) openApp(ctx, id) else WidgetTaps.broadcast(
                        ctx, WidgetTaps.Kind.FAVOURITES_REVEAL, id, Intent(ctx, FavoritesWidget::class.java).setAction(ACTION_REVEAL),
                    )
                    v.setOnClickPendingIntent(R.id.fav_root, reveal)
                    v.setOnClickPendingIntent(R.id.fav_message, reveal)
                }
                FavoritesWidgetPlan.Shown.Empty -> {
                    v.setTextViewText(R.id.fav_message, ctx.getString(R.string.fav_widget_empty))
                    v.setViewVisibility(R.id.fav_message, View.VISIBLE)
                    v.setOnClickPendingIntent(R.id.fav_root, openApp(ctx, id))
                    v.setOnClickPendingIntent(R.id.fav_message, openApp(ctx, id))
                }
                is FavoritesWidgetPlan.Shown.Tiles -> {
                    val tap = tap(ctx, id)
                    shown.tiles.chunked(grid.columns).forEachIndexed { r, row ->
                        val line = RemoteViews(ctx.packageName, R.layout.widget_favorites_row)
                        row.forEachIndexed { col, t -> line.addView(R.id.fav_row, cell(ctx, id, r * grid.columns + col, t, tap, photos)) }
                        // Empty places keep the columns lined up on the last row.
                        repeat(grid.columns - row.size) {
                            val gap = RemoteViews(ctx.packageName, R.layout.widget_favorites_cell)
                            gap.setViewVisibility(R.id.fav_cell, View.INVISIBLE)
                            line.addView(R.id.fav_row, gap)
                        }
                        v.addView(R.id.fav_grid, line)
                    }
                }
            }
            return v
        }

        @Suppress("LongParameterList") // RemoteViews are built from plain values: no state object to pass instead.
        private fun cell(
            ctx: Context, id: Int, index: Int, t: FavoritesWidgetPlan.Tile, tap: FavoritesWidgetPlan.Tap, photos: HashMap<Long, Bitmap>,
        ): RemoteViews {
            val cell = RemoteViews(ctx.packageName, R.layout.widget_favorites_cell)
            cell.setTextViewText(R.id.fav_cell_name, t.name)
            val photo = photos.getOrPut(t.contactId) {
                t.photoUri?.let { uri ->
                    runCatching { ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { s -> BitmapFactory.decodeStream(s) } }.getOrNull()
                }?.let { DialWidget.circle(it, PHOTO_PX) } ?: DialWidget.circle(Shortcuts.monogram(t.name, PHOTO_PX), PHOTO_PX)
            }
            cell.setImageViewBitmap(R.id.fav_cell_photo, photo)
            val call = tap == FavoritesWidgetPlan.Tap.CALL && t.number != null
            cell.setOnClickPendingIntent(R.id.fav_cell, tapIntent(ctx, id, index, t, tap))
            cell.setContentDescription(R.id.fav_cell, ctx.getString(if (call) R.string.circle_call_who else R.string.fav_widget_open_name, t.name))
            return cell
        }

        /** What a tap on [t], the [index]th person of widget [id], starts: a call through the trampoline, or their page. */
        internal fun tapIntent(ctx: Context, id: Int, index: Int, t: FavoritesWidgetPlan.Tile, tap: FavoritesWidgetPlan.Tap): PendingIntent {
            val intent = if (tap == FavoritesWidgetPlan.Tap.CALL && t.number != null) {
                Shortcuts.intent(ctx, Shortcuts.Kind.CALL, t.number, t.contactId, t.name)
            } else {
                IntentRoutes.own(ctx).setAction(MainActivity.ACTION_SHOW_CALLER)
                    .putExtra(MainActivity.EXTRA_CONTACT_ID, t.contactId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return WidgetTaps.activity(ctx, WidgetTaps.Kind.FAVOURITE, id, index, intent)
        }

        private fun openApp(ctx: Context, id: Int): PendingIntent =
            WidgetTaps.activity(ctx, WidgetTaps.Kind.FAVOURITES_APP, id, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        /**
         * Keeps the widgets current while Parley runs: after changes to the contacts (stars, names, photos, the number
         * a tap calls), the favourites order (and, sorted by most called, the calls) or the app-lock setting, and when
         * the screen turns off (names hide) or the phone is unlocked (names return). Called once from the Application.
         */
        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        fun observe(context: Context, c: DataContainer) {
            val ctx = context.applicationContext
            c.scope.launch {
                c.fullStart.await()
                if (present.value == null) present.value = ids(ctx).isNotEmpty()
                present.flatMapLatest { shown ->
                    if (shown != true) {
                        emptyFlow()
                    } else {
                        combine(
                            c.contacts.contacts.map { list ->
                                list?.filter { it.starred }?.map { listOf(it.id, it.displayName, it.photoUri, FavoritesWidgetPlan.numberOf(it)) }
                            },
                            c.people.prefs.settings.flatMapLatest { p ->
                                // "Most called" moves people as calls come in: follow how many calls there are.
                                val calls = if (p.favoriteSort == FavoriteSort.MOST_CALLED) c.history.calls.map { it?.size } else flowOf(null)
                                calls.map { listOf(p.favoriteSort, p.favoriteOrder, it) }
                            },
                            combine(c.settings.settings.map { it.appLock }, AppLock.locked) { on, engaged -> on to engaged },
                        ) { a, b, lock -> listOf(a, b, lock) }
                            .drop(1)
                            .debounce(2_000)
                    }
                }.collect { if (ids(ctx).isNotEmpty()) runCatching { refresh(ctx) } }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (ids(ctx).isEmpty() || !c.settings.settings.value.appLock) return
                    c.scope.launch { runCatching { refresh(ctx) } }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // System broadcasts only; not exported to other apps.
            runCatching { ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        }
    }
}

/**
 * The Favourites widget's settings: what a tap on a person does. Android 10 and 11 show it when the widget is placed;
 * from Android 12 the launcher places the widget without it, and it opens from the widget's settings (long-press).
 * Leaving it with Back keeps the widget and its current choice (a new widget calls): only Done changes anything.
 */
class FavoritesWidgetConfigActivity : LockedActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        // Exported for the launcher: only configure widgets that really are ours.
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.packageName != packageName) {
            finish()
            return
        }
        // The settings can be skipped: a launcher that asks when placing (Android 10 and 11 always do) takes a
        // cancelled result as "don't add the widget", so Back must still answer OK. The widget then calls.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        setContent {
            val s by container.settings.settings.collectAsStateWithLifecycle()
            val loaded by container.settings.loaded.collectAsStateWithLifecycle()
            val locked by AppLock.locked.collectAsStateWithLifecycle()
            ParleyTheme(s.themeMode, s.amoledBlack, s.dynamicColor, s.density) {
                if (!loaded) return@ParleyTheme
                if (locked && s.appLock) {
                    LockScreen { AppLock.authenticate(this@FavoritesWidgetConfigActivity) }
                    return@ParleyTheme
                }
                var choice by remember { mutableIntStateOf(FavoritesWidget.tap(this, id).ordinal) }
                ParleyScaffold(topBar = { ParleyTopBar(stringResource(R.string.blk_favourites), onBack = { finish() }) }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                        ChoiceRow(
                            stringResource(R.string.fav_widget_tap),
                            listOf(stringResource(R.string.circle_widget_call), stringResource(R.string.fav_widget_tap_open)),
                            choice, Icons.Rounded.TouchApp,
                        ) { choice = it }
                        Text(
                            stringResource(R.string.fav_widget_private_note),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Button(
                            onClick = {
                                FavoritesWidget.setTap(this@FavoritesWidgetConfigActivity, id, FavoritesWidgetPlan.Tap.entries[choice])
                                lifecycleScope.launch {
                                    FavoritesWidget.refresh(this@FavoritesWidgetConfigActivity)
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                }
                            },
                            modifier = Modifier.padding(16.dp),
                        ) { Text(stringResource(R.string.dc_done)) }
                    }
                }
            }
        }
    }
}
