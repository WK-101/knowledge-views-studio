package app.parley.ui.export

import android.content.Context
import app.parley.R
import app.parley.common.circle.InteractionType
import app.parley.common.vcard.CardNotes
import app.parley.ui.circle.CircleText
import app.parley.ui.common.Format

/** The words of the readable notes in an export, in the app's language ([CardNotes.Words]). */
object ExportWords {
    fun build(context: Context): CardNotes.Words {
        val res = context.resources
        return CardNotes.Words(
            heading = res.getString(R.string.export_notes_heading),
            forCalls = res.getString(R.string.export_notes_for_calls),
            context = res.getString(R.string.export_notes_context),
            keepInTouch = { d -> res.getString(R.string.export_notes_circle, res.getQuantityString(R.plurals.circle_every_days, d, d)) },
            callNote = res.getString(R.string.circle_call_note),
            moment = { kind ->
                CircleText.type(res, InteractionType.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: InteractionType.OTHER)
            },
            promises = res.getString(R.string.circle_promises),
            date = { t -> Format.fullDate(context, t) },
        )
    }
}
