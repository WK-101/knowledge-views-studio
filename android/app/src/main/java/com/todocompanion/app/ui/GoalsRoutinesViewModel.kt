package com.todocompanion.app.ui

import com.todocompanion.app.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 3, Stage 6 — the dedicated home for the **Goals** (Unified Goals + the review/accountability layer)
 * and **Routines** (press-play sequences) surfaces, lifted out of the 6k-line [AppViewModel] following the exact
 * collaborator pattern already proven by [TimeTrackingViewModel], [NotesViewModel] and [HabitsViewModel]: a
 * plain class (not a `ViewModel`) that the AppViewModel constructs once and drives with its own `viewModelScope`,
 * so there is no second lifecycle. It OWNS the workspace-scoped goal/routine read-model flows plus the goal &
 * routine actions and the goal analytics (health / capacity / coaching / contention), reaching back to the parent
 * only for genuinely cross-feature state — `app.settings`, `app.appCtx`, `app.toast`, and the task/habit/time
 * read models a goal's health is computed from (`app.tasks`, `app.habitCheckins`, `app.habitsWithArchived`,
 * `app.strengthOf`, `app.timeVm.timeEntries`, `app.trackedCapacityHours`) — and leaves the goal/routine↔X bridges
 * (the reminders read-model, note-embeds, the goal journal, the task `isGoal` celebration, the day-review rollup,
 * `runRoutine`'s timer start, and `logRoutineRun`'s habit/task tick) on the coordinating parent. AppViewModel keeps
 * thin forwarding shims (`val goalsState get() = goalsRoutinesVm.goalsState`, `fun goals() = goalsRoutinesVm.goals()`)
 * so every existing goal/routine call site across the screens and the parent's own bridges need no edits.
 *
 * Stage 6-A moves the Routines half (read-model flows + view-state + the press-play actions); Stage 6-B moves the
 * Goals half (read-models, the nested health/capacity/coach types, and the goal actions + analytics).
 */
