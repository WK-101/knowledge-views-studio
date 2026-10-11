package app.parley.data.people

import android.content.Context
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.catching
import app.parley.common.people.Attribution
import app.parley.common.people.NumberOrigin
import app.parley.common.people.NumberPassport
import app.parley.common.people.NumberStamp
import app.parley.common.people.PassportLine
import app.parley.common.people.PassportState
import app.parley.data.PhoneEnv
import app.parley.data.security.RecordSealing
import app.parley.data.security.SealedDocument
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The number passport ([NumberPassport]): when each number came onto a contact and from where, in one small
 * [SealedDocument] (`docs/number_passport` in no-backup storage). Sealed with the small-records key, so the ring path
 * can read it while the phone is locked. It stays on this phone: it describes this phone's address book, and restored
 * contacts are stamped as restored.
 *
 * Private contacts aren't in it: only Parley writes them, so there is nothing to tell apart. A stored document that
 * can't be opened right now is never taken for an empty one ([SealedDocument]); until it can, nothing warns.
 */
class NumberPassportStore internal constructor(
    context: Context,
    private val region: () -> String?,
    /** Seals the encoded document; null when sealing isn't possible right now (tests replace it). */
    sealOverride: ((String) -> String?)?,
) : RecordSealing.Resealable {
    constructor(context: Context) : this(context, { PhoneEnv.countryIso(context.applicationContext) }, null)

    private val appContext = context.applicationContext

    private val folder = File(appContext.noBackupFilesDir, "docs/number_passport")

    private val doc = SealedDocument(
        appContext, folder,
        SealedDocument.Whole(NumberPassport::encode, NumberPassport::decode), sealOverride = sealOverride,
    )

    /** The passport as stored; empty until [load] ran. */
    val state: StateFlow<PassportState> = doc.state

    /** Whether the stored passport could be read. */
    val available: Boolean get() = doc.available

    suspend fun load(): PassportState = doc.load()

    override suspend fun resealPlain(): Boolean = doc.resealPlain()

    /**
     * Where numbers about to be put in the editor came from (pasted text, a scanned code, another app's "Add to
     * contacts"). Kept in memory for [HINT_MS]: a number saved meanwhile in the editor is stamped with it, not "you".
     */
    private val hints = ConcurrentHashMap<String, Pair<NumberOrigin, Long>>()

    fun hint(numbers: Collection<String>, origin: NumberOrigin, now: Long = System.currentTimeMillis()) {
        hints.entries.removeIf { now - it.value.second > HINT_MS }
        numbers.map(::line).filter { it.key.isNotEmpty() }.forEach { hints[it.key] = origin to now }
    }

    private fun hinted(key: String, now: Long): NumberOrigin? = hints[key]?.takeIf { now - it.second in 0..HINT_MS }?.first

    /** One number as the passport keys it. */
    fun line(number: String): PassportLine = PassportLine(PhoneIdentity.key(number, region()), number)

    /**
     * Whether the passport has taken stock once (its document is written by the first [sweep]). Before that a write
     * isn't noted: the first sweep only takes stock anyway, and the key isn't opened for nothing.
     */
    private fun begun(): Boolean = state.value.since != null || File(folder, SealedDocument.INDEX + ".sealed").isFile

    /** Parley's editor saved the contact [lookupKey], whose numbers went from [before] to [after]. */
    suspend fun saved(lookupKey: String, before: List<String>, after: List<String>, now: Long = System.currentTimeMillis()) {
        if (!begun()) return
        doc.write { s -> NumberPassport.saved(s, lookupKey, before.map(::line), after.map(::line), now) { l -> hinted(l.key, now) ?: NumberOrigin.YOU } }
        after.map(::line).forEach { hints.remove(it.key) }
    }

    /** Parley wrote [numbers] in bulk (an import, a restore, a shared label), coming from [origin]. */
    suspend fun written(numbers: List<String>, origin: NumberOrigin, now: Long = System.currentTimeMillis()) {
        if (!begun()) return
        // A number put in the editor from a scanned card is imported, but it came from the code.
        val lines = numbers.map(::line)
        val byOrigin = lines.groupBy { hinted(it.key, now) ?: origin }
        doc.write { s -> byOrigin.entries.fold(s) { acc, (o, l) -> NumberPassport.written(acc, l, o, now) } }
    }

    /**
     * Takes stock of the address book ([contacts]). The contacts with numbers nobody accounted for are asked about
     * ([attribute]: "Why did this change?"), unless more than [NumberPassport.MASS_CHANGE] changed at once.
     */
    suspend fun sweep(contacts: List<ContactSummary>, attribute: suspend (Long) -> Attribution?, now: Long = System.currentTimeMillis()) {
        val s = load()
        if (!available) return
        val current = contacts.filter { it.lookupKey.isNotEmpty() }.groupBy { it.lookupKey }
            .mapValues { (_, cs) -> cs.flatMap { c -> c.phones.map { line(it.number) } }.distinctBy { it.key } }
        val todo = NumberPassport.unattributed(s, current)
        val ids = contacts.associate { it.lookupKey to it.id }
        val origins = if (todo.size > NumberPassport.MASS_CHANGE) {
            emptyMap()
        } else {
            todo.keys.mapNotNull { k -> ids[k]?.let { id -> catching { attribute(id) }.getOrNull()?.let { k to it } } }.toMap()
        }
        doc.write { NumberPassport.sweep(it, current, origins, now) }
    }

    /** What the passport says about [number] now: its stamp, and whether it was seen since the passport began. */
    data class Entry(val line: PassportLine, val stamp: NumberStamp?, val seen: Boolean, val tracking: Boolean, val looked: Long?)

    /** The entry of [number]; null while the passport can't be read. */
    suspend fun entry(number: String): Entry? {
        val s = load()
        if (!available) return null
        val l = line(number)
        if (l.key.isEmpty()) return null
        return Entry(l, s.stamps[l.key], l.key in s.known, s.since != null, s.looked)
    }

    private companion object {
        const val HINT_MS = 2 * 60 * 60 * 1000L
    }
}
