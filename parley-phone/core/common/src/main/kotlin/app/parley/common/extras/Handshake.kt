package app.parley.common.extras

import app.parley.common.circle.Interactions

/**
 * X5 handshake: a contact received by QR gets a "Met at … on …" line. The sentence itself is localised by the app;
 * this keeps the bookkeeping: where the line goes in the note, and the unique key of the Circle entry.
 */
object Handshake {
    /** Longest place name kept (a place, not a diary entry). */
    const val MAX_PLACE = 80

    fun cleanPlace(place: String): String = place.replace(Regex("\\s+"), " ").trim().take(MAX_PLACE)

    /**
     * [existing] note with [line] added on its own line at the end. Nothing is added when the note already has that
     * exact line (receiving the same card twice).
     */
    fun appendToNote(existing: String, line: String): String {
        val l = line.trim()
        if (l.isEmpty()) return existing
        val lines = existing.lines().map { it.trim() }
        if (l in lines) return existing
        return if (existing.isBlank()) l else existing.trimEnd() + "\n" + l
    }

    /** Unique key of the MEET entry for one exchange, so saving twice records one meeting. */
    fun meetKey(nonce: String): String = Interactions.manualKey("handshake:$nonce")
}

/**
 * X5: one value waiting for the screen it was made for: [put] binds it to that screen's launch [id] (passed in the
 * screen's route), and only [take] with the same id gets it, within [ttlMs]. A screen opened any other way (another
 * app's "add contact", a deep link) has no id or another one and never gets it; an old value expires.
 */
class PendingSlot<T>(private val ttlMs: Long = DEFAULT_TTL_MS) {
    private var held: Held<T>? = null

    private class Held<T>(val id: String, val at: Long, val value: T)

    @Synchronized
    fun put(id: String, now: Long, value: T) {
        held = Held(id, now, value)
    }

    /**
     * The value bound to [id], if it's still fresh; the slot is emptied when [id] matches (used once) or the value
     * has expired. Any other id leaves it alone.
     */
    @Synchronized
    fun take(id: String?, now: Long): T? {
        val h = held ?: return null
        val expired = now - h.at !in 0..ttlMs
        if (expired) {
            held = null
            return null
        }
        if (id.isNullOrEmpty() || id != h.id) return null
        held = null
        return h.value
    }

    companion object {
        /** Long enough to fill in the editor, short enough that a forgotten one doesn't linger. */
        const val DEFAULT_TTL_MS = 60 * 60_000L
    }
}
