package app.parley.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every list marks a private contact the way Contacts does: core/ui's [PrivateBadge] on the photo (directly or through
 * [PrivateMarked]), never a lock character before the name or a badge of a list's own. Only code counts: comments may
 * talk about the lock, and a lock character elsewhere than in the app's words (a log line) is no mark.
 */
class PrivateMarkTest {
    private val sources: List<Pair<File, String>> by lazy {
        listOf(File(".").absoluteFile, File("../telecom").absoluteFile).flatMap { m ->
            File(m, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.map { it to it.readText() }
    }

    /** The app's and the call screen's string resources. */
    private val strings: List<Pair<File, String>> by lazy {
        listOf(File(".").absoluteFile, File("../telecom").absoluteFile).flatMap { m ->
            File(m, "src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }.toList()
        }.map { it to it.readText() }
    }

    /** UI code: the screens (`ui` packages) and the notifications, where what people read is written. */
    private fun isUi(f: File): Boolean = f.invariantSeparatorsPath.let { "/ui/" in it || "/calls/" in it || it.endsWith("Notifier.kt") }

    @Test fun no_lock_character_stands_in_for_the_badge() {
        assertTrue("the scan found nothing: wrong folder?", sources.size > 100 && strings.isNotEmpty())
        val inCode = sources.filter { (f, text) -> isUi(f) && KotlinText.literals(text).any { LOCK in it } }.map { it.first.name }
        val inStrings = strings.filter { (_, text) -> LOCK in XML_COMMENT.replace(text, "") }.map { it.first.name }
        assertTrue("A lock character marks private contacts in ${inCode + inStrings}; use PrivateBadge or PrivateMarked", (inCode + inStrings).isEmpty())
    }

    @Test fun the_lists_that_mark_private_callers_use_the_shared_badge() {
        val own = sources.filter { (_, text) -> Regex("""fun\s+PrivateBadge\s*\(""").containsMatchIn(KotlinText.code(text)) }.map { it.first.name }
        assertTrue("A list draws a private badge of its own in $own", own.isEmpty())
        for (list in listOf("ContactsTab.kt", "FavoritesTab.kt", "RecentsTab.kt", "CircleTab.kt", "ToCallScreen.kt", "RecentsLegend.kt", "KeypadTab.kt")) {
            val text = sources.firstOrNull { it.first.name == list }?.second ?: error("$list not found")
            // Calls only: a comment naming the badge doesn't count.
            val code = KotlinText.code(text)
            assertTrue("$list doesn't use the shared private badge", Regex("""\bPrivate(Badge|Marked)\s*\(""").containsMatchIn(code))
        }
    }

    @Test fun the_scan_reads_code_not_comments() {
        val sample = """
            // A comment about the $LOCK lock
            /* and $LOCK in a block, "with quotes" */
            val a = "plain"
            val b = "$LOCK " + name
            val c = '"'
            val d = ${"\"\"\""}raw $LOCK${"\"\"\""}
            val e = "escaped \" quote // not a comment"
            val f = "${'$'}{g("x")} done" // $LOCK after a template
        """.trimIndent()
        val literals = KotlinText.literals(sample)
        assertEquals(listOf("plain", "$LOCK ", "raw $LOCK", "escaped \\\" quote // not a comment", "${'$'}{g(\"x\")} done"), literals)
        val code = KotlinText.code(sample)
        assertTrue("A comment about" !in code && "in a block" !in code && "after a template" !in code)
        assertTrue("PrivateBadge(" !in KotlinText.code("// PrivateBadge(\nval x = 1"))
    }

    /** Kotlin source split into code, comments and string literals (enough for these checks: no nesting of comments). */
    private object KotlinText {
        /** The text of every string literal, in order (raw strings included; templates are kept as written). */
        fun literals(text: String): List<String> = scan(text).second

        /** [text] without its comments (string literals kept). */
        fun code(text: String): String = scan(text).first

        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
        private fun scan(text: String): Pair<String, List<String>> {
            val code = StringBuilder()
            val literals = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> {
                        i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    }
                    text.startsWith("/*", i) -> {
                        i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    }
                    text.startsWith("\"\"\"", i) -> {
                        val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it }
                        literals += text.substring(i + 3, end)
                        code.append(text, i, minOf(end + 3, text.length))
                        i = end + 3
                    }
                    text[i] == '"' -> {
                        var j = i + 1
                        while (j < text.length && text[j] != '"' && text[j] != '\n') {
                            j = when {
                                text[j] == '\\' -> j + 2
                                // A template's code may hold quotes of its own ("${'$'}{f("x")}").
                                text.startsWith("${'$'}{", j) -> templateEnd(text, j + 2)
                                else -> j + 1
                            }
                        }
                        literals += text.substring(i + 1, minOf(j, text.length))
                        code.append(text, i, minOf(j + 1, text.length))
                        i = j + 1
                    }
                    text[i] == '\'' -> {
                        // A character literal ('"', '\''): skipped whole, so its quote opens no string.
                        var j = i + 1
                        while (j < text.length && text[j] != '\'' && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                        code.append(text, i, minOf(j + 1, text.length))
                        i = j + 1
                    }
                    else -> {
                        code.append(text[i])
                        i++
                    }
                }
            }
            return code.toString() to literals
        }

        /** Where the template expression starting at [from] (after its brace) ends, past the closing brace. */
        private fun templateEnd(text: String, from: Int): Int {
            var depth = 1
            var j = from
            while (j < text.length && depth > 0) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> depth--
                    '"' -> j = stringEnd(text, j + 1)
                }
                j++
            }
            return j
        }

        /** The closing quote of the plain string whose text starts at [from]. */
        private fun stringEnd(text: String, from: Int): Int {
            var j = from
            while (j < text.length && text[j] != '"') j += if (text[j] == '\\') 2 else 1
            return j
        }
    }

    private companion object {
        const val LOCK = "🔒"
        val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
