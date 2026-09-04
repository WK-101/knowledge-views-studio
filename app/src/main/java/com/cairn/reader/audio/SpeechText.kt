package com.cairn.reader.audio

import java.text.BreakIterator
import java.util.Locale

/** Turns article text into speakable chunks (title first, then sentences) for [TtsReader]. */
object SpeechText {

    fun chunks(title: String?, plainText: String): List<String> {
        val out = ArrayList<String>()
        title?.trim()?.takeIf { it.isNotBlank() }?.let { out += it }
        out += splitSentences(plainText)
        return out
    }

    fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(text)
        val out = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            start = end
            end = iterator.next()
        }
        return out
    }
}
