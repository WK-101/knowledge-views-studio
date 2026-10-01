package app.parley.ui.memory

import android.content.Context
import androidx.annotation.StringRes
import app.parley.R
import app.parley.common.memory.MemoryHint
import app.parley.common.memory.MemorySource
import app.parley.common.memory.NumberMemory
import app.parley.ui.common.Format
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** What a remembered line's button does. */
enum class MemoryAction(@StringRes val label: Int) {
    /** Restores the deleted contact from History & undo. */
    RESTORE(R.string.number_memory_restore),

    /** Restores the deleted private contact (shown only while the vault is unlocked). */
    RESTORE_PRIVATE(R.string.number_memory_restore),

    /** Opens the contact whose page the note is on. */
    OPEN_NOTE(R.string.number_memory_open_note),

    /** Opens History & undo's Snapshots. */
    OPEN_SNAPSHOT(R.string.number_memory_open_snapshot),
    OPEN_TO_CALL(R.string.number_memory_open_to_call),

    /** Opens the number's history (calls, call notes, the last chat). */
    OPEN_HISTORY(R.string.number_memory_open_history),
}

/** Number memory in words: one line per hint, in the user's language. */
object NumberMemoryText {
    fun line(context: Context, h: MemoryHint, now: Long = System.currentTimeMillis()): String {
        val res = context.resources
        val name = h.name?.takeIf { it.isNotBlank() }
        return when (h.source) {
            MemorySource.DELETED_CONTACT, MemorySource.DELETED_PRIVATE ->
                h.relatedTo?.let { res.getString(R.string.number_memory_deleted_related, name.orEmpty(), month(h.at, now), it) }
                    ?: res.getString(R.string.number_memory_deleted, name.orEmpty(), month(h.at, now))
            MemorySource.SNAPSHOT -> res.getString(R.string.number_memory_snapshot, name.orEmpty(), day(h.at))
            MemorySource.CALL_NOTE -> res.getString(R.string.number_memory_call_note, h.excerpt.orEmpty())
            MemorySource.NOTE -> note(context, name, h.excerpt)
            MemorySource.ARCHIVE_NAME -> res.getString(R.string.number_memory_archive_name, name.orEmpty(), month(h.at, now))
            MemorySource.MESSAGED -> res.getString(R.string.number_memory_messaged, h.excerpt.orEmpty(), month(h.at, now))
            MemorySource.TO_CALL -> res.getString(R.string.number_memory_to_call, Format.shortWhen(context, h.at))
            MemorySource.CALLS -> res.getQuantityString(R.plurals.number_memory_calls, h.count, h.count, month(h.at, now))
        }
    }

    /** "In your note on Ana: 'Dr Lee's office'", with whichever of the two parts is known. */
    private fun note(context: Context, name: String?, excerpt: String?): String {
        val res = context.resources
        return when {
            name != null && excerpt != null -> res.getString(R.string.number_memory_note, name, excerpt)
            name != null -> res.getString(R.string.number_memory_note_plain, name)
            excerpt != null -> res.getString(R.string.number_memory_note_someone, excerpt)
            else -> res.getString(R.string.number_memory_note_someone_plain)
        }
    }

    /** The hint's action at [place]; none for what [place] already shows. */
    fun action(h: MemoryHint, place: NumberMemory.Place): MemoryAction? = when (h.source) {
        MemorySource.DELETED_CONTACT -> MemoryAction.RESTORE.takeIf { h.ref?.toLongOrNull() != null }
        MemorySource.DELETED_PRIVATE -> MemoryAction.RESTORE_PRIVATE.takeIf { h.ref != null }
        MemorySource.SNAPSHOT -> MemoryAction.OPEN_SNAPSHOT
        MemorySource.NOTE -> MemoryAction.OPEN_NOTE.takeIf { h.ref != null }
        MemorySource.TO_CALL -> MemoryAction.OPEN_TO_CALL
        MemorySource.CALL_NOTE, MemorySource.CALLS, MemorySource.ARCHIVE_NAME, MemorySource.MESSAGED ->
            MemoryAction.OPEN_HISTORY.takeIf { place != NumberMemory.Place.HISTORY }
    }

    /** "March", or "March 2024" outside this year. */
    private fun month(at: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val sameYear = Instant.ofEpochMilli(at).atZone(zone).year == Instant.ofEpochMilli(now).atZone(zone).year
        return format(at, if (sameYear) "LLLL" else "LLLLyyyy")
    }

    /** "12 March" in the user's order. */
    private fun day(at: Long): String = format(at, "dMMMM")

    private fun format(at: Long, skeleton: String): String =
        android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, Locale.getDefault()).format(Date(at))
}
