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

/** "What do the colours mean?" explains every chip, badge and mark Recents can show, each in words of its own. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecentsLegendTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val entries = RecentsLegend.sections.flatMap { it.second }

    @Test fun every_filter_chip_has_a_line() {
        val filters = entries.filterIsInstance<RecentsLegend.Entry.Filter>().map { it.filter }
        assertEquals(RecentFilter.entries.toList(), filters)
        val section = RecentsLegend.sections.first { it.first == RecentsLegend.Section.FILTERS }.second
        assertTrue(section.containsAll(filters.map { RecentsLegend.Entry.Filter(it) }))
    }

    @Test fun every_badge_a_call_can_get_has_a_line() {
        val badges = entries.filterIsInstance<RecentsLegend.Entry.Badge>().map { it.cls }.toSet()
        // Every class a call log row can turn into: each type, talked or not.
        val shown = CallType.entries.flatMap { t -> listOf(0L, 60L).map { CallClass.of(t, it) } }.toSet()
        assertTrue("missing ${shown - badges}", badges.containsAll(shown))
        assertEquals(CallClass.entries.toSet(), badges)
    }

    @Test fun every_mark_has_a_line_in_its_section() {
        RecentsMark.entries.forEach { m ->
            val section = RecentsLegend.sections.first { it.first == m.section }.second
            assertTrue(m.name, RecentsLegend.Entry.Mark(m) in section)
        }
    }

    @Test fun every_line_has_a_name_and_its_own_meaning() {
        entries.forEach { e ->
            assertTrue(e.toString(), context.getString(e.label).isNotBlank())
            assertTrue(e.toString(), context.getString(e.meaning).isNotBlank())
        }
        assertEquals("each meaning is said once", entries.size, entries.map { it.meaning }.toSet().size)
        RecentsLegend.Section.entries.forEach { assertTrue(context.getString(it.title).isNotBlank()) }
    }
}
