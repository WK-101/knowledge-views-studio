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
    app: AppViewModel,
    scope: CoroutineScope,
    private val repo: AppRepository,
) : FeatureViewModel(app, scope) {
    // activeWs / activeWorkspace() / state() / scopedBy() are inherited from FeatureViewModel now (#16).
    // Day-rollover zone + "today" epoch-day forward to the parent's settings-aware helpers, so a check-in
    // recorded here honours the same "day starts at" rollover as everywhere else.
    private val zone: java.time.ZoneId get() = app.zoneId
    private fun today(): Long = app.today()

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
    /** T1: a custom timer duration (minutes) chosen in the inline timer sheet; the Focus screen reads it
     *  once alongside [pendingFocusHabitId] instead of defaulting to the habit's target, then clears it. */
    val pendingFocusHabitMinutes = MutableStateFlow<Int?>(null)
    /** W2: a habit whose amount-entry popup should open (e.g. from a Habit Zero widget tap on a numeric
     *  habit). The Habits screen consumes it to show the same NumericEntryDialog the ring tap uses. */
    val pendingValueHabitId = MutableStateFlow<String?>(null)
    // Matrix mode and density are persisted in settings, so the choice survives an app restart.
    // Pure UI projections (only the Habits tab collects them; no imperative .value read), so WhileSubscribed
    // lets them stop when that tab is off-screen instead of staying warm for the app's whole lifetime.
    val habitMatrixMode: StateFlow<Boolean> = app.settings.map { it.habitMatrixMode }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)
    val habitDensity: StateFlow<Int> = app.settings.map { it.habitDensity }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), 1)
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
    /** Refresh every habit-touching home-screen widget. Delegates to the single central fan-out in
     *  [com.todocompanion.app.widget.Widgets] so the list can't drift from the one in WidgetRefresh.
     *  Public so the parent's still-there check-in/Focus bridges reach it through a shim. */
    fun refreshHabitWidgets() = com.todocompanion.app.widget.Widgets.refreshHabitWidgets(app.appCtx)
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

    // ── R33/R34 habit-builder + life-systems coach actions (Stage 5-E1) ───────────────────────────────────
    /** F9 — spend a streak-freeze token to protect a specific missed day (logged as a neutral skip). */
    fun useFreeze(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long) = scope.launch {
        if (h.freezeTokens <= 0) { app.toast("No streak freezes left — earn one with an overachieving day."); return@launch }
        repo.setDay(h.id, epochDay, 0, "skip", "❄ streak freeze")
        repo.upsertHabit(h.copy(freezeTokens = h.freezeTokens - 1))
        app.toast("Streak protected ❄")
    }
    /** F12 — tap a daily pledge on a quit habit (a tiny recommitment ritual). */
    fun pledgeToday(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        repo.upsertHabit(h.copy(lastPledgeDay = today))
        app.toast("Pledged for today. One day at a time.")
    }
    /** F12 — (re)start the clean-time clock for a quit habit from now. */
    fun startQuitClock(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        repo.upsertHabit(h.copy(quitSinceMillis = System.currentTimeMillis()))
        app.toast("Clean-time started. Day one.")
    }
    /** F13 / LS10 — log an urge/craving after surfing it (or slipping), with optional HALT state and duration. */
    fun logCraving(h: com.todocompanion.app.data.entity.HabitEntity, intensity: Int, trigger: String, surfed: Boolean, halt: String = "", durationSec: Int = 0) = scope.launch {
        val now = System.currentTimeMillis()
        val d = java.time.Instant.ofEpochMilli(now).atZone(zone)
        repo.upsertCraving(com.todocompanion.app.data.entity.CravingEventEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = h.id, atMillis = now,
            epochDay = d.toLocalDate().toEpochDay(), minuteOfDay = d.hour * 60 + d.minute,
            intensity = intensity.coerceIn(1, 5), trigger = trigger.trim(), surfed = surfed,
            halt = halt, durationSec = durationSec.coerceAtLeast(0), workspaceId = activeWorkspace(),
        ))
        if (!surfed) logSlip(h, trigger.ifBlank { "urge" })
        app.toast(if (surfed) "You rode it out 🌊 Nicely done." else "Logged. A slip isn't a relapse — back on it.")
    }
    fun deleteCraving(id: String) = scope.launch { repo.deleteCraving(id) }
    /** F16 — start a guided journey: create its habits with staggered start dates so each unlocks on its day. */
    fun startJourney(j: com.todocompanion.app.domain.habit.HabitJourneys.Journey) = scope.launch {
        if (repo.getHabitsOnce().any { it.journeyKey == j.key && !it.archived }) { app.toast("You're already on “${j.name}”."); return@launch }
        val today = java.time.LocalDate.now(zone)
        var order = (repo.getHabitsOnce().maxOfOrNull { it.sortOrder } ?: 0.0)
        j.steps.forEach { s ->
            order += 1.0
            val start = today.plusDays(s.dayOffset.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            repo.upsertHabit(com.todocompanion.app.data.entity.HabitEntity(
                id = java.util.UUID.randomUUID().toString(), name = s.name, emoji = s.emoji,
                targetPerDay = s.target.coerceAtLeast(1), unit = s.unit, createdAt = System.currentTimeMillis(),
                startDate = start, sortOrder = order, description = s.why, journeyKey = j.key,
                workspaceId = activeWorkspace(),
            ))
        }
        app.toast("Started “${j.name}” — step one is ready today.")
        refreshHabitWidgets()
    }
    fun setChronotype(i: Int) = scope.launch { repo.saveSettings(app.settings.value.copy(chronotype = i.coerceIn(0, 2))) }
    fun setCalmMode(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(calmMode = on)) }
    /** Smart habit reminders: adaptive timing + one gentle follow-up. Re-arm alarms so it takes effect now. */
    fun setSmartReminders(on: Boolean) = scope.launch {
        repo.saveSettings(app.settings.value.copy(habitSmartReminders = on))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
    }
    fun addReward(text: String) = scope.launch {
        val t = text.trim(); if (t.isBlank()) return@launch
        if (t !in app.settings.value.rewardMenu) repo.saveSettings(app.settings.value.copy(rewardMenu = app.settings.value.rewardMenu + t))
    }
    fun removeReward(text: String) = scope.launch { repo.saveSettings(app.settings.value.copy(rewardMenu = app.settings.value.rewardMenu - text)) }
    // LS5 values → systems → habits (the CoreValue read-model flow stays on AppViewModel; forwarded via app).
    fun saveValue(id: String?, name: String, emoji: String?, colorArgb: Long?, statement: String) = scope.launch {
        val existing = id?.let { vid -> app.coreValues.value.firstOrNull { it.id == vid } }
        val order = existing?.orderIndex ?: ((app.coreValues.value.maxOfOrNull { it.orderIndex } ?: 0) + 1)
        repo.upsertCoreValue(
            (existing ?: com.todocompanion.app.data.entity.CoreValueEntity(id = java.util.UUID.randomUUID().toString(), name = name, orderIndex = order, createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
                .copy(name = name.trim().ifBlank { "Value" }, emoji = emoji, colorArgb = colorArgb, statement = statement.trim())
        )
    }
    fun deleteValue(id: String) = scope.launch {
        repo.deleteCoreValue(id)
        // Detach any habits pointing at it, so no dangling reference remains.
        repo.getHabitsOnce().filter { it.valueId == id }.forEach { repo.upsertHabit(it.copy(valueId = null)) }
    }
    fun assignHabitValue(h: com.todocompanion.app.data.entity.HabitEntity, valueId: String?) = scope.launch { repo.upsertHabit(h.copy(valueId = valueId)) }
    // LS · habit scorecard
    fun addScorecardItem(text: String, sign: Int) = scope.launch {
        val t = text.trim(); if (t.isBlank()) return@launch
        val order = (scorecardItems.value.maxOfOrNull { it.orderIndex } ?: 0) + 1
        repo.upsertScorecardItem(com.todocompanion.app.data.entity.ScorecardItemEntity(java.util.UUID.randomUUID().toString(), t, sign.coerceIn(-1, 1), order, System.currentTimeMillis(), workspaceId = activeWorkspace()))
    }
    fun setScorecardSign(item: com.todocompanion.app.data.entity.ScorecardItemEntity, sign: Int) = scope.launch { repo.upsertScorecardItem(item.copy(sign = sign.coerceIn(-1, 1))) }
    fun deleteScorecardItem(id: String) = scope.launch { repo.deleteScorecardItem(id) }
    /** Turn a scorecard behaviour into a habit: a "+" becomes one to build, a "−" one to break. */
    fun scorecardToHabit(item: com.todocompanion.app.data.entity.ScorecardItemEntity) = scope.launch {
        if (item.sign == 0) { app.toast("Tag it good (+) or bad (−) first."); return@launch }
        val order = (repo.getHabitsOnce().maxOfOrNull { it.sortOrder } ?: 0.0) + 1
        repo.upsertHabit(com.todocompanion.app.data.entity.HabitEntity(
            id = java.util.UUID.randomUUID().toString(), name = item.text.trim(),
            habitType = if (item.sign > 0) "build" else "break",
            targetComparison = if (item.sign > 0) "atleast" else "atmost",
            targetPerDay = if (item.sign > 0) 1 else 0, sortOrder = order,
            createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace(),
        ))
        app.toast(if (item.sign > 0) "Added “${item.text}” as a habit to build." else "Added “${item.text}” as a habit to break.")
        refreshHabitWidgets()
    }
    // LS7 commitment contract + witness sign-off
    fun addWitness(h: com.todocompanion.app.data.entity.HabitEntity, milestoneLabel: String, note: String) = scope.launch {
        val ref = h.refereeName.trim(); if (ref.isBlank()) { app.toast("Name a referee in the habit's editor first."); return@launch }
        repo.upsertWitness(com.todocompanion.app.data.entity.WitnessEventEntity(
            java.util.UUID.randomUUID().toString(), h.id, ref, milestoneLabel.trim().ifBlank { "Milestone" }, System.currentTimeMillis(), note.trim(), workspaceId = activeWorkspace()))
        app.toast("$ref witnessed it ✍️")
    }
    fun deleteWitness(id: String) = scope.launch { repo.deleteWitness(id) }
    // LS7 self-forfeit + akrasia horizon
    /** A derail happened — escalate the forfeit level (each repeat raises the stake). */
    fun escalateForfeit(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        repo.upsertHabit(h.copy(forfeitLevel = h.forfeitLevel + 1))
        app.toast("Forfeit owed" + (h.forfeitText.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + ". Level ${h.forfeitLevel + 1}.")
    }
    /** Queue a "make it easier" change — it only takes effect after a one-week akrasia horizon. */
    fun queueEase(h: com.todocompanion.app.data.entity.HabitEntity, newTarget: Int) = scope.launch {
        val applyAt = System.currentTimeMillis() + 7L * 24 * 3600 * 1000
        repo.upsertHabit(h.copy(pendingEaseMillis = applyAt, pendingEaseTarget = newTarget.coerceAtLeast(0)))
        app.toast("Change queued — it applies in 7 days. No easing in the heat of the moment.")
    }
    fun cancelEase(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch { repo.upsertHabit(h.copy(pendingEaseMillis = 0, pendingEaseTarget = 0)) }
    /** Apply a queued easing whose horizon has passed (called when the detail screen opens). */
    fun applyPendingEaseIfDue(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        if (h.pendingEaseMillis in 1..System.currentTimeMillis()) {
            repo.upsertHabit(h.copy(targetPerDay = h.pendingEaseTarget.coerceAtLeast(if (h.habitType == "break") 0 else 1), pendingEaseMillis = 0, pendingEaseTarget = 0))
        }
    }

    // ── Buddy digest · integrity ledger · third-wave · reminder-drift · experiments (Stage 5-E2) ──────────
    fun exportBuddyDigest(name: String): String {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val digest = com.todocompanion.app.domain.habit.LifeSystems.buildDigest(name.ifBlank { "Me" }, habits.value, habitCheckins.value, today, app.settings.value.forgivingStreaks)
        return kotlinx.serialization.json.Json.encodeToString(com.todocompanion.app.domain.habit.LifeSystems.BuddyDigest.serializer(), digest)
    }
    fun importBuddyDigest(json: String) = scope.launch {
        val digest = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(com.todocompanion.app.domain.habit.LifeSystems.BuddyDigest.serializer(), json) }.getOrNull()
        if (digest == null) { app.toast("That doesn't look like a buddy digest."); return@launch }
        repo.upsertBuddy(com.todocompanion.app.data.entity.BuddySnapshotEntity(java.util.UUID.randomUUID().toString(), digest.name, System.currentTimeMillis(), json, workspaceId = activeWorkspace()))
        app.toast("Imported ${digest.name}'s progress 🤝")
    }
    fun deleteBuddy(id: String) = scope.launch { repo.deleteBuddy(id) }
    // LS6 save an integrity-review reflection
    fun saveIntegrityReview(kind: String, periodKey: String, note: String, statsJson: String) = scope.launch {
        repo.upsertIntegrityReview(com.todocompanion.app.data.entity.IntegrityReviewEntity(
            java.util.UUID.randomUUID().toString(), kind, periodKey, System.currentTimeMillis(), note.trim(), statsJson, workspaceId = activeWorkspace()))
        app.toast("Review saved to your ledger.")
    }
    fun deleteIntegrityReview(id: String) = scope.launch { repo.deleteIntegrityReview(id) }
    // R35 · third-wave toggles
    fun setBookends(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(bookendsEnabled = on)) }
    fun setCompanion(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(companionEnabled = on)) }
    fun setStrengthMeter(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(strengthMeter = on)) }
    // TW-B self-tuning reminder — accept the suggested time.
    fun applyReminderDrift(h: com.todocompanion.app.data.entity.HabitEntity, minute: Int) = scope.launch {
        val others = h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }
        val typical = com.todocompanion.app.domain.habit.HabitStats.typicalDoneMinute(repo.getHabitCheckinsOnce().filter { it.habitId == h.id })
        val replaced = if (others.isEmpty()) listOf(minute) else {
            val nearest = others.minByOrNull { kotlin.math.abs(it - (typical ?: minute)) }
            (others - (nearest ?: minute) + minute).distinct().sorted()
        }
        repo.upsertHabit(h.copy(reminderTimes = replaced.joinToString(",")))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        app.toast("Reminder moved to ${com.todocompanion.app.domain.habit.HabitStats.minuteLabel(minute)}.")
    }
    // TW-D reward taper — graduate / un-graduate a habit that's reached automaticity.
    fun setGraduated(h: com.todocompanion.app.data.entity.HabitEntity, on: Boolean) = scope.launch {
        repo.upsertHabit(h.copy(graduated = on))
        app.toast(if (on) "🎓 Graduated — this one's part of you now. Prompts will ease off." else "Back to active coaching.")
    }
    // TW-C n-of-1 experiments.
    fun startExperiment(habitId: String, outcome: String, blockLen: Int, blocks: Int) = scope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        repo.upsertExperiment(com.todocompanion.app.data.entity.ExperimentEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = habitId, outcome = outcome,
            startDay = today, blockLenDays = blockLen.coerceIn(1, 14), blocks = blocks.coerceIn(2, 12), createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
        app.toast("Experiment started. Follow the on/off blocks and log your ${outcome}.")
    }
    fun endExperiment(e: com.todocompanion.app.data.entity.ExperimentEntity) = scope.launch { repo.upsertExperiment(e.copy(active = false)) }
    fun deleteExperiment(id: String) = scope.launch { repo.deleteExperiment(id) }

    // ── Stage 5-F · the remaining habit-owned surface (share card, mute, strength/graded, WIP,
    //    escrow, nudge MRT, receptivity) — moved verbatim off AppViewModel behind forwarding shims ──────
    /** Render + share an on-device "progress card" PNG for one habit (0 network, 0 permission). */
    fun shareHabitProgress(h: com.todocompanion.app.data.entity.HabitEntity, onDone: (String?) -> Unit) = scope.launch {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val cks = habitCheckins.value.filter { it.habitId == h.id }
        val (done, skip, relapse) = hs.daySets(h, cks)   // R108 audit C2 — one canonical derivation
        val today = today()
        val strength = hs.strength(h, done, skip, relapse, today)
        val cur = hs.currentStreak(h, done, skip, relapse, today)
        val best = hs.bestStreak(h, done, skip, relapse, today)
        val total = if (h.unit != null) cks.sumOf { it.count } else done.size
        val safe = h.name.filter { it.isLetterOrDigit() }.take(20).ifBlank { "habit" }
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.render(h.emoji, h.name, h.colorArgb, strength, cur, best, h.unit, total, done, skip, today)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(app.appCtx, bmp, "todo-companion-$safe-progress.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(app.appCtx, it) }
        onDone(res.savedLocation)
    }

    /**
     * Render + share one of a set of on-device habit cards — "progress" (strength ring + full-year
     * heatmap), "stats" (a stat grid that also surfaces the weekly-target streak and banked freezes),
     * or "week" (this week's Mon–Sun check row). All 0-network, 0-permission.
     */
    fun shareHabitCard(h: com.todocompanion.app.data.entity.HabitEntity, variant: String, onDone: (String?) -> Unit) = scope.launch {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val cks = habitCheckins.value.filter { it.habitId == h.id }
        val (done, skip, relapse) = hs.daySets(h, cks)
        val today = today()
        val safe = h.name.filter { it.isLetterOrDigit() }.take(20).ifBlank { "habit" }
        val res = withContext(Dispatchers.IO) {
            val bmp = when (variant) {
                "stats" -> {
                    val strength = hs.strength(h, done, skip, relapse, today)
                    val cur = hs.currentStreak(h, done, skip, relapse, today)
                    val best = hs.bestStreak(h, done, skip, relapse, today)
                    val rate = Math.round(hs.rate(h, done, skip, today, 30) * 100f)
                    val total = if (h.unit != null) cks.sumOf { it.count } else done.size
                    val weekly = h.freqType == com.todocompanion.app.domain.habit.HabitStats.FREQ_TIMES_WEEK
                    val stats = buildList {
                        add("Strength" to "$strength")
                        add((if (weekly) "Weekly streak" else "Streak") to "$cur")
                        add("Best streak" to "$best")
                        add("Consistency" to "$rate%")
                        add("All-time" to "$total")
                        if (h.freezeTokens > 0) add("Freezes" to "❄️ ${h.freezeTokens}")
                        else {
                            val wr = hs.weekdayRates(done, skip, today, 180)
                            val bi = wr.indices.filter { wr[it] > 0f }.maxByOrNull { wr[it] }
                            val bd = bi?.let { java.time.DayOfWeek.of(it + 1).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) } ?: "—"
                            add("Best day" to bd)
                        }
                    }
                    com.todocompanion.app.util.ProgressCard.renderStatsCard(
                        ((h.emoji?.plus(" ")) ?: "") + h.name, hs.frequencyLabel(h), stats, h.colorArgb)
                }
                "week" -> {
                    val dow = java.time.LocalDate.ofEpochDay(today).dayOfWeek.value // 1..7 (Mon..Sun)
                    val monday = today - (dow - 1)
                    val marks = IntArray(7) { i ->
                        val d = monday + i
                        when { d > today -> 0; d in done -> 1; d in skip -> 2; else -> 0 }
                    }
                    val weekCount = (0..6).count { (monday + it) in done }
                    val weekly = h.freqType == com.todocompanion.app.domain.habit.HabitStats.FREQ_TIMES_WEEK
                    val label = if (weekly) "of ${h.freqParam} this week" else "days this week"
                    val cur = hs.currentStreak(h, done, skip, relapse, today)
                    com.todocompanion.app.util.ProgressCard.renderHabitWeek(h.emoji, h.name, h.colorArgb, marks, weekCount, label, cur)
                }
                else -> {
                    val strength = hs.strength(h, done, skip, relapse, today)
                    val cur = hs.currentStreak(h, done, skip, relapse, today)
                    val best = hs.bestStreak(h, done, skip, relapse, today)
                    val total = if (h.unit != null) cks.sumOf { it.count } else done.size
                    com.todocompanion.app.util.ProgressCard.render(h.emoji, h.name, h.colorArgb, strength, cur, best, h.unit, total, done, skip, today)
                }
            }
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(app.appCtx, bmp, "todo-companion-$safe-$variant.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(app.appCtx, it) }
        onDone(res.savedLocation)
    }

    fun toggleMutedHabit(id: String) = scope.launch {
        val cur = app.settings.value.mutedHabits
        val muting = id !in cur
        repo.saveSettings(app.settings.value.copy(mutedHabits = if (muting) cur + id else cur - id))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        // Y2: quietly guard the keystone — warn before silencing your highest-leverage habit.
        if (muting && app.keystoneHabitId() == id) app.toast("Heads up — this is your keystone habit")
    }

    // Z8 · graded strength — partial credit for a sub-goal "done" day, behind an opt-in.
    private fun gradedCreditFor(h: com.todocompanion.app.data.entity.HabitEntity, hc: List<com.todocompanion.app.data.entity.HabitCheckinEntity>): Map<Long, Double> {
        if (h.habitType == "break") return emptyMap()
        val target = h.targetPerDay.coerceAtLeast(1)
        val hs = com.todocompanion.app.domain.habit.HabitStats
        return hc.filter { it.status == "done" && !hs.meetsGoal(h, it.count) && it.count > 0 }
            .associate { it.epochDay to (it.count.toDouble() / target).coerceIn(0.0, 0.99) }
    }
    /** The strength score honouring the graded-credit opt-in (Z8) when it's on. */
    fun strengthOf(h: com.todocompanion.app.data.entity.HabitEntity): Int {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val hc = habitCheckins.value.filter { it.habitId == h.id }
        val (done, skip, relapse) = hs.daySets(h, hc)   // R108 audit C2
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val graded = if (app.settings.value.gradedStrength) gradedCreditFor(h, hc) else emptyMap()
        return hs.strength(h, done, skip, relapse, today, gradedCredit = graded)
    }
    /** Z8 preview — average strength across active build habits, binary vs graded, for the opt-in. */
    fun gradedStrengthPreview(): Pair<Int, Int>? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val active = habits.value.filter { !it.archived && !it.paused && it.habitType != "break" }
        if (active.isEmpty()) return null
        val binary = ArrayList<Int>(); val graded = ArrayList<Int>()
        active.forEach { h ->
            val hc = habitCheckins.value.filter { it.habitId == h.id }
            val (done, skip, relapse) = hs.daySets(h, hc)   // R108 audit C2
            binary += hs.strength(h, done, skip, relapse, today)
            graded += hs.strength(h, done, skip, relapse, today, gradedCredit = gradedCreditFor(h, hc))
        }
        return binary.average().toInt() to graded.average().toInt()
    }
    fun setGradedStrength(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(gradedStrength = on)) }

    // FW-5 New-Habit WIP limiter.
    fun setHabitWipLimit(n: Int) = scope.launch { repo.saveSettings(app.settings.value.copy(habitWipLimit = n.coerceIn(0, 20))) }

    // FW-9 Self-escrow contingency reward.
    fun addEscrow(habitId: String?, description: String, kind: String, milestoneKind: String, milestoneValue: Int) = scope.launch {
        val d = description.trim(); if (d.isBlank()) return@launch
        repo.upsertEscrow(com.todocompanion.app.data.entity.EscrowEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = habitId, description = d, kind = kind,
            milestoneKind = milestoneKind, milestoneValue = milestoneValue.coerceAtLeast(1), createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
        app.toast(if (kind == "stake") "Stake locked. It's real now — reach the milestone or it's forfeit." else "Reward escrowed. Earn it at the milestone.")
    }
    fun releaseEscrow(e: com.todocompanion.app.data.entity.EscrowEntity, redeem: Boolean) = scope.launch {
        repo.upsertEscrow(e.copy(released = true, redeemed = redeem))
        app.toast(when {
            e.kind == "stake" && redeem -> "Stake paid. The contract held."
            e.kind == "stake" -> "Stake returned — you made it."
            redeem -> "Enjoy it — you earned this one. 🎉"
            else -> "Banked for later."
        })
    }
    fun deleteEscrow(id: String) = scope.launch { repo.deleteEscrow(id) }

    // FW-14 Personal Nudge MRT — record that an opportunity nudge (variant v) was shown for a habit today,
    // and reconcile past open impressions against whether the habit was completed.
    fun logNudgeShown(habitId: String, variant: Int, day: Long) = scope.launch {
        if (repo.nudgeForHabitDay(habitId, day) != null) return@launch   // one impression per habit per day
        repo.upsertNudgeEvent(com.todocompanion.app.data.entity.NudgeEventEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = habitId, variant = variant, epochDay = day,
            createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
    }
    /** Mark open nudge impressions from the last two weeks as acted/not, by whether the target (habit or
     *  task) was completed that day. R37: extended to task-reminder impressions (targetKind = "task"). */
    fun reconcileNudges() = scope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val open = repo.openNudgesSince(today - 14)
        if (open.isEmpty()) return@launch
        val checkins = repo.getHabitCheckinsOnce()
        val habitsById = repo.getHabitsOnce().associateBy { it.id }
        open.forEach { ev ->
            if (ev.epochDay >= today) return@forEach   // only reconcile past days
            val done = if (ev.targetKind == "task") {
                val t = repo.getTask(ev.habitId)
                t?.completed == true && t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } == ev.epochDay
            } else {
                val h = habitsById[ev.habitId] ?: return@forEach
                checkins.any { it.habitId == ev.habitId && it.epochDay == ev.epochDay && it.status == "done" && com.todocompanion.app.domain.habit.HabitStats.meetsGoal(h, it.count) }
            }
            if (done) repo.upsertNudgeEvent(ev.copy(acted = true))
        }
    }

    // R37 · habit-science receptivity timing (the habit half of the port pair).
    fun setReceptivityTiming(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(receptivityTiming = on)) }

    /** Substring search across habit name/description/identity/category/notes/unit (archived included so a
     *  habit is never unfindable), sorted by the user's manual order. Feeds the whole-app search surface. */
    fun searchHabits(query: String): List<com.todocompanion.app.data.entity.HabitEntity> {
        val q = query.trim().lowercase().removePrefix("#").removePrefix("@")
        if (q.isBlank()) return emptyList()
        return habitsWithArchived.value.filter { h ->
            h.name.lowercase().contains(q) || h.description.lowercase().contains(q) ||
                h.identity.lowercase().contains(q) || h.category.lowercase().contains(q) ||
                h.notes.lowercase().contains(q) || (h.unit?.lowercase()?.contains(q) == true)
        }.sortedBy { it.sortOrder }
    }
}
