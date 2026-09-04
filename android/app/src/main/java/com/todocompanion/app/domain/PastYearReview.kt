package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import java.time.LocalDate
import java.util.Locale

/**
 * Track 3.5 — a Tim-Ferriss "Past Year Review", built on the [YearReviewed] spine. Rather than resolutions,
 * Ferriss reviews the year for the people, activities and commitments that produced the most positive and
 * the most negative emotion, then does two concrete things: schedules MORE of the positives, and writes the
 * negatives onto a NOT-TO-DO list he keeps in view.
 *
 * This produces exactly that ending — two action lists — from data the app already holds: the felt recap
 * ([YearReviewed.Recap]) plus the day logs over the same window. It's deliberately **data-adaptive**: a
 * light year with only a handful of reviewed days still yields a short, honest, non-empty review via calm
 * fallbacks. Pure and Compose-free so it unit-tests as plain Kotlin. Nothing leaves the device; nothing is
 * persisted (no schema change).
 */
object PastYearReview {

    /** A calm narrative panel to scroll through before the two action lists. */
    data class Scene(val emoji: String, val headline: String, val body: String)

    /** The whole review. [moreOf] are positives to schedule more of; [notToDo] the not-to-do list. */
    data class Review(
        val startDay: Long,
        val endDay: Long,
        val scenes: List<Scene>,
        val moreOf: List<String>,
        val notToDo: List<String>,
        val threeWords: List<String>,   // Track 3.3 wiring — the year in three recurring words
    ) {
        val hasData: Boolean get() = scenes.isNotEmpty() && (moreOf.isNotEmpty() || notToDo.isNotEmpty())
    }

    /** How many items each action list caps at, so the ending stays actionable. */
    private const val MAX_ACTIONS = 5

    /** A habit is worth "keep it going" when kept at least this fraction of its scheduled days. */
    private const val KEEP_PCT = 55

    /**
     * Build the review from the felt [recap] and the [dayLogs] over the same window (workspace-scoped, as
     * every felt surface scopes them). Everything is derived; the caller supplies both, both of which it
     * already holds for the year screen.
     */
    fun compute(recap: YearReviewed.Recap, dayLogs: List<DayLogEntity>): Review {
        val logs = dayLogs.filter { it.epochDay in recap.startDay..recap.endDay }
        val docs = logs.map { dayDoc(it) }.filter { it.isNotBlank() }
        val threeWords = TextInsights.threeWords(docs)

        val moreOf = buildMoreOf(recap)
        val notToDo = buildNotToDo(recap, logs)

        val scenes = buildScenes(recap, threeWords)
        return Review(recap.startDay, recap.endDay, scenes, moreOf.take(MAX_ACTIONS), notToDo.take(MAX_ACTIONS), threeWords)
    }

    // ── The positives to schedule MORE of ──────────────────────────────────────────────────────────
    private fun buildMoreOf(recap: YearReviewed.Recap): List<String> {
        val out = mutableListOf<String>()
        // Most-invested activities — protect the time that clearly mattered.
        recap.topActivities.take(2).forEach { a ->
            val h = a.minutes / 60
            out += if (h >= 1) "Protect time for ${a.name} — ${h}h over the year." else "Keep making room for ${a.name}."
        }
        // Kept keystone habits — the routines that held.
        recap.habitConsistency.filter { it.pct >= KEEP_PCT }.take(2).forEach { h ->
            out += "Keep ${h.name} going — ${h.pct}% kept."
        }
        // The feeling you most often named, when it was a good one.
        if (recap.topEmotionWord.isNotBlank() && recap.topEmotionCount >= 3 &&
            EmotionWords.quadrantOf(recap.topEmotionWord).let { it == EmotionQuadrant.HIGH_PLEASANT || it == EmotionQuadrant.LOW_PLEASANT }
        ) {
            out += "Chase more of what left you feeling ${recap.topEmotionWord.lowercase(Locale.getDefault())}."
        }
        // A standout to repeat.
        if (recap.highlightText.isNotBlank()) out += "More days like: “${recap.highlightText.trim()}”."
        // Data-adaptive fallbacks so a light year still ends with something to do more of.
        if (out.isEmpty()) {
            when {
                recap.winsCount > 0 -> out += "More of what you counted as a good thing — you named ${recap.winsCount}."
                recap.activeDays > 0 -> out += "Keep showing up — you did on ${recap.activeDays} days this year."
                else -> out += "Keep reviewing your days — that's how the next year's review gets richer."
            }
        }
        return out.distinct()
    }

