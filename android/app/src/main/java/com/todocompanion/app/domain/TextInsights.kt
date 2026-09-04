package com.todocompanion.app.domain

import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

/**
 * Track 3.3 — on-device text insights, pure Kotlin with NO ML runtime, no model, no network. Two tools,
 * both deterministic and fully offline:
 *
 *  (a) a lightweight **sentiment** scorer over a bundled compact polarity lexicon (AFINN-style: a few
 *      hundred common positive/negative words as a Kotlin map, scored roughly -3..+3), with simple
 *      negation handling ("not great" flips), returning a -1..+1 sentiment for a reflection text; and
 *
 *  (b) a TF-IDF-style **theme / keyword** extractor over a corpus of the user's reflections (stop-word
 *      filtered), returning the top recurring themes and, for a period, a compact "three words" set.
 *
 * The lexicon is small and general on purpose — it isn't a classifier, it's an honest, glanceable read
 * of tone that never leaves the phone. Compose-free so it unit-tests as plain Kotlin, mirroring
 * EmotionWords / DayPrompts. Nothing computed here is persisted (no schema change) — it's derived on
 * the fly from text the day logs already hold.
 */
object TextInsights {

    // ── (a) Sentiment ─────────────────────────────────────────────────────────────────────────────

    /** A compact AFINN-style polarity lexicon: word → valence, roughly -3..+3. Kept general and small. */
    val LEXICON: Map<String, Int> = buildMap {
        // strong positive (+3)
        listOf("amazing", "awesome", "excellent", "fantastic", "wonderful", "brilliant", "thrilled",
            "delighted", "ecstatic", "outstanding", "superb", "grateful", "gratitude", "breakthrough",
            "triumph", "joyful", "overjoyed").forEach { put(it, 3) }
        // positive (+2)
        listOf("happy", "glad", "proud", "great", "good", "love", "loved", "loving", "enjoy", "enjoyed",
            "excited", "energized", "confident", "accomplished", "productive", "progress", "win", "won",
            "success", "successful", "achieved", "achievement", "strong", "healthy", "hopeful", "inspired",
            "motivated", "focused", "clear", "calm", "peaceful", "content", "fulfilled", "rested",
            "refreshed", "relaxed", "connected", "supported", "kind", "kindness", "warm", "beautiful",
            "fun", "laughed", "smile", "smiled", "celebrate", "celebrated", "thankful").forEach { put(it, 2) }
        // mild positive (+1)
        listOf("ok", "okay", "fine", "nice", "better", "improved", "improving", "steady", "solid",
            "helpful", "gentle", "quiet", "manageable", "hope", "hopefully", "learn", "learned", "learning",
            "growth", "grow", "growing", "curious", "willing", "ready", "done", "finished", "complete",
            "completed", "started", "showed", "showing").forEach { putIfAbsent(it, 1) }
        // mild negative (-1)
        listOf("meh", "unsure", "uncertain", "distracted", "restless", "busy", "rushed", "slow", "behind",
            "late", "forgot", "missed", "skipped", "procrastinated", "unmotivated", "flat", "dull", "bored",
            "boring", "worried", "worry", "nervous", "unsettled").forEach { putIfAbsent(it, -1) }
        // negative (-2)
        listOf("sad", "unhappy", "upset", "angry", "annoyed", "frustrated", "frustrating", "stressed",
            "stress", "anxious", "anxiety", "afraid", "scared", "tired", "exhausted", "drained", "overwhelmed",
            "overwhelming", "hard", "difficult", "tough", "struggle", "struggled", "struggling", "failed",
            "failure", "lost", "lonely", "disappointed", "disappointing", "discouraged", "guilty", "ashamed",
            "hurt", "sick", "pain", "painful", "conflict", "argument", "bad", "worse", "weak", "stuck",
            "unproductive", "wasted", "regret", "dread", "dreading", "numb").forEach { putIfAbsent(it, -2) }
        // strong negative (-3)
        listOf("terrible", "awful", "horrible", "miserable", "devastated", "hopeless", "worthless",
            "unbearable", "hate", "hated", "despair", "burnout", "burnt", "crisis", "panic",
            "panicked", "worst").forEach { putIfAbsent(it, -3) }
    }

    /** Words that flip the valence of the next scored word or two ("not good", "no progress"). */
    private val NEGATORS = setOf(
        "not", "no", "never", "none", "nobody", "nothing", "nowhere", "neither", "nor", "hardly",
        "barely", "scarcely", "without", "cannot", "cant", "dont", "didnt", "doesnt", "wasnt", "werent",
        "isnt", "arent", "wont", "couldnt", "wouldnt", "shouldnt", "aint", "less", "lack", "lacking",
    )

    /** Words that amplify the next scored word ("really good", "very hard"). */
    private val INTENSIFIERS = setOf("very", "really", "so", "extremely", "incredibly", "totally", "super", "quite", "deeply")

