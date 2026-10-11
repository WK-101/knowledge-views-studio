package app.parley.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.fail

/**
 * Automated accessibility checks over what a Compose test has on screen, with the test's own semantics tree (the
 * Robolectric smoke tests; the instrumented suite can add Android's Accessibility Test Framework on a device):
 * - everything that can be tapped has words TalkBack can read (its text, a content description or a state);
 * - its touch target is at least 48 × 48 dp (the Material minimum, `minimumInteractiveComponentSize`), counting the
 *   touch area Compose widens around a small pointer target, as Android's touch handling does;
 * - no two tappable things side by side say the same words for different targets ("Call", "Call": which one?).
 * Nodes cut off by the screen's edge (a list's half-shown last row) aren't measured: their touch area is cut too.
 */
object A11yChecks {
    /** The Material minimum touch target, with half a pixel for rounding. */
    private val MIN_TARGET = 48.dp

    /** One problem: what the node says (or its test tag and place) and why it fails. */
    data class Finding(val node: String, val problem: String)

    /** Runs every check over all roots (popups too) and fails the test with every finding at once. */
    fun assertAccessible(rule: ComposeTestRule, allowDuplicates: Set<String> = emptySet()) {
        val findings = findings(rule.onAllNodes(hasClickAction()), rule.density.run { MIN_TARGET.toPx() } - 0.5f, allowDuplicates)
        if (findings.isNotEmpty()) fail("Accessibility:\n" + findings.joinToString("\n") { "- ${it.node}: ${it.problem}" })
    }

    fun findings(clickable: SemanticsNodeInteractionCollection, minPx: Float, allowDuplicates: Set<String>): List<Finding> {
        val nodes = clickable.fetchSemanticsNodes(atLeastOneRootRequired = false)
            .filter { it.config.getOrNull(SemanticsProperties.Disabled) == null && it.config.getOrNull(SemanticsProperties.InvisibleToUser) == null }
        val out = ArrayList<Finding>()
        nodes.forEach { n ->
            val label = label(n)
            if (label.isBlank()) out += Finding(describe(n), "nothing for TalkBack to read (add text or a content description)")
            if (fullyShown(n)) {
                val (w, h) = targetSize(n)
                if (w < minPx || h < minPx) out += Finding(describe(n), "touch target ${w.toInt()} × ${h.toInt()} px is under 48 dp")
            }
        }
        // Same words, different targets, in the same row or column of controls (a parent in common).
        nodes.filter { label(it).isNotBlank() }
            .groupBy { (it.parent?.id ?: -1) to label(it) }
            .filter { (key, list) -> list.size > 1 && key.second !in allowDuplicates }
            .forEach { (key, list) -> out += Finding(key.second, "${list.size} different controls say the same words") }
        return out
    }

    /** What TalkBack reads for [n]: its merged text, content description and state description. */
    fun label(n: SemanticsNode): String {
        val c = n.config
        return listOfNotNull(
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
            c.getOrNull(SemanticsProperties.EditableText)?.text,
            c.getOrNull(SemanticsProperties.StateDescription),
        ).joinToString(" ").trim()
    }

    /**
     * Whether all of [n] is on screen: a node cut off by the edge (a chip scrolled half away, a row below the fold, a
     * popup still animating in) has its touch area cut too, so its size can't be told from here.
     */
    private fun fullyShown(n: SemanticsNode): Boolean {
        val b = n.boundsInRoot
        return b.width >= n.size.width - 1 && b.height >= n.size.height - 1 && n.size.width > 0 && n.size.height > 0
    }

    /** The larger of the node's own size and the touch area Compose gives it (touch bounds can grow a small node). */
    private fun targetSize(n: SemanticsNode): Pair<Float, Float> {
        val touch: Rect = n.touchBoundsInRoot
        val w = maxOf(n.size.width.toFloat(), touch.width)
        val h = maxOf(n.size.height.toFloat(), touch.height)
        return w to h
    }

    private fun describe(n: SemanticsNode): String {
        val tag = n.config.getOrNull(SemanticsProperties.TestTag)
        val near = generateSequence(n.parent) { it.parent }.map(::label).firstOrNull { it.isNotBlank() }
        return listOfNotNull(label(n).ifBlank { null }, tag?.let { "tag $it" }, near?.let { "inside \"${it.take(40)}\"" }, "at ${n.boundsInRoot}")
            .joinToString(", ")
    }
}
