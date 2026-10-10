package app.parley.common.vcard

import app.parley.common.qr.ScannedCard
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import app.parley.common.security.Bounded
import java.io.IOException
import java.io.Reader

/**
 * A plain (unencrypted) vCard, QR code or CSV file may come from anyone: a card sent in a messenger, a printed code.
 * Such a card must not plant a contact Parley hides or trusts. So, unless the file is one of Parley's own encrypted
 * exports, an import drops what changes how Parley and Android treat the caller:
 * - archived: an archived contact is invisible in the lists, yet counts as saved for screening and the family shield;
 * - favourite (starred), which rings through Do Not Disturb;
 * - straight to voicemail, and a ringtone (a `content:` address the system would open);
 * - data rows of any kind but Android's own, Parley's and Google's custom field: another app's kind (a messenger's)
 *   would point that app's actions at the card's numbers.
 *
 * "Private" stays: it only ever keeps a card out of the shared address book. Notes for calls and the like were
 * already kept only from encrypted files ([CardNotes.forImport]).
 *
 * The QR result sheet lets the user tick a flag back on ([ScannedCard]); those come in as `keep`.
 */
object ImportGuard {
    /** What a plain import left out, in cards (one card asking twice counts once per kind). */
    data class Dropped(
        val archived: Int = 0,
        val starred: Int = 0,
        val voicemail: Int = 0,
        val ringtone: Int = 0,
        val otherApps: Int = 0,
    ) {
        val isEmpty: Boolean get() = this == NONE

        operator fun plus(o: Dropped) =
            Dropped(archived + o.archived, starred + o.starred, voicemail + o.voicemail, ringtone + o.ringtone, otherApps + o.otherApps)

        /** What is still left out once the user ticked [keep] back on. */
        fun without(keep: Set<ScannedCard.Flag>) = copy(
            starred = if (ScannedCard.Flag.STARRED in keep) 0 else starred,
            voicemail = if (ScannedCard.Flag.VOICEMAIL in keep) 0 else voicemail,
            ringtone = if (ScannedCard.Flag.RINGTONE in keep) 0 else ringtone,
        )

        companion object {
            val NONE = Dropped()
        }
    }

    /** Android's own data kinds (ContactsContract.CommonDataKinds), as a card may carry them in `X-ANDROID-CUSTOM`. */
    private val ANDROID_KINDS = setOf(
        Mime.NAME, Mime.PHONE, Mime.EMAIL, Mime.POSTAL, Mime.ORG, Mime.NICKNAME, Mime.NOTE, Mime.WEBSITE, Mime.EVENT,
        Mime.IM, Mime.RELATION, Mime.SIP, Mime.PHOTO, Mime.GROUP, Mime.IDENTITY,
    )
    private const val PARLEY_KINDS = "vnd.android.cursor.item/vnd.parley."

    /** Whether a data row of [mime] may come in from a plain file. */
    fun allowedKind(mime: String): Boolean = mime in ANDROID_KINDS || mime.startsWith(PARLEY_KINDS) || mime == Mime.GOOGLE_CUSTOM_FIELD

    /** One card after the guard: the record and notes to import, and what was left out. */
    class Guarded(val record: ContactRecord, val notes: CardNotes?, val dropped: Dropped)

    /**
     * [record] and its [notes] as a plain import keeps them ([fromSealed] false), or unchanged from one of Parley's
     * encrypted files. [keep] are the flags the user ticked back on for this import.
     */
    fun guard(record: ContactRecord, notes: CardNotes?, fromSealed: Boolean, keep: Set<ScannedCard.Flag> = emptySet()): Guarded {
        if (fromSealed) return Guarded(record, notes, Dropped.NONE)
        val starred = record.starred && ScannedCard.Flag.STARRED !in keep
        val voicemail = record.sendToVoicemail && ScannedCard.Flag.VOICEMAIL !in keep
        val ringtone = !record.customRingtone.isNullOrEmpty() && ScannedCard.Flag.RINGTONE !in keep
        val others = record.raws.any { r -> r.rows.any { !allowedKind(it.mimeType) } }
        val archived = notes?.archived == true
        val dropped = Dropped(
            archived = if (archived) 1 else 0,
            starred = if (starred) 1 else 0,
            voicemail = if (voicemail) 1 else 0,
            ringtone = if (ringtone) 1 else 0,
            otherApps = if (others) 1 else 0,
        )
        if (dropped.isEmpty) return Guarded(record, notes, dropped)
        val kept = record.copy(
            starred = record.starred && !starred,
            sendToVoicemail = record.sendToVoicemail && !voicemail,
            customRingtone = record.customRingtone.takeUnless { ringtone },
            raws = if (others) record.raws.map { r -> r.copy(rows = r.rows.filter { allowedKind(it.mimeType) }) } else record.raws,
        )
        return Guarded(kept, notes?.copy(archived = false), dropped)
    }

