package com.todocompanion.app.data

import com.todocompanion.app.data.entity.ChecklistItemEntity
import com.todocompanion.app.data.entity.toDomain
import com.todocompanion.app.data.entity.toEntity
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.FilterEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.FlagEntity
import com.todocompanion.app.data.entity.TemplateEntity
import com.todocompanion.app.data.entity.TemplateTask
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.SettingEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.data.entity.WorkspaceEntity
import com.todocompanion.app.data.entity.AttachmentEntity
import com.todocompanion.app.data.entity.AttachmentMeta
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.port.Backup
import com.todocompanion.app.domain.port.BackupFile
import com.todocompanion.app.data.security.SecurePrefs
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/** Single source of truth over Room. Reads are reactive Flows; writes are suspend.
 *  [appContext] (supplied in production, null in tests) lets the settings path route device-local secrets
 *  — currently the sync/backup passphrase — through the KeyStore-wrapped [SecurePrefs] instead of storing
 *  them in the DB settings table. When null, the old in-table behaviour is preserved. */
class AppRepository(private val db: AppDatabase, private val appContext: android.content.Context? = null) {

    /**
     * A habit auto-credited by a finished time interval (Focus / timer / QS tile / automation).
     * The VM collects this so a *timed* completion travels the same celebration path as a manual tap —
     * shine + momentum point + auto-ramp — no matter which surface stopped the timer.
     */
    data class HabitCreditEvent(val habitId: String, val epochDay: Long, val oldCount: Int)
    val habitCredited = kotlinx.coroutines.flow.MutableSharedFlow<HabitCreditEvent>(extraBufferCapacity = 16)

    /**
     * F1 (task-editor audit) — the single "this task's dates/state changed, re-arm its reminders" signal.
     *
     * A relative reminder (relativeToDue / relativeToStart / relativeToDeadline / dueDayAt / whenOverdue /
     * random) stores an offset and computes its fire time from the task's dates *at arm time*; `saveTask`
     * (and the other task writes below) never touch alarms. So every write that moves a date — or flips a
     * flag that gates scheduling (completed / abandoned / trashed / someday) — emits the task id here, and
     * the ViewModel drains it into ReminderController.rescheduleForTask. Centralising it this way means the
     * re-arm guarantee holds *by construction*: no date-mutating call site (postpone, snooze, calendar
     * drag, carry-forward, auto-schedule, Someday, recurrence roll-forward, …) has to remember to re-arm.
     * A generous buffer covers batch reschedules (carry-forward of many overdue tasks in one tick).
     */
    // D2/N7 — an UNBOUNDED channel, not a buffered SharedFlow: a huge one-tick bulk reschedule can emit
    // more ids than any fixed buffer holds, and a dropped id means a stale alarm. trySend on an unlimited
    // channel never drops. The VM drains it (receiveAsFlow) into ReminderController.rescheduleForTask.
    private val _remindersDirty = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val remindersDirty: kotlinx.coroutines.flow.Flow<String> = _remindersDirty.receiveAsFlow()
    private fun markRemindersDirty(taskId: String) { _remindersDirty.trySend(taskId) }
    /** N7 — a task's reminders by a direct query, instead of scanning the whole reminders table. */
    suspend fun remindersForTask(taskId: String): List<ReminderEntity> = reminders.forTask(taskId)
    // N1 — set by the ViewModel (the repo has no Context): cancel a reminder's scheduled alarm by id.
    // Invoked from deleteSubtree just before a reminder row is permanently removed, so an exact-alarm
    // never outlives the task it belonged to.
    var onCancelReminder: ((String) -> Unit)? = null
    /** True when two task snapshots differ in any field that changes a reminder's fire time or arming. */
    private fun remindersAffected(old: TaskEntity?, now: TaskEntity): Boolean =
        old == null || old.startDate != now.startDate || old.dueDate != now.dueDate ||
            old.deadlineDate != now.deadlineDate || old.completed != now.completed ||
            old.abandoned != now.abandoned || old.trashed != now.trashed || old.someday != now.someday

    /**
     * R53 — user-triggered storage maintenance for a DB kept over years: checkpoint the WAL, VACUUM to
     * compact + defragment the file (deletes only free-list pages otherwise), and refresh the query
     * planner's stats. All offline; safe to run occasionally from Settings.
     */
    suspend fun optimizeStorage(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val sdb = db.openHelper.writableDatabase
            runCatching { rebuildTaskFtsBlocking(sdb) }   // R54 — recover a stale/missing search index too
            runCatching { rebuildNoteFtsBlocking(sdb) }   // …including the note body index, so notes search stays fast
            runCatching { sdb.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
            runCatching { sdb.execSQL("VACUUM") }
            runCatching { sdb.execSQL("PRAGMA optimize") }
            true
        }.getOrDefault(false)
    }

    /** R54 — total on-disk size of the database (main file + WAL + shared-memory), for the storage panel. */
    fun databaseSizeBytes(): Long = runCatching {
        val base = db.openHelper.writableDatabase.path ?: return 0L
        listOf(base, "$base-wal", "$base-shm").sumOf { p -> runCatching { java.io.File(p).length() }.getOrDefault(0L) }
    }.getOrDefault(0L)

