package app.parley.data.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether private contacts' details are unlocked now, as far as Parley can tell (memory only, like the detail key's
 * own window), and "Lock private contacts" (see [VaultRepository.lockAll]), which holds until the next unlock in
 * Parley even across a restart of Parley ([VaultCrypto.lockedByPerson]).
 */
class VaultLock(private val scope: CoroutineScope) {
    private val unlockedNow = MutableStateFlow(false)
    private val guard = Any()
    private var relock: Job? = null

    /**
     * Unlocked in Parley or opened lately, and not locked or forgotten since. It ends with the key's own window
     * ([UNLOCKED_MS]). "Lock private contacts" shows while it holds.
     */
    val unlocked: StateFlow<Boolean> = unlockedNow.asStateFlow()

    private val lockCount = MutableStateFlow(0)

    /** Counts [lockAll] calls: an open private contact's page follows it. */
    val locks: StateFlow<Int> = lockCount.asStateFlow()

    /** Details were just opened, or the person unlocked them: [unlocked] for the key's window from now. */
    fun noteUnlocked() {
        if (VaultCrypto.lockedByPerson || VaultCrypto.detailLocked) return
        synchronized(guard) {
            unlockedNow.value = true
            relock?.cancel()
            relock = scope.launch {
                delay(UNLOCKED_MS)
                unlockedNow.value = false
            }
        }
    }

    /** Opened details were forgotten (the app lock, the screen off): no longer shown as unlocked. */
    fun forgotten() {
        synchronized(guard) {
            relock?.cancel()
            unlockedNow.value = false
        }
    }

    /** The person's unlock in Parley succeeded: an earlier [lockAll] no longer holds. */
    fun unlockedByPerson() {
        VaultCrypto.lockedByPerson = false
        noteUnlocked()
    }

    /** Locks every private contact's details until [unlockedByPerson]; [forget] drops what was opened. */
    fun lockAll(forget: () -> Unit) {
        VaultCrypto.lockedByPerson = true
        forget()
        lockCount.value++
    }

    private companion object {
        /** The detail key's window after an unlock (VaultCrypto's AUTH_SECONDS): how long [unlocked] holds. */
        const val UNLOCKED_MS = 300_000L
    }
}
