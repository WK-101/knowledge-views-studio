package app.parley.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One name for one control: every top bar's ⋮ is core/ui's OverflowMenu (or OverflowButton) with its default label,
 * "More options", never a name of its own ("More", "More actions"). Read from the sources of the app and the call
 * screen: the top bars (ParleyTopBar, SelectionTopBar) and the functions that draw a top bar's actions.
 */
class OverflowNamesTest {
    private val sources: Map<String, String> by lazy {
        listOf(File("src/main/kotlin"), File("../telecom/src/main/kotlin")).flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.associate { it.name to it.readText() }
    }

    /** The text of each top bar: the call's arguments and its trailing actions lambda, if any. */
    private fun topBars(text: String): List<String> = BAR.findAll(text).mapNotNull { m ->
        val args = balanced(text, m.range.last, '(', ')') ?: return@mapNotNull null
        val after = text.substring(args.last + 1).trimStart()
        val trailing = if (after.startsWith("{")) balanced(text, text.indexOf('{', args.last), '{', '}') else null
        text.substring(args.first, (trailing ?: args).last + 1)
    }.toList()

    /** The bodies of the functions that draw a top bar's actions ("ContactBarActions"). */
    private fun barActions(text: String): List<String> = ACTIONS_FUN.findAll(text).mapNotNull { m ->
        val params = balanced(text, m.range.last, '(', ')') ?: return@mapNotNull null
        balanced(text, text.indexOf('{', params.last), '{', '}')?.let { text.substring(it.first, it.last + 1) }
    }.toList()

    /** What is wrong with one top bar's ⋮: drawn by hand, or given a name of its own. */
    private fun problems(name: String, bar: String): List<String> {
        val wrong = mutableListOf<String>()
        if ("MoreVert" in bar) wrong += "$name: a hand-built ⋮ in a top bar"
        OVERFLOW.findAll(bar).forEach { call ->
            // `OverflowMenu { close -> … }` has no arguments at all.
            val args = if (call.value.endsWith("(")) balanced(bar, call.range.last, '(', ')')?.let { bar.substring(it) }.orEmpty() else ""
            if ("label =" in args) wrong += "$name: a top bar's ⋮ with a name of its own"
        }
        return wrong
    }

    @Test fun every_top_bar_overflow_is_the_kit_one_named_more_options() {
        assertTrue("the scan found nothing: wrong folder?", sources.size > 100)
        val bars = sources.flatMap { (name, text) -> (topBars(text) + barActions(text)).map { name to it } }
        val wrong = bars.flatMap { (name, bar) -> problems(name, bar) }
        assertEquals("Every top bar's ⋮ reads \"More options\"", emptyList<String>(), wrong)
        // Number history, History & undo, Case files, a case, Labels, a label, a contact, Contacts' and Recents' selection.
        val checked = bars.sumOf { (_, bar) -> OVERFLOW.findAll(bar).count() }
        assertTrue("only $checked top-bar ⋮ found", checked >= 9)
    }

    @Test fun the_kit_names_the_overflow_more_options() {
        val kit = File("../core/ui/src/main/kotlin/app/parley/ui/OverflowMenu.kt").readText()
        assertEquals(3, Regex("""label: String = kitStrings\(\)\.moreOptions""").findAll(kit).count())
        val core = File("../core/ui/src/main/res/values/strings_core.xml").readText()
        assertTrue(core.contains("""<string name="ui_more_options">More options</string>"""))
        // The app hands the kit the same words.
        assertTrue(File("src/main/kotlin/app/parley/ui/common/AppKit.kt").readText().contains("moreOptions = stringResource(R.string.label_more_options)"))
    }

    private companion object {
        val BAR = Regex("""\b(?:ParleyTopBar|SelectionTopBar)\(""")
        val ACTIONS_FUN = Regex("""fun \w*BarActions\(""")
        val OVERFLOW = Regex("""\bOverflow(?:Menu|Button)\s*[({]""")

        /** The range from the [open] character at or after [from] to its matching [close]. */
        fun balanced(text: String, from: Int, open: Char, close: Char): IntRange? {
            val start = text.indexOf(open, from).takeIf { it >= 0 } ?: return null
            var depth = 0
            for (i in start until text.length) {
                when (text[i]) {
                    open -> depth++
                    close -> if (--depth == 0) return start..i
                }
            }
            return null
        }
    }
}
