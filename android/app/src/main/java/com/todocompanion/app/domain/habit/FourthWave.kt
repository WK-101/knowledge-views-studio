package com.todocompanion.app.domain.habit

import com.todocompanion.app.data.entity.CravingEventEntity
import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.EscrowEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.NudgeEventEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.AppSettings
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * R36 — the FOURTH-WAVE brain. Sixteen permission-free behaviour-change levers, all on-device, rules-only,
 * over data the app already holds. No new permissions, no LLM, no network. Pure functions; each returns
 * null / empty when the history is too thin to say anything honest.
 */
object FourthWave {

    private const val AUTOMATIC_TARGET_PCT = 95.0

    // ── FW-2 · just-in-time micro-lessons ─────────────────────────────────────────────────────────
    /** A 30-second lesson, surfaced when the moment fits. Rules-only catalog keyed to the habit's
     *  current situation — the teachable moment, not a generic tip feed. */
    data class MicroLesson(val id: String, val emoji: String, val title: String, val body: String)

    /** Pick the single most-relevant lesson for a habit right now, or null if none applies. */
    fun microLesson(
        habit: HabitEntity,
        checkins: List<HabitCheckinEntity>,
        cravings: List<CravingEventEntity>,
        today: Long,
    ): MicroLesson? {
        val mine = checkins.filter { it.habitId == habit.id }
        val done = mine.filter { it.status == "done" && HabitStats.meetsGoal(habit, it.count) }.map { it.epochDay }.toSet()
        val skip = mine.filter { it.status == "skip" }.map { it.epochDay }.toSet()
        if (habit.habitType == "break") {
            val urges = cravings.filter { it.habitId == habit.id }
            if (urges.size >= 3) return LESSONS.getValue("urge_surf")
            return LESSONS.getValue("substitution")
        }
        val todayDone = today in done
        val miss = HabitBuilder.missStatus(habit, done, skip, today, todayDone)
        if (miss.atRiskToday) return LESSONS.getValue("never_twice")
        val auto = HabitBuilder.automaticity(done)
        if (auto.reps in 1..6) return LESSONS.getValue("tiny_start")
        val stability = ThirdWave.contextStability(habit, checkins)
        if (stability != null && stability < 45) return LESSONS.getValue("anchor")
        if (auto.pct in 40..79) return LESSONS.getValue("keep_going")
        if (auto.pct >= 90) return LESSONS.getValue("identity")
        return null
    }

    private val LESSONS: Map<String, MicroLesson> = listOf(
        MicroLesson("tiny_start", "🌱", "Make it laughably small", "The first weeks are about showing up, not results. Shrink the habit until it's almost impossible to skip — two push-ups, one page. Consistency now buys capacity later."),
        MicroLesson("anchor", "⚓", "Anchor it to something fixed", "Habits that share a time and place become automatic fastest. Tie this to an existing routine — \"after I pour my coffee\" — so the cue does the remembering for you."),
        MicroLesson("never_twice", "🛡️", "Never miss twice", "One miss is an accident; two is the start of a new pattern. Getting back on the day after a slip is the whole skill. Do the smallest version today and the chain survives."),
        MicroLesson("keep_going", "📈", "You're in the messy middle", "Automaticity climbs steeply, then flattens. You're past the hardest part but not yet on autopilot — this is exactly where most people quit. Hold the line a few more weeks."),
        MicroLesson("identity", "🪪", "It's becoming who you are", "You're near automatic. Shift the story from \"I'm trying to\" to \"I'm someone who\". Identity-based habits are the ones that stick for good."),
        MicroLesson("urge_surf", "🌊", "Surf the urge", "Cravings peak and fall like a wave, usually within a few minutes. Don't fight it — watch it. Name it, breathe, and let it crest. It always passes faster than it feels."),
        MicroLesson("substitution", "🔁", "Swap, don't suppress", "Breaking a habit works best by replacing the routine while keeping the cue and reward. When the trigger hits, run your planned alternative instead of resisting in a vacuum."),
    ).associateBy { it.id }

