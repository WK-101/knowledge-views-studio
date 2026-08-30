package com.todocompanion.app.domain.calendar

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * R43 — the "third horizon". Twelve planner insights that only a UNIFIED local store can form, because
 * each reads across events + tasks + habits + tracked time at once. Every function is pure and offline:
 * no network, no model, no CalendarProvider, no location permission (the daylight rail takes a latitude
 * the user types once). The Planner's "Horizon" tab renders these; the maths lives here so it's testable.
 */
object ThirdHorizon {

    // ── 1 · The cost of yes ──────────────────────────────────────────────────────────────────────
    /** What accepting a [durMin] block now would displace from today's flexible plan. */
    data class Yes(val displacedTitles: List<String>, val displacedMin: Int, val freeAfterMin: Int)

    fun costOfYes(placements: List<CalendarPlanner.Placement>, remainingFreeMin: Int, durMin: Int): Yes {
        val freeAfter = remainingFreeMin - durMin
        if (freeAfter >= 0) return Yes(emptyList(), 0, freeAfter)
        var deficit = -freeAfter
        val bumped = ArrayList<String>(); var bumpedMin = 0
        // autoSchedule returns placements in priority order, so the lowest-priority (last) fall off first.
        for (p in placements.asReversed()) {
            if (deficit <= 0) break
            bumped.add(if (p.parts > 1) "${p.task.title} (${p.part}/${p.parts})" else p.task.title)
            bumpedMin += p.durationMin(); deficit -= p.durationMin()
        }
        return Yes(bumped, bumpedMin, freeAfter)
    }

    // ── 2 · Backfill from actuals ────────────────────────────────────────────────────────────────
    /** A stretch of tracked time today that overlaps no planned event — a candidate to become a block. */
    data class Gap(val startMillis: Long, val endMillis: Long, val minutes: Int, val taskId: String?)

    fun backfillCandidates(
        entries: List<TimeEntryEntity>, occ: List<CalendarEngine.Occurrence>,
        dayStart: Long, dayEnd: Long, minMinutes: Int = 15,
    ): List<Gap> {
        val busy = occ.filter { !it.event.allDay }.map { it.startMillis to it.endMillis }
        return entries.mapNotNull { e ->
            val end = e.endMillis ?: return@mapNotNull null
            val s = maxOf(e.startMillis, dayStart); val en = minOf(end, dayEnd)
            if (en - s < minMinutes * 60_000L) return@mapNotNull null
            // Only if this interval doesn't already sit under a planned block.
            val covered = busy.any { s < it.second && it.first < en }
            if (covered) null else Gap(s, en, ((en - s) / 60_000L).toInt(), e.taskId)
        }.sortedBy { it.startMillis }
    }

    // ── 3 · The ghost week ───────────────────────────────────────────────────────────────────────
    /** Median booked minutes for each weekday slot (0..6 from weekStart) across the prior [weeks] weeks. */
    fun ghostWeek(bookedByDay: Map<Long, Int>, weekStart: Long, weeks: Int = 4): IntArray {
        val out = IntArray(7)
        for (i in 0 until 7) {
            val samples = (1..weeks).map { w -> bookedByDay[weekStart - 7L * w + i] ?: 0 }
            out[i] = median(samples)
        }
        return out
    }

