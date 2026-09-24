package app.parley.ui

import android.text.BidiFormatter
import android.text.TextDirectionHeuristics

/** Localisation helpers for the data screens (history, people, backup, vault…). */
object DataL10n {
    /**
     * Wraps a phone number (or any digits-and-symbols text) so it always reads left to right, even inside an
     * Arabic or Urdu sentence. Only the number is forced LTR, never the surrounding text.
     */
    fun ltr(s: String): String = if (s.isEmpty()) s else BidiFormatter.getInstance().unicodeWrap(s, TextDirectionHeuristics.LTR)
}
