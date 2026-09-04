package com.cairn.reader.ui.reader

/** The bundled highlighter palette, stored as ARGB ints on each highlight. */
object HighlightColors {
    val Yellow = 0xFFFFE082.toInt()
    val Green = 0xFFA5D6A7.toInt()
    val Blue = 0xFF90CAF9.toInt()
    val Pink = 0xFFF48FB1.toInt()

    val all = listOf(Yellow, Green, Blue, Pink)
    val Default = Yellow
}