    // ── The NOT-TO-DO list ──────────────────────────────────────────────────────────────────────────
    private fun buildNotToDo(recap: YearReviewed.Recap, logs: List<DayLogEntity>): List<String> {
        val out = mutableListOf<String>()
        // Recurring obstacles / lessons — the same thing that kept getting in the way.
        recurring(logs).take(2).forEach { (text, n) ->
            out += "Stop letting “$text” derail you — it came up $n times."
        }
        // The lesson from your hardest day — the "what I'd change" you already wrote.
        logs.filter { it.dayRating in 1..2 && it.lesson.isNotBlank() }
            .minByOrNull { it.dayRating }
            ?.let { out += "Less of what made ${dateLabel(it.epochDay)} hard: “${it.lesson.trim()}”." }
        // The feeling you most often named, when it was a draining one.
        if (recap.topEmotionWord.isNotBlank() && recap.topEmotionCount >= 3 &&
            EmotionWords.quadrantOf(recap.topEmotionWord).let { it == EmotionQuadrant.HIGH_UNPLEASANT || it == EmotionQuadrant.LOW_UNPLEASANT }
        ) {
            out += "Notice what leaves you feeling ${recap.topEmotionWord.lowercase(Locale.getDefault())}, and do less of it."
        }
        // Data-adaptive fallback: a gentle, universal not-to-do so the list is never empty on a light year.
        if (out.isEmpty()) {
            out += "Say no to one thing that doesn't matter, to make room for one that does."
        }
        return out.distinct()
    }

    // ── The narrative scenes ────────────────────────────────────────────────────────────────────────
    private fun buildScenes(recap: YearReviewed.Recap, threeWords: List<String>): List<Scene> {
        if (!recap.hasData) return emptyList()
        val out = mutableListOf<Scene>()
        out += Scene("📖", "Your past year", "${recap.daysReviewed} days reviewed. A look back — kindly, and on the record.")
        if (threeWords.isNotEmpty()) {
            out += Scene("🔤", "In three words", threeWords.joinToString(" · "))
        }
        if (recap.ratedDays > 0) {
            val stars = recap.avgRating.let { Math.round(it).toInt() }.coerceIn(1, 5)
            val emo = if (recap.topEmotionWord.isNotBlank() && recap.topEmotionCount >= 3)
                " Most often, you felt ${recap.topEmotionWord.lowercase(Locale.getDefault())}." else ""
            out += Scene("💗", "How it felt", "${"★".repeat(stars)} on average across ${recap.ratedDays} rated days.$emo")
        }
        // What went well.
        val well = buildList {
            recap.topActivities.firstOrNull()?.let { add("time on ${it.name}") }
            recap.habitConsistency.firstOrNull { it.pct >= KEEP_PCT }?.let { add("${it.name} (${it.pct}% kept)") }
            if (recap.winsCount > 0) add("${recap.winsCount} good things noticed")
        }
        if (well.isNotEmpty()) out += Scene("🌱", "What went well", well.joinToString(", ").replaceFirstChar { it.titlecase(Locale.getDefault()) } + ".")
        // A standout.
        if (recap.highlightText.isNotBlank()) out += Scene("✨", "A highlight", "“${recap.highlightText.trim()}”")
        out += Scene("🧭", "Now, two lists", "Schedule more of what worked. Put the rest on a not-to-do list you keep in view.")
        return out
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    /** One document per day for the theme extractor: its reflective free-text fields, joined. */
    private fun dayDoc(l: DayLogEntity): String = listOf(
        l.pmReflection, l.highlight, l.gratitude, l.lesson,
        l.good1, l.good2, l.good3, l.promptAnswer, l.amIntention,
    ).filter { it.isNotBlank() }.joinToString(" ")

    /** Obstacle/lesson strings that recur on 2+ days, most-recurring first (text → count). */
    private fun recurring(logs: List<DayLogEntity>): List<Pair<String, Int>> {
        data class Hit(val norm: String, val text: String, val day: Long)
        val hits = logs.flatMap { l ->
            listOf(l.tomorrowObstacle, l.lesson).map { it.trim() }.filter { it.length >= 4 }
                .map { Hit(it.lowercase(Locale.getDefault()), it, l.epochDay) }
        }
        return hits.groupBy { it.norm }
            .filter { it.value.size >= 2 }
            .map { (_, v) -> v.maxByOrNull { it.day }!!.text to v.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }

    private fun dateLabel(day: Long): String {
        val d = LocalDate.ofEpochDay(day)
        return "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())}"
    }
}