    /**
     * A -1..+1 sentiment for [text]: the sum of scored words (each ±1/±2/±3 from [LEXICON]), with a
     * one-word negation window that flips valence and a mild bump for an intensifier, normalised by the
     * number of scored words so a long entry isn't penalised. 0.0 when nothing scored (neutral / empty).
     */
    fun sentiment(text: String): Double {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return 0.0
        var total = 0.0
        var scored = 0
        for (i in tokens.indices) {
            val base = LEXICON[tokens[i]] ?: continue
            var v = base.toDouble()
            // Look back up to two tokens for a negator / intensifier.
            val prev1 = tokens.getOrNull(i - 1)
            val prev2 = tokens.getOrNull(i - 2)
            val negated = prev1 in NEGATORS || prev2 in NEGATORS
            if (negated) v = -v * 0.8            // "not great" reads as mildly bad, not the full opposite
            if (prev1 in INTENSIFIERS) v *= 1.5
            total += v
            scored++
        }
        if (scored == 0) return 0.0
        // Average valence over scored words, then squash from the ±3 scale into ±1.
        val avg = total / scored
        return (avg / 3.0).coerceIn(-1.0, 1.0)
    }

    /** A coarse label for a sentiment score, for a plain-language read-back. */
    fun label(score: Double): String = when {
        score >= 0.5 -> "very positive"
        score >= 0.15 -> "positive"
        score > -0.15 -> "mixed"
        score > -0.5 -> "negative"
        else -> "very negative"
    }

    // ── (b) Themes / keywords ─────────────────────────────────────────────────────────────────────

    /** One recurring theme word: how often it appears in total, and across how many documents (days). */
    data class Theme(val word: String, val count: Int, val documents: Int) {
        /** Titlecased for display. */
        val display: String get() = word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /**
     * Extract the top recurring themes from [documents] (each a day's reflection text). TF-IDF-style: a
     * word's score blends how often it recurs (total count) with how many days mention it (document
     * frequency), so a word repeated within one entry doesn't outrank one that genuinely comes up again
     * and again. Stop-word filtered, short/numeric tokens dropped, and a word must appear on at least
     * [minDocuments] distinct days to count as "recurring". Deterministic; returns at most [topN].
     */
    fun themes(documents: List<String>, topN: Int = 6, minDocuments: Int = 2): List<Theme> {
        val nonEmpty = documents.map { tokenize(it).filter { w -> isContentWord(w) } }.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return emptyList()
        val totalCount = HashMap<String, Int>()
        val docCount = HashMap<String, Int>()
        nonEmpty.forEach { toks ->
            toks.forEach { totalCount[it] = (totalCount[it] ?: 0) + 1 }
            toks.toSet().forEach { docCount[it] = (docCount[it] ?: 0) + 1 }
        }
        val effectiveMin = if (nonEmpty.size < minDocuments) 1 else minDocuments
        return totalCount.keys
            .filter { (docCount[it] ?: 0) >= effectiveMin }
            .map { Theme(it, totalCount.getValue(it), docCount.getValue(it)) }
            .sortedWith(compareByDescending<Theme> { score(it) }.thenByDescending { it.count }.thenBy { it.word })
            .take(topN)
    }

    /** A compact "three words" set for a period — the top themes as up to [n] display words. */
    fun threeWords(documents: List<String>, n: Int = 3): List<String> =
        themes(documents, topN = n, minDocuments = 2).map { it.display }

    /** The recurrence-weighted score: total frequency times a log of how many days it recurs across. */
    private fun score(t: Theme): Double = t.count.toDouble() * ln(1.0 + t.documents.toDouble())

    // ── Shared text plumbing ────────────────────────────────────────────────────────────────────────

    /** Lowercase, split on non-letters, drop empties. Apostrophes are stripped so "don't" → "dont". */
    private fun tokenize(text: String): List<String> =
        text.lowercase(Locale.getDefault())
            .replace('’', '\'')
            .replace("'", "")
            .split(Regex("[^a-z]+"))
            .filter { it.isNotBlank() }

    /** A content word worth keeping as a theme: 3+ letters and not a stop word. */
    private fun isContentWord(w: String): Boolean = w.length >= 3 && w !in STOP_WORDS

    /** A general English stop-word list, plus a few journalling-specific fillers. */
    val STOP_WORDS: Set<String> = setOf(
        "the", "and", "for", "was", "with", "that", "this", "have", "had", "has", "not", "but", "you",
        "your", "yours", "are", "were", "would", "could", "should", "will", "can", "did", "does", "done",
        "from", "they", "them", "their", "there", "then", "than", "what", "when", "which", "who", "whom",
        "how", "why", "all", "any", "some", "more", "most", "much", "many", "such", "only", "just", "very",
        "too", "also", "into", "onto", "out", "over", "under", "again", "still", "even", "ever", "here",
        "about", "after", "before", "because", "been", "being", "own", "off", "its", "itself", "him", "her",
        "his", "she", "hers", "our", "ours", "ourselves", "myself", "yourself", "himself", "herself",
        "themselves", "get", "got", "getting", "put", "make", "made", "making", "one", "two", "day", "days",
        "today", "yesterday", "tomorrow", "morning", "evening", "night", "week", "time", "really", "thing",
        "things", "went", "back", "feel", "felt", "feeling", "bit", "lot", "little", "few", "now", "way",
        "kind", "sort", "like", "well", "quite", "though", "since", "while", "around", "yeah", "okay",
    )
}
