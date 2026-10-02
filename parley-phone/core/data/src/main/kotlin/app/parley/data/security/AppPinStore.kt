package app.parley.data.security

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.parley.common.security.PinBackoff
import app.parley.common.security.PinHasher
import app.parley.common.security.PinRecord
import app.parley.common.security.PinRules
import app.parley.common.security.PinVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * I21: the Parley PIN and the duress PIN ([PinRecord]: scrypt hashes, never the PINs), with the count of wrong tries.
 * One file under no_backup, sealed with the small-records key (AES-GCM under a Keystore-wrapped key), so a copy of
 * Parley's files alone gives nothing to guess against offline (should the Keystore fail while sealing, the hashes are
 * kept unsealed until the record next changes). It never travels in backups: a new phone sets its PIN
 * again, and the app lock falls back to the screen lock there.
 *
 * During a duress session (`duressSession = true`) the screens may change the PIN as they please: a new PIN replaces
 * the duress PIN (the one the person watching knows), and turning the PIN off only shows it off until the next lock.
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
        /** The fingerprint or screen lock may unlock Parley: always without a duress PIN, never with one. */
        val deviceUnlocks: Boolean get() = !duressSet
    }

    private val _summary = MutableStateFlow<Summary?>(null)

    /** Null until read once ([load]); the lock screen waits for it. */
    val summary: StateFlow<Summary?> = _summary.asStateFlow()

    /** In a duress session, someone turned the PIN off: the screens show it off until the next lock (it stays on). */
    private val _sessionShownOff = MutableStateFlow(false)
    val sessionShownOff: StateFlow<Boolean> = _sessionShownOff.asStateFlow()

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

    private fun write(r: PinRecord?) {
        if (r == null) {
            file.delete()
        } else {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(records().sealBytes(r.encode().toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
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
            // While a duress unlock hides things, the screen lock must not open Parley because the PINs can't be read
            // for a moment: the PIN field stays (and tries the record again); otherwise the screen lock unlocks.
            unreadable && Concealment.hiding -> Summary(pinSet = true, duressSet = true)
            else -> Summary()
        }
    }

    /** One attempt on the lock screen: the verdict, or how long to wait first ([Attempt.waitMs] > 0, nothing checked). */
    data class Attempt(val verdict: PinVerdict, val waitMs: Long = 0L, val lockVaultOnDuress: Boolean = true)

    suspend fun check(pin: String): Attempt = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked()
            val now = SystemClock.elapsedRealtime()
            val r = record?.let { PinBackoff.rebased(it, now) } ?: return@withLock Attempt(PinVerdict.WRONG)
            val wait = PinBackoff.remaining(r, now)
            if (wait > 0) {
                if (r != record) runCatching { write(r) }
                return@withLock Attempt(PinVerdict.WRONG, wait)
            }
            val verdict = PinHasher.verify(r, PinRules.normalize(pin))
            val next = PinBackoff.after(r, verdict, now)
            // The count is written before the answer is given, so pulling the plug after a wrong try doesn't reset it.
            if (next != r) runCatching { withContext(Dispatchers.IO) { write(next) } }
            Attempt(verdict, PinBackoff.remaining(next, now), r.lockVaultOnDuress)
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
     * the other PIN).
     */
    suspend fun setPin(pin: String, duressSession: Boolean): Boolean = edit { r ->
        when {
            duressSession -> if (r != null && r.hasDuress && PinHasher.verify(r, pin) != PinVerdict.NORMAL) {
                _sessionShownOff.value = false
                Change.Put(PinHasher.withDuress(r, pin))
            } else {
                Change.Refuse
            }
            r == null -> Change.Put(PinHasher.create(pin))
            r.hasDuress && PinHasher.verify(r, pin) == PinVerdict.DURESS -> Change.Refuse
            else -> Change.Put(PinHasher.withPin(r, pin))
        }
    }

    /** Turns the Parley PIN off (and the duress PIN with it). In a duress session only the screens show it off. */
    suspend fun removePin(duressSession: Boolean): Boolean {
        if (duressSession) {
            _sessionShownOff.value = true
            return true
        }
        return edit { Change.Remove }
    }

    /** Sets the duress PIN, or removes it with null. False when [pin] can't be the duress PIN or there is no Parley PIN. */
    suspend fun setDuress(pin: String?): Boolean = edit { r ->
        when {
            r == null -> Change.Refuse
            pin == null -> Change.Put(PinHasher.withDuress(r, null))
            PinRules.duressProblem(r, pin) != null -> Change.Refuse
            else -> Change.Put(PinHasher.withDuress(r, pin))
        }
    }

    /** Why [pin] can't become the duress PIN, or null: checked before the screen asks for it a second time. */
    suspend fun duressProblem(pin: String) = withContext(Dispatchers.Default) {
        mutex.withLock { loadLocked(); record?.let { PinRules.duressProblem(it, pin) } }
    }

    suspend fun setLockVaultOnDuress(on: Boolean): Boolean = edit { r -> r?.let { Change.Put(it.copy(lockVaultOnDuress = on)) } ?: Change.Refuse }

    /** Removes everything ("Delete all Parley data"). */
    suspend fun clear(): Boolean = edit { Change.Remove }

    /** The session ended (Parley locked): what it showed changed goes back to what is stored. */
    fun endSession() {
        _sessionShownOff.value = false
    }

    private suspend fun edit(f: (PinRecord?) -> Change): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked()
            val next = when (val c = f(record)) {
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
