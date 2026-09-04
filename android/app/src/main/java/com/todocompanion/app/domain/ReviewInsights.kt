package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.habit.HabitStats
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Wave 2 (feature 5) — the on-device cross-stream correlation engine. A pure, Compose-free fold over an
 * already-loaded slice of the store (day logs, active daily questions, habits + check-ins, tracked time)
 * that mines *descriptive* patterns across every stream at once — day rating, evening mood, precise
 * emotion words, habits kept, time-per-activity, morning-intention journaling, daily-question effort and
 * day-of-week — and returns a ranked list of [Insight] findings.
 *
 * It is deliberately honest: every finding is strictly descriptive (never causal), gated to a minimum
 * qualifying sample and a minimum effect size, and each carries the sample it is based on plus a
 * strength/confidence signal so the UI can be transparent about how much to trust it. Nothing leaves the
 * device; everything is computed from data the Day Review already holds.
 *
 * Kept free of Compose so it unit-tests as plain Kotlin, mirroring ReviewRollup / HabitStats / DayPrompts.
 */
object ReviewInsights {

    /** Below this many *rated* days we don't mine rating-based patterns at all — too little to be honest. */
    const val MIN_RATED_DAYS = 8

    /** At most this many findings surface, so the Patterns card stays scannable. */
    const val MAX_INSIGHTS = 6

    /** Each side of a split (kept vs not, high vs low, a weekday vs the rest) needs at least this many days. */
    private const val MIN_GROUP = 3

    /** Minimum separations for a finding to be worth showing (rating & mood are 1–5 scales). */
    private const val MIN_RATING_GAP = 0.5
    private const val MIN_HABIT_GAP = 0.5
    private const val MIN_MOOD_GAP = 0.5

    /** Mood- and emotion-based findings key off their own logged-day counts, not the rated-day count. */
    private const val MIN_MOOD_DAYS = 8
    private const val MIN_EMOTION_DAYS = 6

    /** The category of a finding — lets the UI pick a glyph and keeps sort ties deterministic. */
    enum class Kind { HABITS, ACTIVITY, DAY_OF_WEEK, MORNING, QUESTION, MOOD_HABIT, EMOTION }

    /** A coarse, honest confidence band derived from [Insight.strength], for a plain-language label. */
    enum class Confidence(val label: String) { SLIGHT("slight"), MODERATE("moderate"), STRONG("strong") }

    /**
     * One descriptive finding. [text] is a plain-English sentence; [strength] (0..1) blends effect size
     * and sample confidence and is the primary sort key; [sampleSize] is how many qualifying days it rests
     * on. Never a causal claim.
     */
    data class Insight(
        val kind: Kind,
        val text: String,
        val strength: Double,
        val sampleSize: Int,
    ) {
        val confidence: Confidence
            get() = when {
                strength >= 0.60 -> Confidence.STRONG
                strength >= 0.35 -> Confidence.MODERATE
                else -> Confidence.SLIGHT
            }
    }

    /**
     * Mine the inclusive epoch-day window [startDay]..[endDay] for patterns. All inputs are plain data the
     * caller already holds (day logs must already be workspace-scoped; habits should be the active set).
     * Returns the top [MAX_INSIGHTS] findings, strongest first.
     */
    fun compute(
        startDay: Long,
        endDay: Long,
        dayLogs: List<DayLogEntity>,
        questions: List<DailyQuestion>,
        habits: List<HabitEntity>,
        checkins: List<HabitCheckinEntity>,
        timeEntries: List<TimeEntryEntity>,
        activities: List<TimeActivityEntity>,
        zone: ZoneId,
        now: Long,
    ): List<Insight> {
        if (endDay < startDay) return emptyList()
        val logByDay = dayLogs.filter { it.epochDay in startDay..endDay }.associateBy { it.epochDay }
        val checkinByKey = checkins.associateBy { it.habitId to it.epochDay }

        // Per-day tracked minutes per activity, over the whole window (reused by the activity + emotion findings).
        val actMinByDay: Map<Long, Map<String, Int>> = (startDay..endDay).associateWith { d ->
            val (ws, we) = millisWindow(d, zone)
            TimeTracking.totalsByActivity(timeEntries, ws, we, now).associate { it.activityId to it.minutes }
        }

        val out = mutableListOf<Insight>()

        // ── Rating-driven findings (only once there are enough rated days to be honest) ──
        val ratedDays = (startDay..endDay).filter { (logByDay[it]?.dayRating ?: 0) in 1..5 }
        if (ratedDays.size >= MIN_RATED_DAYS) {
            val ratingByDay = ratedDays.associateWith { logByDay[it]!!.dayRating.toDouble() }
            val scoresByDay = ratedDays.associateWith { DailyQuestions.parseScores(logByDay[it]?.dailyScoresJson ?: "") }

            bestVsRestHabits(ratedDays, ratingByDay, habits, checkinByKey)?.let { out += it }
            out += dayOfWeek(ratedDays, ratingByDay)
            out += activityRating(ratedDays, ratingByDay, actMinByDay, activities)
            morningJournaling(ratedDays, ratingByDay, logByDay)?.let { out += it }
            out += questionRating(ratedDays, ratingByDay, scoresByDay, questions)
        }

        // ── Mood-driven & emotion-driven findings (key off their own logged-day counts) ──
        out += moodByHabit(startDay, endDay, logByDay, habits, checkinByKey)
        emotionOnActivityHeavy(startDay, endDay, logByDay, actMinByDay, activities)?.let { out += it }

        return out
            .sortedWith(compareByDescending<Insight> { it.strength }.thenBy { it.kind.ordinal }.thenBy { it.text })
            .take(MAX_INSIGHTS)
    }

