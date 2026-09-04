@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.cairn.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.cairn.reader.R

/**
 * Bundled typography. UI text is Inter; long-form reading is Newsreader — both OFL
 * variable fonts, weighted via [FontVariation] (API 26+). Bundling (rather than a
 * downloadable-fonts provider) keeps rendering instant, offline, and tracker-free.
 */

private fun interFont(weight: Int) = Font(
    resId = R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun newsreaderFont(weight: Int, italic: Boolean = false) = Font(
    resId = if (italic) R.font.newsreader_italic else R.font.newsreader_variable,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val InterFamily = FontFamily(
    interFont(400), interFont(500), interFont(600), interFont(700),
)

val NewsreaderFamily = FontFamily(
    newsreaderFont(400), newsreaderFont(500), newsreaderFont(600),
    newsreaderFont(400, italic = true), newsreaderFont(500, italic = true),
)

/** The reading face for article bodies and editorial titles. */
val ReadingSerif: FontFamily = NewsreaderFamily
private val UiSans: FontFamily = InterFamily

private val trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val CairnTypography = Typography(
    displaySmall = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp, lineHeightStyle = trim),
    headlineMedium = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.25).sp, lineHeightStyle = trim),
    headlineSmall = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp, lineHeightStyle = trim),
    titleLarge = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.15).sp, lineHeightStyle = trim),
    titleMedium = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, lineHeightStyle = trim),
    titleSmall = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = UiSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)
