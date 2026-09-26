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