    // ── Finding: best-rated days keep more habits than the rest (extends ReviewRollup's best-vs-rest split) ──
    private fun bestVsRestHabits(
        ratedDays: List<Long>,
        ratingByDay: Map<Long, Double>,
        habits: List<HabitEntity>,
        checkinByKey: Map<Pair<String, Long>, HabitCheckinEntity>,
    ): Insight? {
        if (habits.isEmpty()) return null
        val sorted = ratedDays.sortedByDescending { ratingByDay.getValue(it) }
        val bestN = ceil(sorted.size / 3.0).toInt().coerceIn(1, sorted.size - 1)
        val best = sorted.take(bestN)
        val rest = sorted.drop(bestN)
        if (best.isEmpty() || rest.isEmpty()) return null
        val bestRating = best.map { ratingByDay.getValue(it) }.average()
        val restRating = rest.map { ratingByDay.getValue(it) }.average()
        if (bestRating - restRating < MIN_RATING_GAP) return null // no real rating separation
        val b = best.map { habitsKeptOn(it, habits, checkinByKey) }.average()
        val r = rest.map { habitsKeptOn(it, habits, checkinByKey) }.average()
        val gap = b - r
        if (gap < MIN_HABIT_GAP) return null
        val text = "Your best-rated days average ${oneDp(gap)} more habits kept than the rest (${oneDp(b)} vs ${oneDp(r)})."
        return Insight(Kind.HABITS, text, strengthOf(gap, 2.0, ratedDays.size), ratedDays.size)
    }

    // ── Finding: a weekday rates clearly above / below the other days ──
    private fun dayOfWeek(ratedDays: List<Long>, ratingByDay: Map<Long, Double>): List<Insight> {
        val byDow = ratedDays.groupBy { LocalDate.ofEpochDay(it).dayOfWeek }.filter { it.value.size >= MIN_GROUP }
        if (byDow.size < 2) return emptyList()
        data class Cand(val dow: DayOfWeek, val avg: Double, val gap: Double, val n: Int)
        val cands = byDow.map { (dow, days) ->
            val avg = days.map { ratingByDay.getValue(it) }.average()
            val others = ratedDays.filter { LocalDate.ofEpochDay(it).dayOfWeek != dow }
            val otherAvg = if (others.isEmpty()) avg else others.map { ratingByDay.getValue(it) }.average()
            Cand(dow, avg, avg - otherAvg, days.size)
        }
        val out = mutableListOf<Insight>()
        val highest = cands.maxByOrNull { it.gap }
        val lowest = cands.minByOrNull { it.gap }
        if (highest != null && highest.gap >= MIN_RATING_GAP) {
            out += Insight(Kind.DAY_OF_WEEK, "${dowName(highest.dow)} is your highest-rated day on average (${oneDp(highest.avg)}).", strengthOf(highest.gap, 1.5, highest.n), highest.n)
        }
        if (lowest != null && lowest.gap <= -MIN_RATING_GAP && lowest.dow != highest?.dow) {
            out += Insight(Kind.DAY_OF_WEEK, "${dowName(lowest.dow)} is your lowest-rated day on average (${oneDp(lowest.avg)}).", strengthOf(-lowest.gap, 1.5, lowest.n), lowest.n)
        }
        return out
    }