    // ── FW-3 · adaptive automaticity horizon ──────────────────────────────────────────────────────
    /** A personalised ETA to automatic, projected from your OWN adherence — not a fixed "66 days". */
    data class Horizon(val pct: Int, val reps: Int, val repsToTarget: Int, val etaDays: Int, val adherence: Int)

    fun adaptiveHorizon(habit: HabitEntity, checkins: List<HabitCheckinEntity>, today: Long): Horizon? {
        val mine = checkins.filter { it.habitId == habit.id }
        val done = mine.filter { it.status == "done" && HabitStats.meetsGoal(habit, it.count) }.map { it.epochDay }.toSet()
        val auto = HabitBuilder.automaticity(done)
        if (auto.reps < 3) return null
        val repsTarget = ceil(-21.0 * ln(1.0 - AUTOMATIC_TARGET_PCT / 100.0)).toInt()   // ≈ 63
        val remaining = (repsTarget - auto.reps).coerceAtLeast(0)
        // Recent adherence over expected days, last 8 weeks.
        val start = (today - 55).coerceAtLeast(habit.startEpochDay())
        val expected = (start..today).filter { HabitStats.isExpectedDay(habit, it) }
        val rate = if (expected.isEmpty()) 0.0 else expected.count { it in done }.toDouble() / expected.size
        // Expected days per calendar day (schedule density), so a 3×/week habit takes longer in calendar time.
        val denseStart = (today - 27).coerceAtLeast(habit.startEpochDay())
        val denseDays = (denseStart..today).toList()
        val density = if (denseDays.isEmpty()) 1.0 else denseDays.count { HabitStats.isExpectedDay(habit, it) }.toDouble() / denseDays.size
        val repsPerDay = (rate * density).coerceAtLeast(0.02)
        val eta = if (remaining == 0) 0 else ceil(remaining / repsPerDay).toInt().coerceAtMost(3650)
        return Horizon(auto.pct, auto.reps, remaining, eta, (rate * 100).roundToInt())
    }

    // ── FW-4 · red-chain counter (break habits) ───────────────────────────────────────────────────
    /** Two chains for a quit habit: the green clean-day streak, and the red relapse chain forming now.
     *  A visible red chain interrupts the "one more won't hurt" story. */
    data class RedChain(val cleanDays: Int, val redDays: Int, val longestClean: Int, val relapses: Int)

    fun redChain(habit: HabitEntity, checkins: List<HabitCheckinEntity>, today: Long): RedChain? {
        if (habit.habitType != "break") return null
        val mine = checkins.filter { it.habitId == habit.id }
        val relapseDays = mine.filter { HabitStats.isRelapse(habit, it.count) }.map { it.epochDay }.toSet()
        val startDay = habit.startEpochDay()
        if (today < startDay) return RedChain(0, 0, 0, 0)
        // Clean streak: consecutive days up to today with no relapse.
        var clean = 0; var d = today
        while (d >= startDay && d !in relapseDays) { clean++; d-- }
        // Red chain: consecutive most-recent days that WERE relapses.
        var red = 0; d = today
        while (d >= startDay && d in relapseDays) { red++; d-- }
        // Longest clean run over the whole span.
        var longest = 0; var run = 0
        for (day in startDay..today) { if (day in relapseDays) run = 0 else { run++; if (run > longest) longest = run } }
        return RedChain(clean, red, longest, relapseDays.size)
    }

    // ── FW-5 · new-habit WIP limiter ───────────────────────────────────────────────────────────────
    /** Habits still "in formation" — not graduated and below ~80% automatic. Too many at once splits
     *  attention and tanks them all; the limiter is a gentle stop before starting one more. */
    data class InFormation(val count: Int, val limit: Int, val overCap: Boolean, val habitIds: List<String>)

