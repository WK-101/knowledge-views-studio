package app.parley.data.calls

import android.content.Context
import android.util.Base64
import app.parley.common.calls.ExpectedCalls
import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.ExpectedWindow
import app.parley.common.calls.FamilySafetyState
import app.parley.common.calls.Helper
import app.parley.common.calls.SafeWord
import app.parley.common.LabelRefs
import app.parley.common.calls.SafeWords
import app.parley.common.security.Concealed
import app.parley.data.security.Concealment
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Family safety (WP-8): the safe words per label (I4), the helpers (I5) and the expected-call windows with the answers
 * to "Expecting a call from your notes?" (I7). One small document in its own preferences file, sealed with the vault's
 * caller-ID key like private contacts' caller cards and Circle notes: the call path reads it while the phone is locked,
 * and nothing in it is readable at rest. Backups carry it inside their own encryption ([backupState], [restore]), so a
 * move to a new phone keeps the safe words, helpers and windows.
 *
 * The safe words' questions and answers never leave through [summary]: screens read one with [safeWord], after the
 * user confirmed it's them. A stored document that can't be opened right now is never taken for an empty one: the
 * store stays unloaded and refuses changes; one that can't be sealed stays in memory, never written in plain text.
 */
class FamilySafetyStore(context: Context) {
    private val prefs by lazy { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val mutex = Mutex()

    @Volatile private var loaded = false
    private var doc = FamilySafetyState()

    /** What screens may show without unlocking: which labels have a safe word, the helpers, the choices and windows. */
    data class Summary(
        val safeWordLabels: Set<String> = emptySet(),
        val helpers: List<Helper> = emptyList(),
        val consents: Map<ExpectedSource, Boolean> = emptyMap(),
        val windows: List<ExpectedWindow> = emptyList(),
    )

    private val _summary = MutableStateFlow(Summary())
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    /** Reads the document once (off the main thread); false while it can't be opened. */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }

    private fun loadLocked(): Boolean {
        if (loaded) return true
        val stored = prefs.getString(KEY, null)
        doc = if (stored.isNullOrEmpty()) {
            FamilySafetyState()
        } else {
            try {
                FamilySafetyState.decode(String(VaultCrypto.openCallerId(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8))
                    // Note windows of older versions can't follow their note: they go (written with the next change).
                    .let { d -> d.copy(windows = d.windows.filterNot(ExpectedCalls::untracked)) }
            } catch (_: Exception) {
                // A Keystore hiccup or a damaged value: keep what's stored, try again next time.
                return false
            }
        }
        loaded = true
        publish()
        return true
    }

    private fun publish() {
        _summary.value = Summary(visibleWords().keys, doc.helpers, doc.consents, doc.windows)
    }

    /** I21: after a duress unlock no safe word shows, not even which labels have one. */
    private val wordsHidden: Boolean get() = Concealment.hides(Concealed.SAFE_WORDS)

    /** L1: safe words set over a hidden one while hiding, by label: shown instead of it, in memory, never stored over it. */
    private val overWords = HashMap<String, SafeWord>()

    private fun token(label: String) = "safeword:${label.trim()}"

    /** The safe words that show now: all, or while hiding only those set since (L1). */
    private fun visibleWords(): Map<String, SafeWord> =
        if (!wordsHidden) doc.safeWords else doc.safeWords.filterKeys { Concealment.writtenWhileHiding(token(it)) } + overWords

    /** The duress hiding started or ended: the summary follows. */
    fun refresh() {
        if (!wordsHidden) overWords.clear()
        if (loaded) publish()
    }

    /** Applies [f] and writes the result sealed; false when nothing could be changed or stored. */
    private suspend fun write(f: (FamilySafetyState) -> FamilySafetyState): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loadLocked()) return@withLock false
            val next = f(doc)
            if (next == doc) return@withLock true
            val sealed = runCatching {
                Base64.encodeToString(VaultCrypto.sealCallerId(FamilySafetyState.encode(next).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            }.getOrNull() ?: return@withLock false
            prefs.edit().putString(KEY, sealed).apply()
            doc = next
            publish()
            true
        }
    }

    // ---- backups

