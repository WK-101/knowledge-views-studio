package app.parley.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every list marks a private contact the way Contacts does: core/ui's [PrivateBadge] on the photo (directly or through
 * [PrivateMarked]), never a lock character before the name or a badge of a list's own.
 */
class PrivateMarkTest {
    private val sources: List<Pair<File, String>> by lazy {
        listOf(File(".").absoluteFile, File("../telecom").absoluteFile).flatMap { m ->
            File(m, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.map { it to it.readText() }
    }

    @Test fun no_lock_character_stands_in_for_the_badge() {
        assertTrue("the scan found nothing: wrong folder?", sources.size > 100)
        val emoji = sources.filter { (_, text) -> "🔒" in text }.map { it.first.name }
        assertTrue("A lock character marks private contacts in $emoji; use PrivateBadge or PrivateMarked", emoji.isEmpty())
    }

    @Test fun the_lists_that_mark_private_callers_use_the_shared_badge() {
        val own = sources.filter { (_, text) -> Regex("""fun\s+PrivateBadge\s*\(""").containsMatchIn(text) }.map { it.first.name }
        assertTrue("A list draws a private badge of its own in $own", own.isEmpty())
        for (list in listOf("ContactsTab.kt", "FavoritesTab.kt", "RecentsTab.kt", "CircleTab.kt", "ToCallScreen.kt", "RecentsLegend.kt")) {
            val text = sources.firstOrNull { it.first.name == list }?.second ?: error("$list not found")
            assertTrue("$list doesn't use the shared private badge", "PrivateBadge(" in text || "PrivateMarked(" in text)
        }
    }
}
