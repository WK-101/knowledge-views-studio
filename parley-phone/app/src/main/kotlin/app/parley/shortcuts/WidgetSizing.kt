package app.parley.shortcuts

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import app.parley.common.catching
import app.parley.common.people.FavoritesWidgetPlan

/**
 * Every widget of Parley's is drawn for each size the launcher may show it at ([FavoritesWidgetPlan.layouts]): the
 * launcher picks the drawing for the screen as it is, so a rotation or a foldable needs no redraw, and a small widget
 * gets the layout made for its size ([app.parley.common.widgets.WidgetShape]) rather than a big one cut off.
 */
internal object WidgetSizing {
    /** Widget [id] drawn by [draw] at each of its sizes, as one RemoteViews. */
    fun responsive(manager: AppWidgetManager, id: Int, draw: (FavoritesWidgetPlan.Size) -> RemoteViews): RemoteViews {
        val options = catching { manager.getAppWidgetOptions(id) }.getOrNull()
        fun dp(key: String) = options?.getInt(key, 0) ?: 0
        val layouts = FavoritesWidgetPlan.layouts(
            dp(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), dp(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            dp(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), dp(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
            listedSizes(options),
        )
        return when (layouts) {
            is FavoritesWidgetPlan.Layouts.Listed -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(layouts.sizes.associate { SizeF(it.widthDp.toFloat(), it.heightDp.toFloat()) to draw(it) })
            } else {
                draw(layouts.sizes.first())
            }
            is FavoritesWidgetPlan.Layouts.ByOrientation -> RemoteViews(draw(layouts.landscape), draw(layouts.portrait))
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
}