    private fun median(xs: List<Int>): Int {
        if (xs.isEmpty()) return 0
        val s = xs.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    // ── 4 · Deadline-aware chunking ──────────────────────────────────────────────────────────────
    /** Spread [remainingMin] of a task across the days up to (and including) its deadline, filling the
     *  days with the most free time first, capped at [maxPerDay] per day. */
    data class Chunk(val day: Long, val minutes: Int)

    fun deadlineChunks(
        remainingMin: Int, freeByDay: Map<Long, Int>, fromDay: Long, deadlineDay: Long,
        maxPerDay: Int = 120, chunkStep: Int = 30,
    ): List<Chunk> {
        if (remainingMin <= 0 || deadlineDay < fromDay) return emptyList()
        val days = (fromDay..deadlineDay).toList()
        val cap = HashMap<Long, Int>().apply { days.forEach { put(it, minOf(maxPerDay, freeByDay[it] ?: 0)) } }
        val alloc = HashMap<Long, Int>()
        var left = remainingMin
        // Round-robin a chunk at a time onto the emptiest-relative-to-cap day, so work spreads out.
        var guard = 0
        while (left > 0 && guard++ < 500) {
            val day = days.filter { (alloc[it] ?: 0) < (cap[it] ?: 0) }
                .minByOrNull { (alloc[it] ?: 0) } ?: break
            val room = (cap[day] ?: 0) - (alloc[day] ?: 0)
            val put = minOf(chunkStep, room, left)
            alloc[day] = (alloc[day] ?: 0) + put; left -= put
        }
        return days.mapNotNull { d -> alloc[d]?.takeIf { it > 0 }?.let { Chunk(d, it) } }
    }

    // ── 5 · Habit-stacking finder ────────────────────────────────────────────────────────────────
    /** A short free window that sits right after an existing block — an anchor to stack a micro-habit on. */
    data class Stack(val anchorTitle: String, val slotStartMillis: Long, val minutes: Int)

    fun habitStacks(
        occ: List<CalendarEngine.Occurrence>, dayStart: Long, dayEnd: Long,
        minGap: Int = 10, maxGap: Int = 25,
    ): List<Stack> {
        val blocks = occ.filter { !it.event.allDay }.sortedBy { it.startMillis }
        val out = ArrayList<Stack>()
        for (i in blocks.indices) {
            val a = blocks[i]
            val nextStart = blocks.getOrNull(i + 1)?.startMillis ?: dayEnd
            val gapMin = ((nextStart - a.endMillis) / 60_000L).toInt()
            if (gapMin in minGap..maxGap && a.endMillis in dayStart..dayEnd) {
                out.add(Stack(a.event.title, a.endMillis, gapMin))
            }
        }
        return out
    }

    // ── 6 · Recovery buffers ─────────────────────────────────────────────────────────────────────
    /** A run of consecutive heavy days ending on/at [fromDay] with no lighter day between — energy debt. */
    data class Recovery(val heavyStreak: Int, val suggestBuffer: Boolean)

    fun recovery(bookedByDay: Map<Long, Int>, fromDay: Long, heavyMin: Int = 6 * 60): Recovery {
        var streak = 0; var d = fromDay
        while ((bookedByDay[d] ?: 0) >= heavyMin) { streak++; d-- ; if (streak > 30) break }
        return Recovery(streak, streak >= 3)
    }

    // ── 7 · Daylight rail ────────────────────────────────────────────────────────────────────────
    /** Local sunrise/sunset for a latitude (NOAA approximation; solar noon assumed at 12:00 local, so the
     *  clock times are within a few minutes without needing a longitude/timezone or any permission). */
    data class Daylight(val sunrise: LocalTime, val sunset: LocalTime, val daylightMin: Int, val polar: Int)
    // polar: 0 = normal, 1 = midnight sun, -1 = polar night.

    fun daylight(latitude: Double, date: LocalDate): Daylight? {
        if (latitude < -90.0 || latitude > 90.0 || latitude == 999.0) return null
        val n = date.dayOfYear
        val g = 2.0 * Math.PI / 365.0 * (n - 1)
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val lat = Math.toRadians(latitude)
        val zenith = Math.toRadians(90.833) // standard sunrise/sunset incl. refraction
        val cosH = (cos(zenith) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))
        if (cosH >= 1.0) return Daylight(LocalTime.NOON, LocalTime.NOON, 0, -1)      // sun never rises
        if (cosH <= -1.0) return Daylight(LocalTime.MIDNIGHT, LocalTime.of(23, 59), 24 * 60, 1) // never sets
        val halfDayHours = Math.toDegrees(acos(cosH)) / 15.0
        val riseH = 12.0 - halfDayHours; val setH = 12.0 + halfDayHours
        fun t(h: Double): LocalTime {
            val clamped = h.coerceIn(0.0, 23.9833)
            return LocalTime.of(clamped.toInt(), ((clamped % 1.0) * 60).toInt().coerceIn(0, 59))
        }
        return Daylight(t(riseH), t(setH), (halfDayHours * 2 * 60).toInt(), 0)
    }

