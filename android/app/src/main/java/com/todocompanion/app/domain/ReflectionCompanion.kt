package com.todocompanion.app.domain

/**
 * Wave 3 (feature E) + Track 3.6 — a private, rule-based reflection companion. NO LLM, no model download,
 * nothing on a network: from the day's own signals (rating, evening mood, energy, an optional precise
 * emotion word, whether an obstacle recurred, whether it was a win) it picks — entirely on-device — an
 * adaptive multi-turn chain of context-aware follow-up prompts drawn from established reflection
 * frameworks:
 *   • a hard day → a gentle CBT reframe + the "what would you tell a friend?" move (self-compassion),
 *   • a good day → savoring (relish it, notice what made it, plan for more),
 *   • an ordinary day → the Ignatian Examen's emotion-review (most alive / most drained / carry forward),
 * always closing on a WOOP-forward step (a concrete next step + when), and growing extra branches when
 * energy ran low/high, when a win was marked, when the same obstacle keeps returning, and a deepening
 * follow-up when a previous answer was substantial.
 *
 * It only ever asks questions; the answers are saved back into the day's existing reflection field, so
 * there is no new column and nothing new to store. Fully deterministic and offline. Pure and Compose-free
 * so it unit-tests as plain Kotlin, mirroring AdaptivePrompts / DailyQuestions.
 */
object ReflectionCompanion {

    /** Which reflective track the day's signals selected. */
    enum class Track(val glyph: String, val title: String) {
        SAVOR("✨", "Savor the day"),
        REFRAME("🌦️", "A softer look"),
        EXAMEN("🕯️", "A gentle review"),
        WOOP("🧭", "One step forward"),
    }

    /** A selected chain: the [track], a one-line [intro], and the [prompts] to walk through in order. */
    data class Chain(val track: Track, val intro: String, val prompts: List<String>)

    /**
     * The day's signals the companion branches on. [rating]/[mood]/[energy] are 1–5 (0 = unset);
     * [emotionLabel] is the optional precise emotion word; [obstacleRecurred] is set when the same
     * tomorrow-obstacle/lesson has come up recently; [wasWin] when the day carried a marked win.
     */
    data class Signals(
        val rating: Int = 0,
        val mood: Int = 0,
        val energy: Int = 0,
        val emotionLabel: String = "",
        val obstacleRecurred: Boolean = false,
        val wasWin: Boolean = false,
    )

    /** A previous answer this long (trimmed chars) or more earns a deepening follow-up. */
    private const val SUBSTANTIAL = 12

    /**
     * Pick the BASE follow-up chain (track + 2–3 core prompts) for a day from its own signals. [rating]
     * and [mood] are 1–5 (0 = unset); [emotionLabel] is the optional precise emotion word. A day reads as
     * *hard* when either the rating or the mood is low (1–2), as *good* when either is high (4–5) and
     * neither is low, and otherwise as ordinary. The hard-day track wins when signals conflict — that is
     * the day that most benefits from a kinder second look. The precise emotion word gently varies the
     * opening question. This is the stable spine [adaptiveChain] grows an adaptive tail onto.
     */
    fun chainFor(rating: Int, mood: Int, emotionLabel: String = ""): Chain {
        val hard = rating in 1..2 || mood in 1..2
        val good = !hard && (rating in 4..5 || mood in 4..5)
        val quad = EmotionWords.quadrantOf(emotionLabel)
        return when {
            hard -> {
                // High-energy-unpleasant (anxious/stressed) opens on the surge; low (tired/sad) on the weight.
                val opener = when (quad) {
                    EmotionQuadrant.HIGH_UNPLEASANT -> "What felt most overwhelming today?"
                    EmotionQuadrant.LOW_UNPLEASANT -> "What weighed on you most today?"
                    else -> "What was the hardest part of today?"
                }
                Chain(
                    Track.REFRAME,
                    "Hard days deserve a kinder second look — no fixing, just a few honest questions.",
                    listOf(
                        opener,
                        "If a good friend had this exact day, what would you say to them?",
                        "What's one kinder, truer way to see it?",
                    ),
                )
            }
            good -> {
                val opener = when (quad) {
                    EmotionQuadrant.HIGH_PLEASANT -> "What lit you up most today?"
                    EmotionQuadrant.LOW_PLEASANT -> "What felt most settled and good today?"
                    else -> "What was the best moment of today?"
                }
                Chain(
                    Track.SAVOR,
                    "Good days are worth slowing down for — let's make this one stick.",
                    listOf(
                        opener,
                        "Who or what helped make it happen?",
                        "How could you set up more moments like it?",
                    ),
                )
            }
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
     * The adaptive, multi-turn chain the dialog walks. Starts from the [chainFor] spine and appends extra,
     * deterministic branches keyed on the day's [signals] and on the [answers] gathered so far: an
     * energy-aware refill/spend question, a win-credit question, a recurring-obstacle break-the-pattern
     * question, a deepening follow-up when the first core answer was substantial, and always a closing
     * WOOP-forward step. Extra steps are appended AFTER the core prompts so the already-answered prefix
     * never shifts as the chain grows. Recompute it each turn with the latest [answers].
     */
    fun adaptiveChain(signals: Signals, answers: List<String> = emptyList()): Chain {
        val base = chainFor(signals.rating, signals.mood, signals.emotionLabel)
        val prompts = base.prompts.toMutableList()

        // Energy-aware branch (signal-only, so it's stable from the first turn).
        when (signals.energy) {
            in 1..2 -> prompts += "Energy ran low today — what's one gentle thing that would refill you?"
            in 4..5 -> prompts += "You had energy today — what did you spend it on that was worth it?"
        }
        // Win-credit branch.
        if (signals.wasWin) prompts += "You marked a win today — say plainly what you did to earn it."
        // Recurring-obstacle branch.
        if (signals.obstacleRecurred) prompts += "This obstacle keeps returning — what's one change that would break the pattern?"
        // Reactive deepening when the first core answer was substantial.
        if ((answers.getOrNull(0)?.trim()?.length ?: 0) >= SUBSTANTIAL) {
            prompts += when (base.track) {
                Track.REFRAME -> "You wrote something honest there — what would you tell a friend who said it?"
                Track.SAVOR -> "There's something good under that — what does it tell you matters to you?"
                else -> "There's more under that — what's the deeper reason it stayed with you?"
            }
        }
        // Always close on a concrete forward step (WOOP: wish → outcome → obstacle → plan, distilled).
        prompts += when (base.track) {
            Track.SAVOR -> "One small way to line up more of this — what will you do, and when?"
            Track.REFRAME -> "If tomorrow brings a piece of this again, what's your if-then plan?"
            Track.EXAMEN -> "What's one small thing you'll carry into tomorrow — and when will you do it?"
            Track.WOOP -> "What's the very next step, and when will you take it?"
        }
        return base.copy(prompts = prompts)
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
