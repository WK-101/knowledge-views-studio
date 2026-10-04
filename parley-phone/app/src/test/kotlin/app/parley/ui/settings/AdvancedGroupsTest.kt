package app.parley.ui.settings

import app.parley.common.SettingPlace
import app.parley.common.SettingsCatalog
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pages' "Advanced" folds and the catalog agree: a row drawn inside a fold is marked advanced in [SettingsCatalog]
 * (so search unfolds the group for it), and every advanced setting on a page is drawn inside its fold. Read from the
 * page sources, following the composable groups a fold draws.
 */
class AdvancedGroupsTest {
    private val sources: Map<File, String> by lazy {
        val root = listOf(File("src/main/kotlin/app/parley/ui"), File("app/src/main/kotlin/app/parley/ui")).first { it.isDirectory }
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associateWith { it.readText() }
    }

    /** Bodies of the functions defined in the sources, by name (a group's composable or a scope's row helper). */
    private val functions: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        val def = Regex("""fun (?:SegmentedGroupScope\.)?([A-Za-z0-9_]+)\(""")
        for (text in sources.values) {
            def.findAll(text).forEach { m -> block(text, m.range.last)?.let { out.putIfAbsent(m.groupValues[1], it) } }
        }
        out
    }

    /** The text from the first `{` (or `=`-expression line) after [from] to its matching `}`. */
    private fun block(text: String, from: Int): String? {
        var i = from
        // Skip the parameter list.
        var depth = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '(') depth++
            if (ch == ')') { depth--; if (depth == 0) break }
            i++
        }
        val open = text.indexOf('{', i)
        val eq = text.indexOf('=', i)
        if (eq in 0 until (if (open < 0) Int.MAX_VALUE else open)) return text.substring(eq, text.indexOf('\n', eq).let { if (it < 0) text.length else it })
        return if (open < 0) null else braces(text, open)
    }

    /** The block opening at [open] (a `{`) to its matching `}`. */
    private fun braces(text: String, open: Int): String? {
        var depth = 0
        for (j in open until text.length) {
            if (text[j] == '{') depth++
            if (text[j] == '}') { depth--; if (depth == 0) return text.substring(open, j + 1) }
        }
        return null
    }

    private val rowKey = Regex("""(?:switchRow|linkRow|menuRow|choiceRow|item|blended)\(\s*"([a-z0-9_]+)"""")
    private val call = Regex("""\b([A-Za-z][A-Za-z0-9_]*)\(""")

    /** Setting keys drawn by [body], following calls into the sources' own functions. */
    private fun keysIn(body: String, seen: MutableSet<String> = HashSet()): Set<String> {
        val keys = rowKey.findAll(body).map { it.groupValues[1] }.toMutableSet()
        // Composable groups (capitalised) and a scope's row helpers (…Row, …Rows); other calls are lambdas and the like.
        call.findAll(body).map { it.groupValues[1] }
            .filter { (it[0].isUpperCase() || it.endsWith("Row") || it.endsWith("Rows")) && it in functions && seen.add(it) }.forEach { keys += keysIn(functions.getValue(it), seen) }
        return keys.filterTo(HashSet()) { k -> SettingsCatalog.entries.any { it.key == k } }
    }

    private val folds: Set<String> by lazy {
        val fold = Regex("""\bAdvanced(?:Group|Section)\s*(?:\([^)]*\))?\s*\{""")
        sources.filterKeys { it.path.contains("settings") }.values.flatMap { text ->
            fold.findAll(text).mapNotNull { m -> braces(text, m.range.last)?.let { keysIn(it) } }.flatten().toList()
        }.toSet()
    }

    @Test fun rows_inside_a_fold_are_marked_advanced() {
        assertTrue("no folds found", folds.isNotEmpty())
        val unmarked = folds.filterNot { SettingsCatalog.isAdvanced(it) }
        assertTrue("Folded but not in SettingsCatalog.ADVANCED (search wouldn't unfold them): $unmarked", unmarked.isEmpty())
    }

    @Test fun advanced_settings_on_pages_are_folded() {
        val onPages = setOf(null, SettingPlace.CALLS_ANSWERING, SettingPlace.CALLS_DURING)
        val open = SettingsCatalog.entries.filter { it.advanced && it.place in onPages }.map { it.key }.filterNot { it in folds }
        assertTrue("Marked advanced but drawn outside every fold: $open", open.isEmpty())
    }
}
