package app.parley.data

import android.text.BidiFormatter
import android.text.TextDirectionHeuristics

/** Keeps phone numbers left to right inside localised (possibly right-to-left) sentences built in core/data. */
object DataBidi {
    fun ltr(s: String): String = if (s.isEmpty()) s else BidiFormatter.getInstance().unicodeWrap(s, TextDirectionHeuristics.LTR)
}
