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
 * Phase 3, Stage 5 — the dedicated home for the Habits surface (habit tracking + the habit-science /
 * life-systems coaching layer built on top of it), lifted out of the 6k-line [AppViewModel] following the
 * exact collaborator pattern already proven by [TimeTrackingViewModel] and [NotesViewModel]: a plain class
 * (not a ViewModel) that the AppViewModel constructs once and drives with its own `viewModelScope`, so there
 * is no second lifecycle. It OWNS the workspace-scoped habit read-model flows and (in later stages) the habit
 * actions, reaching back to the parent only for cross-feature state (`app.settings`, `app.appCtx`, `app.toast`,
 * `app.undoEvents`, `app.appendAction`) and leaving the habits↔tasks/time/day-review/goals/Focus bridges on
 * the coordinating parent. AppViewModel keeps thin forwarding shims (`val habits get() = habitsVm.habits`) so
 * the many existing habit call sites across the screens need no edits.
 *
 * Stage 5-A (this file's current scope) moves the read-model flows — the three habit lists (`habits`,
 * `habitsWithArchived`, `trashedHabits`), `habitCheckins`, and the habit-science tables (`cravings`,
 * `witnessEvents`, `scorecardItems`, `buddies`, `integrityReviews`, `experiments`, `escrows`, `nudgeEvents`) —
 * which derive straight from `repo` observe queries and have no writer on the VM, so the move is clean. The
 * habit actions (CRUD, check-ins, coach/builder, reminders, stats, reserve/time) follow in the next stages.
 */
