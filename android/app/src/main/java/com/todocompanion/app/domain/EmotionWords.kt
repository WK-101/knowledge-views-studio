package com.todocompanion.app.domain

/**
 * Wave 1 — a precise emotion word to sit alongside the 5-face mood (affect-labeling; the "How We Feel"
 * mood meter). Naming a feeling specifically — not just "good / bad" — is itself regulating: putting a
 * feeling into words dampens the reaction to it. The set is deliberately small and grouped into four
 * quadrants along two axes — ENERGY (high / low) × PLEASANTNESS (pleasant / unpleasant) — so a picker
 * can read as a compact mood-meter grid rather than a long scrolling list.
 *
 * Pure and Compose-free: the word list + quadrant lookup live here so they unit-test as plain Kotlin,
 * mirroring DayPrompts / DailyQuestions / DayAlignment. The chosen word is stored verbatim on the day's
 * DayLogEntity.emotionLabel ("" = none named). Entirely optional and skippable.
 */
enum class EmotionQuadrant(val label: String) {
    HIGH_PLEASANT("High energy · pleasant"),
    LOW_PLEASANT("Low energy · pleasant"),
    HIGH_UNPLEASANT("High energy · unpleasant"),
    LOW_UNPLEASANT("Low energy · unpleasant"),
}

object EmotionWords {
    val HIGH_PLEASANT: List<String> = listOf("Excited", "Energized", "Proud", "Joyful", "Grateful", "Inspired")
    val LOW_PLEASANT: List<String> = listOf("Calm", "Content", "Relaxed", "Serene", "Fulfilled", "Rested")
    val HIGH_UNPLEASANT: List<String> = listOf("Anxious", "Frustrated", "Stressed", "Angry", "Overwhelmed", "Restless")
    val LOW_UNPLEASANT: List<String> = listOf("Tired", "Sad", "Bored", "Drained", "Discouraged", "Numb")

    /** The four quadrants paired with their words, in display order (pleasant first, high energy first). */
    val QUADRANTS: List<Pair<EmotionQuadrant, List<String>>> = listOf(
        EmotionQuadrant.HIGH_PLEASANT to HIGH_PLEASANT,
        EmotionQuadrant.LOW_PLEASANT to LOW_PLEASANT,
        EmotionQuadrant.HIGH_UNPLEASANT to HIGH_UNPLEASANT,
        EmotionQuadrant.LOW_UNPLEASANT to LOW_UNPLEASANT,
    )

    /** Every curated emotion word, in quadrant order — 24 in all. */
    val ALL: List<String> = QUADRANTS.flatMap { it.second }

    private val byWord: Map<String, EmotionQuadrant> =
        QUADRANTS.flatMap { (q, words) -> words.map { it.lowercase() to q } }.toMap()

    /** The quadrant a word belongs to, or null if it isn't one of the curated words (case-insensitive). */
    fun quadrantOf(word: String): EmotionQuadrant? = byWord[word.trim().lowercase()]

    /** True when [word] is one of the curated emotion words (case-insensitive, trimmed). */
    fun isKnown(word: String): Boolean = quadrantOf(word) != null
}
