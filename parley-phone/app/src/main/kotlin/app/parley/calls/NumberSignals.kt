package app.parley.calls

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.SimAccount
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.DeadNumberRadar
import app.parley.common.calls.SimAdvice
import app.parley.common.people.ContactRef
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.security.Concealment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Gathers what [DeadNumberRadar] and [SimAdvice] decide on: the quality facts Parley keeps per call, the call history
 * (a good call there clears an "out of service" number), the SIMs, the SIM remembered per number and what the user
 * already answered. Nothing here changes a contact or a SIM by itself; the answers come from the user's taps.
 */
object NumberSignals {
    /** A saved number that seems out of service, for the Contact health check. */
    data class DeadNumber(
        /** The contact's navigation id (negative for a private contact). */
        val navId: Long,
        val name: String,
        val number: String,
        val lineKey: String,
        val finding: DeadNumberRadar.Finding,
    ) {
        val private: Boolean get() = ContactRef.ofNavId(navId) is ContactRef.Private
    }

    /** "Calls to Ana drop less on SIM 2": the SIM to suggest, and the line keys of the person's numbers. */
    data class SimTip(val simId: String, val simLabel: String, val lineKeys: List<String>)

    /**
     * Every saved number (contacts and, unless they're hidden, private contacts) that seems out of service, the most
     * recent failure first. Only lines with failures in the quality facts are looked at, so a big address book costs
     * one fingerprint per number at most when there are any. [calls]: the call history with private calls, once it has
     * loaded (a good call there clears a number, so the caller waits for it rather than passing nothing).
     */
    suspend fun deadNumbers(c: DataContainer, contacts: List<ContactSummary>, calls: List<CallEntry>): List<DeadNumber> = withContext(Dispatchers.IO) {
        val byLine = c.callQuality.all().groupBy({ it.first }, { it.second })
        val suspects = byLine.filterValues { list -> list.count(DeadNumberRadar::mayPointAtNumber) >= DeadNumberRadar.MIN_FAILURES }
        if (suspects.isEmpty()) return@withContext emptyList()
        val owners = ArrayList<Triple<Long, String, String>>()
        contacts.forEach { s -> s.phones.forEach { p -> owners += Triple(s.id, s.displayName, p.number) } }
        if (showPrivate(c)) c.vault.contacts.value.forEach { v -> v.numbers.forEach { n -> owners += Triple(ContactRef.Private(v.id).navId, v.name, n) } }
        val alive = aliveSince(calls, c.directory.countryIso)
        val zone = ZoneId.systemDefault()
        owners.mapNotNull { (navId, name, number) ->
            val key = c.callQuality.keyOf(number)?.takeIf { it in suspects } ?: return@mapNotNull null
            val finding = DeadNumberRadar.check(suspects.getValue(key), number, zone, alive[number], c.numberAdvice.deadDismissedAt(key))
                ?: return@mapNotNull null
            DeadNumber(navId, name, number, key, finding)
        }.distinctBy { it.navId to it.lineKey }.sortedByDescending { it.finding.lastFailureAt }
    }

    /** Which of one contact's [numbers] seem out of service (for the hint beside the number on its page). */
    suspend fun deadAmong(c: DataContainer, numbers: List<String>, calls: List<CallEntry>): Set<String> = withContext(Dispatchers.IO) {
        if (numbers.isEmpty()) return@withContext emptySet()
        val alive = aliveSince(calls, c.directory.countryIso)
        val zone = ZoneId.systemDefault()
        numbers.filter { n ->
            val key = c.callQuality.keyOf(n) ?: return@filter false
            DeadNumberRadar.check(c.callQuality.forNumber(n), n, zone, alive[n], c.numberAdvice.deadDismissedAt(key)) != null
        }.toSet()
    }

    fun dismissDead(c: DataContainer, lineKey: String) = c.numberAdvice.dismissDead(lineKey)

    /**
     * "Move to note": takes [dead]'s number off the contact (every row on that line: the same number may be saved twice,
     * with different labels) and adds [noteLine] to its note, through the same save the editor uses (a contact's
     * earlier version goes to History & undo first). Returns the undo, which puts back every row taken off with its
     * label and type, or null when the number couldn't be moved this way (it's kept by an account Parley can't edit:
     * the editor is the way then).
     */
    suspend fun moveToNote(c: DataContainer, dead: DeadNumber, noteLine: String): (suspend () -> Unit)? = withContext(Dispatchers.IO) {
        val same = { p: DataItem -> PhoneIdentity.same(p.value, dead.number, c.directory.countryIso) }
        val withNote = { note: String -> if (note.isBlank()) noteLine else note.trimEnd() + "\n" + noteLine }
        when (val ref = ContactRef.ofNavId(dead.navId)) {
            is ContactRef.Device -> moveDevice(c, ref.contactId, same, withNote)
            is ContactRef.Private -> movePrivate(c, ref.vaultId, same, withNote)
            null -> null
        }
    }