class HabitsViewModel(
    private val app: AppViewModel,
    private val scope: CoroutineScope,
    private val repo: AppRepository,
) {
    // Re-declared locally, exactly as TimeTrackingViewModel/NotesViewModel do (same combine + WhileSubscribed
    // + Default), so this collaborator owns its scoping instead of reaching into AppViewModel's private helpers.
    private val activeWs: Flow<String> = app.settings.map { it.activeWorkspaceId }
    /** The active workspace id, read synchronously — used to STAMP new rows (mirrors AppViewModel's helper). */
    private fun activeWorkspace(): String = app.settings.value.activeWorkspaceId
    // Day-rollover zone + "today" epoch-day forward to the parent's settings-aware helpers, so a check-in
    // recorded here honours the same "day starts at" rollover as everywhere else.
    private val zone: java.time.ZoneId get() = app.zoneId
    private fun today(): Long = app.today()
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)
    private fun <T> Flow<List<T>>.scopedBy(wsOf: (T) -> String): StateFlow<List<T>> =
        combine(this, activeWs) { list, w -> list.filter { wsOf(it) == w } }.state(emptyList())

    // ── Habit read-model flows (Stage 5-A) — active-workspace scoped off repo observe queries ──────────────
    val habits = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && !it.archived && !it.trashed } }.state(emptyList())
    /** Active-workspace habits INCLUDING archived (but never trashed) — for surfaces that must tell
     *  "archived" apart from "deleted" (e.g. a goal's lead-measure hint) and for the Archived view. */
    val habitsWithArchived = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && !it.trashed } }.state(emptyList())
    /** Trashed habits in the active workspace, newest-deleted first — the source for the habits Trash. */
    val trashedHabits = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && it.trashed }.sortedByDescending { it.trashedAt ?: 0L } }.state(emptyList())
    // Check-ins are intentionally NOT workspace-scoped (a check-in has no workspaceId; it is reached through
    // its parent habit's workspace wherever scoping matters) — same behaviour as before the split.
    val habitCheckins = repo.allCheckins.state(emptyList())

    // ── Habit-science / life-systems tables (the coaching layer built on habits) ───────────────────────────
    val cravings = repo.allCravings.scopedBy { it.workspaceId }
    val witnessEvents = repo.allWitnessEvents.scopedBy { it.workspaceId }
    val scorecardItems = repo.allScorecardItems.scopedBy { it.workspaceId }
    val buddies = repo.allBuddies.scopedBy { it.workspaceId }
    val integrityReviews = repo.allIntegrityReviews.scopedBy { it.workspaceId }
    val experiments = repo.allExperiments.scopedBy { it.workspaceId }
    val escrows = repo.allEscrows.scopedBy { it.workspaceId }
    val nudgeEvents = repo.allNudgeEvents.scopedBy { it.workspaceId }

    // ── Habits-tab view-state + settings-backed setters + overlay flags (Stage 5-B) ───────────────────────
    /** Fusion F2: a habit pre-selected to Focus on; the Focus screen consumes it and auto-logs. */
    val pendingFocusHabitId = MutableStateFlow<String?>(null)
    // Matrix mode and density are persisted in settings, so the choice survives an app restart.
    val habitMatrixMode: StateFlow<Boolean> = app.settings.map { it.habitMatrixMode }.stateIn(scope, SharingStarted.Eagerly, false)
    val habitDensity: StateFlow<Int> = app.settings.map { it.habitDensity }.stateIn(scope, SharingStarted.Eagerly, 1)
    fun setHabitMatrixMode(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(habitMatrixMode = on)) }
    fun setHabitDensity(level: Int) = scope.launch { repo.saveSettings(app.settings.value.copy(habitDensity = level.coerceIn(0, 2))) }
    fun setHabitGroupByCategory(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(habitGroupByCategory = on)) }
    fun setHabitSort(mode: String) = scope.launch { repo.saveSettings(app.settings.value.copy(habitSort = mode)) }
    fun setHabitInsightsExpanded(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(habitInsightsExpanded = on)) }
    /** Persist a habit's time-planning config (HabitTime) into settings-JSON, keyed by habit id. */
    fun setHabitTimeCfg(habitId: String, cfg: com.todocompanion.app.domain.habit.HabitTime.Cfg) = scope.launch {
        if (habitId.isBlank()) return@launch
        val m = app.settings.value.habitTimeCfg.toMutableMap()
        if (com.todocompanion.app.domain.habit.HabitTime.isDefault(cfg)) m.remove(habitId)
        else m[habitId] = com.todocompanion.app.domain.habit.HabitTime.encodeCfg(cfg)
        repo.saveSettings(app.settings.value.copy(habitTimeCfg = m))
    }
    /** R108 — persist the minute-of-day a habit's flexible calendar block was dragged to (display placement
     *  only; not a reminder). Merges into the existing HabitTime cfg keyed by habit id. */
    fun setHabitBlockMinute(habitId: String, minute: Int) = scope.launch {
        if (habitId.isBlank()) return@launch
        val cur = com.todocompanion.app.domain.habit.HabitTime.cfgFor(app.settings.value, habitId)
        setHabitTimeCfg(habitId, cur.copy(blockMin = minute.coerceIn(0, 1439)))
    }
    val habitDetailId = MutableStateFlow<String?>(null)    // non-null → the analytics screen overlays the tab
    val habitBatchOpen = MutableStateFlow(false)
    val habitPresetOpen = MutableStateFlow(false)
    val habitEditor = MutableStateFlow<HabitEditRequest?>(null)   // non-null → the full-screen editor is open
    val habitQuickAddOpen = MutableStateFlow(false)               // L6: natural-language "type a habit" dialog
    val habitTrendsOpen = MutableStateFlow(false)                 // M5: full trends & correlations dashboard
    val habitArchiveOpen = MutableStateFlow(false)                // Archived habits + Trash management overlay

    // ── Habit CRUD / lifecycle (Stage 5-C) ────────────────────────────────────────────────────────────────
    /** Refresh the three habit-touching home-screen widgets (habits list, stats, and momentum, which folds in
     *  habit strength). Public so the parent's still-there check-in/Focus bridges reach it through a shim. */
    fun refreshHabitWidgets() {
        com.todocompanion.app.widget.HabitsWidget.refresh(app.appCtx)
        com.todocompanion.app.widget.HabitStatsWidget.refresh(app.appCtx)
        // R104 — the momentum score folds in habit strength, so keep it live on habit changes too.
        com.todocompanion.app.widget.MomentumWidget.refresh(app.appCtx)
    }
    fun createHabit(name: String, emoji: String?, colorArgb: Long?, target: Int, unit: String? = null, scheduleDays: String = "", reminderTimes: String = "") = scope.launch {
        repo.createHabit(name.trim(), emoji, colorArgb, target, activeWorkspace(), unit, scheduleDays, reminderTimes)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(app.appCtx)
    }
    fun saveHabit(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        repo.upsertHabit(h)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(app.appCtx)
    }
    /** Create from a fully-built habit (Tier I editor). Workspace defaults to the active one. */
    fun addHabit(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        repo.createHabit(h.copy(workspaceId = h.workspaceId.ifBlank { activeWorkspace() }))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        refreshHabitWidgets()
    }
    fun addHabits(habits: List<com.todocompanion.app.data.entity.HabitEntity>) = scope.launch {
        val ws = activeWorkspace()
        habits.forEach { repo.createHabit(it.copy(workspaceId = it.workspaceId.ifBlank { ws })) }
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        refreshHabitWidgets()
    }
    /** Soft-delete a habit to Trash (recoverable), with an Undo. History is preserved; restore is lossless. */
    fun trashHabit(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        repo.setHabitTrashed(h.id, true); refreshHabitWidgets()
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        app.undoEvents.tryEmit(UndoEvent(UndoKind.HABIT_TRASHED, h.id, "Habit moved to Trash", habitRestore = h))
    }
    /** Restore a trashed habit back to the active list. */
    fun restoreHabit(id: String) = scope.launch {
        repo.setHabitTrashed(id, false); refreshHabitWidgets()
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
    }
    /** Archive / unarchive a habit (kept out of the active list & analysis, never deleted), with an Undo. */
    fun setHabitArchived(h: com.todocompanion.app.data.entity.HabitEntity, archived: Boolean) = scope.launch {
        repo.setHabitArchived(h.id, archived); refreshHabitWidgets()
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        if (archived) app.undoEvents.tryEmit(UndoEvent(UndoKind.HABIT_ARCHIVED, h.id, "Habit archived", habitRestore = h))
    }
    /** Permanently erase a single trashed habit (Trash → Delete forever). */
    fun deleteHabit(id: String) = scope.launch {
        repo.deleteHabit(id); refreshHabitWidgets()
    }
    /** Permanently erase every trashed habit in the active workspace (Trash → Empty). */
    fun emptyHabitTrash() = scope.launch {
        repo.emptyHabitTrash(activeWorkspace()); refreshHabitWidgets()
    }
    fun setHabitOrder(ids: List<String>) = scope.launch { repo.setHabitOrder(ids); refreshHabitWidgets() }

    // ── Check-in / day-log / streak + the award-celebrate engine (Stage 5-D) ──────────────────────────────
    // N2: reward-unlock celebration — surfaced to the Habits screen (confetti + toast) and a notification.
    val rewardCelebration = MutableStateFlow<String?>(null)
    /** Public because the Focus→habit auto-credit bridge in AppViewModel's init also fires the celebration. */
    fun celebrateIfRewardReached(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long) {
        if (h.rewardText.isBlank() || h.rewardAtStreak <= 0 || h.habitType == "break") return
        // Only a live action *today* earns the celebration — backfilling a past day recomputes streaks but
        // must not pop the reward toast/notification (mirrors awardIfNewlyDone's today-gate).
        val t = today()
        if (epochDay != t) return
        scope.launch {
            val hs = com.todocompanion.app.domain.habit.HabitStats
            val forgiving = app.settings.value.forgivingStreaks
            val (done, skip, rel) = hs.daySets(h, repo.getHabitCheckinsOnce())   // R108 audit C2
            val streakToday = hs.displayStreak(h, done, skip, rel, t, forgiving)
            val streakYesterday = hs.displayStreak(h, done, skip, rel, t - 1, forgiving)
            if (streakToday >= h.rewardAtStreak && streakYesterday < h.rewardAtStreak) {
                com.todocompanion.app.reminders.Notifications.showReward(app.appCtx, h.name, h.rewardText, streakToday)
                rewardCelebration.value = h.rewardText
            }
        }
    }
    /** A day cannot be logged before the habit began — no "history" earlier than the habit itself. */
    private fun beforeStart(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long): Boolean {
        if (epochDay >= h.startEpochDay(zone)) return false
        app.toast("You can only log from the day “${h.name}” started.")
        return true
    }
    fun cycleHabit(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, current: Int) = scope.launch {
        if (beforeStart(h, epochDay)) return@launch
        repo.cycleCheckin(h.id, epochDay, h.targetPerDay, current, h.clickIncrement, h.extraTarget)
        refreshHabitWidgets(); celebrateIfRewardReached(h, epochDay); awardIfNewlyDone(h, epochDay, current)
    }
    /** Numeric / exact value entry for a day (also used to record a break-habit relapse amount). */
    fun setHabitValue(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, count: Int) = scope.launch {
        if (beforeStart(h, epochDay)) return@launch
        val old = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == epochDay }?.count ?: 0
        repo.setCheckinValue(h.id, epochDay, count); refreshHabitWidgets(); celebrateIfRewardReached(h, epochDay); awardIfNewlyDone(h, epochDay, old)
    }
    /** R33 F6 — the "shine" pulse surfaced to the Habits screen when a habit is completed. The [HabitShine]
     *  data class stays nested on AppViewModel (HabitsScreen references it by name); only the flow lives here. */
    val habitShine = MutableStateFlow<AppViewModel.HabitShine?>(null)
    /** V4/V12: when a build habit crosses into "done", earn a momentum point, celebrate, and — R33 F10 —
     *  ramp the target up if the plan says consistency has held. Public for the Focus→habit init bridge. */
    suspend fun awardIfNewlyDone(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, oldCount: Int) {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        if (h.habitType == "break") return
        val newCount = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == epochDay }?.count ?: 0
        if (hs.meetsGoal(h, newCount) && !hs.meetsGoal(h, oldCount)) {
            val isToday = epochDay == java.time.LocalDate.now(zone).toEpochDay()
            if (isToday) repo.awardPoints(1)
            // Wave 2 · Habit Practice Journal — if this habit has a bound journal note, append today's line.
            if (isToday) repo.appendHabitJournalEntry(h.id, epochDay)
            // R35 · reward taper — a graduated habit has eased off celebration; it runs on its own now.
            if (!h.graduated && isToday) {
                val phrase = h.encouragementList().takeIf { it.isNotEmpty() }?.random()
                    ?: listOf(
                        "Yes! That's a vote for who you're becoming.",
                        "Nailed it. 💪",
                        "That's who you are now.",
                        "Done — small wins compound.",
                        "Look at you go. ✨",
                        "Kept the promise to yourself.",
                        "That's the one. 🔥",
                        "Another brick laid.",
                    ).random()
                habitShine.value = AppViewModel.HabitShine(h.name, h.emoji, phrase, h.colorArgb)
            }
            // F10 auto ramp-up — bump the daily target once consistency holds over the step window.
            if (epochDay == java.time.LocalDate.now(zone).toEpochDay()) {
                val done = repo.getHabitCheckinsOnce().filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                com.todocompanion.app.domain.habit.HabitBuilder.rampNextTarget(h, done, epochDay)?.let { nt ->
                    repo.upsertHabit(h.copy(targetPerDay = nt, rampLastStepDay = epochDay))
                    app.toast("You've been consistent — ${h.name} nudged up to $nt${h.unit?.let { " $it" } ?: ""}/day")
                }
            }
        }
    }
    /** Tick a habit for today — the same check-off path the Habits screen uses (setHabitValue). */
    fun completeHabitToday(habitId: String) {
        val h = habits.value.firstOrNull { it.id == habitId } ?: return
        setHabitValue(h, java.time.LocalDate.now(zone).toEpochDay(), h.targetPerDay.coerceAtLeast(1))
    }
    // LS2 context capture at check-in
    fun setCheckinContext(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, energy: Int, mood: Int, place: String) = scope.launch {
        repo.setCheckinContext(h.id, epochDay, energy.coerceIn(0, 5), mood.coerceIn(0, 5), place.trim())
    }
    // TW-F make-up ledger — repay a missed non-negotiable by completing a past expected day.
    fun logMakeUp(h: com.todocompanion.app.data.entity.HabitEntity, day: Long) = scope.launch {
        repo.setDay(h.id, day, h.targetPerDay.coerceAtLeast(1), "done", "make-up")
        refreshHabitWidgets()
        app.toast("Made up ${java.time.LocalDate.ofEpochDay(day)}. Debt cleared — not a failure.")
    }
    /** Streak-repair token: a deliberate opt-in tap only; never auto-consumed. Capped per period, gated on
     *  tokens remaining. Records the repaired day as a settings-side overlay (no DB day is fabricated). */
    fun keepStreak(repairDay: Long) = scope.launch {
        val s = repo.settingsSnapshot()
        val period = com.todocompanion.app.domain.ReviewCadence.periodKey(today())
        val available = com.todocompanion.app.domain.ReviewCadence.tokensForPeriod(s.streakRepairTokens, s.streakRepairPeriod, period)
        if (available <= 0) return@launch
        val repaired = (s.repairedDaysCsv.split(",").mapNotNull { it.trim().toLongOrNull() } + repairDay).distinct()
        repo.saveSettings(s.copy(
            streakRepairTokens = (available - 1).coerceAtLeast(0),
            streakRepairPeriod = period,
            repairedDaysCsv = repaired.joinToString(","),
        ))
    }
    fun skipHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, reason: String = "") = scope.launch {
        if (beforeStart(h, epochDay)) return@launch
        repo.skipDay(h.id, epochDay, reason); refreshHabitWidgets()
    }
    /** N6: log a break-habit slip with an optional trigger (kept in the day's note for a trigger breakdown). */
    fun logSlip(h: com.todocompanion.app.data.entity.HabitEntity, trigger: String) = scope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val existing = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == today }
        val count = (existing?.count ?: 0) + 1
        val note = (existing?.reason?.takeIf { it.isNotBlank() }?.plus("; ") ?: "") + trigger.trim().ifBlank { "slip" }
        repo.setDay(h.id, today, count, "done", note)
        refreshHabitWidgets()
    }
    fun clearHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long) = scope.launch {
        repo.clearCheckin(h.id, epochDay); refreshHabitWidgets()
    }
    /** Write a whole day from the per-day editor: value, done/skip, and a free-text note. */
    fun setHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, count: Int, status: String, note: String) = scope.launch {
        if (beforeStart(h, epochDay)) return@launch
        repo.setDay(h.id, epochDay, count, status, note); refreshHabitWidgets()
    }
    /** K2: spend one earned freeze to protect a missed day. */
    fun spendHabitFreeze(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, onDone: (Boolean) -> Unit = {}) = scope.launch {
        val ok = repo.spendFreeze(h.id, epochDay); refreshHabitWidgets(); onDone(ok)
    }
    /** K5: attach a photo to a day — the picked image is downscaled and copied (encrypted) into app storage. */
    fun setHabitPhoto(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, uri: android.net.Uri?) = scope.launch {
        if (uri == null) { repo.setCheckinPhoto(h.id, epochDay, null); refreshHabitWidgets(); return@launch }
        val path = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = app.appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val maxDim = 1280
                val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1))
                val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
                val dir = java.io.File(app.appCtx.filesDir, "habit_photos").apply { mkdirs() }
                val f = java.io.File(dir, java.util.UUID.randomUUID().toString() + ".jpg")
                com.todocompanion.app.data.security.FileVault.writeEncrypted(f, out.toByteArray())   // SEC-1: at-rest encrypted
                f.absolutePath
            }.getOrNull()
        }
        if (path != null) { repo.setCheckinPhoto(h.id, epochDay, path); refreshHabitWidgets() } else app.toast("Couldn't read that image")
    }
    fun setHabitPaused(h: com.todocompanion.app.data.entity.HabitEntity, paused: Boolean) = scope.launch {
        repo.setHabitPaused(h.id, paused); com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo); refreshHabitWidgets()
    }
    fun pauseAllHabits(paused: Boolean) = scope.launch {
        repo.pauseAllHabits(activeWorkspace(), paused); com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo); refreshHabitWidgets()
    }
}