    // ── Finding: rating runs higher (or lower) on days with ≥X on a given activity ──
    private fun activityRating(
        ratedDays: List<Long>,
        ratingByDay: Map<Long, Double>,
        actMinByDay: Map<Long, Map<String, Int>>,
        activities: List<TimeActivityEntity>,
    ): List<Insight> {
        if (activities.isEmpty()) return emptyList()
        val found = mutableListOf<Insight>()
        for (a in activities) {
            val perDay = ratedDays.map { d -> d to (actMinByDay[d]?.get(a.id) ?: 0) }
            val nonzero = perDay.filter { it.second > 0 }
            if (nonzero.size < MIN_GROUP) continue
            val threshold = (medianOf(nonzero.map { it.second }) / 15.0).roundToInt().coerceAtLeast(1) * 15
            val high = perDay.filter { it.second >= threshold }
            val low = perDay.filter { it.second < threshold }
            if (high.size < MIN_GROUP || low.size < MIN_GROUP) continue
            val highAvg = high.map { ratingByDay.getValue(it.first) }.average()
            val lowAvg = low.map { ratingByDay.getValue(it.first) }.average()
            val gap = highAvg - lowAvg
            if (abs(gap) < MIN_RATING_GAP) continue
            val dir = if (gap > 0) "higher" else "lower"
            val text = "You rate days $dir when you spend ≥${fmtHm(threshold)} on ${a.name} (${oneDp(highAvg)} vs ${oneDp(lowAvg)})."
            found += Insight(Kind.ACTIVITY, text, strengthOf(abs(gap), 2.0, high.size + low.size), high.size + low.size)
        }
        return found.sortedByDescending { it.strength }.take(1)
    }

    // ── Finding: days with a morning intention set rate differently ──
    private fun morningJournaling(ratedDays: List<Long>, ratingByDay: Map<Long, Double>, logByDay: Map<Long, DayLogEntity>): Insight? {
        val withAm = ratedDays.filter { (logByDay[it]?.amIntention ?: "").isNotBlank() }
        val without = ratedDays.filter { (logByDay[it]?.amIntention ?: "").isBlank() }
        if (withAm.size < MIN_GROUP || without.size < MIN_GROUP) return null
        val a = withAm.map { ratingByDay.getValue(it) }.average()
        val b = without.map { ratingByDay.getValue(it) }.average()
        val gap = a - b
        if (abs(gap) < MIN_RATING_GAP) return null
        val dir = if (gap > 0) "higher" else "lower"
        return Insight(Kind.MORNING, "Days you set a morning intention rate $dir (${oneDp(a)} vs ${oneDp(b)}).", strengthOf(abs(gap), 2.0, withAm.size + without.size), withAm.size + without.size)
    }

    // ── Finding: a high effort score on a daily question goes with a higher day-rating ──
    private fun questionRating(
        ratedDays: List<Long>,
        ratingByDay: Map<Long, Double>,
        scoresByDay: Map<Long, Map<String, Int>>,
        questions: List<DailyQuestion>,
    ): List<Insight> {
        if (questions.isEmpty()) return emptyList()
        val found = mutableListOf<Insight>()
        for (q in questions) {
            val scored = ratedDays.mapNotNull { d -> scoresByDay[d]?.get(q.id)?.let { d to it } }
            if (scored.size < MIN_GROUP * 2) continue
            val high = scored.filter { it.second >= 4 }
            val low = scored.filter { it.second <= 3 }
            if (high.size < MIN_GROUP || low.size < MIN_GROUP) continue
            val highAvg = high.map { ratingByDay.getValue(it.first) }.average()
            val lowAvg = low.map { ratingByDay.getValue(it.first) }.average()
            val gap = highAvg - lowAvg
            if (gap < MIN_RATING_GAP) continue
            found += Insight(Kind.QUESTION, "When you score high on “${shortQuestion(q.text)}”, your day-rating is higher (${oneDp(highAvg)} vs ${oneDp(lowAvg)}).", strengthOf(gap, 2.0, high.size + low.size), high.size + low.size)
        }
        return found.sortedByDescending { it.strength }.take(1)
    }

    // ── Finding: evening mood runs higher on days a habit is kept ──
    private fun moodByHabit(
        startDay: Long,
        endDay: Long,
        logByDay: Map<Long, DayLogEntity>,
        habits: List<HabitEntity>,
        checkinByKey: Map<Pair<String, Long>, HabitCheckinEntity>,
    ): List<Insight> {
        if (habits.isEmpty()) return emptyList()
        val moodDays = (startDay..endDay).filter { (logByDay[it]?.pmMood ?: 0) in 1..5 }
        if (moodDays.size < MIN_MOOD_DAYS) return emptyList()
        val found = mutableListOf<Insight>()
        for (h in habits) {
            val expected = moodDays.filter { HabitStats.isExpectedDay(h, it) }
            if (expected.size < MIN_GROUP * 2) continue
            val kept = expected.filter { keptOn(h, it, checkinByKey) }
            val notKept = expected.filter { !keptOn(h, it, checkinByKey) }
            if (kept.size < MIN_GROUP || notKept.size < MIN_GROUP) continue
            val k = kept.map { logByDay[it]!!.pmMood.toDouble() }.average()
            val n = notKept.map { logByDay[it]!!.pmMood.toDouble() }.average()
            val gap = k - n
            if (gap < MIN_MOOD_GAP) continue
            found += Insight(Kind.MOOD_HABIT, "Your mood is higher on days you keep ${h.name} (${oneDp(k)} vs ${oneDp(n)}).", strengthOf(gap, 2.0, kept.size + notKept.size), kept.size + notKept.size)
        }
        return found.sortedByDescending { it.strength }.take(1)
    }

