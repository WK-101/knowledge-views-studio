package app.parley.common.ux

import app.parley.common.AppSettings
import app.parley.common.CallsLayout
import app.parley.common.FavoritesPlacement
import app.parley.common.SurfaceLayout
import app.parley.common.blocking.ScreeningPreset

/** The steps of the first run, in order. Every step after the welcome can be skipped. */
enum class OnboardingStep {
    WELCOME, DEFAULT_APP, PERMISSIONS, BASICS, COMING_FROM;

    /** The step after this one; the last step has none (the first run ends). */
    val next: OnboardingStep? get() = entries.getOrNull(ordinal + 1)

    /**
     * The step after this one for someone [restoring] a Parley backup (chosen on the welcome): the default phone app
     * and the permissions still matter, but Set up the basics and Coming from… don't, since the restore brings the old
     * phone's answers back. After the permissions the first run ends, and the backup screen opens.
     */
    fun next(restoring: Boolean): OnboardingStep? = if (restoring && this >= PERMISSIONS) null else next

    /** The step Back returns to; none from the welcome. */
    val previous: OnboardingStep? get() = entries.getOrNull(ordinal - 1)

    companion object {
        /** A step saved by its position (across a rotation or process death); anything unknown starts over. */
        fun at(index: Int): OnboardingStep = entries.getOrNull(index) ?: WELCOME
    }
}

/** The home layout question of "Set up the basics": the tabs as they are, or calls and contacts combined. */
enum class BasicLayout { TABS, COMBINED }

/**
 * The answers to "Set up the basics", each null while unanswered (null changes nothing): who may ring (one of the
 * screening presets), whether the phone is for someone else (Simple mode is set up next), and the layout.
 */
data class BasicsChoice(
    val screening: ScreeningPreset? = null,
    val forSomeoneElse: Boolean? = null,
    val layout: BasicLayout? = null,
)

/**
 * The first run's "Set up the basics". It only uses what Settings already has, so every answer can be changed
 * later in its usual place: the presets on Blocking & screening, Simple mode and the layout on Layout & gestures.
 */
object Basics {
    /** What the questions start on: the settings as they are now (on a fresh install, everyone rings, tabs apart). */
    fun current(a: AppSettings): BasicsChoice = BasicsChoice(
        screening = ScreeningPreset.current(a.screening).firstOrNull(),
        forSomeoneElse = false,
        layout = layoutOf(a.surfaces),
    )

    fun layoutOf(s: SurfaceLayout): BasicLayout = if (s.merged) BasicLayout.COMBINED else BasicLayout.TABS

    /**
     * [s] with [layout]: the tabs apart, or the keypad docked in Recents and the favourites at the top of Contacts
     * (the two combine options Settings offers). An answer that matches the layout already kept changes nothing.
     */
    fun surfacesFor(layout: BasicLayout, s: SurfaceLayout): SurfaceLayout = when {
        layoutOf(s) == layout -> s
        layout == BasicLayout.TABS -> s.separated()
        else -> s.copy(calls = CallsLayout.COMBINED, favorites = FavoritesPlacement.SECTION)
    }

    /**
     * [a] with the answered questions applied. A preset already in use isn't applied again, so an untouched answer
     * never rewrites a schedule or a switch the person set themselves.
     */
    fun apply(a: AppSettings, choice: BasicsChoice): AppSettings {
        var out = a
        choice.screening?.let { p -> if (p !in ScreeningPreset.current(out.screening)) out = p.apply(out) }
        choice.layout?.let { l -> out = out.copy(surfaces = surfacesFor(l, out.surfaces)) }
        return out
    }

    /** Whether Simple mode's setup opens once the first run ends (the phone is for someone else). */
    fun opensSimpleSetup(choice: BasicsChoice): Boolean = choice.forSomeoneElse == true
}
