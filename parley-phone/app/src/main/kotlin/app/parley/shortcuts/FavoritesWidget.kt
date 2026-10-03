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
import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.parley.MainActivity
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.people.FavoriteOrder
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Favourites widget: a grid of favourites (photo or monogram, and name) in the Favourites tab's order. Plain
 * RemoteViews like the Circle and direct-dial widgets (no Glance). A tap calls through the shortcut trampoline, so
 * "Confirm before calling" and the pocket guard apply, or opens their page (chosen when the widget is placed, and
 * later from the launcher's widget settings).
 *
 * - The launcher draws it, so only phone contacts are in it: private contacts never are ([FavoritesWidgetPlan]).
 * - With the app lock on, a locked phone shows only how many favourites there are; names and photos come back once
 *   the phone is unlocked (as in the Circle widget: USER_PRESENT while Parley runs, opening Parley, or a tap).
 * - Resizable: the grid has as many columns and rows as fit. Light and dark follow the system. No network.
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
            val locked = c.settings.current().appLock && ctx.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
            val manager = AppWidgetManager.getInstance(ctx)
            // Photos are decoded once per refresh, whichever widgets show them.
            val photos = HashMap<Long, Bitmap>()
            ids.forEach { id -> runCatching { manager.updateAppWidget(id, views(ctx, id, manager, favourites, locked, photos)) } }
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

        @Suppress("LongParameterList")
        private fun views(
            ctx: Context, id: Int, manager: AppWidgetManager, favourites: List<ContactSummary>, locked: Boolean, photos: HashMap<Long, Bitmap>,
        ): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_favorites)
            val options = manager.getAppWidgetOptions(id)
            val grid = FavoritesWidgetPlan.grid(
                options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0,
                options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0,
            )
            v.removeAllViews(R.id.fav_grid)
            v.setViewVisibility(R.id.fav_message, View.GONE)
            v.setOnClickPendingIntent(R.id.fav_title, openApp(ctx, id))
            when (val shown = FavoritesWidgetPlan.shown(favourites, grid, locked)) {
                is FavoritesWidgetPlan.Shown.Locked -> {
                    // App lock on and the phone locked: a count only, never names or photos.
                    val res = ctx.resources
                    v.setTextViewText(
                        R.id.fav_message,
                        res.getQuantityString(R.plurals.fav_widget_count, shown.count, shown.count) + "\n" + res.getString(R.string.circle_widget_tap_reveal),
                    )
                    v.setViewVisibility(R.id.fav_message, View.VISIBLE)
                    val reveal = PendingIntent.getBroadcast(
                        ctx, 8500 + id, Intent(ctx, FavoritesWidget::class.java).setAction(ACTION_REVEAL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
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

        @Suppress("LongParameterList")
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
            val code = 8000 + id * 64 + index
            val call = tap == FavoritesWidgetPlan.Tap.CALL && t.number != null
            val intent = if (call) {
                PendingIntent.getActivity(
                    ctx, code, Shortcuts.intent(ctx, Shortcuts.Kind.CALL, t.number, t.contactId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                PendingIntent.getActivity(
                    ctx, code,
                    Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_CALLER)
                        .putExtra(MainActivity.EXTRA_CONTACT_ID, t.contactId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            cell.setOnClickPendingIntent(R.id.fav_cell, intent)
            cell.setContentDescription(R.id.fav_cell, ctx.getString(if (call) R.string.widget_call_name else R.string.fav_widget_open_name, t.name))
            return cell
        }

        private fun openApp(ctx: Context, id: Int): PendingIntent = PendingIntent.getActivity(
            ctx, 8400 + id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Keeps the widgets current while Parley runs: after changes to the contacts (stars, names, photos), the
         * favourites order or the app-lock setting, and when the screen turns off (names hide) or the phone is
         * unlocked (names return). Called once from the Application.
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
                            c.contacts.contacts.map { list -> list?.filter { it.starred }?.map { Triple(it.id, it.displayName, it.photoUri) } },
                            c.people.prefs.settings.map { it.favoriteSort to it.favoriteOrder },
                            c.settings.settings.map { it.appLock },
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
 * The Favourites widget's settings: what a tap on a person does. Shown when the widget is placed (it can be skipped:
 * a tap calls) and from the launcher's widget settings afterwards.
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
                ParleyScaffold(topBar = { ParleyTopBar(stringResource(R.string.fav_widget_title), onBack = { finish() }) }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                        ChoiceRow(
                            stringResource(R.string.fav_widget_tap),
                            listOf(stringResource(R.string.fav_widget_tap_call), stringResource(R.string.fav_widget_tap_open)),
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
                        ) { Text(stringResource(R.string.main_done)) }
                    }
                }
            }
        }
    }
}
