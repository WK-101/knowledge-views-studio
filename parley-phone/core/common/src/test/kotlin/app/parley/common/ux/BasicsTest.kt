package app.parley.common.ux

import app.parley.common.AppSettings
import app.parley.common.CallsLayout
import app.parley.common.FavoritesPlacement
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.SurfaceLayout
import app.parley.common.blocking.ScreeningPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicsTest {
    @Test fun the_first_run_asks_the_basics_before_coming_from_another_phone() {
        assertEquals(
            listOf(OnboardingStep.WELCOME, OnboardingStep.DEFAULT_APP, OnboardingStep.PERMISSIONS, OnboardingStep.BASICS, OnboardingStep.COMING_FROM),
            generateSequence(OnboardingStep.WELCOME) { it.next }.toList(),
        )
        assertNull(OnboardingStep.COMING_FROM.next)
        assertNull(OnboardingStep.WELCOME.previous)
        assertEquals(OnboardingStep.PERMISSIONS, OnboardingStep.BASICS.previous)
        // A saved position that no longer exists starts over.
        assertEquals(OnboardingStep.BASICS, OnboardingStep.at(3))
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.at(42))
    }

    @Test fun a_fresh_install_starts_on_everyone_me_and_tabs() {
        assertEquals(BasicsChoice(ScreeningPreset.EVERYONE, false, BasicLayout.TABS), Basics.current(AppSettings()))
    }

    @Test fun skipping_or_keeping_every_answer_changes_nothing() {
        val a = AppSettings()
        assertEquals(a, Basics.apply(a, BasicsChoice()))
        assertEquals(a, Basics.apply(a, Basics.current(a)))
        assertFalse(Basics.opensSimpleSetup(BasicsChoice()))
        assertFalse(Basics.opensSimpleSetup(Basics.current(a)))
    }

    @Test fun each_answer_uses_what_settings_already_has() {
        val a = Basics.apply(AppSettings(), BasicsChoice(ScreeningPreset.KNOWN, true, BasicLayout.COMBINED))
        assertEquals(listOf(ScreeningPreset.KNOWN), ScreeningPreset.current(a.screening))
        assertEquals(CallsLayout.COMBINED, a.surfaces.calls)
        assertEquals(FavoritesPlacement.SECTION, a.surfaces.favorites)
        assertTrue(Basics.opensSimpleSetup(BasicsChoice(forSomeoneElse = true)))
        // And back: tabs apart again, the row-tap choice kept.
        val tapped = a.copy(surfaces = a.surfaces.copy(recentTap = app.parley.common.RecentTap.CALL))
        val back = Basics.apply(tapped, BasicsChoice(layout = BasicLayout.TABS))
        assertEquals(SurfaceLayout(recentTap = app.parley.common.RecentTap.CALL), back.surfaces)
        assertEquals(BasicLayout.TABS, Basics.current(back).layout)
    }

    @Test fun a_preset_already_in_use_is_not_applied_again() {
        // "Only people I know" would drop a schedule for unknown callers: an untouched answer must keep it.
        val nights = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60)
        val own = AppSettings(screening = ScreeningSettings(blockNonContacts = true, nonContactsSchedule = nights))
        // A mix of switches has no preset to start on, and leaving it unanswered keeps the mix.
        assertNull(Basics.current(own).screening)
        assertEquals(own, Basics.apply(own, Basics.current(own)))
        val known = ScreeningPreset.KNOWN.apply(AppSettings())
        assertEquals(known, Basics.apply(known, BasicsChoice(screening = ScreeningPreset.KNOWN)))
    }

    @Test fun restoring_a_parley_backup_skips_the_basics_it_would_overwrite() {
        // The default phone app and the permissions still come; then the first run ends and the backup screen opens.
        assertEquals(
            listOf(OnboardingStep.WELCOME, OnboardingStep.DEFAULT_APP, OnboardingStep.PERMISSIONS),
            generateSequence(OnboardingStep.WELCOME) { it.next(restoring = true) }.toList(),
        )
        // Without a restore the steps are the usual ones.
        assertEquals(OnboardingStep.BASICS, OnboardingStep.PERMISSIONS.next(restoring = false))
    }
}