    /**
     * The document for a backup. While safe words are hidden (a duress unlock) only those set since go in, like a
     * screen would show them. Throws when the stored document can't be opened now: the backup then names the section
     * as missing instead of saving an empty one.
     */
    suspend fun backupState(): FamilySafetyState = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loadLocked()) { "Family safety can't be opened now" }
            if (wordsHidden) doc.copy(safeWords = doc.safeWords.filterKeys { Concealment.writtenWhileHiding(token(it)) }) else doc
        }
    }

    /** A backup's document put in beside this phone's, which wins wherever both have something; false when not stored. */
    suspend fun restore(backup: FamilySafetyState, region: String?): Boolean =
        write { it.restoredFrom(backup, System.currentTimeMillis(), region) }

    // ---- I4 safe words

    /** The safe word of [label] (question and answer), for a screen the user just unlocked; null when none. */
    suspend fun safeWord(label: String): SafeWord? = withContext(Dispatchers.IO) {
        mutex.withLock { if (loadLocked()) visibleWords().entries.firstOrNull { it.key.trim() == label.trim() }?.value else null }
    }

    /** Every safe word, for the call screen: it decides which to offer and shows the answer only on a deliberate hold. */
    suspend fun safeWords(): Map<String, SafeWord> = withContext(Dispatchers.IO) {
        mutex.withLock { if (loadLocked()) visibleWords() else emptyMap() }
    }

    /**
     * Sets or removes [label]'s safe word. L1: while safe words are hidden a new one is stored and shows as set, as on
     * any phone; one set over a hidden safe word shows instead of it, in memory only, since storing it would replace
     * one the screen can't show.
     */
    suspend fun setSafeWord(label: String, word: SafeWord?): Boolean {
        if (wordsHidden) {
            val hiddenOne = withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (!loadLocked()) return@withContext null
                    val l = label.trim()
                    val stored = doc.safeWords.keys.any { it.trim() == l } && !Concealment.writtenWhileHiding(token(l))
                    if (stored) {
                        if (word == null) overWords.remove(l) else overWords[l] = word
                        publish()
                    }
                    stored
                }
            } ?: return false
            if (hiddenOne) return true
        }
        val ok = write { s ->
            val others = s.safeWords.filterKeys { it.trim() != label.trim() }
            s.copy(safeWords = if (word == null) others else others + (label.trim() to word))
        }
        if (ok && word != null && wordsHidden) {
            Concealment.markWritten(token(label))
            refresh()
        }
        return ok
    }

    /** Labels renamed or merged on the labels screen: their safe words follow. */
    suspend fun labelsRenamed(renames: Map<String, String>) {
        if (renames.isNotEmpty()) write { it.copy(safeWords = SafeWords.renamed(it.safeWords, renames)) }
    }

    suspend fun labelsDeleted(titles: Set<String>) {
        if (titles.isNotEmpty()) write { it.copy(safeWords = SafeWords.deleted(it.safeWords, titles)) }
    }

    /** The stored safe words of [titles] (hidden ones too), kept before their labels are deleted. */
    suspend fun storedSafeWords(titles: Set<String>): Map<String, SafeWord> = withContext(Dispatchers.IO) {
        mutex.withLock { if (loadLocked()) LabelRefs.entriesOf(doc.safeWords, titles) else emptyMap() }
    }

    /** Undo of a label delete: its safe words as they were stored, unless one was set again since. */
    suspend fun restoreSafeWords(words: Map<String, SafeWord>) {
        if (words.isNotEmpty()) write { it.copy(safeWords = LabelRefs.undoDeleteEntries(it.safeWords, words)) }
    }

    // ---- I5 helpers

    suspend fun helpers(): List<Helper> {
        load()
        return _summary.value.helpers
    }

    suspend fun setHelpers(list: List<Helper>): Boolean = write { it.copy(helpers = list) }

    // ---- I7 expected-call hints

    /** The windows that are on now or still to come, for screening (each call reads them; nothing heavy). */
    suspend fun windows(now: Long = System.currentTimeMillis()): List<ExpectedWindow> {
        if (!load()) return emptyList()
        return ExpectedCalls.prune(_summary.value.windows, now).filter { _summary.value.consents[it.source] == true }
    }

    /**
     * The windows in force from memory, for the call path (which must never wait on the Keystore): null while the store
     * hasn't been read yet (it's read at process start, see BlockingSetup.warm).
     */
    fun windowsNow(now: Long = System.currentTimeMillis()): List<ExpectedWindow>? {
        if (!loaded) return null
        val s = _summary.value
        return ExpectedCalls.prune(s.windows, now).filter { s.consents[it.source] == true }
    }

    /** Whether the document has been read (the call path reads only from memory). */
    val isLoaded: Boolean get() = loaded

    /** The user's answer for [source]; turning it off also drops the windows it made. */
    suspend fun setConsent(source: ExpectedSource, on: Boolean): Boolean = write { s ->
        s.copy(consents = s.consents + (source to on), windows = if (on) s.windows else ExpectedCalls.without(s.windows, source))
    }

    suspend fun putWindow(w: ExpectedWindow, now: Long = System.currentTimeMillis()): Boolean = write { s ->
        s.copy(windows = ExpectedCalls.put(s.windows, w, now))
    }

    suspend fun removeWindow(source: ExpectedSource, key: String): Boolean = write { s ->
        s.copy(windows = s.windows.filterNot { it.source == source && it.key == key })
    }

    private companion object {
        const val FILE = "family_safety"
        const val KEY = "state_v1"
    }
}
