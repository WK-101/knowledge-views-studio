package com.todocompanion.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.todocompanion.app.domain.MarkdownStyle

/**
 * Wave Q — the Compose half of inline Markdown live-styling. Applies the pure [MarkdownStyle] spans to the
 * editor's text as an [AnnotatedString], keeping an IDENTITY [OffsetMapping] — no characters are hidden or
 * inserted, so the caret, selection, and every downstream edit offset are exactly the raw text's. Syntax
 * markers are dimmed rather than removed (a deliberate choice: it keeps editing unambiguous and the offset
 * mapping trivially correct, which is what makes this robust enough to ship).
 *
 * Colours come from the caller (the Material scheme / reading theme), so styling stays theme-correct in
 * light, dark and every named reading theme.
 */
class MarkdownVisualTransformation(
    private val base: Color,
    private val muted: Color,
    private val accent: Color,
    private val code: Color,
    private val quote: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val spans = runCatching { MarkdownStyle.spans(raw) }.getOrDefault(emptyList())
        val builder = AnnotatedString.Builder(raw)
        for (s in spans) {
            if (s.start < 0 || s.end > raw.length || s.start >= s.end) continue
            builder.addStyle(styleFor(s.kind), s.start, s.end)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun styleFor(k: MarkdownStyle.Kind): SpanStyle = when (k) {
        MarkdownStyle.Kind.H1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.5.em)
        MarkdownStyle.Kind.H2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.3.em)
        MarkdownStyle.Kind.H3 -> SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 1.15.em)
        MarkdownStyle.Kind.QUOTE -> SpanStyle(color = quote, fontStyle = FontStyle.Italic)
        MarkdownStyle.Kind.MARKER -> SpanStyle(color = accent, fontWeight = FontWeight.Medium)
        MarkdownStyle.Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, color = code, fontSize = 0.92.em)
        MarkdownStyle.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        MarkdownStyle.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        MarkdownStyle.Kind.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        MarkdownStyle.Kind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough, color = muted)
        MarkdownStyle.Kind.LINK, MarkdownStyle.Kind.WIKILINK -> SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
        MarkdownStyle.Kind.TAG -> SpanStyle(color = accent)
        MarkdownStyle.Kind.SYNTAX -> SpanStyle(color = muted.copy(alpha = 0.55f))
    }

    companion object {
        /** Convenience: a no-op-sized [TextUnit] guard kept for callers computing derived sizes. */
        val ZERO: TextUnit = 0.sp
    }
}