    // ── 9 · "What changed" narrative ─────────────────────────────────────────────────────────────
    fun whatChanged(plannedBlocks: Int, keptBlocks: Int, plannedMin: Int, trackedMin: Int): String {
        if (plannedBlocks == 0 && trackedMin == 0) return "No plan and no tracked time yet this week — nothing to compare."
        val kept = keptBlocks.coerceAtMost(plannedBlocks)
        val drift = trackedMin - plannedMin
        val tail = when {
            plannedMin == 0 -> "you tracked ${h(trackedMin)} without a plan to compare."
            abs(drift) <= 30 -> "your time landed within half an hour of the plan."
            drift > 0 -> "you spent ${h(drift)} more than planned."
            else -> "you came in ${h(-drift)} under the plan."
        }
        val head = if (plannedBlocks > 0) "You planned $plannedBlocks block${s(plannedBlocks)}, kept $kept — " else ""
        return head + tail
    }

    // ── 10 · North-star allocation ───────────────────────────────────────────────────────────────
    data class Drift(val calId: String, val targetPct: Int, val actualPct: Int) {
        val delta get() = actualPct - targetPct
    }

    fun parseTargets(csv: String): Map<String, Double> =
        csv.split(",").mapNotNull { part ->
            val kv = part.split(":"); if (kv.size != 2) return@mapNotNull null
            val id = kv[0].trim(); val v = kv[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            if (id.isEmpty()) null else id to v
        }.toMap()

    fun encodeTargets(map: Map<String, Double>): String =
        map.entries.filter { it.value > 0 }.joinToString(",") { "${it.key}:${"%.2f".format(it.value)}" }

    fun northStarDrift(targetsCsv: String, minutesByCal: Map<String, Int>): List<Drift> {
        val targets = parseTargets(targetsCsv)
        if (targets.isEmpty()) return emptyList()
        val total = minutesByCal.values.sum().coerceAtLeast(1)
        return targets.entries.map { (id, share) ->
            Drift(id, (share * 100).toInt(), ((minutesByCal[id] ?: 0) * 100 / total))
        }.sortedByDescending { abs(it.delta) }
    }

    // ── 11 · Attention-residue radar ─────────────────────────────────────────────────────────────
    /** A day chopped into many short, unlike blocks carries context-switch cost. */
    data class Fragmentation(val blocks: Int, val shortBlocks: Int, val switches: Int, val score: Int)

    fun fragmentation(occ: List<CalendarEngine.Occurrence>): Fragmentation {
        val blocks = occ.filter { !it.event.allDay }.sortedBy { it.startMillis }
        if (blocks.size < 3) return Fragmentation(blocks.size, 0, 0, 0)
        val short = blocks.count { it.durationMin() <= 30 }
        // A "switch" = adjacent blocks on different calendars (unlike work).
        var switches = 0
        for (i in 1 until blocks.size) if (blocks[i].event.calendarId != blocks[i - 1].event.calendarId) switches++
        val score = (short * 2 + switches * 3).coerceAtMost(100)
        return Fragmentation(blocks.size, short, switches, score)
    }

    // ── 12 · Time-debt repayment ─────────────────────────────────────────────────────────────────
    /** A task whose tracked time already exceeds its estimate — the overrun to pre-book next time. */
    data class Debt(val taskId: String, val title: String, val estimateMin: Int, val actualMin: Int) {
        val overrunMin get() = (actualMin - estimateMin).coerceAtLeast(0)
    }

    fun timeDebts(tasks: List<TaskEntity>, entries: List<TimeEntryEntity>, minOverrun: Int = 20): List<Debt> {
        val actualByTask = HashMap<String, Int>()
        entries.forEach { e ->
            val id = e.taskId ?: return@forEach
            val end = e.endMillis ?: return@forEach
            actualByTask[id] = (actualByTask[id] ?: 0) + ((end - e.startMillis) / 60_000L).toInt().coerceAtLeast(0)
        }
        return tasks.mapNotNull { t ->
            val est = t.estimateMin ?: return@mapNotNull null
            val act = actualByTask[t.id] ?: return@mapNotNull null
            if (est > 0 && act - est >= minOverrun) Debt(t.id, t.title, est, act) else null
        }.sortedByDescending { it.overrunMin }
    }

    // ── small helpers ────────────────────────────────────────────────────────────────────────────
    private fun h(min: Int): String {
        val m = abs(min)
        return when { m < 60 -> "${m}m"; m % 60 == 0 -> "${m / 60}h"; else -> "${m / 60}h ${m % 60}m" }
    }
    private fun s(n: Int) = if (n == 1) "" else "s"
    // 'tan' kept imported for potential future use of the simpler hour-angle form.
    @Suppress("unused") private fun keepTan() = tan(0.0)
}