    fun inFormation(habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, limit: Int): InFormation {
        val forming = habits.filter { h ->
            !h.archived && !h.paused && h.habitType != "break" && !h.graduated &&
                run {
                    val done = checkins.filter { it.habitId == h.id && it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                    HabitBuilder.automaticity(done).pct < 80
                }
        }.map { it.id }
        return InFormation(forming.size, limit, limit > 0 && forming.size >= limit, forming)
    }

    // ── FW-6 · daily shutdown + carry-forward ─────────────────────────────────────────────────────
    /** The evening ritual list: today's still-open, due-or-overdue tasks. Carrying them forward on
     *  purpose (vs. letting them rot) is the Zeigarnik-closing move that lets you actually stop for the day. */
    fun shutdownCarryForward(tasks: List<TaskEntity>, today: Long, zone: ZoneId = ZoneId.systemDefault(), dayStartMin: Int = 0): List<TaskEntity> {
        val todayDate = LocalDate.ofEpochDay(today)
        return tasks.filter { t ->
            !t.completed && t.dueDate != null &&
                !Instant.ofEpochMilli(t.dueDate!! - dayStartMin * 60_000L).atZone(zone).toLocalDate().isAfter(todayDate)
        }
    }

    // ── FW-7 · load-aware scheduling (our data only) ──────────────────────────────────────────────
    /** Committed minutes on a day, from OUR calendar: task estimates due that day + scheduled habit
     *  minutes. No external calendar, no permissions. */
    data class DayLoad(val day: Long, val taskMin: Int, val habitMin: Int) { val total get() = taskMin + habitMin }

    fun loadByDay(tasks: List<TaskEntity>, habits: List<HabitEntity>, startDay: Long, days: Int, zone: ZoneId = ZoneId.systemDefault(), dayStartMin: Int = 0): List<DayLoad> {
        fun taskDay(t: TaskEntity): Long? = t.dueDate?.let { Instant.ofEpochMilli(it - dayStartMin * 60_000L).atZone(zone).toLocalDate().toEpochDay() }
        fun taskMinutes(t: TaskEntity): Int = t.durationMin ?: t.estimateMax ?: t.estimateMin ?: 0
        val openTasks = tasks.filter { !t(it) }
        val byDay = openTasks.mapNotNull { t -> taskDay(t)?.let { it to taskMinutes(t) } }.groupBy({ it.first }, { it.second })
        return (startDay until startDay + days).map { day ->
            val tMin = byDay[day]?.sum() ?: 0
            val hMin = habits.filter { !it.archived && !it.paused && it.habitType != "break" && HabitStats.isExpectedDay(it, day) && day >= it.startEpochDay() }
                .sumOf { (it.minutesPerUnit * it.targetPerDay).coerceAtLeast(0) }
            DayLoad(day, tMin, hMin)
        }
    }

    private fun t(task: TaskEntity) = task.completed

    /** The lightest day in the next [window] days (excluding today) — where a new commitment fits best. */
    fun suggestBestDay(tasks: List<TaskEntity>, habits: List<HabitEntity>, today: Long, window: Int = 7, zone: ZoneId = ZoneId.systemDefault(), dayStartMin: Int = 0): DayLoad? =
        loadByDay(tasks, habits, today + 1, window, zone, dayStartMin).minByOrNull { it.total }

    // ── FW-8 · cue-exposure extinction ladder (break habits) ──────────────────────────────────────
    /** Urges logged over time, bucketed into early vs. recent halves — is average intensity falling as
     *  you keep facing the cue without acting? Graded exposure, measured. */
    data class Extinction(val exposures: Int, val earlyAvg: Double, val recentAvg: Double, val falling: Boolean, val rung: Int, val rungLabel: String)

    fun extinctionLadder(habit: HabitEntity, cravings: List<CravingEventEntity>): Extinction? {
        if (habit.habitType != "break") return null
        val mine = cravings.filter { it.habitId == habit.id && it.intensity > 0 }.sortedBy { it.atMillis }
        if (mine.size < 4) return null
        val half = mine.size / 2
        val early = mine.take(half).map { it.intensity }.average()
        val recent = mine.drop(mine.size - half).map { it.intensity }.average()
        val resisted = mine.count { it.surfed }
        val rung = when { resisted >= 15 -> 4; resisted >= 8 -> 3; resisted >= 4 -> 2; resisted >= 1 -> 1; else -> 0 }
        val label = listOf("Notice the cue", "Sit with it 1 min", "Sit with it 5 min", "Stay in the situation", "Cue no longer controls you")[rung]
        return Extinction(mine.size, early, recent, recent < early - 0.3, rung, label)
    }

    // ── FW-9 · self-escrow contingency reward ─────────────────────────────────────────────────────
    /** Progress toward an escrow's milestone, computed live from the linked habit's history. */
    data class EscrowStatus(val escrow: EscrowEntity, val current: Int, val target: Int, val reached: Boolean) {
        val pct: Int get() = if (target <= 0) 0 else (current * 100 / target).coerceIn(0, 100)
    }

    fun escrowStatus(escrow: EscrowEntity, habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, today: Long): EscrowStatus {
        val habit = habits.firstOrNull { it.id == escrow.habitId }
        val current = if (habit == null) 0 else {
            val mine = checkins.filter { it.habitId == habit.id }
            val done = mine.filter { it.status == "done" && HabitStats.meetsGoal(habit, it.count) }.map { it.epochDay }.toSet()
            val skip = mine.filter { it.status == "skip" }.map { it.epochDay }.toSet()
            val rel = mine.filter { HabitStats.isRelapse(habit, it.count) }.map { it.epochDay }.toSet()
            when (escrow.milestoneKind) {
                "streak" -> HabitStats.currentStreak(habit, done, skip, rel, today)
                "cleandays" -> redChain(habit, checkins, today)?.cleanDays ?: 0
                "automaticity" -> HabitBuilder.automaticity(done).pct
                else -> 0
            }
        }
        return EscrowStatus(escrow, current, escrow.milestoneValue, current >= escrow.milestoneValue)
    }

    // ── FW-10 · grounding / panic library ─────────────────────────────────────────────────────────
    /** A static, offline toolkit for the hard moments — grounding + de-escalation techniques. */
    data class Grounding(val emoji: String, val title: String, val steps: String)

    fun groundingTechniques(): List<Grounding> = listOf(
        Grounding("🖐️", "5-4-3-2-1 senses", "Name 5 things you can see, 4 you can hear, 3 you can touch, 2 you can smell, 1 you can taste. Slow down on each — it pulls you out of the spiral and back into the room."),
        Grounding("🌬️", "Box breathing", "Breathe in for 4, hold for 4, out for 4, hold for 4. Repeat four rounds. Longer exhales tell your nervous system the danger has passed."),
        Grounding("❄️", "Cold reset", "Hold something cold, splash cold water on your face, or step outside. A sharp temperature change interrupts a panic loop and buys you a clear minute."),
        Grounding("🦶", "Feet on the floor", "Press both feet flat and feel the ground hold you up. Push down gently. You are here, now, supported — the feeling is real but temporary."),
        Grounding("💭", "Name it to tame it", "Say to yourself: \"This is anxiety. It peaks and it passes.\" Labelling the feeling engages the thinking brain and quiets the alarm."),
        Grounding("📞", "Reach out", "Message one person you trust, even just \"having a rough moment\". Connection is a nervous-system regulator — you don't have to ride it out alone."),
        Grounding("🕰️", "Ten-minute rule", "Promise yourself you'll only wait ten minutes before deciding anything. Urges and panic almost always drop within that window — decide on the other side of it."),
    )

    // ── FW-11 · transition detector + reset window ────────────────────────────────────────────────
    /** A declared life transition (or a detected gap) opens a fresh-start window where re-planning
     *  sticks better. Returns a gentle prompt, or null outside the window. */
    data class TransitionState(val label: String, val dayOfWindow: Int, val windowDays: Int, val message: String)

    fun transitionWindow(settings: AppSettings, today: Long): TransitionState? {
        val start = settings.transitionStartDay
        if (start <= 0 || settings.transitionLabel.isBlank()) return null
        val window = 21
        val elapsed = (today - start).toInt()
        if (elapsed < 0 || elapsed > window) return null
        return TransitionState(
            settings.transitionLabel, elapsed + 1, window,
            "You're ${elapsed + 1} days into \"${settings.transitionLabel}\". Transitions reset old cues — a rare window to re-choose your routines. Review which habits still fit this new chapter."
        )
    }

    // ── FW-12 · temporal-landmark nudges ──────────────────────────────────────────────────────────
    /** Is today a fresh-start landmark? People re-commit more readily at temporal boundaries (Dai et al.). */
    data class Landmark(val kind: String, val emoji: String, val label: String)

    fun temporalLandmark(today: Long): Landmark? {
        val date = LocalDate.ofEpochDay(today)
        return when {
            date.dayOfYear == 1 -> Landmark("year", "🎆", "New year — a clean slate. What's the one habit that would make this year different?")
            date.dayOfMonth == 1 && date.monthValue % 3 == 1 -> Landmark("quarter", "🍃", "First day of a new quarter — a natural point to reset your routines.")
            date.dayOfMonth == 1 -> Landmark("month", "📅", "A new month begins. Pick up anything that slipped — the page is fresh.")
            date.dayOfWeek == DayOfWeek.MONDAY -> Landmark("week", "☀️", "New week. Small resets on Mondays tend to stick — line up your habits for the week.")
            else -> null
        }
    }

    // ── FW-13 · causal trigger graph ──────────────────────────────────────────────────────────────
    /** Which habit, done on a day, most raises the odds a good (high-mood) day follows — a lagged,
     *  within-person co-occurrence lift over the app's own logs. Correlational, honestly labelled. */
    data class CausalEdge(val habitId: String, val habitName: String, val emoji: String, val lift: Double, val nWith: Int)

    fun causalPrecursors(habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, today: Long): List<CausalEdge> {
        // "Good day" = next day's average check-in mood ≥ 4.
        val moodByDay = checkins.filter { it.ctxMood > 0 }.groupBy { it.epochDay }.mapValues { e -> e.value.map { it.ctxMood }.average() }
        if (moodByDay.size < 8) return emptyList()
        val goodDays = moodByDay.filterValues { it >= 4.0 }.keys
        val baseRate = goodDays.size.toDouble() / moodByDay.size
        if (baseRate <= 0.0 || baseRate >= 1.0) return emptyList()
        val out = ArrayList<CausalEdge>()
        habits.filter { !it.archived && it.habitType != "break" }.forEach { h ->
            val doneDays = checkins.filter { it.habitId == h.id && it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            // Days where habit done AND the NEXT day has a mood reading.
            val eligible = doneDays.filter { (it + 1) in moodByDay }
            if (eligible.size < 4) return@forEach
            val goodAfter = eligible.count { (it + 1) in goodDays }
            val condRate = goodAfter.toDouble() / eligible.size
            val lift = condRate / baseRate
            if (lift > 1.15) out += CausalEdge(h.id, h.name, h.emoji ?: "•", lift, eligible.size)
        }
        return out.sortedByDescending { it.lift }.take(5)
    }

    // ── FW-14 · personal nudge MRT ────────────────────────────────────────────────────────────────
    /** Per-variant effectiveness of the opportunity nudge — micro-randomised trial over your own logs. */
    data class VariantStat(val variant: Int, val label: String, val shown: Int, val acted: Int) {
        val rate: Int get() = if (shown == 0) 0 else acted * 100 / shown
    }
    data class NudgeReadout(val variants: List<VariantStat>, val bestVariant: Int, val totalShown: Int)

    /** The message variants the MRT randomises between. Index is stored on each NudgeEventEntity. */
    val NUDGE_VARIANTS: List<String> = listOf(
        "Now's your usual window — knock it out.",
        "Two minutes. Just start; you can stop after.",
        "Future-you will be glad you did this now.",
        "Your streak is watching. Keep it alive.",
    )

    fun pickVariant(seed: Long): Int = ((seed % NUDGE_VARIANTS.size + NUDGE_VARIANTS.size) % NUDGE_VARIANTS.size).toInt()

    fun nudgeMrtReadout(events: List<NudgeEventEntity>): NudgeReadout? {
        if (events.isEmpty()) return null
        val stats = NUDGE_VARIANTS.indices.map { v ->
            val forV = events.filter { it.variant == v }
            VariantStat(v, NUDGE_VARIANTS[v], forV.size, forV.count { it.acted })
        }
        val eligible = stats.filter { it.shown >= 3 }
        val best = (eligible.ifEmpty { stats }).maxByOrNull { it.rate }?.variant ?: 0
        return NudgeReadout(stats, best, events.size)
    }

    // ── FW-15 · life-load balancer ────────────────────────────────────────────────────────────────
    /** Next-week load vs. capacity, flagging overcommitted days and the lightest day to move work to. */
    data class LifeLoad(val days: List<DayLoad>, val capacityMin: Int, val overloaded: List<Long>, val advice: String)

    fun lifeLoadForecast(tasks: List<TaskEntity>, habits: List<HabitEntity>, settings: AppSettings, today: Long, window: Int = 7, zone: ZoneId = ZoneId.systemDefault(), dayStartMin: Int = 0): LifeLoad {
        val days = loadByDay(tasks, habits, today, window, zone, dayStartMin)
        val over = days.filter { dl ->
            val cap = settings.capacityHoursFor(LocalDate.ofEpochDay(dl.day).dayOfWeek) * 60
            dl.total > cap
        }.map { it.day }
        val avgCap = settings.dailyCapacityHours * 60
        val advice = when {
            over.isEmpty() -> "Your week looks balanced — no day is over its capacity."
            over.size >= window - 1 -> "Nearly every day is over capacity. Consider deferring or dropping some commitments this week."
            else -> {
                val lightest = days.filter { it.day !in over }.minByOrNull { it.total }
                val d = lightest?.let { LocalDate.ofEpochDay(it.day).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
                "${over.size} day(s) run over capacity." + (if (d != null) " $d is your lightest — move something there." else "")
            }
        }
        return LifeLoad(days, avgCap, over, advice)
    }

    // ── FW-16 · cross-domain receptivity model ────────────────────────────────────────────────────
    /** When are you most receptive — actually acting — across habits AND tasks? Learned from when
     *  check-ins and task completions land, by 3-hour bucket and by weekday. Times nudges to your peaks. */
    data class Receptivity(val byBucket: IntArray, val bestBucket: Int, val byDow: IntArray, val bestDow: Int, val n: Int) {
        fun bucketLabel(b: Int): String = listOf("12–3am", "3–6am", "6–9am", "9am–12pm", "12–3pm", "3–6pm", "6–9pm", "9pm–12am")[b.coerceIn(0, 7)]
        fun dowLabel(d: Int): String = DayOfWeek.of(d.coerceIn(1, 7)).getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    fun receptivity(checkins: List<HabitCheckinEntity>, tasks: List<TaskEntity>, zone: ZoneId = ZoneId.systemDefault()): Receptivity? {
        val buckets = IntArray(8)
        val dows = IntArray(8)   // 1..7 used
        var n = 0
        checkins.filter { it.status == "done" }.forEach { c ->
            c.doneAtMinute?.let { m -> buckets[(m / 180).coerceIn(0, 7)]++; n++ }
            dows[LocalDate.ofEpochDay(c.epochDay).dayOfWeek.value]++
        }
        tasks.filter { it.completed && it.completedAt != null }.forEach { t ->
            val zdt = Instant.ofEpochMilli(t.completedAt!!).atZone(zone)
            buckets[(zdt.hour / 3).coerceIn(0, 7)]++; n++
            dows[zdt.dayOfWeek.value]++
        }
        if (n < 8) return null
        val bestBucket = buckets.indices.maxByOrNull { buckets[it] } ?: 0
        val bestDow = (1..7).maxByOrNull { dows[it] } ?: 1
        return Receptivity(buckets, bestBucket, dows, bestDow, n)
    }
}
