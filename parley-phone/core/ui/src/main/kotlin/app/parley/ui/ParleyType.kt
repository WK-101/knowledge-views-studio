package app.parley.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/**
 * The few type roles that differ from the theme's plain scale, kept here so call sites don't each tweak weights.
 * The words that lead a screen (a caller's name, the home title) take Material 3 Expressive's emphasized styles, at
 * least a step heavier than the plain scale (some material3 releases still give the emphasized headlines the plain
 * weight); digits that tick or line up (call timers, countdowns, keypads, typed tones) take tabular figures, so they
 * don't wobble from one second to the next. No bundled font: the system's own, with its weights.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ParleyType {
    /** Digits all the same width ("tnum"). */
    val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

    /** The caller's name on the call screen. */
    val callerName: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = callerName(MaterialTheme.typography)

    /** The caller's name with the keypad open (and on the call-waiting sheet). */
    val callerNameCompact: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = callerNameCompact(MaterialTheme.typography)

    /** The caller's name set large over a poster picture. */
    val posterName: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = posterName(MaterialTheme.typography)

    /** The running time in the call screen's status pill. */
    val callTimer: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = callTimer(MaterialTheme.typography)

    /** A big running time (how long you've been on hold). */
    val bigClock: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.displayMedium.tabular()

    /** Digits typed or sent during a call, the in-call keys, and the main keypad's number. */
    val typedDigits: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineMedium.tabular()

    /** The home screen's title (Contacts, Recents, Favourites). */
    val homeTitle: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = homeTitle(MaterialTheme.typography)

    /** A keypad key's digit at [size]: light, so twelve of them stay calm, and tabular like every other digit. */
    fun keypadDigit(size: TextUnit): TextStyle = TextStyle(fontSize = size, lineHeight = size, fontWeight = FontWeight.Light).tabular()

    internal fun callerName(t: Typography): TextStyle = t.headlineLargeEmphasized.emphasized()

    internal fun callerNameCompact(t: Typography): TextStyle = t.headlineSmallEmphasized.emphasized()

    internal fun posterName(t: Typography): TextStyle = t.displayMediumEmphasized.emphasized()

    internal fun callTimer(t: Typography): TextStyle = t.titleMediumEmphasized.emphasized(FontWeight.SemiBold).tabular()

    internal fun homeTitle(t: Typography): TextStyle = t.headlineSmallEmphasized.emphasized()

    /** At least [weight]: heavier than the plain scale (Regular headlines, Medium titles). */
    private fun TextStyle.emphasized(weight: FontWeight = FontWeight.Medium): TextStyle =
        if ((fontWeight ?: FontWeight.Normal) < weight) copy(fontWeight = weight) else this
}

/** This style with tabular figures. */
fun TextStyle.tabular(): TextStyle = merge(ParleyType.TabularFigures)
