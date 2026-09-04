package com.todocompanion.app.domain

/**
 * Wave 3 (feature E) — a private, rule-based reflection companion. NO LLM, no model download, nothing on
 * a network: given the day's own signals (rating, evening mood, an optional precise emotion word) it
 * picks — entirely on-device — a short chain of 2–3 context-aware follow-up prompts drawn from
 * established reflection frameworks:
 *   • a hard day → a gentle CBT reframe + the "what would you tell a friend?" move (self-compassion),
 *   • a good day → savoring (relish it, notice what made it, plan for more),
 *   • an ordinary day → the Ignatian Examen's emotion-review (most alive / most drained / carry forward).
 *
 * It only ever asks questions; the answers are saved back into the day's existing reflection field, so
 * there is no new column and nothing new to store. Pure and Compose-free so it unit-tests as plain
 * Kotlin, mirroring AdaptivePrompts / DailyQuestions.
 */
object ReflectionCompanion {

    /** Which reflective track the day's signals selected. */
    enum class Track(val glyph: String, val title: String) {
        SAVOR("✨", "Savor the day"),
        REFRAME("🌦️", "A softer look"),
        EXAMEN("🕯️", "A gentle review"),
    }

    /** A selected chain: the [track], a one-line [intro], and 2–3 [prompts] to walk through in order. */
    data class Chain(val track: Track, val intro: String, val prompts: List<String>)

    /**
     * Pick the follow-up chain for a day from its own signals. [rating] and [mood] are 1–5 (0 = unset);
     * [emotionLabel] is the optional precise emotion word. A day reads as *hard* when either the rating or
     * the mood is low (1–2), as *good* when either is high (4–5) and neither is low, and otherwise as
     * ordinary. The hard-day track wins when signals conflict, because that is the day that most benefits
     * from a kinder second look.
     */
    fun chainFor(rating: Int, mood: Int, emotionLabel: String = ""): Chain {
        val hard = rating in 1..2 || mood in 1..2
        val good = !hard && (rating in 4..5 || mood in 4..5)
        return when {
            hard -> Chain(
                Track.REFRAME,
                "Hard days deserve a kinder second look — no fixing, just a few honest questions.",
                listOf(
                    "What was the hardest part of today?",
                    "If a good friend had this exact day, what would you say to them?",
                    "What's one kinder, truer way to see it?",
                ),
            )
            good -> Chain(
                Track.SAVOR,
                "Good days are worth slowing down for — let's make this one stick.",
                listOf(
                    "What was the best moment of today?",
                    "Who or what helped make it happen?",
                    "How could you set up more moments like it?",
                ),
            )
            else -> Chain(
                Track.EXAMEN,
                "A quiet look back — where the day had energy, and where it drained.",
                listOf(
                    "When did you feel most alive today?",
                    "When did you feel most drained?",
                    "What's one thing worth carrying into tomorrow?",
                ),
            )
        }
    }

    /**
     * Fold the user's answers to a chain back into a single reflection block to save into the day's
     * existing reflection field. Blank answers are dropped; each kept answer is written as a short
     * "Question / — answer" couplet so the day reads back as a small dialogue. Returns "" when nothing
     * was answered (so an untouched walk-through never overwrites anything).
     */
    fun compose(chain: Chain, answers: List<String>): String {
        val couplets = chain.prompts.zip(answers)
            .filter { it.second.isNotBlank() }
            .joinToString("\n") { (q, a) -> "$q\n— ${a.trim()}" }
        return couplets
    }

    /** Merge a composed companion block onto any existing reflection text, without duplicating it. */
    fun merge(existing: String, block: String): String {
        val e = existing.trim()
        val b = block.trim()
        return when {
            b.isBlank() -> e
            e.isBlank() -> b
            e.contains(b) -> e
            else -> "$e\n\n$b"
        }
    }
}
