package com.todocompanion.app.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * TickTick-style live token highlighting for the quick-add field. Colours recognised
 * tokens (priority, date, #tag, @context, ~list) inline as the user types. Colouring
 * never changes the text length, so the offset mapping is the identity.
 */
private data class Tok(val re: Regex, val color: Color, val bold: Boolean = true)

private val TOKENS = listOf(
    // reminder shortcut — !30m / !2h / !1d / !1w
    Tok(Regex("(?i)(?<=\\s|^)!\\d{1,4}\\s*(m|min|mins|h|hr|hrs|hour|hours|d|day|days|w|wk|week|weeks)(?=\\s|$)"), Color(0xFF0891B2)),
    // priority — !, !!, !!! or p1..p4
    Tok(Regex("(?<=\\s|^)(!{1,3}|[pP][1-4])(?=\\s|$)"), Color(0xFFEA580C)),
    // list — ~name
    Tok(Regex("(?<=\\s|^)~[\\p{L}0-9_-]+"), Color(0xFF0D9488)),
    // #tag
    Tok(Regex("(?<=\\s|^)#[\\p{L}0-9_-]+"), Color(0xFF7C3AED)),
    // #t25 estimate — listed after #tag so it wins the overlap (an estimate, not a tag)
    Tok(Regex("(?<=\\s|^)#t\\d{1,4}(?=\\s|$)"), Color(0xFF0891B2)),
    // * star
    Tok(Regex("(?<=\\s|^)\\*(?=\\s|$)"), Color(0xFFD97706)),
    // @context
    Tok(Regex("(?<=\\s|^)@[\\p{L}0-9_-]+"), Color(0xFFDB2777)),
    // dates & times
    Tok(Regex("(?i)(?<=\\s|^)(today|tonight|tomorrow|next\\s+week|next\\s+(mon|tue|wed|thu|fri|sat|sun)\\w*|(mon|tue|wed|thu|fri|sat|sun)(day|s|nes|rs|urday)?|in\\s+\\d{1,3}\\s+(hour|day|week)s?|noon|midnight|morning|afternoon|evening)(?=\\s|$)"), Color(0xFF2563EB)),
    Tok(Regex("(?i)(?<=\\s|^)((at\\s+)?\\d{1,2}(:\\d{2})?\\s*(am|pm)|([01]?\\d|2[0-3]):[0-5]\\d)(?=\\s|$)"), Color(0xFF2563EB)),
)

fun buildQuickAddAnnotated(text: String): AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    for (tok in TOKENS) {
        for (m in tok.re.findAll(text)) {
            addStyle(SpanStyle(color = tok.color, fontWeight = if (tok.bold) FontWeight.SemiBold else FontWeight.Normal), m.range.first, m.range.last + 1)
        }
    }
}

val QuickAddTransformation = VisualTransformation { text ->
    TransformedText(buildQuickAddAnnotated(text.text), OffsetMapping.Identity)
}
