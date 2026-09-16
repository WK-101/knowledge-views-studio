package com.todocompanion.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.todocompanion.app.data.entity.NoteCardEntity

/**
 * Wave 3 — Active Recall card store. Cards are materialized from note bodies (see NoteCards) but their
 * SM-2 schedule persists here across re-parses, keyed by a stable card id.
 */
@Dao
interface NoteCardDao {
    @Query("SELECT * FROM note_cards")
    suspend fun getAll(): List<NoteCardEntity>

    @Query("SELECT * FROM note_cards WHERE noteId = :noteId")
    suspend fun forNote(noteId: String): List<NoteCardEntity>

    @Query("SELECT * FROM note_cards WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): NoteCardEntity?

    /** Cards due at or before [now], soonest first. */
    @Query("SELECT * FROM note_cards WHERE dueAt <= :now ORDER BY dueAt ASC")
    suspend fun due(now: Long): List<NoteCardEntity>

    @Query("SELECT COUNT(*) FROM note_cards WHERE dueAt <= :now")
    suspend fun dueCount(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(card: NoteCardEntity): Long

    @Upsert
    suspend fun upsert(card: NoteCardEntity)

    /** Replace only the content of a card (front/back/kind), preserving its schedule. */
    @Query("UPDATE note_cards SET front = :front, back = :back, cardKind = :kind WHERE id = :id")
    suspend fun updateContent(id: String, front: String, back: String, kind: String)

    @Query("DELETE FROM note_cards WHERE noteId = :noteId AND id NOT IN (:keepIds)")
    suspend fun deleteForNoteExcept(noteId: String, keepIds: List<String>)

    @Query("DELETE FROM note_cards WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String)

    /** Wipe every flashcard — used by a replace-restore so stale SM-2 schedules don't survive a full reset. */
    @Query("DELETE FROM note_cards")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<NoteCardEntity>)
}
