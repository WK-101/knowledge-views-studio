package app.parley.data.security

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.parley.common.security.PinBackoff
import app.parley.common.security.PinHasher
import app.parley.common.security.PinProblem
import app.parley.common.security.PinRecord
import app.parley.common.security.PinRules
import app.parley.common.security.PinVerdict
import app.parley.common.storage.DurableFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * I21: the Parley PIN and the duress PIN ([PinRecord]: scrypt hashes, never the PINs), with the count of wrong tries.
 * One file under no_backup, sealed with the small-records key (AES-GCM under a Keystore-wrapped key), so a copy of
 * Parley's files alone gives nothing to guess against offline. The hashes are never written unsealed: should the
 * Keystore fail while sealing, the record stays in memory (a change is refused, a try count fails closed) and an older
 * plain record, from before this rule, is sealed at the next read. It never travels in backups: a new phone sets its PIN
 * again, and the app lock falls back to the screen lock there.
 *
 * During a duress session (`duressSession = true`) the screens may change the PIN as they please: a new PIN replaces
 * the duress PIN (the one the person watching knows), and turning the PIN off only shows it off until the next lock.
 * The screens of a session see [shown]: the duress PIN as off, exactly as on a phone where none was ever set (M7).
 */
class AppPinStore(context: Context, private val records: () -> RecordCrypto) {
    private val file = File(context.applicationContext.noBackupFilesDir, "app_pin")
    private val mutex = Mutex()

    /** What screens may know: whether each PIN is set (never the hashes). */
    data class Summary(
        val pinSet: Boolean = false,
        val duressSet: Boolean = false,
        val lockVaultOnDuress: Boolean = true,
    ) {
        /**
         * The fingerprint or screen lock may unlock Parley: only without a Parley PIN. With one, only a PIN does,
         * whether or not a duress PIN is set, so the lock screen looks the same either way (M7).
         */
        val deviceUnlocks: Boolean get() = !pinSet
    }

    private val _summary = MutableStateFlow<Summary?>(null)

    /** Null until read once ([load]); the lock screen waits for it. */
    val summary: StateFlow<Summary?> = _summary.asStateFlow()

    /**
     * What the screens of a duress session show, in memory until the next lock (null outside one): the PIN on unless
     * someone turned it off, the duress PIN off unless someone set one in the session ([sessionDuress], a hash under
     * the record's salt, never stored).
     */
    private data class SessionView(val pinOff: Boolean = false, val sessionDuress: String? = null, val lockVault: Boolean = true)

    @Volatile private var session: SessionView? = null

    private val _sessionShownOff = MutableStateFlow(false)

    /** In a duress session, someone turned the PIN off: the screens show it off until the next lock (it stays on). */
    val sessionShownOff: StateFlow<Boolean> = _sessionShownOff.asStateFlow()

    private val _shown = MutableStateFlow<Summary?>(null)

    /** What Settings shows: [summary], or during a duress session the session's view of it (M7). */
    val shown: StateFlow<Summary?> = _shown.asStateFlow()

    private fun setSession(v: SessionView?) {
        session = v
        publishShown()
    }

    private fun publishShown() {
        val real = _summary.value
        val sess = session
        _shown.value = if (sess == null || real == null) {
            real
        } else {
            Summary(pinSet = !sess.pinOff, duressSet = !sess.pinOff && sess.sessionDuress != null, lockVaultOnDuress = sess.lockVault)
        }
        _sessionShownOff.value = sess?.pinOff == true
    }

    /** A duress session began: its screens start from "no duress PIN" (M7). */
    fun beginSession() = setSession(SessionView())

    @Volatile private var record: PinRecord? = null

    @Volatile private var loaded = false

