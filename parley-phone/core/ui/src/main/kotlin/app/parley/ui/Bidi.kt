package app.parley.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat

/**
 * L3: phone numbers, keypads and DTMF digits read left to right in every language, also in Arabic and Urdu.
 * Only these are forced; the rest of the app follows the language's direction.
 */
object Bidi {
    /**
     * [text] (a phone number, or a line that starts with one) as an isolated left-to-right run, so "+49 30 1234"
     * keeps its order inside right-to-left text. Unchanged in left-to-right languages.
     */
    fun ltr(text: String): String = BidiFormatter.getInstance().unicodeWrap(text, TextDirectionHeuristicsCompat.LTR)

    /** Nullable convenience for [ltr]. */
    fun ltrOrNull(text: String?): String? = text?.let(::ltr)
}

/** Lays out [content] left to right (a keypad, a number field), whatever the language (L3). */
@Composable
fun ForceLtr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