    // ── Finding: a modal emotion word on days heavy in one activity ──
    private fun emotionOnActivityHeavy(
        startDay: Long,
        endDay: Long,
        logByDay: Map<Long, DayLogEntity>,
        actMinByDay: Map<Long, Map<String, Int>>,
        activities: List<TimeActivityEntity>,
    ): Insight? {
        if (activities.isEmpty()) return null
        val emoDays = (startDay..endDay).filter { EmotionWords.isKnown(logByDay[it]?.emotionLabel ?: "") }
        if (emoDays.size < MIN_EMOTION_DAYS) return null
        val totalByAct = HashMap<String, Int>()
        emoDays.forEach { d -> actMinByDay[d]?.forEach { (id, m) -> totalByAct[id] = (totalByAct[id] ?: 0) + m } }
        val topActId = totalByAct.maxByOrNull { it.value }?.key ?: return null
        val a = activities.firstOrNull { it.id == topActId } ?: return null
        val minutesList = emoDays.map { it to (actMinByDay[it]?.get(topActId) ?: 0) }.filter { it.second > 0 }
        if (minutesList.size < MIN_GROUP) return null
        val threshold = medianOf(minutesList.map { it.second })
        val heavy = minutesList.filter { it.second >= threshold }
        if (heavy.size < MIN_GROUP) return null
        val wordCounts = heavy.groupingBy { logByDay[it.first]!!.emotionLabel.trim() }.eachCount()
        val (word, count) = wordCounts.maxByOrNull { it.value } ?: return null
        val frac = count.toDouble() / heavy.size
        if (frac < 0.40) return null // must be genuinely the most-frequent feeling
        return Insight(Kind.EMOTION, "You most often feel ${word.lowercase(Locale.getDefault())} on ${a.name}-heavy days.", (frac * 0.8).coerceIn(0.0, 1.0), heavy.size)
    }

    // ── Shared helpers ──

    /** Blend effect size (gap over its natural scale) with sample confidence into a 0..1 strength. */
    private fun strengthOf(gap: Double, scale: Double, n: Int): Double {
        val effect = (gap / scale).coerceIn(0.0, 1.0)
        val conf = (n.toDouble() / (MIN_RATED_DAYS * 2)).coerceIn(0.0, 1.0)
        return (effect * 0.65 + conf * 0.35).coerceIn(0.0, 1.0)
    }

    private fun keptOn(h: HabitEntity, d: Long, checkinByKey: Map<Pair<String, Long>, HabitCheckinEntity>): Boolean =
        checkinByKey[h.id to d]?.let { it.status == "done" && HabitStats.meetsGoal(h, it.count) } == true

    private fun habitsKeptOn(d: Long, habits: List<HabitEntity>, checkinByKey: Map<Pair<String, Long>, HabitCheckinEntity>): Int =
        habits.count { HabitStats.isExpectedDay(it, d) && keptOn(it, d, checkinByKey) }

    private fun medianOf(v: List<Int>): Int {
        if (v.isEmpty()) return 0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else ((s[s.size / 2 - 1] + s[s.size / 2] + 1) / 2)
    }

    private fun millisWindow(day: Long, zone: ZoneId): Pair<Long, Long> {
        val s = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val e = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return s to e
    }

    private fun dowName(dow: DayOfWeek): String = dow.getDisplayName(TextStyle.FULL, Locale.getDefault())

    private fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun fmtHm(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    /** Trim the shared "Did I do my best to …?" scaffolding so the phrase reads inside a sentence. */
    private fun shortQuestion(text: String): String {
        var s = text.trim().removeSuffix("?").trim()
        val prefixes = listOf("did i do my best to ", "did i do my best ", "did i ")
        val lower = s.lowercase(Locale.getDefault())
        prefixes.firstOrNull { lower.startsWith(it) }?.let { s = s.substring(it.length) }
        return s.ifBlank { text }
    }
}
