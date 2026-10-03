package app.parley.shortcuts

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The PendingIntents behind every tap on Parley's widgets. Android matches PendingIntents by request code and by the
 * intent without its extras, and the widgets' taps differ mostly in extras (the number to call, the contact to
 * open). With FLAG_UPDATE_CURRENT, two taps that matched would share one PendingIntent, and whichever widget redrew
 * last would decide whom both call. So each tap carries its own data URI (widget kind, widget id and place in the
 * widget), which no other tap has, whatever the widget ids are; the request code is derived from it too.
 */
internal object WidgetTaps {
    /** Every kind of tap, one per place a widget can be tapped. */
    enum class Kind(val path: String) {
        DIAL("dial"),
        FAVOURITE("favourites/person"),
        FAVOURITES_APP("favourites/app"),
        FAVOURITES_REVEAL("favourites/reveal"),
        CIRCLE_APP("circle/app"),
        CIRCLE_PERSON("circle/person"),
        CIRCLE_CALL("circle/call"),
        CIRCLE_DATE("circle/date"),
        CIRCLE_REVEAL("circle/reveal"),
    }

    private const val SCHEME = "parley-widget"

    /** The data URI that tells this tap apart from every other one (never read by the activity it starts). */
    fun key(kind: Kind, widgetId: Int, place: Int = 0): String = "$SCHEME://${kind.path}/$widgetId/$place"

    /** [intent] marked as this tap. */
    fun mark(intent: Intent, kind: Kind, widgetId: Int, place: Int = 0): Intent = intent.setData(Uri.parse(key(kind, widgetId, place)))

    fun activity(context: Context, kind: Kind, widgetId: Int, place: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context, key(kind, widgetId, place).hashCode(), mark(intent, kind, widgetId, place),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun broadcast(context: Context, kind: Kind, widgetId: Int, intent: Intent): PendingIntent = PendingIntent.getBroadcast(
        context, key(kind, widgetId).hashCode(), mark(intent, kind, widgetId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
