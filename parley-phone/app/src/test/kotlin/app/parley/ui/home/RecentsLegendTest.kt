package app.parley.ui.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.RecentFilter
import app.parley.common.CallType
import app.parley.common.ux.CallClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "What do the colours mean?" explains every chip, icon and mark Recents shows, in the style in use (Rich or Simple),
 * each in words of its own, and nothing that style doesn't show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecentsLegendTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val styles = listOf(true, false)

    private fun entries(rich: Boolean) = RecentsLegend.sections(rich).flatMap { it.second }

    private fun legendMarks(rich: Boolean) = entries(rich).filterIsInstance<RecentsLegend.Entry.Mark>().map { it.mark }.toSet()

    /** Every kind of row Recents can draw: each call class, alone or several, with and without each extra. */
    private val rows: List<RecentRowFacts> = buildList {
        val flags = listOf(false, true)
        for (cls in CallClass.entries) for (calls in listOf(1, 3)) for (unreturned in flags) for (hidden in flags) for (extra in flags) for (button in flags) {
            add(
                RecentRowFacts(
                    calls = calls, cls = cls, missed = cls == CallClass.MISSED || cls == CallClass.DECLINED, sequence = calls > 1,
                    unreturned = unreturned, hidden = hidden, video = extra, private = extra, screening = extra, callButton = button,
                ),
            )
        }
    }

    @Test fun every_filter_chip_has_a_line_in_both_styles() {
        styles.forEach { rich ->
            val section = RecentsLegend.sections(rich).first { it.first == RecentsLegend.Section.FILTERS }.second
            val filters = section.filterIsInstance<RecentsLegend.Entry.Filter>().map { it.filter }
            assertEquals(RecentFilter.entries.toList(), filters)
            // The Filter and saved-filter chips sit beside them in both styles.
            assertTrue(section.contains(RecentsLegend.Entry.Mark(RecentsMark.FILTER, rich)))
            assertTrue(section.contains(RecentsLegend.Entry.Mark(RecentsMark.SAVED_FILTER, rich)))
        }
    }

    @Test fun every_call_a_row_can_show_has_a_line_in_the_style_that_shows_it() {
        val shown = CallType.entries.flatMap { t -> listOf(0L, 60L).map { t to CallClass.of(t, it) } }
        // Rich: the shape-coded badges, and no arrows.
        val rich = entries(true)
        val badges = rich.filterIsInstance<RecentsLegend.Entry.Badge>().map { it.cls }.toSet()
        assertTrue("missing ${shown.map { it.second }.toSet() - badges}", badges.containsAll(shown.map { it.second }))
        assertEquals(CallClass.entries.toSet(), badges)
        assertTrue(rich.none { it is RecentsLegend.Entry.TypeIcon })
        // Simple: the arrow icons, and no shapes.
        val simple = entries(false)
        val icons = simple.filterIsInstance<RecentsLegend.Entry.TypeIcon>().map { it.type }.toSet()
        shown.forEach { (t, _) -> assertTrue("no arrow for $t", RecentsLegend.simpleIcon(t) in icons) }
        assertTrue(simple.none { it is RecentsLegend.Entry.Badge })
    }

    @Test fun every_mark_a_row_draws_has_a_line_and_the_legend_shows_no_other() {
        styles.forEach { rich ->
            val drawn = rows.flatMap { RecentsMark.onRow(it, rich) }.toSet()
            val legend = legendMarks(rich)
            assertTrue("rich=$rich: drawn without a line ${drawn - legend}", legend.containsAll(drawn))
            // The row marks the legend explains are exactly the ones this style draws (no Rich shapes in Simple).
            val rowLines = legend.filter { it.section == RecentsLegend.Section.ROWS }.toSet()
            assertEquals("rich=$rich", drawn, rowLines)
        }
    }

    @Test fun simple_rows_draw_their_own_marks() {
        val missedTwice = RecentRowFacts(
            calls = 2, cls = CallClass.MISSED, missed = true, sequence = false, unreturned = true, hidden = false,
            video = false, private = false, screening = false, callButton = true,
        )
        assertEquals(setOf(RecentsMark.COUNT_TEXT, RecentsMark.MISSED_NAME), RecentsMark.onRow(missedTwice, richStyle = false))
        assertEquals(
            setOf(RecentsMark.ACCENT, RecentsMark.NOT_RETURNED, RecentsMark.COUNT, RecentsMark.CALL_BACK),
            RecentsMark.onRow(missedTwice, richStyle = true),
        )
    }

    @Test fun every_line_has_a_name_and_its_own_meaning() {
        styles.forEach { rich ->
            val all = entries(rich)
            all.forEach { e ->
                assertTrue(e.toString(), context.getString(e.label).isNotBlank())
                assertTrue(e.toString(), context.getString(e.meaning).isNotBlank())
            }
            assertEquals("rich=$rich: each meaning is said once", all.size, all.map { it.meaning }.toSet().size)
            assertTrue(context.getString(RecentsLegend.footer(rich)).isNotBlank())
        }
        RecentsLegend.Section.entries.forEach { assertTrue(context.getString(it.title).isNotBlank()) }
    }
}
