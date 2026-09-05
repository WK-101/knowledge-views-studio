package com.cairn.reader.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.cairn.reader.MainActivity
import com.cairn.reader.R
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.WidgetRow
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking

/** Feeds the list widget's rows. Reads the DB directly through the widget Hilt entry point
 *  (widgets/services aren't Hilt-injected). */
class CairnWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val scope = intent.getStringExtra(EXTRA_SCOPE) ?: "INBOX"
        return CairnRowFactory(applicationContext, scope)
    }

    companion object {
        const val EXTRA_SCOPE = "widget_scope"
    }
}

private class CairnRowFactory(
    private val context: Context,
    private val scope: String,
) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<WidgetRow> = emptyList()

    private fun dao(): ItemDao =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            CairnWidgetProvider.WidgetEntryPoint::class.java,
        ).itemDao()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        rows = runCatching {
            runBlocking {
                if (scope == "SAVED") dao().latestSavedForWidget(25) else dao().latestUnreadForWidget(25)
            }
        }.getOrDefault(emptyList())
    }

    override fun onDestroy() { rows = emptyList() }
    override fun getCount(): Int = rows.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_row)
        val views = RemoteViews(context.packageName, R.layout.widget_row)
        views.setTextViewText(R.id.widget_row_title, row.title)
        views.setTextViewText(R.id.widget_row_source, row.source ?: "")
        // Fill-in intent carries the item id; the provider set the template targeting MainActivity.
        views.setOnClickFillInIntent(
            R.id.widget_row_root,
            Intent().putExtra(MainActivity.EXTRA_OPEN_ITEM, row.id),
        )
        return views
    }
}