class GoalsRoutinesViewModel(
    app: AppViewModel,
    scope: CoroutineScope,
    private val repo: AppRepository,
) : FeatureViewModel(app, scope) {
    // activeWs / activeWorkspace() / state() / scopedBy() are inherited from FeatureViewModel now (#16).
    private val zone: java.time.ZoneId get() = app.zoneId
    private fun today(): Long = app.today()

    // ══ Routines · press-play sequences (Stage 6-A) ══════════════════════════════════════════════════════
    // Routines are per-workspace: a blank workspaceId is legacy data, treated as the default workspace.
    private fun routineWs(r: com.todocompanion.app.domain.Routine) = r.workspaceId.ifBlank { com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID }
    // W3 (cross-module unification) — Routines live in a Room table, not the settings-JSON blob. These flows drive
    // the routine screens; `routines()` returns the active-workspace value so the synchronous internal callers
    // (routinesDueToday / logRoutineRun / runRoutineByName) are unchanged.
    private val allRoutines: StateFlow<List<com.todocompanion.app.domain.Routine>> = repo.observeRoutines().state(emptyList())
    val routinesState: StateFlow<List<com.todocompanion.app.domain.Routine>> =
        combine(allRoutines, activeWs) { all, ws -> all.filter { routineWs(it) == ws } }.state(emptyList())
    val routineRunsState: StateFlow<List<com.todocompanion.app.domain.RoutineRun>> = repo.observeRoutineRuns().state(emptyList())
    fun routines(): List<com.todocompanion.app.domain.Routine> = routinesState.value
    /** [list] is the ACTIVE workspace's routines; other workspaces' are left intact. Blank ids are stamped with
     *  the active workspace. Re-arms the daily routine nudges whenever the set/times change (self-healing). */
    fun saveRoutines(list: List<com.todocompanion.app.domain.Routine>) = scope.launch {
        val ws = activeWorkspace()
        val mine = list.map { if (it.workspaceId.isBlank()) it.copy(workspaceId = ws) else it }
        repo.replaceWorkspaceRoutines(ws, mine)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleRoutineReminders(app.appCtx, repo)
    }
    /** A routine to auto-open in the runner (set by the reminder deep-link; RoutinesScreen consumes it). */
    val pendingRoutineRun = MutableStateFlow<String?>(null)
    fun requestRoutineRun(id: String) { pendingRoutineRun.value = id }

    /** The single in-progress run persisted so a routine survives the app being killed mid-run. */
    fun activeRoutineRun(): com.todocompanion.app.domain.ActiveRoutineRun? =
        com.todocompanion.app.domain.ActiveRoutineRuns.parse(app.settings.value.activeRoutineRunJson)
    fun saveActiveRoutineRun(run: com.todocompanion.app.domain.ActiveRoutineRun) = scope.launch {
        repo.saveSettings(app.settings.value.copy(activeRoutineRunJson = com.todocompanion.app.domain.ActiveRoutineRuns.encode(run)))
    }
    fun clearActiveRoutineRun() = scope.launch {
        if (app.settings.value.activeRoutineRunJson.isNotBlank())
            repo.saveSettings(app.settings.value.copy(activeRoutineRunJson = ""))
    }
    /** Dispatch a routine by name → the parent's `runRoutine` (a time-tracking bridge kept on the parent). */
    fun runRoutineByName(name: String) = scope.launch {
        com.todocompanion.app.domain.Routines.byName(routines(), name)?.let { app.runRoutine(it) }
    }
    /** Insert or replace a routine by id, then persist. */
    fun upsertRoutine(r: com.todocompanion.app.domain.Routine) {
        val list = routines()
        val idx = list.indexOfFirst { it.id == r.id }
        saveRoutines(if (idx >= 0) list.toMutableList().also { it[idx] = r } else list + r)
    }
    fun deleteRoutine(id: String) = scope.launch {
        repo.deleteRoutine(id)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleRoutineReminders(app.appCtx, repo)
    }
    /** Capped press-play run history (adherence, keystone, on-this-day) — W3: Room-backed. */
    fun routineRuns(): List<com.todocompanion.app.domain.RoutineRun> = routineRunsState.value
    /** Runnable routines scheduled today (by cadence) that recur — reminder-set or with explicit days — and
     *  aren't yet FINISHED today. The "press play" set the Today screen surfaces, so a scheduled ritual is
     *  reachable from the daily plan, not only the drawer. */
    fun routinesDueToday(): List<com.todocompanion.app.domain.Routine> {
        val t = today()
        // Only a FINISHED run clears the ritual from Today — a partial/abandoned run (finished=false) shouldn't
        // make it disappear as if kept. Surface a ritual that's scheduled today and is either reminder-set OR
        // has an explicit day cadence, so a cadenced routine reaches the daily plan even without a reminder.
        val ranToday = routineRuns().filter { it.epochDay == t && it.finished }.map { it.routineId }.toSet()
        return routines().filter {
            it.isRunnable && it.scheduledOn(t) && it.id !in ranToday && (it.whenReminderMin != null || it.days.isNotEmpty())
        }
    }
    fun searchRoutines(query: String): List<com.todocompanion.app.domain.Routine> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        return routines().filter { it.name.lowercase().contains(q) || it.note.lowercase().contains(q) }
    }

    // ══ X1 · Unified Goals (Stage 6-B) ═══════════════════════════════════════════════════════════════════
    // Goals are per-workspace: a blank workspaceId is legacy data, treated as the default workspace.
    private fun goalWs(g: com.todocompanion.app.domain.Goal) = g.workspaceId.ifBlank { com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID }
    // W3 (cross-module unification) — Goals live in a Room table, not the settings-JSON blob. `goalsState` is the
    // active-workspace set the UI collects; `goals()` returns its value so the synchronous internal callers
    // (goalHealth / goalCapacity / search / note-embed / upsert) are unchanged.
    private val allGoals: StateFlow<List<com.todocompanion.app.domain.Goal>> = repo.observeGoals().state(emptyList())
    val goalsState: StateFlow<List<com.todocompanion.app.domain.Goal>> =
        combine(allGoals, activeWs) { all, ws -> all.filter { goalWs(it) == ws } }.state(emptyList())
    val goalReviewsState: StateFlow<List<com.todocompanion.app.domain.GoalReview>> = repo.observeGoalReviews().state(emptyList())
    fun goals(): List<com.todocompanion.app.domain.Goal> = goalsState.value
    /** [list] is the ACTIVE workspace's goals; other workspaces' goals are left intact so a save here never
     *  wipes them. Blank ids are stamped with the active workspace. */
    fun saveGoals(list: List<com.todocompanion.app.domain.Goal>) = scope.launch {
        val ws = activeWorkspace()
        val mine = list.map { if (it.workspaceId.isBlank()) it.copy(workspaceId = ws) else it }
        repo.replaceWorkspaceGoals(ws, mine)
    }
    data class GoalHealth(
        val goal: com.todocompanion.app.domain.Goal,
        val taskDone: Int, val taskTotal: Int,
        val habitStreak: Int, val habitStrength: Int,
        val minutesTracked: Int, val budgetMin: Int,
        val overall: Double, val daysLeft: Int?,
    )
    fun goalHealth(g: com.todocompanion.app.domain.Goal): GoalHealth {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val now = System.currentTimeMillis()
        var tDone = 0; var tTotal = 0
        if (g.hasTasks) {
            val inList = app.tasks.value.filter { it.listId == g.listId && !it.trashed && !it.isNote && !it.abandoned }
            tTotal = inList.size; tDone = inList.count { it.completed }
        }
        var streak = 0; var strength = 0
        // Archived-inclusive: an archived lead habit's strength/streak should freeze at its last value (its
        // check-ins simply stop growing), not drop to 0 — otherwise the goal reads as failing the moment the
        // supporting practice is retired. habits.value strips archived, so use the archived-inclusive flow.
        if (g.hasHabit) app.habitsWithArchived.value.firstOrNull { it.id == g.habitId }?.let { h ->
            val (done, skip, relapse) = hs.daySets(h, app.habitCheckins.value)   // R108 audit C2
            val today = java.time.LocalDate.now(zone).toEpochDay()
            streak = hs.displayStreak(h, done, skip, relapse, today, app.settings.value.forgivingStreaks)
            strength = app.strengthOf(h)   // Z8: honours the graded-strength opt-in
        }
        var mins = 0
        if (g.hasBudget) mins = app.timeVm.timeEntries.value.filter { it.activityId == g.activityId }.sumOf { it.minutes(now) }
        // Weighted arms (Goals.kt lead/lag: habit + time are LEAD inputs you control day-to-day; the
        // task list, key results and milestones are LAG outcomes they produce). Outcomes weigh more
        // than inputs (0.65 vs 0.35), so a goal reads by what it actually produces rather than by the
        // activity poured in — but a lead-only or lag-only goal still uses its own arms at full weight.
        val lead = ArrayList<Double>()
        val lag = ArrayList<Double>()
        if (g.hasHabit) lead += strength / 100.0
        if (g.hasBudget && g.budgetMinutes > 0) lead += (mins.toDouble() / g.budgetMinutes).coerceAtMost(1.0)
        if (g.hasTasks && tTotal > 0) lag += tDone.toDouble() / tTotal
        // A goal's outcomes count toward its health too — a KR-only or milestone-only goal must not read 0%.
        // Auto-pull KRs (G1b) resolve their live `current` first, so health tracks the real, current number.
        withResolvedKeyResults(g).keyResultFraction?.let { lag += it }
        if (g.milestones.isNotEmpty()) lag += g.milestonesDone.toDouble() / g.milestones.size
        val leadAvg = lead.takeIf { it.isNotEmpty() }?.average()
        val lagAvg = lag.takeIf { it.isNotEmpty() }?.average()
        val overall = when {
            leadAvg != null && lagAvg != null -> 0.35 * leadAvg + 0.65 * lagAvg
            lagAvg != null -> lagAvg
            leadAvg != null -> leadAvg
            else -> 0.0
        }
        val daysLeft = if (g.targetEpochDay > 0) (g.targetEpochDay - java.time.LocalDate.now(zone).toEpochDay()).toInt() else null
        return GoalHealth(g, tDone, tTotal, streak, strength, mins, g.budgetMinutes, overall, daysLeft)
    }

    // ── G1b · Key-Result auto-pull ────────────────────────────────────────────────────────────────
    /** Resolve a sourced KR's live `current` from its linked object, or null if it's manual / can't be
     *  resolved (source object gone). Reads the same live stores goalHealth already uses. */
    fun resolveKeyResultCurrent(kr: com.todocompanion.app.domain.KeyResult): Double? {
        if (!kr.sourced) return null
        val src = com.todocompanion.app.domain.KeyResultSource
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val cutoff30 = now - 30L * 86_400_000L
        return when (kr.sourceType) {
            src.HABIT -> {
                val h = app.habitsWithArchived.value.firstOrNull { it.id == kr.sourceId } ?: return null
                val hs = com.todocompanion.app.domain.habit.HabitStats
                val (done, skip, relapse) = hs.daySets(h, app.habitCheckins.value)
                when (kr.sourceMetric) {
                    "streak" -> hs.displayStreak(h, done, skip, relapse, today, app.settings.value.forgivingStreaks).toDouble()
                    "rate30" -> (hs.rate(h, done, skip, today, 30) * 100.0)
                    "checkins30" -> done.count { it > today - 30 }.toDouble()
                    "totalAll" -> app.habitCheckins.value.filter { it.habitId == h.id && it.status == "done" }.sumOf { it.count }.toDouble()
                    else -> null
                }
            }
            src.LIST -> {
                val inList = app.tasks.value.filter { it.listId == kr.sourceId && !it.trashed && !it.isNote && !it.abandoned }
                when (kr.sourceMetric) {
                    "done" -> inList.count { it.completed }.toDouble()
                    "remaining" -> inList.count { !it.completed }.toDouble()
                    "percent" -> if (inList.isEmpty()) 0.0 else inList.count { it.completed } * 100.0 / inList.size
                    else -> null
                }
            }
            src.ACTIVITY -> {
                val entries = app.timeVm.timeEntries.value.filter { it.activityId == kr.sourceId }
                when (kr.sourceMetric) {
                    "minutesAll" -> entries.sumOf { it.minutes(now) }.toDouble()
                    "hoursAll" -> entries.sumOf { it.minutes(now) } / 60.0
                    "hours30" -> entries.filter { it.startMillis >= cutoff30 }.sumOf { it.minutes(now) } / 60.0
                    "sessions30" -> entries.count { it.startMillis >= cutoff30 }.toDouble()
                    else -> null
                }
            }
            else -> null
        }
    }

    /** A copy of [g] with every sourced KR's `current` replaced by its live resolved value. Cheap no-op
     *  when the goal has no sourced KRs, so it's safe to call on every render. */
    fun withResolvedKeyResults(g: com.todocompanion.app.domain.Goal): com.todocompanion.app.domain.Goal {
        if (g.keyResults.none { it.sourced }) return g
        return g.copy(keyResults = g.keyResults.map { kr ->
            resolveKeyResultCurrent(kr)?.let { kr.copy(current = it) } ?: kr
        })
    }

    // ── Phase B · goal editing + the review/accountability layer ─────────────────────────────────
    fun upsertGoal(g: com.todocompanion.app.domain.Goal) {
        val cur = goals()
        saveGoals(if (cur.any { it.id == g.id }) cur.map { if (it.id == g.id) g else it } else cur + g)
    }
    fun deleteGoal(id: String) = scope.launch { repo.deleteGoal(id) }

    /** The weekly-review log (newest last). Drives the scoreboard trend + the integrity chain. */
    fun goalReviews(): List<com.todocompanion.app.domain.GoalReview> = goalReviewsState.value
    fun saveGoalReviews(list: List<com.todocompanion.app.domain.GoalReview>) = scope.launch {
        repo.replaceGoalReviews(list)
    }
    /** Record one review sitting (portfolio when goalId is blank). */
    fun logGoalReview(goalId: String, executionPct: Int, commitmentsKept: Int, commitmentsTotal: Int, note: String) {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val total = commitmentsTotal.coerceAtLeast(0)
        val r = com.todocompanion.app.domain.GoalReview(
            id = java.util.UUID.randomUUID().toString(), goalId = goalId, epochDay = today,
            executionPct = executionPct.coerceIn(0, 100), commitmentsKept = commitmentsKept.coerceIn(0, total),
            commitmentsTotal = total, note = note.trim(), createdAt = System.currentTimeMillis(),
        )
        saveGoalReviews(com.todocompanion.app.domain.GoalReviews.append(goalReviews(), r))
    }

    /**
     * moat #4 — capacity-honest check for a single goal: the weekly hours its time budget implies vs
     * the honest tracked-focus capacity a week actually holds. Returns null when the goal has no time
     * arm or no deadline to spread the budget across.
     */
    data class GoalCapacity(val weeklyNeedH: Double, val weeklyHaveH: Double, val overcommitted: Boolean)
    fun goalCapacity(g: com.todocompanion.app.domain.Goal): GoalCapacity? {
        if (!g.hasBudget) return null
        val today = java.time.LocalDate.now(zone).toEpochDay()
        // Weeks remaining: from the cycle window, else the deadline, else assume a 12-week horizon.
        val weeksLeft: Double = when {
            g.hasCycle -> (com.todocompanion.app.domain.GoalScore.cycle(g, today)?.daysLeft ?: (g.cycleWeeks * 7)).toDouble() / 7.0
            g.targetEpochDay > today -> (g.targetEpochDay - today).toDouble() / 7.0
            else -> 12.0
        }.coerceAtLeast(0.5)
        val mins = app.timeVm.timeEntries.value.filter { it.activityId == g.activityId }.sumOf { it.minutes(System.currentTimeMillis()) }
        val remainingMin = (g.budgetMinutes - mins).coerceAtLeast(0)
        val needH = (remainingMin / 60.0) / weeksLeft
        val haveH = app.trackedCapacityHours()?.let { it * 7.0 } ?: (app.settings.value.dailyCapacityHours * 7.0)
        // Flag when this one goal's weekly demand exceeds your whole weekly focus budget — the number
        // shown (haveH) is the same one the threshold tests, so the warning never contradicts its own text.
        // (Competition *between* goals for the same hours is caught separately by goalContention.)
        return GoalCapacity(needH, haveH, overcommitted = needH > haveH)
    }

    private fun hm(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

    // ── Y1 · self-coaching Goals ──────────────────────────────────────────────────────────────────
    data class GoalCoach(val text: String, val startActivityId: String?)
    /** For a goal that's behind pace or has a slipping arm, the single most useful nudge — plus the
     *  activity to start a catch-up session on, when the time arm is the one behind. */
    fun goalCoaching(g: com.todocompanion.app.domain.Goal): GoalCoach? {
        val gh = goalHealth(g)
        // Time arm: required run-rate to hit the target date.
        if (g.hasBudget && gh.daysLeft != null) {
            val remaining = (g.budgetMinutes - gh.minutesTracked).coerceAtLeast(0)
            if (remaining > 0) {
                val text = if (gh.daysLeft <= 0) "Past the target date with ${hm(remaining)} of the budget left — a session still counts."
                    else "To hit the target, about ${hm(remaining / gh.daysLeft.coerceAtLeast(1))}/day for ${gh.daysLeft} more day${if (gh.daysLeft == 1) "" else "s"}."
                return GoalCoach(text, g.activityId)
            }
        }
        // Habit arm slipping.
        if (g.hasHabit && gh.habitStrength in 1..39)
            return GoalCoach("Its habit is slipping (${gh.habitStrength}%) — a check-in today is the highest-leverage move.", null)
        // Task arm with a near deadline.
        if (g.hasTasks && gh.taskTotal > 0 && gh.taskDone < gh.taskTotal && gh.daysLeft != null && gh.daysLeft in 0..3)
            return GoalCoach("${gh.taskTotal - gh.taskDone} task${if (gh.taskTotal - gh.taskDone == 1) "" else "s"} left with the deadline near.", null)
        return null
    }

    // ── Y8 · goal contention — two goals drawing on the same hours ────────────────────────────────
    fun goalContention(): List<String> {
        val gs = goals().filter { it.hasBudget }
        val actName = app.timeVm.timeActivities.value.associate { it.id to ((it.emoji?.plus(" ") ?: "") + it.name) }
        return gs.groupBy { it.activityId }.filter { it.value.size >= 2 }
            .map { (act, list) ->
                val (label, verb) = if (list.size == 2) "‘${list[0].name}’ and ‘${list[1].name}’" to "both draw"
                    else list.joinToString(", ") { "‘${it.name}’" } to "all draw"
                "$label $verb on ${actName[act] ?: "the same activity"} — they compete for the same hours."
            }
            .take(2)
    }

    fun searchGoals(query: String): List<com.todocompanion.app.domain.Goal> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        return goals().filter { !it.archived && (it.name.lowercase().contains(q) || it.note.lowercase().contains(q) ||
            it.area.lowercase().contains(q) || it.identity.lowercase().contains(q)) }
    }

    /** CU5 — share a goal's progress as an on-device image card (0 network, 0 permission). */
    fun shareGoalSnapshot(g: com.todocompanion.app.domain.Goal, onDone: (String?) -> Unit = {}) = scope.launch {
        val h = goalHealth(g)
        val stats = buildList {
            add("progress" to "${(h.overall * 100).toInt()}%")
            if (g.hasTasks) add("tasks" to "${h.taskDone}/${h.taskTotal}")
            if (g.hasHabit) add("streak" to "${h.habitStreak}d")
            if (g.hasBudget) add("time" to "${"%.1f".format(h.minutesTracked / 60.0)}h/${h.budgetMin / 60}h")
        }.take(4)
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.renderStatsCard("${g.emoji} ${g.name}", "Goal progress", stats)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(app.appCtx, bmp, "modular-goal.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(app.appCtx, it) }
        onDone(res.savedLocation)
    }
}
