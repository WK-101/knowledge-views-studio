package com.wkhan.hexis.ui.screens

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
// U7 — each token carries a light AND a dark hue. The old code used fixed light-mode hexes that did not
// adapt to dark / AMOLED (a real theme-correctness gap flagged in the audit); the dark variants are brighter
// shades of the same hue so tokens stay legible on a dark surface. The regexes are compiled once (top-level).
private data class Tok(val re: Regex, val light: Color, val dark: Color, val bold: Boolean = true)

private val TOKENS = listOf(
    // reminder shortcut — !30m / !2h / !1d / !1w
    Tok(Regex("(?i)(?<=\\s|^)!\\d{1,4}\\s*(m|min|mins|h|hr|hrs|hour|hours|d|day|days|w|wk|week|weeks)(?=\\s|$)"), Color(0xFF0891B2), Color(0xFF22D3EE)),
    // priority — !, !!, !!! or p1..p4
    Tok(Regex("(?<=\\s|^)(!{1,3}|[pP][1-4])(?=\\s|$)"), Color(0xFFEA580C), Color(0xFFFB923C)),
    // list — ~name
    Tok(Regex("(?<=\\s|^)~[\\p{L}0-9_-]+"), Color(0xFF0D9488), Color(0xFF2DD4BF)),
    // #tag
    Tok(Regex("(?<=\\s|^)#[\\p{L}0-9_-]+"), Color(0xFF7C3AED), Color(0xFFA78BFA)),
    // #t25 estimate — listed after #tag so it wins the overlap (an estimate, not a tag)
    Tok(Regex("(?<=\\s|^)#t\\d{1,4}(?=\\s|$)"), Color(0xFF0891B2), Color(0xFF22D3EE)),
    // * star
    Tok(Regex("(?<=\\s|^)\\*(?=\\s|$)"), Color(0xFFD97706), Color(0xFFFBBF24)),
    // @context
    Tok(Regex("(?<=\\s|^)@[\\p{L}0-9_-]+"), Color(0xFFDB2777), Color(0xFFF472B6)),
    // dates & times
    Tok(Regex("(?i)(?<=\\s|^)(today|tonight|tomorrow|next\\s+week|next\\s+(mon|tue|wed|thu|fri|sat|sun)\\w*|(mon|tue|wed|thu|fri|sat|sun)(day|s|nes|rs|urday)?|in\\s+\\d{1,3}\\s+(hour|day|week)s?|noon|midnight|morning|afternoon|evening)(?=\\s|$)"), Color(0xFF2563EB), Color(0xFF60A5FA)),
    Tok(Regex("(?i)(?<=\\s|^)((at\\s+)?\\d{1,2}(:\\d{2})?\\s*(am|pm)|([01]?\\d|2[0-3]):[0-5]\\d)(?=\\s|$)"), Color(0xFF2563EB), Color(0xFF60A5FA)),
)

fun buildQuickAddAnnotated(text: String, dark: Boolean = false): AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    for (tok in TOKENS) {
        val c = if (dark) tok.dark else tok.light
        for (m in tok.re.findAll(text)) {
            addStyle(SpanStyle(color = c, fontWeight = if (tok.bold) FontWeight.SemiBold else FontWeight.Normal), m.range.first, m.range.last + 1)
        }
    }
}

/** Build the highlight transformation for the current theme (pass whether the surface is dark). */
fun quickAddTransformation(dark: Boolean): VisualTransformation = VisualTransformation { text ->
    TransformedText(buildQuickAddAnnotated(text.text, dark), OffsetMapping.Identity)
}

/** Back-compat light-mode transformation (kept so any other caller still compiles). */
val QuickAddTransformation = quickAddTransformation(false)