    private suspend fun moveDevice(c: DataContainer, id: Long, same: (DataItem) -> Boolean, withNote: (String) -> String): (suspend () -> Unit)? {
        val before = c.contacts.editable(id) ?: return null
        val removed = before.phones.filter(same).ifEmpty { return null }
        val edited = before.copy(phones = before.phones.filterNot(same), note = withNote(before.note))
        val saved = c.contacts.save(before, edited, account = null, photo = null, removePhoto = false) ?: return null
        return {
            withContext(Dispatchers.IO) {
                c.contacts.editable(saved.contactId)?.let { now ->
                    // The note goes back only when nobody changed it since.
                    val note = if (now.note == edited.note) before.note else now.note
                    c.contacts.save(now, now.copy(phones = restored(now.phones, removed, same), note = note), null, null, false)
                }
            }
        }
    }

    private suspend fun movePrivate(c: DataContainer, vaultId: Long, same: (DataItem) -> Boolean, withNote: (String) -> String): (suspend () -> Unit)? {
        val before = c.vault.open(vaultId)?.details ?: return null
        val removed = before.phones.filter(same).ifEmpty { return null }
        val edited = before.copy(phones = before.phones.filterNot(same), note = withNote(before.note))
        c.vault.save(vaultId, edited, loaded = before)
        return {
            withContext(Dispatchers.IO) {
                c.vault.open(vaultId)?.details?.let { now ->
                    val note = if (now.note == edited.note) before.note else now.note
                    c.vault.save(vaultId, now.copy(phones = restored(now.phones, removed, same), note = note), loaded = now)
                }
            }
        }
    }

    /**
     * Undoing "Move to note": every row [removed] goes back (as new rows, with their labels and types), except when the
     * number was saved again since ([same] matches a row in [now]): then the contact is left as it is now.
     */
    internal fun restored(now: List<DataItem>, removed: List<DataItem>, same: (DataItem) -> Boolean): List<DataItem> =
        if (now.any(same)) now else now + removed.map { it.copy(id = null) }

    /**
     * The SIM to suggest for the person with [numbers], or null: never on a single-SIM phone, never one already
     * remembered for them, and never one they answered before.
     */
    suspend fun simTip(c: DataContainer, numbers: List<String>, sims: List<SimAccount>, extra: CallQualityFacts? = null): SimTip? =
        withContext(Dispatchers.IO) {
            if (sims.size < 2 || numbers.isEmpty()) return@withContext null
            val keys = numbers.mapNotNull { c.callQuality.keyOf(it) }.distinct()
            if (keys.isEmpty()) return@withContext null
            val facts = numbers.flatMap { c.callQuality.forNumber(it) }.let { list ->
                // The call that just ended may not be stored yet.
                if (extra == null || list.any { it.startedAt == extra.startedAt }) list else list + extra
            }.distinctBy { it.startedAt }
            val calls = facts.mapNotNull { f -> simIdOf(f, sims)?.let { SimAdvice.SimCall(it, f) } }
            val remembered = numbers.map { c.prefs.simFor(it) }.distinct()
            val tip = SimAdvice.suggest(calls, sims.map { it.id }.toSet(), remembered.singleOrNull(), c.numberAdvice.simAnswered(keys))
                ?: return@withContext null
            SimTip(tip.simId, sims.first { it.id == tip.simId }.label, keys)
        }

    /** "Use SIM 2 for Ana" ([accept]) or dismissed: either way this suggestion is answered for good. */
    suspend fun answerSim(c: DataContainer, numbers: List<String>, tip: SimTip, accept: Boolean) = withContext(Dispatchers.IO) {
        if (accept) numbers.forEach { c.prefs.setSimFor(it, tip.simId) }
        c.numberAdvice.answerSim(tip.lineKeys, tip.simId)
    }

    /** The call-ended screen offers a suggestion once; true when this one wasn't offered there yet (and marks it). */
    fun offerAfterCall(c: DataContainer, tip: SimTip): Boolean {
        if (tip.simId in c.numberAdvice.simOfferedAfterCall(tip.lineKeys)) return false
        c.numberAdvice.markSimOfferedAfterCall(tip.lineKeys, tip.simId)
        return true
    }

    /** The SIM a call was on: its account id, or (rows from before that was kept) the SIM with its name. */
    private fun simIdOf(f: CallQualityFacts, sims: List<SimAccount>): String? =
        f.simId?.takeIf { id -> sims.any { it.id == id } } ?: f.sim?.let { label -> sims.singleOrNull { it.label == label }?.id }

    private suspend fun showPrivate(c: DataContainer): Boolean = !c.settings.current().hideVault && !Concealment.hiding

    /** The latest call per line that shows it works: any call from it, or one to it that was answered. */
    internal fun aliveSince(calls: List<CallEntry>, iso: String): PhoneIdentity.LineMap<Long> {
        val map = PhoneIdentity.LineMap<Long>(iso)
        calls.sortedByDescending { it.date }.forEach { e ->
            if (e.type != CallType.OUTGOING || e.durationSec > 0) map.putIfAbsent(e.number, e.date)
        }
        return map
    }
}
