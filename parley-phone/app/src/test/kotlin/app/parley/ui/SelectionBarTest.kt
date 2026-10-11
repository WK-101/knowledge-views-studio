package app.parley.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Selecting looks the same on every list: core/ui's [SelectionTopBar] takes the top bar's place, and no list draws a
 * selection panel of its own (Recents once had one under the top bar).
 */
class SelectionBarTest {
    private val modules = listOf("." to "app", "../telecom" to "telecom").map { (dir, name) -> File(dir).absoluteFile to name }

    /** Kotlin sources of the app and the call screen, with their text. */
    private val sources: List<Pair<File, String>> by lazy {
        modules.flatMap { (m, _) -> File(m, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .map { it to it.readText() }
    }

    @Test fun every_multi_select_list_uses_the_shared_selection_bar() {
        assertTrue("the scan found nothing: wrong folder?", sources.isNotEmpty())
        val byName = sources.associate { it.first.name to it.second }
        // Contacts, Recents and merging labels: every list where rows can be selected together.
        for (host in SELECTION_HOSTS) {
            val text = byName[host] ?: error("$host is gone: update SELECTION_HOSTS")
            assertTrue("$host draws its own selection bar; use SelectionTopBar", "SelectionTopBar(" in text)
        }
    }

    @Test fun recents_selection_lives_in_the_top_bar_not_under_it() {
        val home = sources.single { it.first.name == "HomeScreen.kt" }.second
        val recents = sources.single { it.first.name == "RecentsTab.kt" }.second
        assertTrue("Recents' selection bar belongs in the home top bar", "RecentsSelectionBar(" in home)
        assertTrue("Recents draws a selection panel in its list", "RecentsSelectionBar(" !in recents && "stickyHeader(key = \"selection\")" !in recents)
    }

    @Test fun no_list_draws_a_selection_panel_of_its_own() {
        // The shape of a hand-made selection bar: a Clear selection button, or a tinted panel with Close and a count.
        val handMade = sources.filter { (_, text) ->
            "Clear selection" in text || "stickyHeader(key = \"selection\")" in text ||
                ("colorScheme.secondaryContainer" in text && "Icons.Rounded.Close" in text && "selected" in text)
        }.map { it.first.name }
        assertEquals("Selection bars drawn by hand in $handMade; use SelectionTopBar", emptyList<String>(), handMade)
    }

    @Test fun only_the_shared_bar_says_how_many_are_selected() {
        val strings = modules.flatMap { (m, _) ->
            File(m, "src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }.toList()
        }
        assertTrue("the scan found nothing: wrong folder?", strings.isNotEmpty())
        val counts = strings.filter { COUNT_SELECTED.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("\"N selected\" counts in $counts; SelectionTopBar says it (core/ui)", emptyList<String>(), counts)
    }

    private companion object {
        val SELECTION_HOSTS = listOf("SelectionBar.kt", "BlockingHooks.kt", "LabelScreens.kt")
        val COUNT_SELECTED = Regex(""">%(1\$)?d selected<""")
    }
}