    /** A quick look at a file before it is imported: about how many entries, how many private, what will be left out. */
    data class Scan(val entries: Int, val private: Int, val dropped: Dropped, val capped: Boolean)

    /** Characters a pre-scan reads at most; past it, the scan stops and says it is [Scan.capped]. */
    const val SCAN_CHARS = 128L shl 20

    /**
     * Scans vCard text line by line, without parsing, for [Scan]. Bounded: lines over [Bounded.Caps.TEXT_LINE], more
     * than [maxChars] characters or [Bounded.Caps.IMPORT_ENTRIES] cards stop it, so a crafted endless file can't fill
     * the memory. Never throws for a bad file: what was counted so far is returned, [Scan.capped] when it stopped early.
     */
    fun scanVCards(input: Reader, maxChars: Long = SCAN_CHARS): Scan {
        var cards = 0
        var private = 0
        var dropped = Dropped.NONE
        var card = CardFlags()
        val capped = eachLine(input, maxChars, { cards >= Bounded.Caps.IMPORT_ENTRIES }) { line ->
            // A folded line continues the property before it.
            val colon = line.indexOf(':')
            if (line.startsWith(' ') || line.startsWith('\t') || colon < 0) return@eachLine
            val name = line.substring(0, colon).substringBefore(';').substringAfterLast('.').trim().uppercase()
            val value = line.substring(colon + 1).trim()
            when {
                name == "BEGIN" && value.equals("VCARD", true) -> card = CardFlags()
                name == "END" && value.equals("VCARD", true) -> {
                    cards++
                    if (card.private) private++
                    dropped += card.dropped()
                }
                else -> card.note(name, value)
            }
        }
        return Scan(cards, private, dropped, capped)
    }

    /**
     * Scans a CSV file for its rows (a header line included) and, in Parley's own layout, the rows its last
     * [ContactCsv.ARCHIVED] column marks; bounded like [scanVCards].
     */
    fun scanLines(input: Reader, maxChars: Long = SCAN_CHARS): Scan {
        var rows = 0
        var archived = 0
        var archivedColumn = false
        val capped = eachLine(input, maxChars, { rows >= Bounded.Caps.IMPORT_ENTRIES }) { line ->
            if (line.isBlank()) return@eachLine
            val last = line.trimEnd().substringAfterLast(',').trim('"')
            if (rows++ == 0) archivedColumn = last.equals(ContactCsv.ARCHIVED, ignoreCase = true)
            else if (archivedColumn && last == "1") archived++
        }
        return Scan(rows, 0, Dropped(archived = archived), capped)
    }

    /**
     * Hands each line of [input] to [onLine] until the end, more than [maxChars] characters, [full] or a line over
     * the line cap; true when it stopped early.
     */
    private fun eachLine(input: Reader, maxChars: Long, full: () -> Boolean, onLine: (String) -> Unit): Boolean {
        val lines = Bounded.LineReader(input)
        var read = 0L
        return try {
            var line = lines.readLine()
            while (line != null) {
                read += line.length + 1
                if (read > maxChars || full()) return true
                onLine(line)
                line = lines.readLine()
            }
            false
        } catch (_: IOException) {
            // LimitExceededException included: a line too long for the cap.
            true
        }
    }

    private fun on(v: String) = v == "1" || v.equals("true", ignoreCase = true)

    /** What one card asks for, by property name (`CATEGORIES:starred` counted as the star). */
    private class CardFlags {
        private val asked = HashSet<String>()
        val private get() = CardNotes.X_PRIVATE in asked

        fun note(name: String, value: String) {
            val yes = when (name) {
                CardNotes.X_PRIVATE, CardNotes.X_ARCHIVED -> value == "1"
                VCardMapper.X_STARRED, VCardMapper.X_VOICEMAIL -> on(value)
                VCardMapper.X_RINGTONE -> value.isNotEmpty()
                CATEGORIES -> value.split(',').any { it.trim().equals("starred", true) }
                VCardMapper.X_ANDROID_CUSTOM -> !allowedKind(value.substringBefore(';').trim())
                else -> false
            }
            if (yes) asked += if (name == CATEGORIES) VCardMapper.X_STARRED else name
        }

        fun dropped() = Dropped(
            archived = count(CardNotes.X_ARCHIVED),
            starred = count(VCardMapper.X_STARRED),
            voicemail = count(VCardMapper.X_VOICEMAIL),
            ringtone = count(VCardMapper.X_RINGTONE),
            otherApps = count(VCardMapper.X_ANDROID_CUSTOM),
        )

        private fun count(name: String) = if (name in asked) 1 else 0
    }

    private const val CATEGORIES = "CATEGORIES"
}
