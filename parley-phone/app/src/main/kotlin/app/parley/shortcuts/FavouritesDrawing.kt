package app.parley.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.rounded.Groups
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
import app.parley.common.people.FavoritesWidgetPlan
import app.parley.common.people.PeopleWidgetPlan
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The People widget in Favourites mode: a grid of favourites (photo or monogram, and name) in the Favourites tab's
 * order. A tap calls through the shortcut trampoline, so "Confirm before calling" and the pocket guard apply, or opens
 * their page (chosen in the widget's settings, [FavoritesWidgetConfigActivity]).
 *
 * - The launcher draws it, so only phone contacts are in it: private contacts never are ([FavoritesWidgetPlan]).
 * - With the app lock on, a locked phone shows only how many favourites there are.
 * - Resizable: the grid has as many columns and rows as fit, drawn for each size the widget takes (portrait and
 *   landscape, or the sizes Android 12+ lists). Light and dark follow the system. No network.
 * - Every tap has its own PendingIntent ([WidgetTaps]), so one widget's redraw never changes whom another one calls.
 */
internal object FavouritesDrawing {
    /** Side of a photo or monogram drawn for the grid, in px (shown at 48 dp). */
    private const val PHOTO_PX = 128

    /** Favourites in the Favourites tab's order (phone contacts only: the address book's starred contacts). */
    suspend fun load(ctx: Context, c: DataContainer): List<ContactSummary> {
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
    fun views(
        ctx: Context, id: Int, manager: AppWidgetManager, favourites: List<ContactSummary>, look: PeopleWidget.Look, photos: HashMap<Long, Bitmap>,
    ): RemoteViews {
        val options = manager.getAppWidgetOptions(id)
        fun dp(key: String) = options?.getInt(key, 0) ?: 0
        val layouts = FavoritesWidgetPlan.layouts(
            dp(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), dp(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            dp(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), dp(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
            listedSizes(options),
        )
        fun at(size: FavoritesWidgetPlan.Size) =
            sized(ctx, id, FavoritesWidgetPlan.grid(size.widthDp, size.heightDp), favourites, look, photos)
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
        val sizes = catching {
            BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        }.getOrNull() ?: return null
        return sizes.map { FavoritesWidgetPlan.Size(it.width.toInt(), it.height.toInt()) }
    }

    /** One drawing of widget [id] with [grid], as [look] says. */
    @Suppress("LongParameterList") // RemoteViews are built from plain values: no state object to pass instead.
    private fun sized(
        ctx: Context, id: Int, grid: FavoritesWidgetPlan.Grid, favourites: List<ContactSummary>, look: PeopleWidget.Look, photos: HashMap<Long, Bitmap>,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_favorites)
        v.removeAllViews(R.id.fav_grid)
        v.setViewVisibility(R.id.fav_message, View.GONE)
        v.setOnClickPendingIntent(R.id.fav_title, openApp(ctx, id))
        when (val shown = FavoritesWidgetPlan.shown(favourites, grid, look.locked)) {
            is FavoritesWidgetPlan.Shown.Locked -> {
                // App lock on and the phone or Parley locked: a count only, never names or photos.
                val res = ctx.resources
                v.setTextViewText(
                    R.id.fav_message,
                    res.getQuantityString(R.plurals.fav_widget_count, shown.count, shown.count) + "\n" + res.getString(R.string.circle_widget_tap_reveal),
                )
                v.setViewVisibility(R.id.fav_message, View.VISIBLE)
                val reveal = if (look.unlockInParley) openApp(ctx, id) else PeopleWidget.reveal(ctx, WidgetTaps.Kind.FAVOURITES_REVEAL, id, look)
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
                val tap = PeopleWidget.tap(ctx, id)
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
                // runCatching, not catching: a large image can throw OutOfMemoryError, and that should fall back too.
                runCatching { ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { s -> BitmapFactory.decodeStream(s) } }.getOrNull()
            }?.let { DialWidget.circle(it, PHOTO_PX) } ?: DialWidget.circle(Shortcuts.monogram(t.name, PHOTO_PX), PHOTO_PX)
        }
        cell.setImageViewBitmap(R.id.fav_cell_photo, photo)
        val call = tap == FavoritesWidgetPlan.Tap.CALL && t.number != null
        cell.setOnClickPendingIntent(R.id.fav_cell, tapIntent(ctx, id, index, t, tap))
        cell.setContentDescription(R.id.fav_cell, ctx.getString(if (call) R.string.action_call_named else R.string.action_open_named, t.name))
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
}

/**
 * The People widget's settings: Favourites or your Circle, and for Favourites what a tap on a person does. Shown when
 * the widget is placed, and again from its settings (long-press). Leaving it with Back keeps the widget and its current
 * choice (a new widget shows Favourites and calls): only Done changes anything.
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
                var mode by remember { mutableIntStateOf(PeopleWidget.mode(this, id).ordinal) }
                var choice by remember { mutableIntStateOf(PeopleWidget.tap(this, id).ordinal) }
                ParleyScaffold(topBar = { ParleyTopBar(stringResource(R.string.people_widget_title), onBack = { finish() }) }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                        ChoiceRow(
                            stringResource(R.string.people_widget_shows),
                            listOf(stringResource(R.string.label_favourites), stringResource(R.string.circle_section)),
                            mode, Icons.Rounded.Groups,
                        ) { mode = it }
                        // A tap in the Circle mode has its own Call button, so the choice is for Favourites only.
                        if (PeopleWidgetPlan.Mode.entries[mode] == PeopleWidgetPlan.Mode.FAVOURITES) {
                            ChoiceRow(
                                stringResource(R.string.fav_widget_tap),
                                listOf(stringResource(R.string.action_call), stringResource(R.string.fav_widget_tap_open)),
                                choice, Icons.Rounded.TouchApp,
                            ) { choice = it }
                        }
                        Text(
                            stringResource(R.string.people_widget_private_note),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Button(
                            onClick = {
                                val ctx = this@FavoritesWidgetConfigActivity
                                PeopleWidget.choose(ctx, id, PeopleWidgetPlan.Mode.entries[mode], FavoritesWidgetPlan.Tap.entries[choice])
                                lifecycleScope.launch {
                                    PeopleWidget.refresh(ctx)
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
