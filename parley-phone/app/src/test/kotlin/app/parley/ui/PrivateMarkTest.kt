package app.parley.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's words never mark a private contact with a lock character: core/ui's [PrivateBadge] does that. The code is
 * checked by the `PrivateMark` detekt rule (tools/detekt-rules), which reads Kotlin only; this checks the string
 * resources, comments aside.
 */
class PrivateMarkTest {
    /** The app's and the call screen's string resources. */
    private val strings: List<Pair<File, String>> by lazy {
        listOf(File(".").absoluteFile, File("../telecom").absoluteFile).flatMap { m ->
            File(m, "src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }.toList()
        }.map { it to it.readText() }
    }

    @Test fun no_lock_character_stands_in_for_the_badge_in_the_apps_words() {
        assertTrue("the scan found nothing: wrong folder?", strings.isNotEmpty())
        val marked = strings.filter { (_, text) -> LOCK in XML_COMMENT.replace(text, "") }.map { it.first.name }
        assertTrue("A lock character marks private contacts in $marked; use PrivateBadge or PrivateMarked", marked.isEmpty())
    }

    private companion object {
        const val LOCK = "🔒"
        val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
