package com.todocompanion.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.NoteAppearance

/**
 * L13 — the one place the app decides how to render a Markdown body *inline* (inside a scrolling parent).
 * Task notes and any inline note surface call this instead of choosing a renderer themselves, so
 * math / diagrams / callouts never diverge between tasks and notes again.
 *
 * Routing policy ([RichMarkdownPolicy]): plain prose, lists, tables, callouts and checklists render with
 * the lightweight Compose [MarkdownText] — selectable text, tappable checkboxes that round-trip to the
 * source, no WebView cost. Content that genuinely needs the offline rich engine — `$math$`, ```mermaid /
 * ```math diagrams — renders with the bundled KaTeX / Mermaid / Prism [RichNoteView], auto-sized to its
 * content so it embeds in a scroll. The heavy path is taken *only* when the content demands it, so the
 * common case stays cheap while rich task notes finally match note reading fidelity.
 */
object RichMarkdownPolicy {
    // A closed inline-math pair: `$…$` that opens and closes on non-space (so "$5 and $10" and a lone
    // "$5" never trip it). Block math and fenced diagrams are matched by literal probes below.
    private val INLINE_MATH = Regex("""\$[^\s$][^$\n]*[^\s$]\$|\$[^\s$]\$""")

    /** True if [text] contains a construct only the rich WebView engine (KaTeX/Mermaid) can render. */
    fun needsRichEngine(text: String): Boolean {
        if (text.length < 3) return false
        if (text.contains("```mermaid", ignoreCase = true) || text.contains("```math", ignoreCase = true)) return true
        if (text.contains("$$")) return true              // block math $$ … $$
        return INLINE_MATH.containsMatchIn(text)
    }
}

@Composable
fun RichMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    images: Map<String, String> = emptyMap(),
    readingThemeId: String = "match",
    type: NoteAppearance.NoteType = NoteAppearance.NoteType(),
    onToggleCheckbox: ((sourceLineIndex: Int) -> Unit)? = null,
    maxRichHeight: Dp = 4000.dp,
) {
    val rich = remember(text) { RichMarkdownPolicy.needsRichEngine(text) }
    if (rich) {
        RichNoteView(
            markdown = text,
            images = images,
            readingThemeId = readingThemeId,
            type = type,
            autoHeight = true,
            maxAutoHeight = maxRichHeight,
            modifier = modifier,
        )
    } else {
        MarkdownText(text = text, modifier = modifier, onToggleCheckbox = onToggleCheckbox)
    }
}
