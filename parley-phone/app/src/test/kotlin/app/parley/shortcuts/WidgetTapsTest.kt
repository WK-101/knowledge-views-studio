package app.parley.shortcuts

import android.app.Application
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import app.parley.MainActivity
import app.parley.common.people.FavoritesWidgetPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every tap on every widget has its own PendingIntent: taps that start the same activity differ only in extras,
 * which Android ignores when it matches PendingIntents, so a shared one would make one widget call another's person.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetTapsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    /** How many places each kind of tap has in one widget, at its largest size. */
    private fun places(kind: WidgetTaps.Kind): Int = when (kind) {
        WidgetTaps.Kind.FAVOURITE -> FavoritesWidgetPlan.MAX_COLUMNS * FavoritesWidgetPlan.MAX_ROWS
        WidgetTaps.Kind.CIRCLE_PERSON, WidgetTaps.Kind.CIRCLE_CALL, WidgetTaps.Kind.CIRCLE_DATE -> 3
        else -> 1
    }

    @Test fun every_tap_of_every_widget_kind_and_size_has_its_own_key() {
        val keys = HashSet<String>()
        var count = 0
        for (kind in WidgetTaps.Kind.entries) {
            for (id in 0..400) {
                for (place in 0 until places(kind)) {
                    keys += WidgetTaps.key(kind, id, place)
                    count++
                }
            }
        }
        assertEquals("a key used twice", count, keys.size)
    }

    @Test fun a_favourite_and_a_circle_call_never_share_a_pending_intent() {
        // The ids that collided before: Favourites widget 1, place 4, and Circle widget 96, place 0 (code 8068 both).
        val ana = FavoritesWidgetPlan.Tile(11, "Ana", "+44 20 7946 0011", null)
        val fav = FavoritesWidget.tapIntent(context, 1, 4, ana, FavoritesWidgetPlan.Tap.CALL)
        val circle = WidgetTaps.activity(
            context, WidgetTaps.Kind.CIRCLE_CALL, 96, 0, Shortcuts.intent(context, Shortcuts.Kind.CALL, "+44 20 7946 0022", 22, "Ben"),
        )
        assertNotEquals(fav, circle)
        // Drawn again in either order, each keeps its own person.
        FavoritesWidget.tapIntent(context, 1, 4, ana, FavoritesWidgetPlan.Tap.CALL)
        assertEquals("+44 20 7946 0011", shadowOf(fav).savedIntent.getStringExtra(ShortcutActivity.EXTRA_NUMBER))
        assertEquals("Ana", shadowOf(fav).savedIntent.getStringExtra(ShortcutActivity.EXTRA_NAME))
        assertEquals("+44 20 7946 0022", shadowOf(circle).savedIntent.getStringExtra(ShortcutActivity.EXTRA_NUMBER))
    }

    @Test fun the_same_place_of_two_widgets_of_one_kind_stays_apart() {
        val ana = FavoritesWidgetPlan.Tile(11, "Ana", "+44 20 7946 0011", null)
        val ben = FavoritesWidgetPlan.Tile(22, "Ben", null, null)
        val first = FavoritesWidget.tapIntent(context, 3, 0, ana, FavoritesWidgetPlan.Tap.CALL)
        val second = FavoritesWidget.tapIntent(context, 4, 0, ben, FavoritesWidgetPlan.Tap.CALL)
        assertNotEquals(first, second)
        assertEquals("+44 20 7946 0011", shadowOf(first).savedIntent.getStringExtra(ShortcutActivity.EXTRA_NUMBER))
        // Ben has no number: his tap opens his page.
        val open = shadowOf(second).savedIntent
        assertEquals(MainActivity.ACTION_SHOW_CALLER, open.action)
        assertEquals(22L, open.getLongExtra(MainActivity.EXTRA_CONTACT_ID, -1))
    }

    @Test fun the_mark_is_only_a_data_uri_the_activities_ignore() {
        val i = WidgetTaps.mark(Shortcuts.intent(context, Shortcuts.Kind.CALL, "1", 1), WidgetTaps.Kind.DIAL, 7)
        assertEquals("parley-widget", i.data?.scheme)
        assertEquals(ShortcutActivity.ACTION, i.action)
        // A PendingIntent made for it is an activity one, immutable.
        val pi: PendingIntent = WidgetTaps.activity(context, WidgetTaps.Kind.DIAL, 7, 0, Shortcuts.intent(context, Shortcuts.Kind.CALL, "1", 1))
        assertTrue(shadowOf(pi).isActivityIntent)
        assertTrue(shadowOf(pi).isImmutable)
    }
}