    // ── R54 · full-text search (FTS4) ───────────────────────────────────────────────────────────────
    // A best-effort acceleration for large task histories. The virtual table is invisible to Room's own
    // schema (created with raw SQL, IF NOT EXISTS, all guarded by runCatching), so it can never fail a
    // migration or lose data — search always has an in-memory fallback. Kept fresh by an incremental
    // upsert on the two content-authoring paths (create/save) plus a cheap count-mismatch rebuild.
    private fun ftsDb() = db.openHelper.writableDatabase
    /**
     * One FTS4 index, parameterized by table shape. Both the tasks and the notes index are the same
     * machinery — a raw-SQL virtual table invisible to Room's schema, guarded end-to-end, kept fresh by an
     * incremental upsert on save plus a cheap count-mismatch rebuild, with an empty-list fallback on any
     * failure so search degrades to the in-memory path rather than crashing. Previously this was written
     * out twice (task_fts / note_fts); the two copies drifted (only the note copy learned the `trashed`
     * scoping), which is exactly the bug class a single implementation removes.
     *
     * @param source     the backing table to (re)build from.
     * @param sourceWhere optional filter for which source rows belong in the index (e.g. non-trashed only).
     */
    private inner class FtsIndex(
        val table: String,          // virtual-table name, e.g. "note_fts"
        val idCol: String,          // id column, e.g. "noteId"
        val contentCols: List<String>,   // searchable columns, e.g. ["title", "body"]
        val source: String,         // backing table, e.g. "notes"
        val sourceIdCol: String = "id",  // backing id column
        val sourceWhere: String = "",    // e.g. "WHERE trashed = 0"; "" for all rows
    ) {
        private val allCols = (listOf(idCol) + contentCols).joinToString(", ")            // "noteId, title, body"
        private val srcSelect = (listOf(sourceIdCol) + contentCols).joinToString(", ")    // "id, title, body"
        private val placeholders = (0..contentCols.size).joinToString(", ") { "?" }       // "?, ?, ?"

        fun create(sdb: androidx.sqlite.db.SupportSQLiteDatabase) =
            sdb.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $table USING fts4($allCols, tokenize=unicode61)")

        fun rebuild(sdb: androidx.sqlite.db.SupportSQLiteDatabase) {
            create(sdb)
            sdb.execSQL("DELETE FROM $table")
            sdb.execSQL("INSERT INTO $table($allCols) SELECT $srcSelect FROM $source $sourceWhere".trim())
        }
        fun deleteOne(sdb: androidx.sqlite.db.SupportSQLiteDatabase, id: String) =
            runCatching { sdb.execSQL("DELETE FROM $table WHERE $idCol = ?", arrayOf<Any?>(id)) }.let {}

        /** Incremental single-row upsert. Off the main thread, fully guarded. `values` matches [contentCols]. */
        suspend fun syncOne(id: String, vararg values: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val sdb = ftsDb(); create(sdb)
                sdb.execSQL("DELETE FROM $table WHERE $idCol = ?", arrayOf<Any?>(id))
                sdb.execSQL("INSERT INTO $table($allCols) VALUES($placeholders)", arrayOf<Any?>(id, *values))
            }
            Unit
        }
        /** Create if missing; rebuild only when clearly stale (row-count mismatch vs the filtered source). */
        fun ensureFresh(sdb: androidx.sqlite.db.SupportSQLiteDatabase) {
            create(sdb)
            val ftsCount = runCatching { sdb.query("SELECT count(*) FROM $table").use { if (it.moveToFirst()) it.getLong(0) else -1L } }.getOrDefault(-1L)
            val srcCount = runCatching { sdb.query("SELECT count(*) FROM $source $sourceWhere".trim()).use { if (it.moveToFirst()) it.getLong(0) else -2L } }.getOrDefault(-2L)
            if (ftsCount != srcCount) rebuild(sdb)
        }
        /**
         * L3 — ranked, typo-tolerant search. Ids whose content matches [query] (prefix, all-terms),
         * returned best-first: exact-title and title-prefix hits float above body-only hits. If the FTS
         * MATCH finds nothing, a one-edit fuzzy fallback scans the source so a small typo still lands.
         * Empty on any failure → the caller degrades to its in-memory path.
         */
        suspend fun search(query: String): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val terms = query.trim().split(Regex("\\s+")).map { it.replace(Regex("[\"*^:()]"), "") }.filter { it.isNotBlank() }
                if (terms.isEmpty()) return@runCatching emptyList<String>()
                val match = terms.joinToString(" ") { "$it*" }
                val sdb = ftsDb(); ensureFresh(sdb)
                val scored = ArrayList<Pair<String, Int>>()
                sdb.query("SELECT $allCols FROM $table WHERE $table MATCH ?", arrayOf<Any?>(match)).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val title = if (contentCols.isNotEmpty()) (c.getString(1) ?: "") else ""
                        val rest = buildString { for (i in 2..contentCols.size) append((c.getString(i) ?: "") + " ") }
                        scored += id to scoreRow(terms, title, rest)
                    }
                }
                if (scored.isEmpty()) fuzzyFallback(sdb, terms)
                else scored.sortedByDescending { it.second }.map { it.first }
            }.getOrDefault(emptyList())
        }

        /** Relevance score: exact/prefix title hits dominate; body hits count a little; shorter titles edge ahead. */
        private fun scoreRow(terms: List<String>, title: String, body: String): Int {
            val t = title.lowercase(); val b = body.lowercase(); val full = terms.joinToString(" ").lowercase()
            var s = 0
            if (t == full) s += 1000 else if (t.contains(full)) s += 200
            for (term in terms) {
                val tm = term.lowercase()
                s += when { t == tm -> 300; t.startsWith(tm) -> 120; t.contains(tm) -> 60; else -> 0 }
                if (b.contains(tm)) s += 10
            }
            return s - (t.length / 40)
        }

        /** One-edit fuzzy scan of the source, only on a total FTS miss (rare) — capped so it stays bounded. */
        private fun fuzzyFallback(sdb: androidx.sqlite.db.SupportSQLiteDatabase, terms: List<String>): List<String> {
            val out = ArrayList<Pair<String, Int>>()
            runCatching {
                sdb.query("SELECT $srcSelect FROM $source $sourceWhere".trim()).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val title = if (contentCols.isNotEmpty()) (c.getString(1) ?: "") else ""
                        val rest = buildString { for (i in 2..contentCols.size) append((c.getString(i) ?: "") + " ") }
                        val tokens = (title + " " + rest).lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
                        var s = 0
                        for (term in terms) {
                            val tm = term.lowercase()
                            if (tm.length < 4) { if (tokens.any { it.startsWith(tm) }) s += 30 }
                            else if (tokens.any { editWithin1(it, tm) }) s += 40
                        }
                        if (s > 0) out += id to (s + scoreRow(terms, title, rest))
                    }
                }
            }
            return out.sortedByDescending { it.second }.take(50).map { it.first }
        }

        /** True when [a] is within one insertion/deletion/substitution of [b]. Cheap, no full DP matrix. */
        private fun editWithin1(a: String, b: String): Boolean {
            if (a == b) return true
            val la = a.length; val lb = b.length
            if (kotlin.math.abs(la - lb) > 1) return false
            var i = 0; var j = 0; var edits = 0
            while (i < la && j < lb) {
                if (a[i] == b[j]) { i++; j++ } else {
                    if (++edits > 1) return false
                    when { la > lb -> i++; la < lb -> j++; else -> { i++; j++ } }
                }
            }
            if (i < la || j < lb) edits++
            return edits <= 1
        }
    }

    private val taskFts = FtsIndex("task_fts", "taskId", listOf("title", "note"), "tasks")
    // Notes: only non-trashed rows belong in the index — a trashed note deletes its FTS row on trash, so
    // re-adding it on rebuild would resurrect it into search (and skew the freshness count).
    private val noteFts = FtsIndex("note_fts", "noteId", listOf("title", "body"), "notes", sourceWhere = "WHERE trashed = 0")

    // Thin, named delegators so every existing call site (save/trash/delete/import/search) is unchanged.
    private fun rebuildTaskFtsBlocking(sdb: androidx.sqlite.db.SupportSQLiteDatabase) = taskFts.rebuild(sdb)
    private fun rebuildNoteFtsBlocking(sdb: androidx.sqlite.db.SupportSQLiteDatabase) = noteFts.rebuild(sdb)
    suspend fun syncTaskFts(id: String, title: String, note: String) = taskFts.syncOne(id, title, note)
    suspend fun syncNoteFts(id: String, title: String, body: String) = noteFts.syncOne(id, title, body)
    private fun deleteTaskFts(sdb: androidx.sqlite.db.SupportSQLiteDatabase, id: String) = taskFts.deleteOne(sdb, id)
    private fun deleteNoteFts(sdb: androidx.sqlite.db.SupportSQLiteDatabase, id: String) = noteFts.deleteOne(sdb, id)
    suspend fun searchTaskIds(query: String): List<String> = taskFts.search(query)
    suspend fun searchNoteIds(query: String): List<String> = noteFts.search(query)

    /**
     * R56 (Wave B / robustness R1) — DB-side COUNT(*) aggregates. A `SELECT count(*)` is orders of
     * magnitude cheaper than materialising rows into memory just to count them, and it stays fast as the
     * store grows. Read-only, fully guarded, off the hot path; powers the maintenance "database health"
     * readout and demonstrates the aggregate-in-SQL pattern the heavier counters will move onto.
     */
    suspend fun databaseRowCounts(): Map<String, Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val sdb = db.openHelper.writableDatabase
        fun cnt(sql: String): Long = runCatching { sdb.query(sql).use { if (it.moveToFirst()) it.getLong(0) else 0L } }.getOrDefault(0L)
        linkedMapOf(
            "Active tasks" to cnt("SELECT count(*) FROM tasks WHERE trashed = 0"),
            "Completed" to cnt("SELECT count(*) FROM tasks WHERE completed = 1 AND trashed = 0"),
            "In Trash" to cnt("SELECT count(*) FROM tasks WHERE trashed = 1"),
            "Events" to cnt("SELECT count(*) FROM events"),
            "Habit check-ins" to cnt("SELECT count(*) FROM habit_checkins"),
            "Time entries" to cnt("SELECT count(*) FROM time_entries"),
            "Attachments" to cnt("SELECT count(*) FROM attachments"),
            "Occasions" to cnt("SELECT count(*) FROM countdowns"),
        )
    }

    private val tasks = db.taskDao()
    private val folders = db.folderDao()
    private val lists = db.listDao()
    private val checklist = db.checklistDao()
    private val tags = db.tagDao()
    private val contexts = db.contextDao()
    private val reminders = db.reminderDao()
    private val deps = db.dependencyDao()
    private val settings = db.settingDao()
    private val attachments = db.attachmentDao()
    private val flags = db.flagDao()
    private val templates = db.templateDao()
    private val countdowns = db.countdownDao()
    private val activity = db.activityDao()
    private val revisions = db.revisionDao()
    private val timeTrack = db.timeTrackingDao()
    private val sealedNotes = db.sealedNoteDao()
    private val cravings = db.cravingDao()
    private val coreValues = db.coreValueDao()
    private val witnesses = db.witnessDao()
    private val scorecard = db.scorecardDao()
    private val buddies = db.buddyDao()
    private val integrityReviews = db.integrityReviewDao()
    private val experiments = db.experimentDao()
    private val activation = db.activationDao()
    private val dayLogs = db.dayLogDao()
    private val escrows = db.escrowDao()
    private val nudgeEvents = db.nudgeEventDao()
    private val eventCalendars = db.eventCalendarDao()
    private val events = db.eventDao()
    private val notes = db.noteDao()
    private val notebooks = db.notebookDao()
    private val goals = db.goalDao()
    private val routines = db.routineDao()
    private val templateJson = com.todocompanion.app.util.AppJson

    // ----- task time-travel: sparse revision history (H5) -----
    private val lastRevSig = HashMap<String, Int>()
    private val lastRevAt = HashMap<String, Long>()
    private companion object {
        const val REV_KEEP = 25; const val REV_MIN_GAP_MS = 30_000L
        // Track 3.4 — settings (DataStore) key holding the CSV set of already-notified sealed-letter ids.
        const val SEALED_NOTIFIED_KEY = "sealedLetterNotifiedIds"
        // SEC (R2-A/H2) — non-secret token bumped on every settings save so the reactive flow re-emits
        // and re-reads the KeyStore-wrapped sync passphrase (which lives outside the DB table).
        const val SYNC_PASS_REV = "sync_pass_rev"
    }

    /** Fields worth versioning — cosmetic/order/timestamp churn is deliberately excluded. */
    private fun revisionSignature(t: TaskEntity): Int = listOf(
        t.title, t.note, t.importance, t.urgency, t.startDate, t.dueDate, t.deadlineDate,
        t.listId, t.folderId, t.flagId, t.energy, t.rrule, t.estimateMin, t.estimateMax,
        t.durationMin, t.completed, t.abandoned, t.isNote, t.isGoal, t.isProject, t.progressPct,
    ).hashCode()

    private fun revisionLabel(t: TaskEntity): String = when {
        t.abandoned -> "Won't do — ${t.title.take(40)}"
        t.completed -> "Completed — ${t.title.take(40)}"
        else -> t.title.ifBlank { "Untitled" }.take(48)
    }

    /**
     * Records a snapshot when a meaningful field changed since the last one, coalescing rapid
     * keystrokes (min gap) so history stays sparse. Cheap: one hash compare on the hot path.
     */
    private suspend fun maybeRecordRevision(t: TaskEntity) {
        val sig = revisionSignature(t)
        val prevSig = lastRevSig[t.id]
        if (prevSig == sig) return
        val now = now()
        val last = lastRevAt[t.id] ?: revisions.lastAt(t.id) ?: 0L
        if (prevSig != null && now - last < REV_MIN_GAP_MS) { lastRevSig[t.id] = sig; return }
        runCatching {
            revisions.insert(
                com.todocompanion.app.data.entity.TaskRevisionEntity(
                    id = uid(), taskId = t.id, at = now, snapshotJson = templateJson.encodeToString(TaskEntity.serializer(), t), label = revisionLabel(t),
                )
            )
            revisions.trim(t.id, REV_KEEP)
        }
        lastRevSig[t.id] = sig; lastRevAt[t.id] = now
    }

    fun taskRevisions(taskId: String): Flow<List<com.todocompanion.app.data.entity.TaskRevisionEntity>> = revisions.observeForTask(taskId)

    /** Restores a saved revision, snapshotting the current state first so the restore is reversible. */
    suspend fun restoreRevision(revisionId: String) {
        val rev = revisions.byId(revisionId) ?: return
        val current = tasks.getById(rev.taskId)
        if (current != null) maybeRecordRevision(current)
        val restored = runCatching { templateJson.decodeFromString(TaskEntity.serializer(), rev.snapshotJson) }.getOrNull() ?: return
        tasks.upsert(restored.copy(updatedAt = now()))
        logActivity(rev.taskId, "restored", "version")
    }

    // ----- activity log (private, on-device audit trail) -----
    fun taskActivity(taskId: String): Flow<List<com.todocompanion.app.data.entity.ActivityEntity>> = activity.observeForTask(taskId)
    val allActivity: Flow<List<com.todocompanion.app.data.entity.ActivityEntity>> = activity.observeAll()
    suspend fun getActivitiesOnce(): List<com.todocompanion.app.data.entity.ActivityEntity> = activity.getAll()
    // R23: the activity trail is an independent append-only log — deleting one row (or clearing the task's
    // whole history) removes only those rows; no cascade. Derived state (e.g. a recurring task's reliability
    // score, computed on the fly from completion events) simply recomputes from what remains.
    suspend fun deleteActivity(id: String) = activity.deleteById(id)
    suspend fun clearTaskActivity(taskId: String) = activity.clearForTask(taskId)
    suspend fun getFocusSessionsOnce(): List<com.todocompanion.app.data.entity.FocusSessionEntity> = focus.getAll()
    private suspend fun logActivity(taskId: String, type: String, detail: String? = null) {
        activity.insert(com.todocompanion.app.data.entity.ActivityEntity(uid(), taskId, type, now(), detail))
    }

    // ----- reactive reads -----
    val allTasks: Flow<List<TaskEntity>> = tasks.observeAll()
    val allFolders: Flow<List<FolderEntity>> = folders.observeAll()
    val allLists: Flow<List<ListEntity>> = lists.observeAll()
    val allChecklist: Flow<List<ChecklistItemEntity>> = checklist.observeAll()
    val allTags: Flow<List<TagEntity>> = tags.observeAll()
    val allContexts: Flow<List<ContextEntity>> = contexts.observeAll()
    val taskTagRefs: Flow<List<TaskTagCrossRef>> = tags.observeCrossRefs()
    val taskContextRefs: Flow<List<TaskContextCrossRef>> = contexts.observeCrossRefs()
    val allReminders: Flow<List<ReminderEntity>> = reminders.observeAll()
    val allDependencies: Flow<List<DependencyEntity>> = deps.observeAll()
    val allFlags: Flow<List<FlagEntity>> = flags.observeAll()
    val allTemplates: Flow<List<TemplateEntity>> = templates.observeAll()
    val allCountdowns: Flow<List<com.todocompanion.app.data.entity.CountdownEntity>> = countdowns.observeAll()
    suspend fun allCountdownsOnce(): List<com.todocompanion.app.data.entity.CountdownEntity> = countdowns.getAll()
    suspend fun upsertCountdown(c: com.todocompanion.app.data.entity.CountdownEntity) = countdowns.upsert(c)
    suspend fun deleteCountdown(id: String) = countdowns.deleteById(id)
    val allSealedNotes: Flow<List<com.todocompanion.app.data.entity.SealedNoteEntity>> = sealedNotes.observeAll()
    suspend fun upsertSealedNote(n: com.todocompanion.app.data.entity.SealedNoteEntity) = sealedNotes.upsert(n)
    suspend fun deleteSealedNote(id: String) = sealedNotes.deleteById(id)
    // Track 3.4 — one-shot reads for the local sealed-letter reveal notification (scheduler / receiver).
    suspend fun allSealedNotesOnce(): List<com.todocompanion.app.data.entity.SealedNoteEntity> = sealedNotes.getAll()
    suspend fun sealedNoteById(id: String): com.todocompanion.app.data.entity.SealedNoteEntity? =
        sealedNotes.getAll().firstOrNull { it.id == id }
    /** Track 3.4 — which sealed letters have already fired their "ready to open" notification. Stored as a
     *  comma-separated set under a single settings (DataStore) key — no new Room column, no migration. */
    suspend fun sealedLetterNotifiedIds(): Set<String> =
        settings.get(SEALED_NOTIFIED_KEY)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    suspend fun markSealedLetterNotified(id: String) {
        val cur = sealedLetterNotifiedIds()
        if (id in cur) return
        settings.put(SettingEntity(SEALED_NOTIFIED_KEY, (cur + id).joinToString(",")))
    }
    val allCravings: Flow<List<com.todocompanion.app.data.entity.CravingEventEntity>> = cravings.observeAll()
    suspend fun upsertCraving(c: com.todocompanion.app.data.entity.CravingEventEntity) = cravings.upsert(c)
    suspend fun deleteCraving(id: String) = cravings.deleteById(id)
    // R34 — life-systems layer accessors.
    val allCoreValues: Flow<List<com.todocompanion.app.data.entity.CoreValueEntity>> = coreValues.observeAll()
    suspend fun upsertCoreValue(v: com.todocompanion.app.data.entity.CoreValueEntity) = coreValues.upsert(v)
    suspend fun deleteCoreValue(id: String) = coreValues.deleteById(id)
    val allWitnessEvents: Flow<List<com.todocompanion.app.data.entity.WitnessEventEntity>> = witnesses.observeAll()
    suspend fun upsertWitness(w: com.todocompanion.app.data.entity.WitnessEventEntity) = witnesses.upsert(w)
    suspend fun deleteWitness(id: String) = witnesses.deleteById(id)
    val allScorecardItems: Flow<List<com.todocompanion.app.data.entity.ScorecardItemEntity>> = scorecard.observeAll()
    suspend fun upsertScorecardItem(s: com.todocompanion.app.data.entity.ScorecardItemEntity) = scorecard.upsert(s)
    suspend fun deleteScorecardItem(id: String) = scorecard.deleteById(id)
    val allBuddies: Flow<List<com.todocompanion.app.data.entity.BuddySnapshotEntity>> = buddies.observeAll()
    suspend fun upsertBuddy(b: com.todocompanion.app.data.entity.BuddySnapshotEntity) = buddies.upsert(b)
    suspend fun deleteBuddy(id: String) = buddies.deleteById(id)
    val allIntegrityReviews: Flow<List<com.todocompanion.app.data.entity.IntegrityReviewEntity>> = integrityReviews.observeAll()
    suspend fun upsertIntegrityReview(r: com.todocompanion.app.data.entity.IntegrityReviewEntity) = integrityReviews.upsert(r)
    suspend fun deleteIntegrityReview(id: String) = integrityReviews.deleteById(id)
    // R35 — third-wave accessors.
    val allExperiments: Flow<List<com.todocompanion.app.data.entity.ExperimentEntity>> = experiments.observeAll()
    suspend fun upsertExperiment(e: com.todocompanion.app.data.entity.ExperimentEntity) = experiments.upsert(e)
    suspend fun deleteExperiment(id: String) = experiments.deleteById(id)
    val allActivationItems: Flow<List<com.todocompanion.app.data.entity.ActivationItemEntity>> = activation.observeAll()
    suspend fun upsertActivationItem(a: com.todocompanion.app.data.entity.ActivationItemEntity) = activation.upsert(a)
    suspend fun deleteActivationItem(id: String) = activation.deleteById(id)
    val allDayLogs: Flow<List<com.todocompanion.app.data.entity.DayLogEntity>> = dayLogs.observeAll()
    suspend fun dayLogFor(day: Long): com.todocompanion.app.data.entity.DayLogEntity? = dayLogs.forDay(day, activeWs())
    // Phase F — the active workspace's day logs (for the evening reminder's skip-if-done + adaptive-time layer).
    suspend fun dayLogsOnce(): List<com.todocompanion.app.data.entity.DayLogEntity> {
        val ws = activeWs(); return dayLogs.getAll().filter { it.workspaceId == ws }
    }
    suspend fun upsertDayLog(d: com.todocompanion.app.data.entity.DayLogEntity) = dayLogs.upsert(d)
    // R36 — fourth-wave accessors.
    val allEscrows: Flow<List<com.todocompanion.app.data.entity.EscrowEntity>> = escrows.observeAll()
    suspend fun upsertEscrow(e: com.todocompanion.app.data.entity.EscrowEntity) = escrows.upsert(e)
    suspend fun deleteEscrow(id: String) = escrows.deleteById(id)
    val allNudgeEvents: Flow<List<com.todocompanion.app.data.entity.NudgeEventEntity>> = nudgeEvents.observeAll()
    suspend fun nudgeForHabitDay(habitId: String, day: Long): com.todocompanion.app.data.entity.NudgeEventEntity? = nudgeEvents.forHabitDay(habitId, day)
    suspend fun openNudgesSince(sinceDay: Long): List<com.todocompanion.app.data.entity.NudgeEventEntity> = nudgeEvents.openSince(sinceDay)
    suspend fun upsertNudgeEvent(e: com.todocompanion.app.data.entity.NudgeEventEntity) = nudgeEvents.upsert(e)
    // R38 — dedicated-calendar accessors.
    val allEventCalendars: Flow<List<com.todocompanion.app.data.entity.EventCalendarEntity>> = eventCalendars.observeAll()
    suspend fun upsertEventCalendar(c: com.todocompanion.app.data.entity.EventCalendarEntity) = eventCalendars.upsert(c)
    suspend fun deleteEventCalendar(id: String) = eventCalendars.deleteById(id)
    suspend fun eventCalendarsOnce(): List<com.todocompanion.app.data.entity.EventCalendarEntity> = eventCalendars.getAll()
    val allEvents: Flow<List<com.todocompanion.app.data.entity.EventEntity>> = events.observeAll()
    suspend fun eventById(id: String): com.todocompanion.app.data.entity.EventEntity? = events.getById(id)
    suspend fun eventsOnce(): List<com.todocompanion.app.data.entity.EventEntity> = events.getAll()
    suspend fun upsertEvent(e: com.todocompanion.app.data.entity.EventEntity) = events.upsert(e)
    suspend fun upsertEvents(e: List<com.todocompanion.app.data.entity.EventEntity>) = events.upsertAll(e)
    suspend fun deleteEvent(id: String) { events.deleteOverridesOf(id); events.deleteById(id) }
    val allSettings: Flow<List<SettingEntity>> = settings.observeAll()
    private val habits = db.habitDao()
    val allHabits: Flow<List<HabitEntity>> = habits.observeAll()
    val allCheckins: Flow<List<HabitCheckinEntity>> = habits.observeCheckins()
    suspend fun createHabit(name: String, emoji: String?, colorArgb: Long?, target: Int, workspaceId: String, unit: String? = null, scheduleDays: String = "", reminderTimes: String = ""): String {
        val id = uid()
        habits.upsert(HabitEntity(id = id, name = name, emoji = emoji, colorArgb = colorArgb, targetPerDay = target.coerceAtLeast(1), unit = unit, scheduleDays = scheduleDays, reminderTimes = reminderTimes, sortOrder = now().toDouble(), workspaceId = workspaceId, createdAt = now()))
        return id
    }
    /** Create from a fully-built habit (Tier I editor); id/sortOrder/createdAt filled if blank. */
    suspend fun createHabit(h: HabitEntity): String {
        val id = h.id.ifBlank { uid() }
        habits.upsert(h.copy(id = id, sortOrder = if (h.sortOrder == 0.0) now().toDouble() else h.sortOrder, createdAt = if (h.createdAt == 0L) now() else h.createdAt))
        return id
    }
    // Excludes trashed habits, so every analysis / reminder / widget path that reads a one-shot habit list
    // ignores soft-deleted habits. Backup/export gathers `habits.getAll()` directly to stay lossless.
    suspend fun getHabitsOnce(): List<HabitEntity> = habits.getAll().filter { !it.trashed }
    suspend fun getHabitCheckinsOnce(): List<HabitCheckinEntity> = habits.getCheckins()
    suspend fun upsertHabit(h: HabitEntity) = habits.upsert(h)
    /** Persist a manual habit order by rewriting sortOrder to the given list index. */
    suspend fun setHabitOrder(orderedIds: List<String>) {
        val byId = habits.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { habits.upsert(it.copy(sortOrder = i.toDouble())) } }
    }
    /** Permanent removal (check-ins + habit row). Only reachable from the habits Trash. */
    suspend fun deleteHabit(id: String) { habits.clearHabit(id); habits.deleteById(id) }
    /** Soft-delete: move a habit to Trash (recoverable) or restore it. Check-ins are preserved either way. */
    suspend fun setHabitTrashed(id: String, trashed: Boolean) {
        habits.getById(id)?.let {
            habits.upsert(it.copy(trashed = trashed, trashedAt = if (trashed) now() else null))
        }
    }
    /** Archive / unarchive a whole habit — kept out of the active list & analysis but never deleted. */
    suspend fun setHabitArchived(id: String, archived: Boolean) {
        habits.getById(id)?.let { habits.upsert(it.copy(archived = archived)) }
    }
    /** Permanently erase every trashed habit in a workspace (the Trash "empty" action). */
    suspend fun emptyHabitTrash(workspaceId: String) {
        habits.getAll().filter { it.workspaceId == workspaceId && it.trashed }.forEach { deleteHabit(it.id) }
    }
    /**
     * Cycle today's progress by [increment] up to the ceiling (extra goal if set, else target),
     * then back to 0 (removes the check-in). Marks the day "done".
     */
    suspend fun cycleCheckin(habitId: String, epochDay: Long, target: Int, current: Int, increment: Int = 1, extra: Int? = null) {
        val ceiling = (extra ?: target).coerceAtLeast(1)
        val next = current + increment.coerceAtLeast(1)
        if (next > ceiling) habits.deleteCheckin(habitId, epochDay)
        else {
            val existing = habits.getCheckin(habitId, epochDay)
            habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, next, status = "done", reason = existing?.reason ?: "", photoUri = existing?.photoUri,
                doneAtMinute = existing?.doneAtMinute ?: stampMinute(epochDay)))
            // K2: reaching the stretch goal earns a streak-freeze token (once per day, capped).
            if (extra != null && current < extra && next >= extra) awardFreeze(habitId)
        }
    }
    /** O2: the current minute-of-day (0–1439) when marking *today* done — else null (past days unstamped). */
    private fun stampMinute(epochDay: Long): Int? {
        val z = java.time.ZoneId.systemDefault()
        if (java.time.LocalDate.now(z).toEpochDay() != epochDay) return null
        val t = java.time.LocalTime.now(z); return t.hour * 60 + t.minute
    }
    /** K2: grant one streak-freeze token, capped at 5, for overachieving. */
    suspend fun awardFreeze(habitId: String) {
        habits.getById(habitId)?.let { h ->
            if (h.freezeTokens < 5) habits.upsert(h.copy(freezeTokens = h.freezeTokens + 1))
        }
    }
    /** K2: spend one freeze to protect a missed day — records it as a neutral skip. No-op if none left. */
    suspend fun spendFreeze(habitId: String, epochDay: Long): Boolean {
        val h = habits.getById(habitId) ?: return false
        if (h.freezeTokens <= 0) return false
        habits.upsert(h.copy(freezeTokens = h.freezeTokens - 1))
        habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, 0, status = "skip", reason = "❄️ Streak freeze"))
        return true
    }
    /** K5: attach or clear a photo on a day, preserving the day's count/status/note. */
    suspend fun setCheckinPhoto(habitId: String, epochDay: Long, photoUri: String?) {
        val existing = habits.getCheckin(habitId, epochDay)
        val base = existing ?: HabitCheckinEntity(habitId, epochDay, 0, status = "done")
        habits.upsertCheckin(base.copy(photoUri = photoUri))
    }
    /** Set an exact value for a day (numeric entry / relapse amount). 0 clears the day. Preserves any note. */
    suspend fun setCheckinValue(habitId: String, epochDay: Long, count: Int) {
        if (count <= 0) habits.deleteCheckin(habitId, epochDay)
        else {
            val existing = habits.getCheckin(habitId, epochDay)
            habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, count, status = "done", reason = existing?.reason ?: "", photoUri = existing?.photoUri,
                doneAtMinute = existing?.doneAtMinute ?: stampMinute(epochDay)))
        }
    }
    /**
     * Write a whole day at once from the per-day editor: [count] value, [status] ("done"/"skip"),
     * and a free-text [note]. An empty done day with no note clears the record entirely.
     */
    suspend fun setDay(habitId: String, epochDay: Long, count: Int, status: String, note: String) {
        val c = count.coerceAtLeast(0)
        val photo = habits.getCheckin(habitId, epochDay)?.photoUri
        if (status == "done" && c <= 0 && note.isBlank() && photo == null) habits.deleteCheckin(habitId, epochDay)
        else habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, c, status = status, reason = note, photoUri = photo))
    }
    /** Mark a day as skipped (a neutral rest day: streak and score are unaffected). */
    suspend fun skipDay(habitId: String, epochDay: Long, reason: String = "") =
        habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, 0, status = "skip", reason = reason))
    /** Remove any record for a day (back to unmarked). */
    suspend fun clearCheckin(habitId: String, epochDay: Long) = habits.deleteCheckin(habitId, epochDay)
    /** R34 · LS2 — attach the context tags (energy/mood/place) to a day's check-in without disturbing
     *  its count/status; seeds a done record if none exists yet. */
    suspend fun setCheckinContext(habitId: String, epochDay: Long, energy: Int, mood: Int, place: String) {
        val existing = habits.getCheckin(habitId, epochDay) ?: HabitCheckinEntity(habitId, epochDay, 1, status = "done")
        habits.upsertCheckin(existing.copy(ctxEnergy = energy, ctxMood = mood, ctxPlace = place))
    }
    /** Pause / resume a whole habit (vacation) without touching its history. */
    suspend fun setHabitPaused(habitId: String, paused: Boolean) {
        habits.getById(habitId)?.let { habits.upsert(it.copy(paused = paused)) }
    }
    /** Pause or resume every habit in a workspace at once. */
    suspend fun pauseAllHabits(workspaceId: String, paused: Boolean) {
        habits.getAll().filter { it.workspaceId == workspaceId && !it.archived }.forEach { habits.upsert(it.copy(paused = paused)) }
    }

    private val focus = db.focusDao()
    val allFocusSessions: Flow<List<FocusSessionEntity>> = focus.observeAll()
    suspend fun addFocusSession(epochDay: Long, startMillis: Long, minutes: Int, kind: String, taskId: String? = null) =
        focus.upsert(FocusSessionEntity(uid(), epochDay, startMillis, minutes, kind, taskId, activeWs()))

    // ----- Tier S: time tracking -----
    val allTimeActivities: Flow<List<com.todocompanion.app.data.entity.TimeActivityEntity>> = timeTrack.observeActivities()
    val allTimeEntries: Flow<List<com.todocompanion.app.data.entity.TimeEntryEntity>> = timeTrack.observeEntries()

    suspend fun createTimeActivity(name: String, emoji: String?, colorArgb: Long?, goalMinutesPerDay: Int = 0): String {
        val id = uid()
        val order = (timeTrack.getActivities().maxOfOrNull { it.sortOrder } ?: 0.0) + 1.0
        timeTrack.upsertActivity(com.todocompanion.app.data.entity.TimeActivityEntity(id, name.trim().ifBlank { "Activity" }, emoji, colorArgb, false, order, now(), goalMinutesPerDay, "", activeWs()))
        return id
    }
    suspend fun upsertTimeActivity(a: com.todocompanion.app.data.entity.TimeActivityEntity) = timeTrack.upsertActivity(a)
    /** Soft-archive: keep the activity's tracked time but hide it from the picker (its stats survive). */
    suspend fun archiveTimeActivity(id: String) {
        timeTrack.getActivities().firstOrNull { it.id == id }?.let { timeTrack.upsertActivity(it.copy(archived = true)) }
    }

    /**
     * Full delete — removes an activity from EVERY place it's referenced, so nothing stale lingers:
     * its time entries, the tasks that defaulted to it, the habits linked to it, the pinned tiles, the
     * automation rules that fire on it, and the nested-activity parent map (its children are re-parented
     * to its own parent, or promoted to top level). Use archiveTimeActivity to keep the time instead.
     */
    suspend fun deleteTimeActivity(id: String) {
        timeTrack.deleteEntriesForActivity(id)
        tasks.getAll().filter { it.defaultActivityId == id }.forEach { tasks.upsert(it.copy(defaultActivityId = null, updatedAt = now())) }
        habits.getAll().filter { it.timeActivityId == id }.forEach { habits.upsert(it.copy(timeActivityId = null)) }
        val s = settingsSnapshot()
        val parents = s.timeActivityParents
        val grandparent = parents[id]
        val newParents = parents
            .filterKeys { it != id }                                   // drop it as a child
            .mapValues { (_, p) -> if (p == id) (grandparent ?: "") else p }   // re-parent its children
            .filterValues { it.isNotBlank() }
        val rules = com.todocompanion.app.domain.AutomationRules.parse(s.automationRulesJson)
            .filter { it.whenActivityId != id && it.startActivityId != id }
        saveSettings(s.copy(
            pinnedActivities = s.pinnedActivities - id,
            timeActivityParents = newParents,
            automationRulesJson = com.todocompanion.app.domain.AutomationRules.encode(rules),
        ))
        timeTrack.deleteActivity(id)
    }

    /**
     * Start tracking an activity. [stopFirst] keeps the single-timer discipline (default): any running
     * entry is stopped first. Pass false (U15 multi-timer) to let activities overlap. Passing [startMillis]
     * (U5 timeline-fill) back-dates the start so the new block closes a gap since the last one ended.
     */
    suspend fun startTimeTracking(activityId: String, taskId: String? = null, habitId: String? = null, stopFirst: Boolean = true, startMillis: Long? = null, kind: String = "manual", noteId: String? = null): String {
        if (stopFirst) stopTimeTracking()
        val id = uid()
        val start = startMillis ?: now()
        timeTrack.upsertEntry(com.todocompanion.app.data.entity.TimeEntryEntity(id, activityId, start, null, "", taskId, habitId, now(), kind = kind, workspaceId = activeWs(), noteId = noteId))
        return id
    }

    /** L6 — a stable "Notes" activity bucket, mirroring [ensureTaskActivity]: the generic activity for time
     *  tracked while working inside a note. */
    suspend fun ensureNotesActivity(): String {
        timeTrack.getActivities().firstOrNull { it.name == "Notes" && !it.archived }?.let { return it.id }
        return createTimeActivity("Notes", "📝", 0xFF7A5CD8L)
    }

    /** L6 — start tracking time against a note (Work-on-this-note), on the stable "Notes" activity. */
    suspend fun startTimeTrackingForNote(noteId: String): String =
        startTimeTracking(ensureNotesActivity(), noteId = noteId, stopFirst = true)

    /** L6 — total minutes ever tracked against a note (via its own entries + any on its linked task). */
    suspend fun trackedMinutesForNote(noteId: String): Int {
        val note = notes.getById(noteId)
        val linkedTask = note?.linkedTaskId
        return timeTrack.getEntries().filter { it.noteId == noteId || (linkedTask != null && it.taskId == linkedTask) }
            .sumOf { it.minutes(now()) }
    }
    /** Wave 2 · Writing Sprints — log a completed writing sprint as tracked time on a note (Notes activity). */
    suspend fun logNoteTime(noteId: String, startMillis: Long, endMillis: Long, note: String = "") {
        if (endMillis <= startMillis) return
        timeTrack.upsertEntry(com.todocompanion.app.data.entity.TimeEntryEntity(
            uid(), ensureNotesActivity(), startMillis, endMillis, note, null, null, now(),
            workspaceId = activeWs(), noteId = noteId,
        ))
    }

    /** Wave 2 · Habit Practice Journal — the note bound to a habit as its reflective log, or null. */
    suspend fun habitJournalNote(habitId: String): com.todocompanion.app.data.entity.NoteEntity? =
        notes.getAll().firstOrNull { !it.trashed && it.linkedHabitId == habitId }

    /** Append one dated check-in line to a habit's journal note, once per day (idempotent; skips a locked
     *  vault note so it never touches ciphertext). */
    suspend fun appendHabitJournalEntry(habitId: String, epochDay: Long) {
        val note = habitJournalNote(habitId) ?: return
        if (note.vault && com.todocompanion.app.domain.NoteVault.isLocked(note.body)) return
        val date = java.time.LocalDate.ofEpochDay(epochDay).toString()
        val marker = "- **$date**"
        if (note.body.contains(marker)) return
        val line = "$marker — ✅ done"
        upsertNote(note.copy(body = if (note.body.isBlank()) line else note.body.trimEnd() + "\n" + line, updatedAt = now()))
    }

    /** All currently-running entries (multi-timer aware). */
    suspend fun runningTimeEntries(): List<com.todocompanion.app.data.entity.TimeEntryEntity> = timeTrack.getEntries().filter { it.running }
    /** Every recorded time entry (R41 planner: planned-vs-actual, estimate calibration, weekly audit). */
    suspend fun timeEntriesOnce(): List<com.todocompanion.app.data.entity.TimeEntryEntity> = timeTrack.getEntries()
    /** Every time activity, one-shot (the self-contained TimeTrackingController scopes these by workspace). */
    suspend fun timeActivitiesOnce(): List<com.todocompanion.app.data.entity.TimeActivityEntity> = timeTrack.getActivities()
    /** Stop the (first) running entry, if any. With multi-timer on this stops one; callers can loop. */
    suspend fun stopTimeTracking() { timeTrack.runningEntry()?.let { finalizeEntry(it) } }
    /** Stop a specific running entry by id (U15). */
    suspend fun stopTimeEntry(id: String) { timeTrack.getEntries().firstOrNull { it.id == id && it.running }?.let { finalizeEntry(it) } }
    /** Close one running interval, discarding zero-length blips and crediting any linked habit once. */
    private suspend fun finalizeEntry(running: com.todocompanion.app.data.entity.TimeEntryEntity) {
        val end = now()
        if (end - running.startMillis < 1_000L) { timeTrack.deleteEntry(running.id); return }
        timeTrack.upsertEntry(running.copy(endMillis = end))
        // T3 (I4): a habit-linked interval credits the habit's check-in once, with its minutes — the same
        // single auto-log the Focus coach uses, so a timed habit is never logged twice. The link can be
        // direct (entry.habitId) or via the activity a habit is bound to (Habit.timeActivityId).
        val mins = ((end - running.startMillis) / 60_000L).toInt()
        if (mins > 0) {
            val all = habits.getAll()
            val h = running.habitId?.let { id -> all.firstOrNull { it.id == id } }
                ?: all.firstOrNull { it.timeActivityId == running.activityId && !it.archived }
            // V3 / R64: credit by the habit's link mode —
            //   minutes  (default) : add the interval's minutes to the count (a "meditate 20 min" habit
            //                        auto-completes once tracked minutes reach the target).
            //   sessions           : each timed interval adds one clickIncrement (Streaks-style).
            //   complete           : any tracked session marks the habit DONE for the day (fills the count
            //                        up to the target) — the right choice for a habit whose unit isn't
            //                        minutes (e.g. "8000 steps"), which time alone can't measure.
            //   off                : tracking never auto-logs.
            if (h != null && h.linkMode != "off") {
                val day = java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                val cur = habits.getCheckins().firstOrNull { it.habitId == h.id && it.epochDay == day }?.count ?: 0
                val newCount = when (h.linkMode) {
                    "complete" -> maxOf(cur, h.targetPerDay.coerceAtLeast(1))
                    "sessions" -> cur + h.clickIncrement.coerceAtLeast(1)
                    else -> cur + mins
                }
                setCheckinValue(h.id, day, newCount)
                // Let the VM celebrate this timed completion exactly like a tap (shine/point/ramp).
                habitCredited.tryEmit(HabitCreditEvent(h.id, day, cur))
            }
        }
    }
    /** Read the current settings snapshot (for automation/behaviour toggles outside the VM). */
    suspend fun automationRulesOnce(): List<com.todocompanion.app.domain.AutomationRule> =
        com.todocompanion.app.domain.AutomationRules.parse(settingsSnapshot().automationRulesJson)
    suspend fun addManualTimeEntry(activityId: String, startMillis: Long, endMillis: Long, note: String = "", taskId: String? = null, habitId: String? = null) =
        timeTrack.upsertEntry(com.todocompanion.app.data.entity.TimeEntryEntity(uid(), activityId, startMillis, endMillis, note, taskId, habitId, now(), workspaceId = activeWs()))
    suspend fun upsertTimeEntry(e: com.todocompanion.app.data.entity.TimeEntryEntity) = timeTrack.upsertEntry(e)
    suspend fun deleteTimeEntry(id: String) = timeTrack.deleteEntry(id)
    /** U4: split a completed interval in two at [atMillis], keeping both halves' links and tags. */
    suspend fun splitTimeEntry(id: String, atMillis: Long) {
        val e = timeTrack.getEntries().firstOrNull { it.id == id } ?: return
        val end = e.endMillis ?: return
        if (atMillis <= e.startMillis || atMillis >= end) return
        timeTrack.upsertEntry(e.copy(endMillis = atMillis))
        timeTrack.upsertEntry(e.copy(id = uid(), startMillis = atMillis, endMillis = end, createdAt = now()))
    }
    suspend fun runningTimeEntry(): com.todocompanion.app.data.entity.TimeEntryEntity? = timeTrack.runningEntry()
    suspend fun getTimeActivitiesOnce(): List<com.todocompanion.app.data.entity.TimeActivityEntity> = timeTrack.getActivities()

    /** T1 (I1): a stable "Focus" activity used to mirror Focus/Pomodoro sessions onto the one timeline. */
    suspend fun ensureFocusActivity(): String {
        timeTrack.getActivities().firstOrNull { it.name == "Focus" && !it.archived }?.let { return it.id }
        return createTimeActivity("Focus", "🎯", 0xFF6650A4L)
    }
    /** T1 (I1): record a completed Focus session as a time interval (kind=focus) so the tracker is the
     *  single source of truth for time. A completed interval — it never disturbs the running entry. */
    suspend fun mirrorFocusInterval(startMillis: Long, endMillis: Long, activityId: String, taskId: String?, habitId: String?) {
        if (endMillis <= startMillis) return
        timeTrack.upsertEntry(
            com.todocompanion.app.data.entity.TimeEntryEntity(
                id = uid(), activityId = activityId, startMillis = startMillis, endMillis = endMillis,
                note = "", taskId = taskId, habitId = habitId, createdAt = now(), kind = "focus",
                workspaceId = activeWs(),
            )
        )
    }
    /** T2: total minutes tracked against a task (all intervals, focus + manual). Single source of truth. */
    suspend fun trackedMinutesForTask(taskId: String): Int =
        timeTrack.getEntries().filter { it.taskId == taskId }.sumOf { it.minutes(now()) }
    /** T2: a stable "Tasks" activity — the generic bucket for time tracked against a task with no chosen activity. */
    suspend fun ensureTaskActivity(): String {
        timeTrack.getActivities().firstOrNull { it.name == "Tasks" && !it.archived }?.let { return it.id }
        return createTimeActivity("Tasks", "📋", 0xFF3E6DDFL)
    }

    private val filters = db.filterDao()
    val allFilters: Flow<List<FilterEntity>> = filters.observeAll()
    suspend fun upsertFilter(f: FilterEntity) = filters.upsert(f)
    suspend fun deleteFilter(id: String) = filters.deleteById(id)
    suspend fun setFilterOrder(orderedIds: List<String>) {
        val byId = filters.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { filters.upsert(it.copy(sortOrder = i.toDouble())) } }
    }
    suspend fun createFilter(name: String, workspaceId: String): String {
        val id = uid()
        filters.upsert(FilterEntity(id = id, name = name, sortOrder = now().toDouble(), workspaceId = workspaceId))
        return id
    }

    fun observeTask(id: String): Flow<TaskEntity?> = tasks.observeById(id)
    suspend fun getTask(id: String): TaskEntity? = tasks.getById(id)
    suspend fun allTasksOnce(): List<TaskEntity> = tasks.getAll()
    suspend fun allListsOnce(): List<ListEntity> = lists.getAll()
    suspend fun setCompletedById(id: String, completed: Boolean) {
        tasks.getById(id)?.let { setCompleted(it, completed) }
    }

    private fun now() = System.currentTimeMillis()
    private fun uid() = UUID.randomUUID().toString()

    // ============ notes (v66) ============
    private val noteRevisions = db.noteRevisionDao()
    private val noteLinks = db.noteLinkDao()
    private val smartViews = db.smartViewDao()
    private val noteCards = db.noteCardDao()
    fun observeNotes(): Flow<List<com.todocompanion.app.data.entity.NoteEntity>> = notes.observeAll()
    /** W2 (scale) — DB-side workspace+trashed filter (index-backed), replacing the in-memory VM filter. */
    fun observeNotesByWorkspace(ws: String, trashed: Boolean): Flow<List<com.todocompanion.app.data.entity.NoteEntity>> =
        notes.observeByWorkspace(ws, trashed)
    /** W2 (scale) — DB-side active-workspace task set (index-backed), replacing the in-memory wsTasks filter. */
    fun observeTasksByWorkspace(ws: String): Flow<List<TaskEntity>> =
        tasks.observeWorkspaceScoped(ws, ListEntity.INBOX_ID)
    fun observeNotebooks(): Flow<List<com.todocompanion.app.data.entity.NotebookEntity>> = notebooks.observeAll()
    // W3 (cross-module unification) — Goals & their review log now live in Room (Increment 2). The table is the
    // runtime source of truth; the settings `goals`/`goal_reviews` k/v entries are kept only as the backward-
    // compatible BACKUP transport (regenerated from the table at export, consumed into the table at import).
    fun observeGoals(): Flow<List<com.todocompanion.app.domain.Goal>> = goals.observeAll().map { it.map { e -> e.toDomain() } }
    fun observeGoalReviews(): Flow<List<com.todocompanion.app.domain.GoalReview>> = goals.observeReviews().map { it.map { e -> e.toDomain() } }
    suspend fun goalsFromTableOnce(): List<com.todocompanion.app.data.entity.GoalEntity> = goals.getAll()
    suspend fun goalReviewsFromTableOnce(): List<com.todocompanion.app.data.entity.GoalReviewEntity> = goals.getAllReviews()
    // W3 (routines→Room, Increment 2) — Routines & their run history now live in Room. The table is the runtime
    // source of truth; the settings `routines`/`routine_runs` k/v entries are kept only as the backward-compatible
    // BACKUP transport (regenerated from the table at export, consumed into the table at import).
    fun observeRoutines(): Flow<List<com.todocompanion.app.domain.Routine>> = routines.observeAll().map { it.map { e -> e.toDomain() } }
    fun observeRoutineRuns(): Flow<List<com.todocompanion.app.domain.RoutineRun>> = routines.observeRuns().map { it.map { e -> e.toDomain() } }
    suspend fun routinesOnce(): List<com.todocompanion.app.domain.Routine> = routines.getAll().map { it.toDomain() }
    suspend fun routineRunsOnce(): List<com.todocompanion.app.domain.RoutineRun> = routines.getAllRuns().map { it.toDomain() }
    suspend fun routinesFromTableOnce(): List<com.todocompanion.app.data.entity.RoutineEntity> = routines.getAll()
    suspend fun routineRunsFromTableOnce(): List<com.todocompanion.app.data.entity.RoutineRunEntity> = routines.getAllRuns()
    private fun routineWsOf(ws: String) = ws.ifBlank { com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID }
    /** Replace the ACTIVE workspace's routines with [list] (leaving other workspaces' intact) — mirrors the old
     *  settings-JSON saveRoutines semantics exactly, against the table, in one transaction. */
    suspend fun replaceWorkspaceRoutines(ws: String, list: List<com.todocompanion.app.domain.Routine>) {
        db.withTransaction {
            val keepIds = list.map { it.id }.toSet()
            routines.getAll().filter { routineWsOf(it.workspaceId) == ws && it.id !in keepIds }.forEach { routines.deleteById(it.id) }
            routines.upsertAll(list.map { it.toEntity() })
        }
    }
    suspend fun deleteRoutine(id: String) = routines.deleteById(id)
    /** Append a press-play run, keeping the newest 400 (matches the old JSON cap + the backup transport cap). */
    suspend fun appendRoutineRun(run: com.todocompanion.app.domain.RoutineRun) {
        db.withTransaction { routines.upsertRuns(listOf(run.toEntity())); routines.trimRunsTo(400) }
    }
    /** One-time, idempotent safety net for the JSON→table flip: adopt into the tables any routine (by id) or run
     *  (by routineId+startedAtMillis) that still exists only in the legacy settings-JSON. Additive; never deletes. */
    suspend fun reconcileRoutinesFromLegacyJson(routinesJson: String, runsJson: String) {
        val haveRoutineIds = routines.getAll().map { it.id }.toSet()
        val missingRoutines = com.todocompanion.app.domain.Routines.parse(routinesJson).filter { it.id !in haveRoutineIds }
        if (missingRoutines.isNotEmpty()) routines.upsertAll(missingRoutines.map { it.toEntity() })
        val haveRunKeys = routines.getAllRuns().map { it.routineId to it.startedAtMillis }.toSet()
        val missingRuns = com.todocompanion.app.domain.RoutineRuns.parse(runsJson).filter { (it.routineId to it.startedAtMillis) !in haveRunKeys }
        if (missingRuns.isNotEmpty()) { routines.upsertRuns(missingRuns.map { it.toEntity() }); routines.trimRunsTo(400) }
    }
    private fun goalWsOf(ws: String) = ws.ifBlank { com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID }
    /** Replace the ACTIVE workspace's goals with [list] (leaving other workspaces' goals intact) — mirrors the
     *  old settings-JSON saveGoals semantics exactly, but against the table, in one transaction. */
    suspend fun replaceWorkspaceGoals(ws: String, list: List<com.todocompanion.app.domain.Goal>) {
        db.withTransaction {
            val keepIds = list.map { it.id }.toSet()
            goals.getAll().filter { goalWsOf(it.workspaceId) == ws && it.id !in keepIds }.forEach { goals.deleteById(it.id) }
            goals.upsertAll(list.map { it.toEntity() })
        }
    }
    suspend fun deleteGoal(id: String) = goals.deleteById(id)
    /** Replace the whole review log (reviews are global, not workspace-scoped — as in the old blob). */
    suspend fun replaceGoalReviews(list: List<com.todocompanion.app.domain.GoalReview>) {
        db.withTransaction { goals.clearReviews(); goals.upsertReviews(list.takeLast(500).map { it.toEntity() }) }
    }
    /** One-time, idempotent safety net for the JSON→table flip: adopt into the table any goal/review that
     *  still exists only in the legacy settings-JSON (e.g. one created on an Increment-1 build before the flip).
     *  Additive — never deletes — so it can run every startup harmlessly. */
    suspend fun reconcileGoalsFromLegacyJson(goalsJson: String, reviewsJson: String) {
        val haveGoalIds = goals.getAll().map { it.id }.toSet()
        val missingGoals = com.todocompanion.app.domain.Goals.parse(goalsJson).filter { it.id !in haveGoalIds }
        if (missingGoals.isNotEmpty()) goals.upsertAll(missingGoals.map { it.toEntity() })
        val haveRevIds = goals.getAllReviews().map { it.id }.toSet()
        val missingRev = com.todocompanion.app.domain.GoalReviews.parse(reviewsJson).filter { it.id !in haveRevIds }
        if (missingRev.isNotEmpty()) goals.upsertReviews(missingRev.map { it.toEntity() })
    }
    suspend fun getNotesOnce(): List<com.todocompanion.app.data.entity.NoteEntity> = notes.getAll()
    suspend fun getNote(id: String): com.todocompanion.app.data.entity.NoteEntity? = notes.getById(id)
    // Wave F/H — set/clear a note's reminder (metadata-only; leaves updatedAt/FTS/links alone).
    suspend fun setNoteReminderAt(id: String, atMillis: Long?) = notes.setReminderAt(id, atMillis)
    suspend fun setNoteReminderAll(id: String, at: Long?, rrule: String?, extra: String, keep: Boolean) =
        notes.setReminderAll(id, at, rrule, extra, keep)
    suspend fun setNoteReminderPrimary(id: String, at: Long?, rrule: String?) = notes.setReminderPrimary(id, at, rrule)
    suspend fun setNoteReminderExtra(id: String, extra: String) = notes.setReminderExtra(id, extra)
    suspend fun clearNoteReminder(id: String) = notes.clearReminderAll(id)
    suspend fun setNoteSealedUntil(id: String, until: Long?) = notes.setSealedUntil(id, until)
    suspend fun getNotebooksOnce(): List<com.todocompanion.app.data.entity.NotebookEntity> = notebooks.getAll()

    /** Create (or update) a note, stamping timestamps + sort order, and keep the FTS index fresh. */
    suspend fun upsertNote(n: com.todocompanion.app.data.entity.NoteEntity): String {
        val id = n.id.ifBlank { uid() }
        // L11 — a vaulted note's body is a ciphertext envelope (the VM encrypts before calling here). It must
        // never be scanned as plaintext: no derived preview, no tag/context/link materialization, no FTS.
        val vaulted = n.vault && com.todocompanion.app.domain.NoteVault.isLocked(n.body)
        val stamped = n.copy(
            id = id,
            sortOrder = if (n.sortOrder == 0.0) now().toDouble() else n.sortOrder,
            createdAt = if (n.createdAt == 0L) now() else n.createdAt,
            updatedAt = now(),
            // P7 — materialize the card's derived render data so the home list reads columns, not regex.
            preview = if (vaulted) "🔒 Locked" else com.todocompanion.app.domain.NoteDerived.preview(n.body),
            hasOpen = if (vaulted) false else com.todocompanion.app.domain.NoteDerived.hasOpenItems(n.body),
        )
        notes.upsert(stamped)
        if (vaulted) {
            // Purge any structured trace from a prior plaintext save, and keep the note out of search.
            notes.unlinkAllTagsForNote(id); notes.unlinkAllContextsForNote(id); noteLinks.clearForNote(id)
            runCatching { deleteNoteFts(ftsDb(), id) }
            noteCards.deleteForNote(id)   // Wave 3 — no flashcards from ciphertext
        } else {
            materializeNoteTags(id)      // inline body #tags → structured note_tags (before FTS so tag names index)
            materializeNoteContexts(id)  // L2 — inline body @contexts → structured note_contexts (first-class, like tags)
            materializeNoteLinks(id)
            syncBoundCheckboxTasks(id)   // L5 — a ticked "- [ ] [[Task]]" line completes the task it's bound to
            materializeNoteCards(stamped)  // Wave 3 — derive Active-Recall cards, preserving each card's schedule
            // Wave 2 · Privacy Governance Dial — a note flagged noIndex keeps its structural links/tags but
            // is dropped from FTS so it never surfaces in search / Ask / related.
            if (n.noIndex) runCatching { deleteNoteFts(ftsDb(), id) } else reindexNoteFts(stamped)
        }
        return id
    }

    // ── Wave 3 · Active Recall — materialize/query/grade flashcards derived from note bodies ──────────
    /** Re-derive [note]'s cards from its body, preserving the SM-2 schedule of cards that still exist. */
    private suspend fun materializeNoteCards(note: com.todocompanion.app.data.entity.NoteEntity) {
        val parsed = com.todocompanion.app.domain.NoteCards.parse(note.id, note.body)
        if (parsed.isEmpty()) { noteCards.deleteForNote(note.id); return }
        val nowMs = now()
        val keep = ArrayList<String>(parsed.size)
        for (c in parsed) {
            keep.add(c.id)
            val existing = noteCards.byId(c.id)
            if (existing == null) {
                val s = com.todocompanion.app.domain.NoteCards.fresh(nowMs)
                noteCards.upsert(com.todocompanion.app.data.entity.NoteCardEntity(
                    id = c.id, noteId = note.id, front = c.front, back = c.back, cardKind = c.kind,
                    easiness = s.easiness, intervalDays = s.intervalDays, reps = s.reps, lapses = s.lapses,
                    dueAt = s.dueAt, lastGradedAt = 0L, createdAt = nowMs))
            } else if (existing.front != c.front || existing.back != c.back || existing.cardKind != c.kind) {
                noteCards.updateContent(c.id, c.front, c.back, c.kind)
            }
        }
        noteCards.deleteForNoteExcept(note.id, keep)
    }

    suspend fun dueNoteCards(nowMs: Long = now()): List<com.todocompanion.app.data.entity.NoteCardEntity> = noteCards.due(nowMs)
    suspend fun dueNoteCardCount(nowMs: Long = now()): Int = noteCards.dueCount(nowMs)
    suspend fun noteCardsForNote(noteId: String): List<com.todocompanion.app.data.entity.NoteCardEntity> = noteCards.forNote(noteId)
    suspend fun allNoteCards(): List<com.todocompanion.app.data.entity.NoteCardEntity> = noteCards.getAll()

    /** Apply a grade to a card (SM-2), persist the new schedule, and return the updated row. */
    suspend fun gradeNoteCard(id: String, grade: com.todocompanion.app.domain.NoteCards.Grade): com.todocompanion.app.data.entity.NoteCardEntity? {
        val card = noteCards.byId(id) ?: return null
        val nowMs = now()
        val s = com.todocompanion.app.domain.NoteCards.schedule(
            com.todocompanion.app.domain.NoteCards.Sched(card.easiness, card.intervalDays, card.reps, card.lapses, card.dueAt),
            grade, nowMs)
        val updated = card.copy(easiness = s.easiness, intervalDays = s.intervalDays, reps = s.reps,
            lapses = s.lapses, dueAt = s.dueAt, lastGradedAt = nowMs)
        noteCards.upsert(updated)
        return updated
    }

    /** Wave 3 · Notes ⇄ Goals — the note bound to a goal as its reflective evidence/journal, or null. */
    suspend fun goalJournalNote(goalId: String): com.todocompanion.app.data.entity.NoteEntity? =
        notes.getAll().firstOrNull { !it.trashed && it.linkedGoalId == goalId }

    /**
     * L5 — push direction of Shared Checkboxes: for each checkbox line bound to a task by a `[[Title]]`
     * wiki-link, set that task's completed state to match the box. So ticking an action item inside a note
     * completes the real task (and un-ticking re-opens it). Title match is case-insensitive; only the first
     * non-trashed task with that title is bound. Idempotent — a no-op when the states already agree.
     */
    suspend fun syncBoundCheckboxTasks(noteId: String) {
        val n = notes.getById(noteId) ?: return
        val boxes = com.todocompanion.app.domain.NoteCheckboxSync.boundBoxes(n.body)
        if (boxes.isEmpty()) return
        val byTitle = tasks.getAll().filter { !it.trashed }.associateBy { it.title.trim().lowercase() }
        boxes.forEach { box ->
            val task = byTitle[box.title.trim().lowercase()] ?: return@forEach
            if (task.completed != box.checked) setCompleted(task, box.checked)
        }
    }

    /**
     * L5 — pull direction: rewrite a note's bound checkbox lines to match the live completion state of the
     * tasks they link to, persisting only when something actually changed. Called when a note opens, so
     * completing a task elsewhere shows up (checked) the next time you read the note. Loop-safe: after this
     * the note and its tasks agree, so the save-time push is a no-op.
     */
    suspend fun reconcileNoteCheckboxesFromTasks(noteId: String) {
        val n = notes.getById(noteId) ?: return
        if (!com.todocompanion.app.domain.NoteCheckboxSync.hasBound(n.body)) return
        val byTitle = tasks.getAll().filter { !it.trashed }.associateBy { it.title.trim().lowercase() }
        val newBody = com.todocompanion.app.domain.NoteCheckboxSync.reconcile(n.body) { title -> byTitle[title.trim().lowercase()]?.completed }
        if (newBody != n.body) upsertNote(n.copy(body = newBody))
    }

    /**
     * P6 — one tag set. Materialize the inline `#tags` in a note's body into structured [note_tags] rows
     * (creating a workspace tag once per name), so the header chips, the `untagged`/`hasTag` Smart-View
     * predicates, and the Wrapped recap all agree on the same tags instead of tracking two parallel
     * notions. Additive + idempotent (INSERT OR IGNORE), exactly like [materializeNoteLinks]: the body is
     * the source, so an inline tag reappears on the next save if re-typed, and a tag assigned only through
     * the picker (never in the body) is never removed here.
     */
    suspend fun materializeNoteTags(noteId: String) {
        val n = notes.getById(noteId) ?: return
        val names = com.todocompanion.app.domain.NoteGrammar.TAG.findAll(n.body)
            .map { it.groupValues[1] }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.toList()
        if (names.isEmpty()) return
        val ws = n.workspaceId
        val byName = tags.getAll().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }
        val refs = names.map { name ->
            val id = byName[name.lowercase()]?.id ?: uid().also {
                tags.upsert(com.todocompanion.app.data.entity.TagEntity(it, name, workspaceId = ws))
            }
            com.todocompanion.app.data.entity.NoteTagCrossRef(noteId, id)
        }
        notes.linkTags(refs)
    }

    /** Wave I / L2 — (re)index a note into note_fts, mirroring its tag + context + attachment names into the
     *  body text so search finds the note by those too (child-row denormalization). Safe to call often. */
    suspend fun reindexNoteFts(note: com.todocompanion.app.data.entity.NoteEntity) {
        val extra = buildList {
            runCatching { addAll(notes.tagNamesForNote(note.id)) }
            runCatching { addAll(notes.contextNamesForNote(note.id)) }
            runCatching { addAll(notes.attachmentNamesForNote(note.id)) }
        }.joinToString(" ")
        val body = if (extra.isBlank()) note.body else note.body + "\n" + extra
        syncNoteFts(note.id, note.title, body)
    }

    /**
     * L2 — one context set. Materialize inline `@contexts` in a note's body into structured [note_contexts]
     * rows (creating a workspace context once per name), the exact mirror of [materializeNoteTags]: additive
     * + idempotent, body is the source, a picker-only context is never removed here. This makes `@context`
     * a first-class inline citizen alongside `#tags`, so chips, Smart-View predicates and search all agree.
     */
    suspend fun materializeNoteContexts(noteId: String) {
        val n = notes.getById(noteId) ?: return
        val names = com.todocompanion.app.domain.NoteGrammar.CONTEXT.findAll(n.body)
            .map { it.groupValues[1] }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.toList()
        if (names.isEmpty()) return
        val ws = n.workspaceId
        val byName = contexts.getAll().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }
        val refs = names.map { name ->
            val id = byName[name.lowercase()]?.id ?: uid().also {
                contexts.upsert(com.todocompanion.app.data.entity.ContextEntity(id = it, name = name, workspaceId = ws))
            }
            com.todocompanion.app.data.entity.NoteContextCrossRef(noteId, id)
        }
        notes.linkContexts(refs)
    }

    /** Soft-delete → Trash (kept for a possible restore, like tasks). FTS row dropped so it stops matching.
     *  Stamps deletedAt/deletedBy so the auto-empty-trash sweep can age it out. */
    suspend fun trashNote(id: String, trashed: Boolean = true) {
        notes.getById(id)?.let {
            notes.upsert(it.copy(
                trashed = trashed, updatedAt = now(),
                deletedAt = if (trashed) now() else null,
                deletedBy = if (trashed) "user" else null,
            ))
        }
        if (trashed) runCatching { deleteNoteFts(ftsDb(), id) } else notes.getById(id)?.let { syncNoteFts(id, it.title, it.body) }
    }

    /**
     * Hard-delete a note and every child row hanging off it (tags, contexts, attachments, revisions,
     * links, FTS). This method IS the referential-integrity contract for notes: the schema declares no
     * SQL foreign keys (see [com.todocompanion.app.data.entity.NoteEntity]'s KDoc for why), so the
     * repository is the single writer that keeps the graph orphan-free. An orphan-free repository test
     * pins this guarantee — if a new child table is added for notes, its cleanup belongs here.
     */
    suspend fun deleteNote(id: String) {
        notes.unlinkAllTagsForNote(id)
        notes.unlinkAllContextsForNote(id)
        attachments.deleteForNote(id)
        noteRevisions.clearForNote(id)
        noteLinks.clearForNote(id)
        noteCards.deleteForNote(id)      // Wave 3 — cascade Active-Recall cards with the note
        notes.deleteById(id)
        runCatching { deleteNoteFts(ftsDb(), id) }
    }

    suspend fun setNoteTags(noteId: String, tagIds: List<String>) {
        notes.unlinkAllTagsForNote(noteId)
        notes.linkTags(tagIds.map { com.todocompanion.app.data.entity.NoteTagCrossRef(noteId, it) })
        notes.getById(noteId)?.let { reindexNoteFts(it) }   // Wave I — keep tag names in the FTS index fresh
    }
    suspend fun setNoteContexts(noteId: String, contextIds: List<String>) {
        notes.unlinkAllContextsForNote(noteId)
        notes.linkContexts(contextIds.map { com.todocompanion.app.data.entity.NoteContextCrossRef(noteId, it) })
        notes.getById(noteId)?.let { reindexNoteFts(it) }   // L2 — keep context names in the FTS index fresh
    }
    suspend fun getNoteTagCrossRefs(): List<com.todocompanion.app.data.entity.NoteTagCrossRef> = notes.getTagCrossRefs()
    /** Live note↔tag links — so the editor reflects a tag toggle immediately (writing note_tags doesn't touch the notes table). */
    fun observeNoteTagCrossRefs(): kotlinx.coroutines.flow.Flow<List<com.todocompanion.app.data.entity.NoteTagCrossRef>> = notes.observeTagCrossRefs()
    /** Live note↔context links — so the editor reflects a context toggle immediately (mirrors tags). */
    fun observeNoteContextCrossRefs(): kotlinx.coroutines.flow.Flow<List<com.todocompanion.app.data.entity.NoteContextCrossRef>> = notes.observeContextCrossRefs()
    // Wave L — a note's attachment rows (for the rich renderer's inline-image resolution).
    suspend fun noteAttachments(noteId: String): List<com.todocompanion.app.data.entity.AttachmentEntity> = notes.attachmentsForNote(noteId)
    suspend fun getNoteContextCrossRefs(): List<com.todocompanion.app.data.entity.NoteContextCrossRef> = notes.getContextCrossRefs()

    suspend fun upsertNotebook(nb: com.todocompanion.app.data.entity.NotebookEntity): String {
        val id = nb.id.ifBlank { uid() }
        notebooks.upsert(nb.copy(
            id = id,
            sortOrder = if (nb.sortOrder == 0.0) now().toDouble() else nb.sortOrder,
            createdAt = if (nb.createdAt == 0L) now() else nb.createdAt,
        ))
        return id
    }
    /** Delete a notebook; its notes fall back to "no notebook" (never deleted with the notebook). */
    suspend fun deleteNotebook(id: String) {
        notes.getAll().filter { it.notebookId == id }.forEach { notes.upsert(it.copy(notebookId = null, updatedAt = now())) }
        notebooks.deleteById(id)
    }

    // ---- Wave B: archive · duplicate · version history · auto-empty-trash ----
    /** Archive (or un-archive) — a third state beyond Trash: out of the main list but still live. */
    suspend fun archiveNote(id: String, archived: Boolean = true) {
        notes.getById(id)?.let { notes.upsert(it.copy(archived = archived, updatedAt = now())) }
    }

    /** Duplicate a note (body, colour, container, tags & contexts) as a fresh, un-pinned note. */
    suspend fun duplicateNote(id: String): String? {
        val n = notes.getById(id) ?: return null
        val newId = uid()
        notes.upsert(n.copy(
            id = newId, title = n.title.ifBlank { "Untitled" } + " (copy)",
            pinned = false, favorite = false, archived = false, trashed = false, deletedAt = null, deletedBy = null,
            // A copy starts clean: no reminder (we never arm one for the copy, so a carried-over pill would
            // be a lie) and never sealed (a duplicate shouldn't silently vanish behind a future reveal date).
            reminderAt = null, reminderRrule = null, reminderExtra = "", reminderKeep = false, sealedUntil = null,
            createdAt = now(), updatedAt = now(), sortOrder = now().toDouble(),
        ))
        val tagIds = notes.getTagCrossRefs().filter { it.noteId == id }.map { it.tagId }
        val ctxIds = notes.getContextCrossRefs().filter { it.noteId == id }.map { it.contextId }
        if (tagIds.isNotEmpty()) notes.linkTags(tagIds.map { com.todocompanion.app.data.entity.NoteTagCrossRef(newId, it) })
        if (ctxIds.isNotEmpty()) notes.linkContexts(ctxIds.map { com.todocompanion.app.data.entity.NoteContextCrossRef(newId, it) })
        notes.getById(newId)?.let { syncNoteFts(newId, it.title, it.body) }
        return newId
    }

    fun observeNoteRevisions(noteId: String): Flow<List<com.todocompanion.app.data.entity.NoteRevisionEntity>> = noteRevisions.observeForNote(noteId)
    suspend fun getNoteRevisionsOnce(): List<com.todocompanion.app.data.entity.NoteRevisionEntity> = noteRevisions.getAll()

    /** Capture a version snapshot if the note changed since the last one; prune to [keep] newest. */
    suspend fun saveNoteRevision(noteId: String, keep: Int) {
        val n = notes.getById(noteId) ?: return
        if (n.title.isBlank() && n.body.isBlank()) return
        val last = noteRevisions.latestForNote(noteId)
        if (last != null && last.title == n.title && last.body == n.body) return
        val delta = n.body.length - (last?.body?.length ?: 0)
        noteRevisions.insert(com.todocompanion.app.data.entity.NoteRevisionEntity(
            id = uid(), noteId = noteId, createdAt = now(), title = n.title, body = n.body, charDelta = delta,
        ))
        noteRevisions.pruneForNote(noteId, keep.coerceAtLeast(1))
    }

    /** Lazy on-launch sweep: hard-delete trashed notes older than [retentionDays] (0 = never). */
    suspend fun purgeExpiredTrashedNotes(retentionDays: Int) {
        if (retentionDays <= 0) return
        val cutoff = now() - retentionDays.toLong() * 86_400_000L
        notes.getAll().filter { it.trashed && (it.deletedAt ?: 0L) in 1 until cutoff }.forEach { deleteNote(it.id) }
    }

    // ---- Wave C: cross-module [[wiki-link]] edges (materialized on save) ----
    /** Re-derive this note's outgoing links from its body, resolving each [[title]] to a note / task /
     *  habit / event (first match, in that priority); an unresolved title is stored with targetId "". */
    suspend fun materializeNoteLinks(noteId: String) {
        val n = notes.getById(noteId) ?: return
        noteLinks.clearForNote(noteId)
        val titles = com.todocompanion.app.domain.NoteLinks.outgoingTitles(n.body)
        if (titles.isEmpty()) return
        val allNotes = notes.getAll(); val allTasks = tasks.getAll(); val allHabits = habits.getAll(); val allEvents = events.getAll()
        fun norm(s: String) = s.trim().lowercase()
        val rows = titles.map { t ->
            val key = norm(t)
            val note = allNotes.firstOrNull { !it.trashed && it.id != noteId && norm(it.title) == key }
            val task = if (note == null) allTasks.firstOrNull { norm(it.title) == key } else null
            val habit = if (note == null && task == null) allHabits.firstOrNull { norm(it.name) == key } else null
            val event = if (note == null && task == null && habit == null) allEvents.firstOrNull { norm(it.title) == key } else null
            when {
                note != null -> com.todocompanion.app.data.entity.NoteLinkEntity(noteId, t, "note", note.id)
                task != null -> com.todocompanion.app.data.entity.NoteLinkEntity(noteId, t, "task", task.id)
                habit != null -> com.todocompanion.app.data.entity.NoteLinkEntity(noteId, t, "habit", habit.id)
                event != null -> com.todocompanion.app.data.entity.NoteLinkEntity(noteId, t, "event", event.id)
                else -> com.todocompanion.app.data.entity.NoteLinkEntity(noteId, t, "note", "")
            }
        }.distinctBy { it.targetTitle }
        noteLinks.insertAll(rows)
    }
    suspend fun notesLinkingTo(type: String, id: String): List<String> = noteLinks.notesLinkingTo(type, id)
    fun observeNoteLinks(noteId: String): kotlinx.coroutines.flow.Flow<List<com.todocompanion.app.data.entity.NoteLinkEntity>> = noteLinks.observeForNote(noteId)
    suspend fun getNoteLinksOnce(): List<com.todocompanion.app.data.entity.NoteLinkEntity> = noteLinks.getAll()

    /** Wave E — titles of existing notes/tasks/habits/events that appear in [body] but aren't `[[linked]]`
     *  yet (the "unlinked mentions" affordance). Computed against the passed body so it tracks the draft. */
    suspend fun unlinkedMentions(body: String, excludeNoteId: String): List<String> {
        if (body.isBlank()) return emptyList()
        val lower = body.lowercase()
        val linked = com.todocompanion.app.domain.NoteLinks.outgoingTitles(body).map { it.lowercase() }.toSet()
        val self = notes.getById(excludeNoteId)?.title?.trim()?.lowercase()
        val titles = (
            notes.getAll().filter { !it.trashed && it.id != excludeNoteId }.map { it.title } +
                tasks.getAll().map { it.title } + habits.getAll().map { it.name } + events.getAll().map { it.title }
            ).map { it.trim() }.filter { it.length >= 3 }.distinct()
        return titles.filter { t -> val tl = t.lowercase(); tl != self && tl !in linked && lower.contains(tl) }.take(8)
    }

    /** Wave E (moonshot) — the self-writing daily note: a Markdown digest of a day, assembled from the
     *  rest of the app (tasks completed, time tracked, habits kept, felt rating). No notes app can do
     *  this; Kairo can, because the day's real data lives right here. */
    suspend fun dayDigestMarkdown(epochDay: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = start + 86_400_000L
        val doneTasks = tasks.getAll().filter { val c = it.completedAt; c != null && c in start until end }
        val entries = timeTrack.getEntries().filter { it.startMillis in start until end }
        val trackedMin = entries.sumOf { val e = it.endMillis; (if (e != null) e - it.startMillis else 0L).coerceAtLeast(0L) } / 60_000L
        val checkins = habits.getCheckins().filter { it.epochDay == epochDay && it.count > 0 }
        val habitNames = habits.getAll().associateBy { it.id }
        val log = dayLogs.getAll().firstOrNull { it.epochDay == epochDay }
        return buildString {
            append("## Today\n")
            append("- ✅ Tasks completed: ${doneTasks.size}")
            if (doneTasks.isNotEmpty()) append(" — " + doneTasks.take(6).joinToString(", ") { it.title })
            append("\n")
            if (trackedMin > 0) append("- ⏱ Time tracked: ${trackedMin / 60}h ${trackedMin % 60}m across ${entries.size} block${if (entries.size == 1) "" else "s"}\n")
            if (checkins.isNotEmpty()) append("- 🔁 Habits: " + checkins.mapNotNull { habitNames[it.habitId]?.name }.take(6).joinToString(", ") + "\n")
            if (log != null && log.dayRating > 0) append("- 🙂 Day rating: ${log.dayRating}/5\n")
        }
    }

    // ---- Wave D: Smart Views ----
    fun observeSmartViews(): kotlinx.coroutines.flow.Flow<List<com.todocompanion.app.data.entity.SmartViewEntity>> = smartViews.observeAll()
    suspend fun getSmartViewsOnce(): List<com.todocompanion.app.data.entity.SmartViewEntity> = smartViews.getAll()
    suspend fun upsertSmartView(v: com.todocompanion.app.data.entity.SmartViewEntity): String {
        val id = v.id.ifBlank { uid() }
        smartViews.upsert(v.copy(
            id = id,
            sortOrder = if (v.sortOrder == 0.0) now().toDouble() else v.sortOrder,
            createdAt = if (v.createdAt == 0L) now() else v.createdAt,
        ))
        return id
    }
    suspend fun deleteSmartView(id: String) = smartViews.deleteById(id)

    // ============ tasks ============
    suspend fun createTask(
        listId: String,
        title: String,
        parentId: String? = null,
        importance: Int = 2,   // default "None" priority (was 3 = Low)
        urgency: Int = 2,
        dueDate: Long? = null,
        startDate: Long? = null,
        folderId: String? = null,
    ): String {
        val id = uid()
        val order = tasks.maxSortOrder(listId, parentId) + 1.0
        // R64 — stamp the owning workspace so isolation holds even in the SHARED Inbox. Derive it from the
        // task's container (its list's / folder's workspace); a bare Inbox capture belongs to the workspace
        // you captured it from. This is what keeps a workspace's Inbox tasks out of OTHER workspaces'
        // smart lists (Someday/Today/…), while the Inbox list itself stays the one shared surface.
        val ws = when {
            listId == ListEntity.INBOX_ID -> activeWs()
            listId.isNotBlank() -> lists.getById(listId)?.workspaceId ?: activeWs()
            folderId != null -> folders.getById(folderId)?.workspaceId ?: activeWs()
            else -> activeWs()
        }
        tasks.upsert(
            TaskEntity(
                id = id,
                listId = listId,
                folderId = folderId,
                parentId = parentId,
                sortOrder = order,
                title = title.ifBlank { "Untitled" },
                importance = importance,
                urgency = urgency,
                dueDate = dueDate,
                startDate = startDate,
                workspaceId = ws,
                createdAt = now(),
                updatedAt = now(),
            )
        )
        syncTaskFts(id, title.ifBlank { "Untitled" }, "")
        logActivity(id, "created")
        return id
    }

    /**
     * One-shot quick capture for the home-screen popup widget and app shortcuts: parse natural-language
     * text (date/time, p1-p4, #estimate, *, ~list) and create the task in the Inbox (or a named ~list),
     * entirely off the UI thread. Returns the new id, or null if the text has no title. Fully offline.
     */
    suspend fun quickCaptureTask(text: String): String? {
        val tok = com.todocompanion.app.domain.nlp.QuickTokens.parse(text, handleActivity = false)
        val parsed = com.todocompanion.app.domain.nlp.QuickAddParser.parse(tok.text)
        val title = parsed.title.trim()
        if (title.isBlank()) return null
        val zone = java.time.ZoneId.systemDefault()
        val due = parsed.dateTime?.atZone(zone)?.toInstant()?.toEpochMilli()
        val imp = parsed.priority?.importance ?: 2
        val urg = parsed.priority?.urgency ?: 2
        // Make sure the Inbox exists (first-run seeding may not have happened if a widget fires first).
        if (lists.getById(ListEntity.INBOX_ID) == null) lists.upsert(ListEntity(id = ListEntity.INBOX_ID, name = "Inbox", sortOrder = 0.0))
        val listId = parsed.list?.let { name -> lists.getAll().firstOrNull { !it.archived && it.name.equals(name, ignoreCase = true) }?.id }
            ?: ListEntity.INBOX_ID
        // P3 keep-vs-strip: honour the same title preference the in-app funnel uses.
        val finalTitle = if (settingsSnapshot().keepParsedText) tok.text.replace(Regex("\\s+"), " ").trim().ifBlank { title } else title
        val id = createTask(listId, finalTitle, importance = imp, urgency = urg, dueDate = due)
        if (parsed.rrule != null || tok.estimateMin != null || tok.star) getTask(id)?.let {
            saveTask(it.copy(rrule = parsed.rrule ?: it.rrule, estimateMin = tok.estimateMin ?: it.estimateMin, star = it.star || tok.star))
        }
        if (parsed.hasTime && due != null)
            upsertReminder(com.todocompanion.app.data.entity.ReminderEntity(uid(), taskId = id, type = "absolute", atTime = due))
        return id
    }

    suspend fun saveTask(task: TaskEntity) {
        // Capture user-visible reschedules for the activity log (title/note edits don't log).
        val old = tasks.getById(task.id)
        var saved = task.copy(updatedAt = now())
        // R37 · deferral chain ("never defer twice"): pushing a due-today-or-overdue OPEN task to a later
        // day counts as a defer (once per day). Moving it earlier resets the chain. Recurrence advances a
        // just-completed task, so the "both open" guard keeps those from counting.
        val od = old?.dueDate; val nd = saved.dueDate
        if (old != null && !old.completed && !saved.completed && od != null && nd != null) {
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone).toEpochDay()
            val oldDay = java.time.Instant.ofEpochMilli(od).atZone(zone).toLocalDate().toEpochDay()
            val newDay = java.time.Instant.ofEpochMilli(nd).atZone(zone).toLocalDate().toEpochDay()
            if (newDay > oldDay && oldDay <= today) {
                val bump = if (saved.lastDeferDay == today) saved.deferCount else saved.deferCount + 1
                saved = saved.copy(deferCount = bump, lastDeferDay = today)
            } else if (newDay < oldDay) {
                saved = saved.copy(deferCount = 0)
            }
        }
        tasks.upsert(saved)
        syncTaskFts(saved.id, saved.title, saved.note)
        if (old != null && old.dueDate != task.dueDate) logActivity(task.id, "rescheduled", task.dueDate?.toString())
        // F1 — re-arm relative reminders whenever a date (or a scheduling-gate flag) moved. Cheap: the
        // guard skips the common non-date edits (title, notes, priority, drag reorder, list move, …).
        if (remindersAffected(old, saved)) markRemindersDirty(saved.id)
        maybeRecordRevision(saved)
    }

    suspend fun setCompleted(task: TaskEntity, completed: Boolean) {
        val transition = completed && !task.completed
        // R37: finishing clears the deferral chain (you did it — no penalty carried forward).
        tasks.upsert(task.copy(completed = completed, completedAt = if (completed) now() else null, abandoned = false,
            deferCount = if (completed) 0 else task.deferCount, updatedAt = now()))
        logActivity(task.id, if (completed) "completed" else "reopened")
        markRemindersDirty(task.id)   // F1 — completing/reopening changes whether alarms should be armed
        if (transition) onTaskCompleted(task)
    }
    /** V3: completing a task ticks any habit linked to it (via a shared time-activity). V12: earns a point. */
    private suspend fun onTaskCompleted(task: TaskEntity) {
        val actId = task.defaultActivityId
        if (actId != null) {
            val day = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
            habits.getAll().filter { it.timeActivityId == actId && !it.archived && !it.paused && !it.trashed && it.linkMode != "off" && it.habitType != "break" }.forEach { h ->
                val cur = habits.getCheckins().firstOrNull { it.habitId == h.id && it.epochDay == day }?.count ?: 0
                setCheckinValue(h.id, day, cur + h.clickIncrement.coerceAtLeast(1))
            }
        }
        awardPoints(1)
    }
    /** V12: add momentum points to the wallet (earned by finishing work). */
    suspend fun awardPoints(n: Int) {
        if (n == 0) return
        val s = settingsSnapshot()
        saveSettings(s.copy(pointsBalance = (s.pointsBalance + n).coerceAtLeast(0)))
    }

    /**
     * P1: a recurring task advances in place on completion (it never sits "completed"), so it would
     * otherwise leave no completion record. Log one explicitly — the timestamped "completed" rows are
     * what the reliability score and time-of-day rhythm read.
     */
    suspend fun logRecurringCompletion(taskId: String) = logActivity(taskId, "completed")

    suspend fun setAbandoned(task: TaskEntity, abandoned: Boolean) {
        tasks.upsert(task.copy(abandoned = abandoned, completed = false, updatedAt = now()))
        logActivity(task.id, if (abandoned) "wontdo" else "reopened")
        markRemindersDirty(task.id)   // F1 — abandoning/reopening changes whether alarms should be armed
    }

    suspend fun setCollapsed(task: TaskEntity, collapsed: Boolean) =
        tasks.upsert(task.copy(collapsed = collapsed, updatedAt = now()))

    private suspend fun subtreeIds(rootId: String): List<String> {
        val out = mutableListOf(rootId)
        var frontier = listOf(rootId)
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < 10_000) {
            val next = frontier.flatMap { tasks.childrenOf(it).map { c -> c.id } }
            out.addAll(next)
            frontier = next
        }
        return out
    }

    /** Move a task (and subtree) to Trash, or restore it. When trashing, [workspaceId] stamps which
     *  workspace owns the trash entry so a trashed task doesn't leak across workspaces via the shared Inbox. */
    suspend fun setTrashed(rootId: String, trashed: Boolean, workspaceId: String? = null) {
        val ids = subtreeIds(rootId)
        for (id in ids) {
            val t = tasks.getById(id) ?: continue
            tasks.upsert(t.copy(
                trashed = trashed, trashedAt = if (trashed) now() else null,
                workspaceId = if (trashed && workspaceId != null) workspaceId else t.workspaceId,
                updatedAt = now(),
            ))
            markRemindersDirty(id)   // F1 — trashing cancels alarms; restoring re-arms them
        }
        logActivity(rootId, if (trashed) "trashed" else "restored")
    }

    /** Permanently delete a task and its subtree. */
    suspend fun deleteSubtree(rootId: String) {
        for (id in subtreeIds(rootId)) {
            tags.unlinkAllForTask(id)
            contexts.unlinkAllForTask(id)
            // N1 — cancel each reminder's scheduled alarm before deleting its row, so a permanent delete
            // doesn't leave an orphaned exact-alarm armed. (Trashing already re-arms via the F1 signal.)
            reminders.forTask(id).forEach { onCancelReminder?.invoke(it.id) }
            reminders.deleteForTask(id)
            deps.removeAllInvolving(id)
            checklist.deleteForTask(id)
            activity.clearForTask(id)
            tasks.deleteById(id)
            runCatching { deleteTaskFts(ftsDb(), id) }   // R54 — keep the search index aligned
        }
    }

    /** Empty the Trash. When [workspaceId] is given, only tasks trashed in that workspace are purged —
     *  the shared Inbox otherwise let one workspace's "Empty Trash" delete another's trashed tasks. */
    suspend fun emptyTrash(workspaceId: String? = null) {
        tasks.getAll().filter { it.trashed && (workspaceId == null || it.workspaceId == workspaceId) }.forEach { deleteSubtree(it.id) }
    }

    private suspend fun siblingsIn(listId: String, parentId: String?): List<TaskEntity> =
        tasks.childrenIn(listId, parentId)

    suspend fun indent(task: TaskEntity) {
        val sibs = siblingsIn(task.listId, task.parentId)
        val idx = sibs.indexOfFirst { it.id == task.id }
        if (idx <= 0) return
        val newParent = sibs[idx - 1]
        val order = tasks.maxSortOrder(task.listId, newParent.id) + 1.0
        tasks.upsert(task.copy(parentId = newParent.id, sortOrder = order, updatedAt = now()))
    }

    suspend fun outdent(task: TaskEntity) {
        val parentId = task.parentId ?: return
        val parent = tasks.getById(parentId) ?: return
        tasks.upsert(task.copy(parentId = parent.parentId, sortOrder = parent.sortOrder + 0.5, updatedAt = now()))
        renormalize(task.listId, parent.parentId)
    }

    suspend fun moveUp(task: TaskEntity) = swap(task, -1)
    suspend fun moveDown(task: TaskEntity) = swap(task, +1)
    private suspend fun swap(task: TaskEntity, dir: Int) {
        val sibs = siblingsIn(task.listId, task.parentId)
        val idx = sibs.indexOfFirst { it.id == task.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        tasks.upsert(task.copy(sortOrder = other.sortOrder, updatedAt = now()))
        tasks.upsert(other.copy(sortOrder = task.sortOrder, updatedAt = now()))
    }

    private suspend fun renormalize(listId: String, parentId: String?) {
        siblingsIn(listId, parentId).sortedBy { it.sortOrder }.forEachIndexed { i, t ->
            val target = (i + 1).toDouble()
            if (t.sortOrder != target) tasks.upsert(t.copy(sortOrder = target))
        }
    }

    /** Move a task and its whole subtree to another list; the root becomes a top-level task there. */
    suspend fun moveToList(rootId: String, newListId: String) {
        val ids = subtreeIds(rootId)
        val rootOrder = tasks.maxSortOrder(newListId, null) + 1.0
        // R64 — re-home the workspace stamp with the task. A real destination list owns the task in its
        // workspace; moving into the SHARED Inbox keeps the task's origin ownership (null → keep) so it
        // still surfaces only in the workspace it came from, never leaking into others' smart lists.
        val destWs = if (newListId == ListEntity.INBOX_ID) null else lists.getById(newListId)?.workspaceId
        for (id in ids) {
            val t = tasks.getById(id) ?: continue
            val ws = destWs ?: t.workspaceId
            // Moving into a real list clears any folder-direct association so it lives in one place.
            if (id == rootId) {
                tasks.upsert(t.copy(listId = newListId, folderId = null, parentId = null, sortOrder = rootOrder, workspaceId = ws, updatedAt = now()))
            } else {
                tasks.upsert(t.copy(listId = newListId, folderId = null, workspaceId = ws, updatedAt = now()))
            }
        }
        logActivity(rootId, "moved", lists.getById(newListId)?.name)
    }

    /** Move a task (and its subtree) directly into a folder, with no list — mirrors [moveToList]. */
    suspend fun moveToFolder(rootId: String, folderId: String) {
        val ids = subtreeIds(rootId)
        val rootOrder = tasks.maxSortOrder("", null) + 1.0
        // R64 — the folder's workspace owns the task now (keeps isolation consistent on a move).
        val destWs = folders.getById(folderId)?.workspaceId
        for (id in ids) {
            val t = tasks.getById(id) ?: continue
            val ws = destWs ?: t.workspaceId
            if (id == rootId) tasks.upsert(t.copy(listId = "", folderId = folderId, parentId = null, sortOrder = rootOrder, workspaceId = ws, updatedAt = now()))
            else tasks.upsert(t.copy(listId = "", folderId = folderId, workspaceId = ws, updatedAt = now()))
        }
        logActivity(rootId, "moved", folders.getById(folderId)?.name)
    }

    // ============ workspaces ============
    val allWorkspaces: Flow<List<WorkspaceEntity>> = db.workspaceDao().observeAll()
    private val workspaces = db.workspaceDao()
    suspend fun ensureDefaultWorkspace() {
        if (workspaces.getAll().none { it.id == WorkspaceEntity.DEFAULT_ID }) {
            workspaces.upsert(WorkspaceEntity(WorkspaceEntity.DEFAULT_ID, "Personal", 0.0))
        }
    }
    suspend fun upsertWorkspace(w: WorkspaceEntity) = workspaces.upsert(w)
    suspend fun createWorkspace(name: String): String {
        val id = uid()
        workspaces.upsert(WorkspaceEntity(id, name, now().toDouble()))
        return id
    }
    /** Delete a workspace, reassigning its folders/lists/tags/contexts (and thus tasks) to the default
     *  space so nothing is left stranded on a workspaceId that no longer exists (which would hide it). */
    suspend fun deleteWorkspace(id: String) {
        if (id == WorkspaceEntity.DEFAULT_ID) return
        val def = WorkspaceEntity.DEFAULT_ID
        // R62 — deleting a workspace never loses data: EVERY workspace-scoped row it owns is reassigned to
        // the default space (matching how folders/lists always behaved), across the whole feature set.
        folders.getAll().filter { it.workspaceId == id }.forEach { folders.upsert(it.copy(workspaceId = def)) }
        lists.getAll().filter { it.workspaceId == id }.forEach { lists.upsert(it.copy(workspaceId = def)) }
        tags.getAll().filter { it.workspaceId == id }.forEach { tags.upsert(it.copy(workspaceId = def)) }
        contexts.getAll().filter { it.workspaceId == id }.forEach { contexts.upsert(it.copy(workspaceId = def)) }
        tasks.upsertAll(tasks.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        habits.upsertAll(habits.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        filters.upsertAll(filters.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        flags.upsertAll(flags.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        templates.upsertAll(templates.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        countdowns.upsertAll(countdowns.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        focus.upsertAll(focus.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        timeTrack.upsertActivities(timeTrack.getActivities().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        timeTrack.upsertEntries(timeTrack.getEntries().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        sealedNotes.upsertAll(sealedNotes.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        cravings.upsertAll(cravings.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        coreValues.upsertAll(coreValues.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        witnesses.upsertAll(witnesses.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        scorecard.upsertAll(scorecard.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        buddies.upsertAll(buddies.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        integrityReviews.upsertAll(integrityReviews.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        experiments.upsertAll(experiments.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        activation.upsertAll(activation.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        escrows.upsertAll(escrows.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        nudgeEvents.upsertAll(nudgeEvents.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        // Events follow their calendar, so reassigning the calendars carries the events with them.
        eventCalendars.upsertAll(eventCalendars.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        // Notes module — these three tables are workspace-scoped too; reassign them or deleting a workspace
        // would strand its notes/notebooks/smart-views on a dead workspaceId (invisible, unrecoverable).
        notes.upsertAll(notes.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        notebooks.upsertAll(notebooks.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        smartViews.upsertAll(smartViews.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        workspaces.deleteById(id)
    }

    // ============ folders ============
    suspend fun createFolder(name: String, parentId: String? = null, workspaceId: String = WorkspaceEntity.DEFAULT_ID): String {
        val id = uid()
        folders.upsert(FolderEntity(id = id, parentId = parentId, name = name, sortOrder = now().toDouble(), workspaceId = workspaceId))
        return id
    }

    suspend fun saveFolder(folder: FolderEntity) = folders.upsert(folder)

    /** Delete a folder; its lists and child folders move up to its parent. */
    suspend fun deleteFolder(id: String) {
        val f = folders.getAll().firstOrNull { it.id == id } ?: return
        lists.getAll().filter { it.folderId == id }.forEach { lists.upsert(it.copy(folderId = f.parentId)) }
        folders.getAll().filter { it.parentId == id }.forEach { folders.upsert(it.copy(parentId = f.parentId)) }
        folders.deleteById(id)
    }

    /** Permanently erase a folder and everything beneath it — sub-folders, their lists (and every task
     *  in them, via [deleteList]) and any tasks captured directly into the folders. Used by "Delete
     *  forever" from the container Trash; a normal delete goes to Trash and stays recoverable. */
    suspend fun purgeFolder(id: String) {
        val descFolderIds = mutableSetOf(id)
        var changed = true
        while (changed) { changed = false; folders.getAll().forEach { if (it.parentId in descFolderIds && it.id !in descFolderIds) { descFolderIds.add(it.id); changed = true } } }
        lists.getAll().filter { it.folderId in descFolderIds }.forEach { deleteList(it.id) }
        tasks.getAll().filter { it.folderId in descFolderIds && it.parentId == null }.forEach { deleteSubtree(it.id) }
        tasks.getAll().filter { it.folderId in descFolderIds }.forEach { tasks.deleteById(it.id) }
        descFolderIds.forEach { folders.deleteById(it) }
    }

    // ============ lists ============
    suspend fun ensureInbox() {
        if (lists.getById(ListEntity.INBOX_ID) == null) {
            lists.upsert(ListEntity(id = ListEntity.INBOX_ID, name = "Inbox", sortOrder = 0.0))
        }
    }

    suspend fun createList(name: String, folderId: String? = null, colorArgb: Long? = null, emoji: String? = null, workspaceId: String = WorkspaceEntity.DEFAULT_ID, parentListId: String? = null): String {
        val id = uid()
        val order = lists.maxSortOrder() + 1.0
        lists.upsert(ListEntity(id = id, folderId = folderId, parentListId = parentListId, name = name, colorArgb = colorArgb, emoji = emoji, sortOrder = order, workspaceId = workspaceId))
        return id
    }

    suspend fun saveList(list: ListEntity) = lists.upsert(list)
    suspend fun getList(id: String): ListEntity? = lists.getById(id)
    /** Set (or clear, when null) a list's embedded background image (already-encoded JPEG base64). */
    suspend fun setListBackground(listId: String, base64: String?) {
        lists.getById(listId)?.let { lists.upsert(it.copy(backgroundBase64 = base64)) }
    }

    /** Delete a list and permanently remove its tasks. Child lists are re-parented up
     *  (to this list's own parent / folder root) so they aren't orphaned. */
    suspend fun deleteList(id: String) {
        if (id == ListEntity.INBOX_ID) return
        val victim = lists.getById(id)
        lists.getAll().filter { it.parentListId == id }.forEach {
            lists.upsert(it.copy(parentListId = victim?.parentListId, folderId = victim?.folderId ?: it.folderId))
        }
        tasks.getAll().filter { it.listId == id && it.parentId == null }.forEach { deleteSubtree(it.id) }
        // any orphaned tasks with this listId (safety)
        tasks.getAll().filter { it.listId == id }.forEach { tasks.deleteById(it.id) }
        lists.deleteById(id)
    }

    // ============ drawer reordering / nesting ============
    suspend fun moveListOrder(list: ListEntity, dir: Int) {
        val sibs = lists.getAll().filter { it.folderId == list.folderId && it.parentListId == list.parentListId && it.id != ListEntity.INBOX_ID && !it.archived }.sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == list.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        lists.upsert(list.copy(sortOrder = other.sortOrder))
        lists.upsert(other.copy(sortOrder = list.sortOrder))
    }

    suspend fun moveFolderOrder(folder: FolderEntity, dir: Int) {
        val sibs = folders.getAll().filter { it.parentId == folder.parentId }.sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == folder.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        folders.upsert(folder.copy(sortOrder = other.sortOrder))
        folders.upsert(other.copy(sortOrder = folder.sortOrder))
    }

    suspend fun moveListToFolder(listId: String, folderId: String?) {
        val all = lists.getAll()
        val list = all.firstOrNull { it.id == listId } ?: return
        // Moving into a folder makes the list top-level there (clears any list nesting).
        lists.upsert(list.copy(folderId = folderId, parentListId = null, sortOrder = now().toDouble()))
        // R31 #3 — carry the whole sub-list subtree into the new folder too, so folder counts, folder
        // views and folder-scoped filters/calendar/matrix stay consistent with the sidebar's visual
        // nesting. Without this, a moved parent's children keep a stale folderId and silently vanish
        // from the target folder's totals while lingering in the old folder's.
        cascadeSublistFolder(listId, folderId, all)
    }

    /** Nest a list under another list (or pass null to un-nest to folder root). Cycle-safe;
     *  the child adopts the parent's folder so the subtree stays in one place. */
    suspend fun setListParent(listId: String, parentListId: String?) {
        if (listId == parentListId || listId == ListEntity.INBOX_ID) return
        val all = lists.getAll()
        val list = all.firstOrNull { it.id == listId } ?: return
        // prevent cycles: parentListId must not be a descendant of listId
        val descendants = mutableSetOf(listId)
        var changed = true
        while (changed) {
            changed = false
            all.forEach { if (it.parentListId in descendants && it.id !in descendants) { descendants.add(it.id); changed = true } }
        }
        if (parentListId != null && parentListId in descendants) return
        val newFolder = if (parentListId != null) all.firstOrNull { it.id == parentListId }?.folderId else list.folderId
        lists.upsert(list.copy(parentListId = parentListId, folderId = newFolder, sortOrder = now().toDouble()))
        // R31 #3 — descendants follow the moved list into its new folder (see moveListToFolder).
        cascadeSublistFolder(listId, newFolder, all)
    }

    /** Push [folderId] onto every list nested (directly or transitively) under [rootListId] via
     *  parentListId. The [snapshot] is the list of all lists read before the root was re-parented, so
     *  the descendant edges are still intact. The root itself is assumed already updated by the caller. */
    private suspend fun cascadeSublistFolder(rootListId: String, folderId: String?, snapshot: List<ListEntity>) {
        val descendants = mutableSetOf(rootListId)
        var changed = true
        while (changed) {
            changed = false
            snapshot.forEach { if (it.parentListId in descendants && it.id !in descendants) { descendants.add(it.id); changed = true } }
        }
        descendants.remove(rootListId)
        descendants.forEach { id ->
            snapshot.firstOrNull { it.id == id }?.let { if (it.folderId != folderId) lists.upsert(it.copy(folderId = folderId)) }
        }
    }

    suspend fun moveFolderToParent(folderId: String, parentId: String?) {
        if (folderId == parentId) return
        // prevent cycles: parentId must not be a descendant of folderId
        val all = folders.getAll()
        val descendants = mutableSetOf(folderId)
        var changed = true
        while (changed) {
            changed = false
            all.forEach { if (it.parentId in descendants && it.id !in descendants) { descendants.add(it.id); changed = true } }
        }
        if (parentId != null && parentId in descendants) return
        all.firstOrNull { it.id == folderId }?.let { folders.upsert(it.copy(parentId = parentId, sortOrder = now().toDouble())) }
    }

    // ============ checklist ============
    suspend fun checklistFor(taskId: String): List<ChecklistItemEntity> = checklist.forTask(taskId)
    suspend fun addChecklistItem(taskId: String, text: String) {
        val order = checklist.maxSortOrder(taskId) + 1.0
        checklist.upsert(ChecklistItemEntity(id = uid(), taskId = taskId, sortOrder = order, text = text))
    }
    suspend fun saveChecklistItem(item: ChecklistItemEntity) = checklist.upsert(item)
    suspend fun deleteChecklistItem(id: String) = checklist.deleteById(id)

    // ============ attachments ============
    /** Max size accepted PER FILE (50 MB). There is no limit on the NUMBER of attachments a
     *  task can hold. Bytes live Base64 in the DB and travel losslessly in JSON backups. Any
     *  file type is accepted (images, PDF, Office docs, epub, txt/md, etc.); the per-file cap
     *  just keeps any single file from bloating the backup. */
    val maxAttachmentBytes = 50L * 1024 * 1024
    fun attachmentMeta(taskId: String): Flow<List<AttachmentMeta>> = attachments.observeMetaForTask(taskId)
    val allAttachmentMeta: Flow<List<AttachmentMeta>> = attachments.observeAllMeta()
    fun attachmentCount(taskId: String): Flow<Int> = attachments.observeCountForTask(taskId)
    suspend fun attachmentContent(id: String): String? = attachments.contentOf(id)
    suspend fun attachmentFilePath(id: String): String? = attachments.filePathOf(id)
    /** Store raw bytes as a task attachment (Base64 in the DB — used by imports). */
    suspend fun addAttachment(taskId: String, fileName: String, mime: String, bytes: ByteArray): Boolean {
        if (bytes.size > maxAttachmentBytes) return false
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        attachments.upsert(
            AttachmentEntity(
                id = uid(), taskId = taskId, fileName = fileName, mime = mime,
                sizeBytes = bytes.size.toLong(), isImage = mime.startsWith("image/"),
                addedAt = now(), contentBase64 = b64,
            ),
        )
        return true
    }
    /** File-backed attachment (F4): the caller has already written [filePath]; the DB stays lean. */
    suspend fun addAttachmentFile(taskId: String, fileName: String, mime: String, sizeBytes: Long, filePath: String) {
        attachments.upsert(
            AttachmentEntity(
                id = uid(), taskId = taskId, fileName = fileName, mime = mime,
                sizeBytes = sizeBytes, isImage = mime.startsWith("image/"),
                addedAt = now(), contentBase64 = "", filePath = filePath,
            ),
        )
    }
    suspend fun deleteAttachment(id: String) {
        attachments.filePathOf(id)?.let { runCatching { java.io.File(it).delete() } }
        attachments.deleteById(id)
    }
    /** L14 — store bytes (e.g. a handwriting/ink PNG) as a NOTE image attachment; returns its id. Base64
     *  in the DB so it round-trips losslessly through backup and the `.md` mirror like any note attachment. */
    suspend fun addNoteAttachment(noteId: String, fileName: String, mime: String, bytes: ByteArray): String? {
        if (bytes.size > maxAttachmentBytes) return null
        val id = uid()
        attachments.upsert(
            AttachmentEntity(
                id = id, taskId = "", noteId = noteId, fileName = fileName, mime = mime,
                sizeBytes = bytes.size.toLong(), isImage = mime.startsWith("image/"),
                addedAt = now(), contentBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            ),
        )
        return id
    }

    /** Attachments with bytes materialised inline (reads file-backed ones from disk) — for a
     *  lossless JSON export. filePath is dropped so the backup is portable. */
    private suspend fun hydratedAttachments(): List<AttachmentEntity> = attachments.getAll().map { a ->
        if (a.contentBase64.isBlank() && !a.filePath.isNullOrBlank()) {
            val f = java.io.File(a.filePath!!)
            // SEC-1: the file is encrypted at rest; decrypt (tolerant of legacy plaintext) so the backup
            // carries the raw bytes as Base64 and stays portable + lossless.
            if (f.exists()) a.copy(contentBase64 = android.util.Base64.encodeToString(com.todocompanion.app.data.security.FileVault.readDecrypted(f), android.util.Base64.NO_WRAP), filePath = null) else a
        } else a
    }

    // ============ tags / contexts ============
    suspend fun getTagsOnce(): List<TagEntity> = tags.getAll()
    suspend fun getContextsOnce(): List<ContextEntity> = contexts.getAll()
    suspend fun upsertTag(tag: TagEntity) = tags.upsert(tag)
    suspend fun deleteTag(id: String) = tags.deleteById(id)
    /** Persist a new order for the given tags by rewriting their sortOrder to the list index. */
    suspend fun setTagOrder(orderedIds: List<String>) {
        val byId = tags.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { tags.upsert(it.copy(sortOrder = i.toDouble())) } }
    }
    suspend fun setTaskTags(taskId: String, tagIds: List<String>) {
        tags.unlinkAllForTask(taskId)
        tags.linkAll(tagIds.map { TaskTagCrossRef(taskId, it) })
    }
    suspend fun upsertContext(context: ContextEntity) = contexts.upsert(context)
    suspend fun deleteContext(id: String) = contexts.deleteById(id)
    suspend fun setContextOrder(orderedIds: List<String>) {
        val byId = contexts.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { contexts.upsert(it.copy(sortOrder = i.toDouble())) } }
    }
    suspend fun setTaskContexts(taskId: String, contextIds: List<String>) {
        contexts.unlinkAllForTask(taskId)
        contexts.linkAll(contextIds.map { TaskContextCrossRef(taskId, it) })
    }

    // ============ flags ============
    suspend fun getFlagsOnce(): List<FlagEntity> = flags.getAll()
    suspend fun upsertFlag(f: FlagEntity) = flags.upsert(f)
    suspend fun createFlag(name: String, colorArgb: Long, icon: String = "bookmark"): String {
        val id = uid()
        flags.upsert(FlagEntity(id = id, name = name.ifBlank { "Flag" }, colorArgb = colorArgb, icon = icon, sortOrder = flags.maxSortOrder() + 1.0, createdAt = now(), workspaceId = activeWs()))
        return id
    }
    /** Delete a flag and clear it (id + colour cache) from every task that wore it. */
    suspend fun deleteFlag(id: String) {
        tasks.getAll().filter { it.flagId == id }.forEach { tasks.upsert(it.copy(flagId = null, flagColorArgb = null, updatedAt = now())) }
        flags.deleteById(id)
    }
    suspend fun moveFlagOrder(flag: FlagEntity, dir: Int) {
        val sibs = flags.getAll().sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == flag.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        flags.upsert(flag.copy(sortOrder = other.sortOrder))
        flags.upsert(other.copy(sortOrder = flag.sortOrder))
    }
    /** Assign (or clear, when [flagId] is null) a task's flag, caching the flag colour on the task. */
    suspend fun setTaskFlag(task: TaskEntity, flagId: String?) {
        val color = flagId?.let { fid -> flags.getAll().firstOrNull { it.id == fid }?.colorArgb }
        tasks.upsert(task.copy(flagId = flagId, flagColorArgb = color, updatedAt = now()))
    }
    /** Seed the default flags once, unless the user has already been given them. */
    suspend fun ensureDefaultFlags() {
        if (settings.get("flagsSeeded") == "true") return
        if (flags.getAll().isEmpty()) flags.upsertAll(FlagEntity.DEFAULTS)
        settings.put(SettingEntity("flagsSeeded", "true"))
    }

    // ============ templates ============
    suspend fun deleteTemplate(id: String) = templates.deleteById(id)
    suspend fun getTemplatesOnce(): List<TemplateEntity> = templates.getAll()
    suspend fun renameTemplate(id: String, name: String) {
        templates.getById(id)?.let { templates.upsert(it.copy(name = name.ifBlank { it.name })) }
    }

    private fun dayOffset(millis: Long?, todayStart: Long): Int? =
        millis?.let { ((it - todayStart) / 86_400_000L).toInt() }

    /** Freeze a task subtree (note, priority, flag, recurrence, checklist, tags, contexts,
     *  relative dates) into a named, reusable template. */
    suspend fun saveAsTemplate(rootTaskId: String, name: String): String? {
        val root = tasks.getById(rootTaskId) ?: return null
        val tagName = tags.getAll().associate { it.id to it.name }
        val ctxName = contexts.getAll().associate { it.id to it.name }
        val tagRefs = tags.getCrossRefs().groupBy { it.taskId }
        val ctxRefs = contexts.getCrossRefs().groupBy { it.taskId }
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        suspend fun node(t: TaskEntity): TemplateTask = TemplateTask(
            title = t.title, note = t.note, isNote = t.isNote,
            importance = t.importance, urgency = t.urgency,
            flagId = t.flagId, flagColorArgb = t.flagColorArgb,
            durationMin = t.durationMin, estimateMin = t.estimateMin, leadTimeMin = t.leadTimeMin,
            completeInOrder = t.completeInOrder, isProject = t.isProject, isGoal = t.isGoal,
            rrule = t.rrule, recurrenceMode = t.recurrenceMode,
            startOffsetDays = dayOffset(t.startDate, todayStart), dueOffsetDays = dayOffset(t.dueDate, todayStart),
            tagNames = tagRefs[t.id].orEmpty().mapNotNull { tagName[it.tagId] },
            contextNames = ctxRefs[t.id].orEmpty().mapNotNull { ctxName[it.contextId] },
            checklist = checklist.forTask(t.id).map { it.text },
            children = tasks.childrenOf(t.id).map { node(it) },
        )

        val payload = node(root)
        val id = uid()
        templates.upsert(TemplateEntity(id, name.ifBlank { root.title }, templateJson.encodeToString(TemplateTask.serializer(), payload), now(), workspaceId = activeWs()))
        return id
    }

    /** Instantiate a template into [listId] under [parentId], returning the new root task id.
     *  If [folderId] is set (and [parentId] is null), the root is captured directly into that folder. */
    suspend fun instantiateTemplate(templateId: String, listId: String, parentId: String? = null, folderId: String? = null): String? {
        val tpl = templates.getById(templateId) ?: return null
        val payload = runCatching { templateJson.decodeFromString(TemplateTask.serializer(), tpl.payloadJson) }.getOrNull() ?: return null
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tagByName = tags.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()
        val ctxByName = contexts.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()

        suspend fun tagId(name: String): String = tagByName.getOrPut(name.lowercase()) {
            uid().also { tags.upsert(TagEntity(it, name)) }
        }
        suspend fun ctxId(name: String): String = ctxByName.getOrPut(name.lowercase()) {
            uid().also { contexts.upsert(ContextEntity(id = it, name = name)) }
        }

        suspend fun create(node: TemplateTask, parent: String?): String {
            val id = uid()
            val order = tasks.maxSortOrder(listId, parent) + 1.0
            tasks.upsert(
                TaskEntity(
                    id = id, listId = listId, folderId = if (parent == null) folderId else null, parentId = parent, sortOrder = order,
                    title = node.title.ifBlank { "Untitled" }, note = node.note, isNote = node.isNote,
                    importance = node.importance, urgency = node.urgency,
                    flagId = node.flagId, flagColorArgb = node.flagColorArgb,
                    durationMin = node.durationMin, estimateMin = node.estimateMin, leadTimeMin = node.leadTimeMin,
                    completeInOrder = node.completeInOrder, isProject = node.isProject, isGoal = node.isGoal,
                    rrule = node.rrule, recurrenceMode = node.recurrenceMode,
                    startDate = node.startOffsetDays?.let { todayStart + it * 86_400_000L },
                    dueDate = node.dueOffsetDays?.let { todayStart + it * 86_400_000L },
                    createdAt = now(), updatedAt = now(),
                ),
            )
            node.checklist.forEachIndexed { i, text -> checklist.upsert(ChecklistItemEntity(id = uid(), taskId = id, sortOrder = (i + 1).toDouble(), text = text)) }
            if (node.tagNames.isNotEmpty()) tags.linkAll(node.tagNames.map { TaskTagCrossRef(id, tagId(it)) })
            if (node.contextNames.isNotEmpty()) contexts.linkAll(node.contextNames.map { TaskContextCrossRef(id, ctxId(it)) })
            node.children.forEach { create(it, id) }
            return id
        }
        return create(payload, parentId)
    }

    // ============ reminders / deps ============
    suspend fun remindersFor(taskId: String): List<ReminderEntity> = reminders.forTask(taskId)
    suspend fun upsertReminder(reminder: ReminderEntity) = reminders.upsert(reminder)
    suspend fun deleteReminder(id: String) = reminders.deleteById(id)
    suspend fun allRemindersOnce(): List<ReminderEntity> = reminders.getAll()
    suspend fun addDependency(taskId: String, dependsOn: String, mode: String = "AND", delayDays: Int = 0) =
        deps.add(DependencyEntity(taskId, dependsOn, mode, delayDays))
    suspend fun removeDependency(dep: DependencyEntity) = deps.remove(dep)

    // ============ settings ============
    suspend fun settingsSnapshot(): AppSettings {
        val base = AppSettings.fromMap(settings.getAll().associate { it.key to it.value })
        // SEC (R2-A/H2) — the sync passphrase lives KeyStore-wrapped in SecurePrefs, not the DB table.
        // Inject it here so every consumer of a snapshot sees the real value with zero call-site changes.
        val ctx = appContext ?: return base
        val sp = SecurePrefs.getSecret(ctx, AppSettings.Keys.SYNC_PASS) ?: return base
        return base.copy(syncPassphrase = sp)
    }

    /** SEC (R2-A/H2) — one-time move of any legacy cleartext SYNC_PASS row into the KeyStore-wrapped
     *  SecurePrefs, then delete the row. Idempotent; safe to call on every start. */
    suspend fun migrateSyncPassToSecurePrefs() {
        val ctx = appContext ?: return
        val legacy = settings.get(AppSettings.Keys.SYNC_PASS) ?: return
        if (legacy.isNotBlank()) runCatching { SecurePrefs.putSecret(ctx, AppSettings.Keys.SYNC_PASS, legacy) }
        runCatching { settings.delete(AppSettings.Keys.SYNC_PASS) }
    }
    /** R62 — the active workspace, read synchronously, so repo-side creates stamp the right isolation. */
    suspend fun activeWs(): String = settingsSnapshot().activeWorkspaceId

    // R62 — workspace-scoped one-shot reads for home-screen widgets and background notifications, so those
    // surfaces show ONLY the active workspace's data. The scoping logic lives here once, mirroring the flows.
    /** Tasks in the active workspace — by list/folder membership, exactly like the app's [wsTasks]. An Inbox
     *  task belongs to the workspace it was captured in ([workspaceId]), so widgets/notifications show only
     *  the active workspace's Inbox items, never every workspace's (R64). */
    suspend fun wsTasksOnce(): List<TaskEntity> {
        val ws = activeWs()
        val listIds = lists.getAll().filter { it.workspaceId == ws }.map { it.id }.toSet()
        val folderIds = folders.getAll().filter { it.workspaceId == ws }.map { it.id }.toSet()
        return tasks.getAll().filter {
            if (it.listId == ListEntity.INBOX_ID) it.workspaceId == ws
            else it.listId in listIds || (it.folderId != null && it.folderId in folderIds)
        }
    }
    suspend fun wsCountdownsOnce(): List<com.todocompanion.app.data.entity.CountdownEntity> =
        countdowns.getAll().filter { it.workspaceId == activeWs() }
    suspend fun wsHabitsOnce(): List<HabitEntity> = habits.getAll().filter { it.workspaceId == activeWs() && !it.trashed }
    suspend fun wsTimeActivitiesOnce(): List<com.todocompanion.app.data.entity.TimeActivityEntity> =
        timeTrack.getActivities().filter { it.workspaceId == activeWs() }
    suspend fun wsFocusSessionsOnce(): List<com.todocompanion.app.data.entity.FocusSessionEntity> =
        focus.getAll().filter { it.workspaceId == activeWs() }
    suspend fun wsActivitiesOnce(): List<com.todocompanion.app.data.entity.ActivityEntity> {
        val taskIds = wsTasksOnce().map { it.id }.toSet()
        return activity.getAll().filter { it.taskId in taskIds }
    }
    suspend fun wsEventsOnce(): List<com.todocompanion.app.data.entity.EventEntity> {
        val calIds = eventCalendars.getAll().filter { it.workspaceId == activeWs() }.map { it.id }.toSet()
        return events.getAll().filter { it.calendarId in calIds }
    }
    suspend fun saveSettings(s: AppSettings) {
        val ctx = appContext
        if (ctx == null) { settings.putAll(s.toMap().map { SettingEntity(it.key, it.value) }); return }
        // SEC (R2-A/H2) — keep the sync passphrase out of the DB settings table (cleartext when DB
        // encryption is off). Write it KeyStore-wrapped into SecurePrefs and drop it from the table map.
        // A non-secret `sync_pass_rev` token changes on every save so the reactive settings flow — which
        // only observes the DB table — re-emits and re-reads SecurePrefs, keeping the UI fresh.
        runCatching { SecurePrefs.putSecret(ctx, AppSettings.Keys.SYNC_PASS, s.syncPassphrase) }
        val rows = s.toMap().filterNot { it.key == AppSettings.Keys.SYNC_PASS }
            .map { SettingEntity(it.key, it.value) } + SettingEntity(SYNC_PASS_REV, System.currentTimeMillis().toString())
        settings.putAll(rows)
    }

    // ============ export / import ============
    /** Settings for export/sync, minus device-secret keys (the encryption passphrase never leaves, and the
     *  reactive-refresh rev token is device-local noise). */
    private suspend fun exportableSettings(): List<SettingEntity> {
        val K = com.todocompanion.app.domain.AppSettings.Keys
        // W3 — goals/goal_reviews are runtime-owned by the Room table now, so regenerate their transport k/v
        // from the live table (the settings-table copy is frozen at the Increment-1 migration value). This keeps
        // the backup format byte-identical and backward-compatible while always reflecting the current goals.
        val base = settings.getAll().filterNot {
            it.key == K.SYNC_PASS || it.key == SYNC_PASS_REV || it.key == K.GOALS || it.key == K.GOAL_REVIEWS ||
                it.key == K.ROUTINES || it.key == K.ROUTINE_RUNS
        }
        val goalsJson = com.todocompanion.app.domain.Goals.encode(goals.getAll().map { it.toDomain() })
        val reviewsJson = com.todocompanion.app.domain.GoalReviews.encode(goals.getAllReviews().map { it.toDomain() })
        // W3 — routines/routine_runs are runtime-owned by their tables now; regenerate their transport k/v from
        // the live tables so backups stay byte-compatible and always current. (active_routine_run stays in settings.)
        val routinesJson = com.todocompanion.app.domain.Routines.encode(routines.getAll().map { it.toDomain() })
        val runsJson = com.todocompanion.app.domain.RoutineRuns.encode(routines.getAllRuns().map { it.toDomain() })
        return base + SettingEntity(K.GOALS, goalsJson) + SettingEntity(K.GOAL_REVIEWS, reviewsJson) +
            SettingEntity(K.ROUTINES, routinesJson) + SettingEntity(K.ROUTINE_RUNS, runsJson)
    }

    suspend fun exportJson(): String = Backup.encode(
        BackupFile(
            exportedAt = now(),
            workspaces = workspaces.getAll(),
            filters = filters.getAll(),
            habits = habits.getAll(),
            habitCheckins = habits.getCheckins(),
            focusSessions = focus.getAll(),
            folders = folders.getAll(),
            lists = lists.getAll(),
            tasks = tasks.getAll(),
            checklist = checklist.getAll(),
            tags = tags.getAll(),
            taskTags = tags.getCrossRefs(),
            contexts = contexts.getAll(),
            taskContexts = contexts.getCrossRefs(),
            reminders = reminders.getAll(),
            dependencies = deps.getAll(),
            settings = exportableSettings(),
            attachments = hydratedAttachments(),
            flags = flags.getAll(),
            templates = templates.getAll(),
            countdowns = countdowns.getAll(),
            activities = activity.getAll(),
            timeActivities = timeTrack.getActivities(),
            timeEntries = timeTrack.getEntries(),
            sealedNotes = sealedNotes.getAll(),
            cravingEvents = cravings.getAll(),
            coreValues = coreValues.getAll(),
            witnessEvents = witnesses.getAll(),
            scorecardItems = scorecard.getAll(),
            buddySnapshots = buddies.getAll(),
            integrityReviews = integrityReviews.getAll(),
            experiments = experiments.getAll(),
            activationItems = activation.getAll(),
            dayLogs = dayLogs.getAll(),
            escrows = escrows.getAll(),
            nudgeEvents = nudgeEvents.getAll(),
            revisions = revisions.getAll(),
            eventCalendars = eventCalendars.getAll(),
            events = events.getAll(),
            notes = notes.getAll().filter { !it.noBackup },   // Wave 2 · Privacy Dial — omit noBackup notes
            notebooks = notebooks.getAll(),
            noteTags = notes.getTagCrossRefs(),
            noteContexts = notes.getContextCrossRefs(),
            noteRevisions = noteRevisions.getAll(),
            noteLinks = noteLinks.getAll(),
            smartViews = smartViews.getAll(),
            // Wave 3 — Active-Recall schedules ride the backup; exclude cards of no-backup notes.
            noteCards = notes.getAll().filter { it.noBackup }.map { it.id }.toHashSet().let { skip ->
                noteCards.getAll().filter { it.noteId !in skip }
            },
        )
    )

    /** Human-readable Markdown outline (lossy, portable). */
    suspend fun exportMarkdown(includeCompleted: Boolean): String =
        com.todocompanion.app.domain.port.Export.toMarkdown(
            tasks = tasks.getAll(), lists = lists.getAll(), tags = tags.getAll(),
            taskTagPairs = tags.getCrossRefs().map { it.taskId to it.tagId }, includeCompleted = includeCompleted,
            redactNotes = settingsSnapshot().exportRedactNotes,   // R103 — privacy: strip notes from shareable exports
        )

    /** iCalendar (.ics) of dated tasks + deadlines — importable by any calendar app. */
    suspend fun exportIcs(includeCompleted: Boolean): String =
        com.todocompanion.app.domain.port.Export.toIcs(
            tasks = tasks.getAll(), includeCompleted = includeCompleted,
            redactNotes = settingsSnapshot().exportRedactNotes, now = System.currentTimeMillis(),
        )

    /** Habit check-ins as long-format CSV (re-importable). */
    suspend fun exportHabitsCsv(): String =
        com.todocompanion.app.domain.port.Export.toHabitsCsv(habits.getAll(), habits.getCheckins())

    /** Import habit check-ins from our CSV or a Loop "Checkmarks" export; returns rows imported. */
    suspend fun importHabitsCsv(text: String): Int {
        val res = com.todocompanion.app.data.sync.HabitImporter.parse(text) ?: return 0
        val existing = habits.getAll().associateBy { it.name.lowercase() }.toMutableMap()
        res.rows.groupBy { it.habit }.forEach { (name, rows) ->
            val h = existing[name.lowercase()] ?: run {
                val id = createHabit(name.trim(), null, null, 1, com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID)
                habits.getAll().first { it.id == id }.also { existing[name.lowercase()] = it }
            }
            rows.forEach { r ->
                if (r.count > 0 || r.status == "skip")
                    habits.upsertCheckin(HabitCheckinEntity(h.id, r.epochDay, r.count, r.status))
            }
        }
        return res.rows.size
    }

    /** Flat CSV (lossy, portable — opens in any spreadsheet). */
    suspend fun exportCsv(includeCompleted: Boolean): String =
        com.todocompanion.app.domain.port.Export.toCsv(
            tasks = tasks.getAll(), lists = lists.getAll(), tags = tags.getAll(),
            taskTagPairs = tags.getCrossRefs().map { it.taskId to it.tagId }, includeCompleted = includeCompleted,
            redactNotes = settingsSnapshot().exportRedactNotes,   // R103 — privacy: blank the Note column when set
        )

    suspend fun importJsonReplace(text: String) {
        val b = Backup.decode(text)
        // The sync/backup-file passphrase is a device-local secret that never travels in a backup
        // (exportableSettings drops it). Preserve THIS device's passphrase across a replace-restore so a
        // restore doesn't silently blank it — otherwise encrypted-folder users would suddenly write
        // plaintext backups and fail to read their encrypted sync folder until they re-entered it.
        val keepPass = settings.getAll().firstOrNull { it.key == com.todocompanion.app.domain.AppSettings.Keys.SYNC_PASS }
        // D1 — the whole clear-then-reinsert runs in ONE transaction: a process kill (or any thrown DAO
        // call) mid-restore now rolls back to the pre-restore state instead of leaving a half-wiped DB.
        db.withTransaction {
        tasks.clear(); folders.clear(); lists.clear(); checklist.clear()
        tags.clear(); tags.clearCrossRefs(); contexts.clear(); contexts.clearCrossRefs()
        reminders.clear(); deps.clear(); settings.clear(); workspaces.clear(); filters.clear()
        habits.clear(); habits.clearCheckins(); focus.clear(); attachments.clear(); flags.clear(); templates.clear(); countdowns.clear(); activity.clear(); revisions.clear()
        timeTrack.clearEntries(); timeTrack.clearActivities(); sealedNotes.clear(); cravings.clear()
        coreValues.clear(); witnesses.clear(); scorecard.clear(); buddies.clear(); integrityReviews.clear()
        experiments.clear(); activation.clear(); dayLogs.clear()
        escrows.clear(); nudgeEvents.clear(); eventCalendars.clear(); events.clear()
        notes.clear(); notes.clearTagCrossRefs(); notes.clearContextCrossRefs(); notebooks.clear(); noteRevisions.clear(); noteLinks.clear(); smartViews.clear()
        noteCards.clear()   // replace-restore must reset flashcards too, else stale SM-2 schedules survive
        goals.clear(); goals.clearReviews()   // W3 — goals live in Room now; replace them from the imported transport below
        routines.clear(); routines.clearRuns()   // W3 — routines live in Room now; replace them from the imported transport below
        folders.upsertAll(b.folders)
        lists.upsertAll(b.lists)
        tasks.upsertAll(b.tasks)
        checklist.upsertAll(b.checklist)
        tags.upsertAll(b.tags); tags.linkAll(b.taskTags)
        contexts.upsertAll(b.contexts); contexts.linkAll(b.taskContexts)
        reminders.upsertAll(b.reminders)
        deps.addAll(b.dependencies)
        settings.putAll(b.settings)
        // Restore the preserved device passphrase unless the backup itself carried one (it never does today).
        if (keepPass != null && b.settings.none { it.key == com.todocompanion.app.domain.AppSettings.Keys.SYNC_PASS }) settings.putAll(listOf(keepPass))
        // W3 — populate the goals table from the imported transport k/v (goals ride in `settings` for backward
        // compatibility; the table is the runtime source of truth). Old backups carry the same keys, so this
        // restores goals from any backup, new or old.
        run {
            val K = com.todocompanion.app.domain.AppSettings.Keys
            val gj = b.settings.firstOrNull { it.key == K.GOALS }?.value ?: ""
            val rj = b.settings.firstOrNull { it.key == K.GOAL_REVIEWS }?.value ?: ""
            goals.upsertAll(com.todocompanion.app.domain.Goals.parse(gj).map { it.toEntity() })
            goals.upsertReviews(com.todocompanion.app.domain.GoalReviews.parse(rj).map { it.toEntity() })
            // W3 — routines/routine_runs ride in `settings` transport too; repopulate their tables from any backup.
            val roj = b.settings.firstOrNull { it.key == K.ROUTINES }?.value ?: ""
            val ruj = b.settings.firstOrNull { it.key == K.ROUTINE_RUNS }?.value ?: ""
            routines.upsertAll(com.todocompanion.app.domain.Routines.parse(roj).map { it.toEntity() })
            routines.upsertRuns(com.todocompanion.app.domain.RoutineRuns.parse(ruj).map { it.toEntity() })
        }
        workspaces.upsertAll(b.workspaces)
        filters.upsertAll(b.filters)
        habits.upsertAll(b.habits); habits.upsertCheckins(b.habitCheckins)
        focus.upsertAll(b.focusSessions)
        attachments.upsertAll(b.attachments)
        flags.upsertAll(b.flags)
        templates.upsertAll(b.templates)
        countdowns.upsertAll(b.countdowns)
        activity.insertAll(b.activities)
        timeTrack.upsertActivities(b.timeActivities); timeTrack.upsertEntries(b.timeEntries)
        sealedNotes.upsertAll(b.sealedNotes)
        cravings.upsertAll(b.cravingEvents)
        coreValues.upsertAll(b.coreValues); witnesses.upsertAll(b.witnessEvents); scorecard.upsertAll(b.scorecardItems)
        buddies.upsertAll(b.buddySnapshots); integrityReviews.upsertAll(b.integrityReviews)
        experiments.upsertAll(b.experiments); activation.upsertAll(b.activationItems); dayLogs.upsertAll(b.dayLogs)
        escrows.upsertAll(b.escrows); nudgeEvents.upsertAll(b.nudgeEvents); revisions.upsertAll(b.revisions)
        eventCalendars.upsertAll(b.eventCalendars); events.upsertAll(b.events)
        notebooks.upsertAll(b.notebooks); notes.upsertAll(b.notes)
        notes.linkTags(b.noteTags); notes.linkContexts(b.noteContexts)
        noteRevisions.insertAll(b.noteRevisions)
        noteLinks.insertAll(b.noteLinks)
        smartViews.upsertAll(b.smartViews)
        if (b.noteCards.isNotEmpty()) noteCards.insertAll(b.noteCards)   // Wave 3 — restore review schedules
        ensureDefaultWorkspace()
        ensureInbox()
        ensureDefaultFlags()
        }   // end withTransaction — the destructive replace is now atomic
        // Reset the FTS indices to match the freshly-replaced rows, AFTER the replace commits so the index
        // reflects the committed rows. The count-freshness heuristic can't catch a same-cardinality
        // replacement (restoring N notes over a different N), so rebuild eagerly or search would return
        // stale, pre-restore ids.
        runCatching { val sdb = ftsDb(); rebuildTaskFtsBlocking(sdb); rebuildNoteFtsBlocking(sdb) }
    }

    /**
     * O4: import a backup by MERGING into the current data instead of replacing it — moving between
     * phones, or combining two devices, without losing either side. Reuses the last-write-wins sync
     * policy (tasks by updatedAt, check-ins by higher count, structural rows by the newer snapshot).
     */
    suspend fun importJsonMerge(text: String) {
        val incoming = Backup.decode(text)
        val merged = com.todocompanion.app.data.sync.SyncEngine.merge(snapshot(), incoming)
        applyMerged(merged)
        // R62 — the sync snapshot only reconciles the core 19 tables. Union the remaining tables additively
        // (insert only rows this device doesn't already have, by id) so a merge-import NEVER drops the other
        // device's time tracking, calendar, occasions, life-systems, sealed notes, revisions or attachments.
        mergeNewerTables(incoming)
        ensureDefaultWorkspace(); ensureInbox(); ensureDefaultFlags()
    }

    /** Additive, lossless union of the tables outside the core sync snapshot: keep every local row, add any
     *  incoming row whose id (or natural key) isn't already present. Used by merge-import (with attachment
     *  bytes) and by folder-sync via applyMerged ([includeAttachments] = false, since attachment bytes stay
     *  local and syncing metadata-only rows would create un-openable phantom attachments on the peer). */
    private suspend fun mergeNewerTables(b: com.todocompanion.app.domain.port.BackupFile, includeAttachments: Boolean = true) {
        fun <T> missing(local: List<T>, incoming: List<T>, key: (T) -> Any?): List<T> {
            val have = local.map(key).toHashSet(); return incoming.filter { key(it) !in have }
        }
        timeTrack.upsertActivities(missing(timeTrack.getActivities(), b.timeActivities) { it.id })
        timeTrack.upsertEntries(missing(timeTrack.getEntries(), b.timeEntries) { it.id })
        sealedNotes.upsertAll(missing(sealedNotes.getAll(), b.sealedNotes) { it.id })
        cravings.upsertAll(missing(cravings.getAll(), b.cravingEvents) { it.id })
        coreValues.upsertAll(missing(coreValues.getAll(), b.coreValues) { it.id })
        witnesses.upsertAll(missing(witnesses.getAll(), b.witnessEvents) { it.id })
        scorecard.upsertAll(missing(scorecard.getAll(), b.scorecardItems) { it.id })
        buddies.upsertAll(missing(buddies.getAll(), b.buddySnapshots) { it.id })
        integrityReviews.upsertAll(missing(integrityReviews.getAll(), b.integrityReviews) { it.id })
        experiments.upsertAll(missing(experiments.getAll(), b.experiments) { it.id })
        activation.upsertAll(missing(activation.getAll(), b.activationItems) { it.id })
        dayLogs.upsertAll(missing(dayLogs.getAll(), b.dayLogs) { it.epochDay to it.workspaceId })
        escrows.upsertAll(missing(escrows.getAll(), b.escrows) { it.id })
        nudgeEvents.upsertAll(missing(nudgeEvents.getAll(), b.nudgeEvents) { it.id })
        revisions.upsertAll(missing(revisions.getAll(), b.revisions) { it.id })
        eventCalendars.upsertAll(missing(eventCalendars.getAll(), b.eventCalendars) { it.id })
        events.upsertAll(missing(events.getAll(), b.events) { it.id })
        if (includeAttachments) attachments.upsertAll(missing(attachments.getAll(), b.attachments) { it.id })
        notebooks.upsertAll(missing(notebooks.getAll(), b.notebooks) { it.id })
        notes.upsertAll(missing(notes.getAll(), b.notes) { it.id })
        notes.linkTags(missing(notes.getTagCrossRefs(), b.noteTags) { it.noteId to it.tagId })
        notes.linkContexts(missing(notes.getContextCrossRefs(), b.noteContexts) { it.noteId to it.contextId })
        noteRevisions.insertAll(missing(noteRevisions.getAll(), b.noteRevisions) { it.id })
        noteLinks.insertAll(missing(noteLinks.getAll(), b.noteLinks) { it.noteId to it.targetTitle })
        smartViews.upsertAll(missing(smartViews.getAll(), b.smartViews) { it.id })
        noteCards.insertAll(missing(noteCards.getAll(), b.noteCards) { it.id })   // Wave 3 — union card schedules
    }

    /** Full snapshot of the current data as a BackupFile (for sync merges). R110 — now carries the FULL
     *  table set (notes, events, time-tracking, life-systems, revisions, smart-views, cards, …), not just
     *  the core 19, so every domain propagates across devices through folder-sync. Attachment bytes stay
     *  local (blanked) since syncing megabytes through the folder isn't worth it; noBackup notes are omitted
     *  for privacy exactly as exportJson does. */
    suspend fun snapshot(): com.todocompanion.app.domain.port.BackupFile =
        com.todocompanion.app.domain.port.BackupFile(
            exportedAt = now(),
            workspaces = workspaces.getAll(), filters = filters.getAll(), habits = habits.getAll(),
            habitCheckins = habits.getCheckins(), focusSessions = focus.getAll(), folders = folders.getAll(),
            lists = lists.getAll(), tasks = tasks.getAll(), checklist = checklist.getAll(),
            tags = tags.getAll(), taskTags = tags.getCrossRefs(), contexts = contexts.getAll(),
            taskContexts = contexts.getCrossRefs(), reminders = reminders.getAll(), dependencies = deps.getAll(),
            // Attachment bytes stay local — syncing megabytes through the folder isn't worth it.
            settings = exportableSettings(), attachments = attachments.getAll().map { it.copy(contentBase64 = "") }, flags = flags.getAll(),
            templates = templates.getAll(), countdowns = countdowns.getAll(), activities = activity.getAll(),
            timeActivities = timeTrack.getActivities(), timeEntries = timeTrack.getEntries(),
            sealedNotes = sealedNotes.getAll(), cravingEvents = cravings.getAll(),
            coreValues = coreValues.getAll(), witnessEvents = witnesses.getAll(), scorecardItems = scorecard.getAll(),
            buddySnapshots = buddies.getAll(), integrityReviews = integrityReviews.getAll(),
            experiments = experiments.getAll(), activationItems = activation.getAll(), dayLogs = dayLogs.getAll(),
            escrows = escrows.getAll(), nudgeEvents = nudgeEvents.getAll(), revisions = revisions.getAll(),
            eventCalendars = eventCalendars.getAll(), events = events.getAll(),
            notes = notes.getAll().filter { !it.noBackup }, notebooks = notebooks.getAll(),
            noteTags = notes.getTagCrossRefs(), noteContexts = notes.getContextCrossRefs(),
            noteRevisions = noteRevisions.getAll(), noteLinks = noteLinks.getAll(), smartViews = smartViews.getAll(),
            noteCards = notes.getAll().filter { it.noBackup }.map { it.id }.toHashSet().let { skip ->
                noteCards.getAll().filter { it.noteId !in skip }
            },
        )

    /** Apply a merged snapshot to the local DB, preserving this device's own settings (sync/backup
     *  folder URIs, device id, theme). Used by the folder-sync engine. */
    suspend fun applyMerged(b: com.todocompanion.app.domain.port.BackupFile) {
        // D1 — one transaction: a kill mid-apply rolls back to the pre-merge state, never a half-wiped DB.
        db.withTransaction {
            // Attachments are intentionally NOT synced (their bytes live locally in files), so they're
            // left untouched here — only structural + task data is reconciled by clear+replace.
            tasks.clear(); folders.clear(); lists.clear(); checklist.clear()
            tags.clear(); tags.clearCrossRefs(); contexts.clear(); contexts.clearCrossRefs()
            reminders.clear(); deps.clear(); workspaces.clear(); filters.clear()
            habits.clear(); habits.clearCheckins(); focus.clear(); flags.clear(); templates.clear(); countdowns.clear(); activity.clear()
            folders.upsertAll(b.folders); lists.upsertAll(b.lists); tasks.upsertAll(b.tasks); checklist.upsertAll(b.checklist)
            tags.upsertAll(b.tags); tags.linkAll(b.taskTags); contexts.upsertAll(b.contexts); contexts.linkAll(b.taskContexts)
            reminders.upsertAll(b.reminders); deps.addAll(b.dependencies)
            workspaces.upsertAll(b.workspaces); filters.upsertAll(b.filters)
            habits.upsertAll(b.habits); habits.upsertCheckins(b.habitCheckins); focus.upsertAll(b.focusSessions)
            flags.upsertAll(b.flags); templates.upsertAll(b.templates)
            countdowns.upsertAll(b.countdowns); activity.insertAll(b.activities)
            // R110 — additively union the non-core tables (notes/events/time/life-systems/revisions/…) so
            // folder-sync reconciles the FULL store, not just the core 19. Additive (never clears) so a
            // delete on the other device can't wipe local rows, and noBackup notes (excluded from snapshots)
            // are never deleted by a peer's snapshot. Attachments excluded — their bytes stay local.
            mergeNewerTables(b, includeAttachments = false)
            ensureDefaultWorkspace(); ensureInbox(); ensureDefaultFlags()
        }
    }

    // ============ first-run seed ============
    suspend fun ensureSeed() {
        ensureDefaultWorkspace()
        ensureInbox()
        ensureDefaultFlags()
        if (tasks.getAll().isNotEmpty()) return
        val work = createFolder("Work")
        val personal = createFolder("Personal")
        val quarterly = createList("Quarterly Report", folderId = work, colorArgb = 0xFFE5484D)
        val admin = createList("Admin", folderId = work, colorArgb = 0xFFF59E0B)
        val home = createList("Home", folderId = personal, colorArgb = 0xFF3E7BFA)

        createTask(ListEntity.INBOX_ID, "Try quick-add: \"pay rent tomorrow 5pm !! #home\"")
        createTask(ListEntity.INBOX_ID, "Everything is offline, private, and free")
        val report = createTask(quarterly, "Draft summary for board deck", importance = 5, urgency = 4)
        createTask(quarterly, "Collect figures", parentId = report, importance = 4)
        createTask(quarterly, "Write exec overview", parentId = report)
        createTask(admin, "File expense receipts", importance = 3, urgency = 4)
        createTask(home, "Water the plants", importance = 2)
        createTask(home, "Book dentist", importance = 3, urgency = 4)
    }
}