    suspend fun load(): Summary = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }

    /** The file is there but can't be opened right now (a Keystore hiccup): read again next time, never overwritten silently. */
    @Volatile private var unreadable = false

    private fun loadLocked(): Summary {
        if (!loaded) {
            val exists = file.isFile
            record = read()
            unreadable = exists && record == null
            loaded = !unreadable
            publish()
            record?.let(::resealIfPlain)
        }
        return _summary.value ?: Summary()
    }

    private fun read(): PinRecord? {
        if (!file.isFile) return null
        return try {
            PinRecord.decode(String(records().openBytes(file.readBytes()), Charsets.UTF_8))
        } catch (ignored: Exception) {
            // Unreadable now (a Keystore hiccup): see publish(); the file stays as it is and is read again next time.
            Log.w(TAG, "PIN record unreadable: ${ignored.javaClass.simpleName}")
            null
        }
    }

    /** A record stored plain by an older version (a Keystore failure then) is sealed as soon as sealing works. */
    private fun resealIfPlain(r: PinRecord) {
        val plain = runCatching { !records().isSealed(file.readBytes()) }.getOrDefault(false)
        if (plain) runCatching { write(r) }.onFailure { Log.w(TAG, "PIN record still unsealed: ${it.javaClass.simpleName}") }
    }

    private fun write(r: PinRecord?) {
        if (r == null) {
            file.delete()
        } else {
            DurableFiles.writeOrThrow(file, records().sealBytesOrThrow(r.encode().toByteArray(Charsets.UTF_8)))
        }
        record = r
        unreadable = false
        loaded = true
        publish()
    }

    private fun publish() {
        val r = record
        _summary.value = when {
            r != null -> Summary(pinSet = true, duressSet = r.hasDuress, lockVaultOnDuress = r.lockVaultOnDuress)
            // L5: the PINs can't be read for a moment (a Keystore hiccup): the PIN field stays, and tries the record
            // again with every PIN typed. The screen lock must not open Parley because of a hiccup (fail closed).
            unreadable -> Summary(pinSet = true)
            else -> Summary()
        }
        publishShown()
    }

    /** One attempt on the lock screen: the verdict, or how long to wait first ([Attempt.waitMs] > 0, nothing checked). */
    data class Attempt(val verdict: PinVerdict, val waitMs: Long = 0L, val lockVaultOnDuress: Boolean = true)

    /** M9: a try counted while the count couldn't be stored has already been refused once in this process. */
    @Volatile private var closedOnce = false

    suspend fun check(pin: String): Attempt = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked()
            val now = SystemClock.elapsedRealtime()
            val r = record?.let { PinBackoff.rebased(it, now) } ?: return@withLock Attempt(PinVerdict.WRONG)
            val wait = PinBackoff.remaining(r, now)
            if (wait > 0) {
                if (r != record) persist(r)
                return@withLock Attempt(PinVerdict.WRONG, wait)
            }
            // M9: the try is counted, and stored, before the PIN is checked, so pulling the plug (or a crash) after a
            // wrong try doesn't reset it. A count that can't be stored is kept in memory, and fails closed: the first
            // such try in a process isn't checked at all, and every wrong one after it waits, so restarting Parley
            // to forget the count buys nothing.
            val counted = PinBackoff.after(r, PinVerdict.WRONG, now)
            val stored = persist(counted)
            if (!stored && !closedOnce) {
                closedOnce = true
                val closed = closedCount(counted)
                record = closed
                return@withLock Attempt(PinVerdict.WRONG, PinBackoff.remaining(closed, now))
            }
            val verdict = PinHasher.verify(r, PinRules.normalize(pin))
            val next = PinBackoff.after(r, verdict, now).let { if (!stored && verdict == PinVerdict.WRONG) closedCount(it) else it }
            if (next != counted) persist(next) else record = next
            Attempt(verdict, if (verdict == PinVerdict.WRONG) PinBackoff.remaining(next, now) else 0L, r.lockVaultOnDuress)
        }
    }

    /** A wrong-try count that couldn't be stored: never below the free tries, so every wrong try waits. */
    private fun closedCount(r: PinRecord) = r.copy(failures = maxOf(r.failures, PinBackoff.FREE_TRIES))

    /** Writes [r]; false when it couldn't be stored (it is kept in memory either way). */
    private suspend fun persist(r: PinRecord): Boolean = try {
        withContext(Dispatchers.IO) { write(r) }
        true
    } catch (ignored: Exception) {
        Log.w(TAG, "PIN record not saved: ${ignored.javaClass.simpleName}")
        record = r
        false
    }

    /**
     * How long before the PIN may be changed again (M5, [PinBackoff.changeWait]): the same whatever is typed and in
     * every session, so a wait says nothing about which PIN opened Parley.
     */
    suspend fun changeWait(): Long = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked()
            val now = SystemClock.elapsedRealtime()
            record?.let { PinBackoff.changeWait(PinBackoff.rebased(it, now), now) } ?: 0L
        }
    }

    /** How long the lock screen must wait now (after a restart too). */
    suspend fun waitNow(): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            record?.let { PinBackoff.remaining(it, SystemClock.elapsedRealtime()) } ?: 0L
        }
    }

    /** What an edit does to the stored record. */
    private sealed interface Change {
        data object Refuse : Change
        data object Remove : Change
        data class Put(val record: PinRecord) : Change
    }

    /**
     * Sets or changes the Parley PIN. In a duress session it becomes the new duress PIN instead: the person who asked
     * for the change knows it, and the real PIN must stay what only you know. False when [pin] can't be used (it is
     * the duress PIN, outside a session) or a change must wait ([changeWait]).
     *
     * M5: in a session the answer is the same whatever is typed, so "Change PIN" can't be used to test PINs: one scrypt
     * run either way, and a "new PIN" that happens to be the real one leaves the duress PIN as it was, silently (the
     * person watching then knows the real PIN anyway). Every change counts towards [changeWait], and only the real PIN
     * resets that count, so setting guesses and unlocking with them can't go on without limit either.
     */
    suspend fun setPin(pin: String, duressSession: Boolean): Boolean = edit { r, now ->
        when {
            r != null && PinBackoff.changeWait(r, now) > 0 -> Change.Refuse
            duressSession -> if (r != null && r.hasDuress) {
                setSession((session ?: SessionView()).copy(pinOff = false))
                val candidate = PinHasher.withDuress(r, pin)
                val real = MessageDigest.isEqual(Base64.getDecoder().decode(candidate.duress!!), Base64.getDecoder().decode(r.pin))
                Change.Put(PinBackoff.afterChange(if (real) r else candidate, now))
            } else {
                Change.Refuse
            }
            r == null -> Change.Put(PinHasher.create(pin))
            r.hasDuress && PinHasher.verify(r, pin) == PinVerdict.DURESS -> Change.Refuse
            else -> Change.Put(PinBackoff.afterChange(PinHasher.withPin(r, pin), now))
        }
    }

    /** Turns the Parley PIN off (and the duress PIN with it). In a duress session only the screens show it off. */
    suspend fun removePin(duressSession: Boolean): Boolean {
        if (duressSession) {
            setSession(SessionView(pinOff = true))
            return true
        }
        return edit { _, _ -> Change.Remove }
    }

    /**
     * Sets the duress PIN, or removes it with null. False when [pin] can't be the duress PIN or there is no Parley PIN.
     * M7: in a duress session the screens show no duress PIN, so one can be "set" there like on any phone: it is kept
     * in memory for the session's screens and gone at the next lock; what is stored doesn't change.
     */
    suspend fun setDuress(pin: String?, duressSession: Boolean = false): Boolean {
        if (duressSession) return setSessionDuress(pin)
        return edit { r, _ ->
            when {
                r == null -> Change.Refuse
                pin == null -> Change.Put(PinHasher.withDuress(r, null))
                PinRules.duressProblem(r, pin) != null -> Change.Refuse
                else -> Change.Put(PinHasher.withDuress(r, pin))
            }
        }
    }

    private suspend fun setSessionDuress(pin: String?): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked()
            val r = record ?: return@withLock false
            val sess = session ?: SessionView()
            if (pin == null) {
                setSession(sess.copy(sessionDuress = null))
                return@withLock true
            }
            if (sessionProblem(r, pin) != null) return@withLock false
            setSession(sess.copy(sessionDuress = PinHasher.withDuress(r, pin).duress))
            true
        }
    }

    /**
     * Why [pin] can't become the duress PIN, or null: checked before the screen asks for it a second time. In a duress
     * session the "Parley PIN" is the duress PIN the person watching knows, so that is what it is compared with: never
     * the real one, which would make this a way to test PINs (M5, M7).
     */
    suspend fun duressProblem(pin: String, duressSession: Boolean = false) = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked()
            record?.let { r -> if (duressSession) sessionProblem(r, pin) else PinRules.duressProblem(r, pin) }
        }
    }

    /** In a session: [pin] is the session's "Parley PIN" (the stored duress PIN), or not valid. */
    private fun sessionProblem(r: PinRecord, pin: String): PinProblem? = when {
        !PinRules.valid(pin) -> PinProblem.INVALID
        r.duress?.let { PinHasher.matches(r, it, pin) } == true -> PinProblem.SAME_AS_PIN
        else -> null
    }

    /**
     * Whether [pin] is the duress PIN as the screens know it, for "a new Parley PIN can't be the duress PIN": the
     * stored one outside a session, the one set in the session inside one (never compared with the real PIN there).
     */
    suspend fun isShownDuress(pin: String, duressSession: Boolean): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked()
            val r = record ?: return@withLock false
            if (!duressSession) return@withLock PinRules.duressProblem(r, pin) == PinProblem.SAME_AS_PIN
            val d = session?.sessionDuress ?: return@withLock false
            PinRules.valid(pin) && PinHasher.matches(r, d, pin)
        }
    }

    suspend fun setLockVaultOnDuress(on: Boolean, duressSession: Boolean = false): Boolean {
        if (duressSession) {
            setSession((session ?: SessionView()).copy(lockVault = on))
            return true
        }
        return edit { r, _ -> r?.let { Change.Put(it.copy(lockVaultOnDuress = on)) } ?: Change.Refuse }
    }

    /** Removes everything ("Delete all Parley data"). */
    suspend fun clear(): Boolean = edit { _, _ -> Change.Remove }

    /** The session ended (Parley locked): what it showed changed goes back to what is stored. */
    fun endSession() = setSession(null)

    private suspend fun edit(f: (PinRecord?, Long) -> Change): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked()
            val now = SystemClock.elapsedRealtime()
            val next = when (val c = f(record?.let { PinBackoff.rebased(it, now) }, now)) {
                Change.Refuse -> return@withLock false
                Change.Remove -> null
                is Change.Put -> c.record
            }
            if (next == record) return@withLock true
            try {
                withContext(Dispatchers.IO) { write(next) }
                true
            } catch (ignored: Exception) {
                Log.w(TAG, "PIN record not saved: ${ignored.javaClass.simpleName}")
                false
            }
        }
    }

    private companion object {
        const val TAG = "AppPinStore"
    }
}
