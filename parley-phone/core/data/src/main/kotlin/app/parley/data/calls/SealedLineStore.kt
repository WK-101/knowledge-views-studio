package app.parley.data.calls

import android.content.SharedPreferences
import android.os.Looper
import android.util.Base64
import app.parley.common.Hex
import app.parley.data.history.CallFactEntity
import app.parley.data.history.CallHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Facts per call, per line, kept beside the call-history archive and never readable at rest: rows are keyed by the
 * archive's keyed fingerprint of the line (never the number), each row's facts are sealed with the archive key, and
 * the newest [maxRows] of the last [keepDays] days are kept. Nothing leaves the phone.
 *
 * One row per fact in the history database's `call_facts` table ([Table]), under this store's [kind]: a call adds or
 * replaces its own rows and pruning is SQL, so nothing re-seals or rewrites the rest (it used to be one preferences
 * value re-sealed whole on every call, waited for on the main thread as the call screen closed). Each row opens on its
 * own: a damaged one is skipped, and one the key can't open right now is kept as it is and never written over.
 *
 * Rows kept in preferences by earlier versions ([legacy]) are copied into the table once, before anything reads it.
 */
class SealedLineStore<T>(
    private val table: () -> Table,
    /** This store's rows in the table (one table holds every store's). */
    private val kind: String,
    private val keySource: KeySource,
    private val encode: (T) -> String,
    private val decode: (String) -> T?,
    private val startedAt: (T) -> Long,
    private val maxRows: Int = 400,
    private val keepDays: Long = 60,
    private val legacy: Legacy? = null,
) {
    /** Where the keys come from, asked on each use (the archive opens lazily, and may not be ready). */
    fun interface KeySource {
        fun keys(): Keys
    }

    /** The archive's keyed fingerprint and its key (tests use their own). */
    interface Keys {
        fun lineMac(number: String): String
        fun seal(plain: ByteArray): ByteArray
        fun open(blob: ByteArray): ByteArray
    }

    /** The call-history archive's keys. */
    class HistoryKeys(private val h: CallHistory) : Keys {
        override fun lineMac(number: String) = h.lineMac(number)
        override fun seal(plain: ByteArray) = h.sealAux(plain)
        override fun open(blob: ByteArray) = h.openAux(blob)
    }

    /** The rows on disk: the history database's `call_facts` table ([app.parley.data.history.CallFactsDao]). */
    interface Table {
        fun rows(kind: String): List<CallFactEntity>
        fun count(kind: String): Int

        /** The kinds starting with [prefix]. */
        fun kinds(prefix: String): List<String>
        fun clear(kind: String)

        /** In one transaction: removes the rows [drop], adds [add], then drops [kind]'s rows before [before] and all but its newest [max]. */
        fun write(kind: String, drop: Collection<String>, add: List<CallFactEntity>, before: Long, max: Int)

        /**
         * In one transaction, unless [marker] is already there: adds [rows] (those already present are left alone) and
         * [marker]. False when it was there (the copy was made before).
         */
        fun adopt(kind: String, rows: List<CallFactEntity>, marker: CallFactEntity): Boolean
    }

    /** The preferences value earlier versions kept the rows in: "<key>\t<sealed facts, Base64>" per line. */
    class Legacy(val prefs: SharedPreferences, val key: String)

    private fun keys(): Keys = keySource.keys()

    /**
     * The table, used where Room allows it: off the main thread. A screen that asks on the main thread waits for the
     * disk thread instead, as it used to wait for the preferences file to load.
     */
    private fun disk(): Table = offMain(table())

    private class OffMain(private val t: Table) : Table {
        private fun <R> run(block: () -> R): R =
            if (Looper.myLooper() == Looper.getMainLooper()) DISK.submit(Callable { block() }).get() else block()

        override fun rows(kind: String) = run { t.rows(kind) }
        override fun count(kind: String) = run { t.count(kind) }
        override fun kinds(prefix: String) = run { t.kinds(prefix) }
        override fun clear(kind: String) = run { t.clear(kind) }
        override fun write(kind: String, drop: Collection<String>, add: List<CallFactEntity>, before: Long, max: Int) =
            run { t.write(kind, drop, add, before, max) }
        override fun adopt(kind: String, rows: List<CallFactEntity>, marker: CallFactEntity) = run { t.adopt(kind, rows, marker) }
    }

    private class Row<T>(val id: String, val key: String, val facts: T)

    /** The rows that opened, and those that couldn't be opened right now (kept as they are). */
    private class Loaded<T>(val rows: List<Row<T>>, val unread: List<CallFactEntity>)

    private var cache: Loaded<T>? = null

    /** Bumped on every write, so screens re-read. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** The line key of [number] (null when the key can't be used now); a hidden number has its own. */
    fun keyOf(number: String?): String? = if (number.isNullOrBlank()) HIDDEN else runCatching { MAC + keys().lineMac(number) }.getOrNull()

    /**
     * The stored rows, or null when the key or the table can't be used right now. Only a fully read list is cached: a
     * row that couldn't be opened (a Keystore hiccup) is tried again next time, and every write keeps it as it is.
     */
    @Synchronized
    private fun loaded(): Loaded<T>? {
        cache?.let { return it }
        val h = runCatching { keys() }.getOrNull() ?: return null
        val t = runCatching { disk() }.getOrNull() ?: return null
        if (!moveIn(h, t)) return null
        val stored = runCatching { t.rows(kind) }.getOrNull() ?: return null
        val rows = ArrayList<Row<T>>()
        val unread = ArrayList<CallFactEntity>()
        for (e in stored) {
            val opened = runCatching { String(h.open(e.blob)) }
            if (opened.isFailure) {
                unread += e
            } else {
                decode(opened.getOrThrow())?.let { rows += Row(e.id, e.lineKey, it) }
            }
        }
        val l = Loaded(rows.sortedByDescending { startedAt(it.facts) }, unread)
        // Rows past [keepDays] go when they're read, not only on the next write (which may never come).
        val now = System.currentTimeMillis()
        if (l.rows.any { !kept(it.facts, now) }) {
            if (!save(t, emptyList(), emptyList(), now)) return l
            val fresh = Loaded(l.rows.filter { kept(it.facts, now) }, l.unread)
            if (fresh.unread.isEmpty()) cache = fresh
            return fresh
        }
        if (l.unread.isEmpty()) cache = l
        return l
    }

    /**
     * Copies the rows earlier versions kept in preferences into the table, once: in one transaction with a marker row,
     * so a copy cut short by a crash leaves either nothing or everything, and a copy already made is never made again
     * (rows forgotten since would come back). The preferences value goes only after the copy is in. False when it
     * couldn't be made now (nothing is read until it can).
     */
    private fun moveIn(h: Keys, t: Table): Boolean {
        val from = legacy ?: return true
        val text = from.prefs.getString(from.key, null) ?: return true
        val now = System.currentTimeMillis()
        val rows = text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val key = line.substring(0, tab)
            val blob = runCatching { Base64.decode(line.substring(tab + 1), Base64.NO_WRAP) }.getOrNull() ?: return@mapNotNull null
            // A row that can't be opened now has no date to keep it by: it ages from the move.
            val at = runCatching { decode(String(h.open(blob)))?.let(startedAt) }.getOrNull() ?: now
            CallFactEntity(idOf(kind, key, blob), kind, key, at, blob)
        }.toList()
        val marker = CallFactEntity(MOVED + kind, kind + MOVED, "", Long.MAX_VALUE, ByteArray(0))
        runCatching { t.adopt(kind, rows, marker) }.getOrElse { return false }
        from.prefs.edit().remove(from.key).commit()
        return true
    }

    private fun kept(facts: T, now: Long): Boolean = now - startedAt(facts) < keepDays * DAY_MS

    /** The rows still within [keepDays] at [now] (the cache may hold one that expired since it was read). */
    private fun rows(now: Long): List<Row<T>> = loaded()?.rows.orEmpty().filter { kept(it.facts, now) }

    @Synchronized
    fun add(number: String?, facts: T, now: Long = System.currentTimeMillis()) {
        // Without the key nothing is stored (never in plain text).
        val k = keyOf(number) ?: return
        val l = loaded() ?: return
        val row = sealed(k, facts) ?: return
        val replaced = l.rows.filter { it.key == k && startedAt(it.facts) == startedAt(facts) }
        store(l, replaced.map { it.id }, listOf(row to facts), now)
    }

    /**
     * Replaces [number]'s rows with what [change] makes of them (newest first), in one write; nothing is written
     * without the key. For stores that keep a few facts per line rather than one per call.
     */
    fun update(number: String?, now: Long = System.currentTimeMillis(), change: (List<T>) -> List<T>) {
        updateKey(keyOf(number) ?: return, now, change)
    }

    /** [update] for rows already keyed ([all]'s keys): put back from a copy set aside. */
    @Synchronized
    fun updateKey(k: String, now: Long = System.currentTimeMillis(), change: (List<T>) -> List<T>) {
        val l = loaded() ?: return
        val mine = l.rows.filter { it.key == k }
        val next = change(mine.map { it.facts }.sortedByDescending(startedAt)).map { (sealed(k, it) ?: return) to it }
        store(l, mine.map { it.id }, next, now)
    }

    /** Adds rows already keyed (set aside, or moved from an older format); false when they couldn't be sealed or stored. */
    @Synchronized
    fun merge(keyed: List<Pair<String, T>>): Boolean {
        val l = loaded() ?: return false
        val rows = keyed.map { (k, facts) -> (sealed(k, facts) ?: return false) to facts }
        return store(l, emptyList(), rows, System.currentTimeMillis())
    }

    /** Facts for [number], newest first; none older than [keepDays] at [now]. */
    fun forNumber(number: String?, now: Long = System.currentTimeMillis()): List<T> {
        val k = keyOf(number) ?: return emptyList()
        return rows(now).filter { it.key == k }.map { it.facts }
    }

    /** Every row as (line key, facts), newest first; none older than [keepDays] at [now]. */
    fun all(now: Long = System.currentTimeMillis()): List<Pair<String, T>> = rows(now).map { it.key to it.facts }

    /** Whether any row is kept, readable now or not. */
    fun any(): Boolean {
        val t = runCatching { disk() }.getOrNull() ?: return true
        if (legacy?.prefs?.getString(legacy.key, null)?.isNotBlank() == true) return true
        return runCatching { t.count(kind) > 0 }.getOrDefault(true)
    }

    /**
     * Forgets [number]'s facts: those of the calls at [dates] (call-log dates, matched by [near]), or all of them when
     * [dates] is null, unreadable rows included (their key is readable; only their facts are sealed).
     */
    @Synchronized
    fun forget(number: String, dates: List<Long>?, near: (List<T>, Long) -> T?) {
        val k = keyOf(number) ?: return
        val l = loaded() ?: return
        val unread = if (dates == null) l.unread.filter { it.lineKey == k }.map { it.id } else emptyList()
        val mine = l.rows.filter { it.key == k }
        val drop = if (dates == null) {
            mine.map { it.id }
        } else {
            val facts = mine.map { it.facts }
            val gone = dates.mapNotNull { d -> near(facts, d) }.toSet()
            mine.filter { it.facts in gone }.map { it.id }
        }
        if (drop.isEmpty() && unread.isEmpty()) return
        store(l, drop + unread, emptyList(), System.currentTimeMillis())
    }

    /** Forgets everything. */
    @Synchronized
    fun clear() {
        runCatching { disk().clear(kind) }
        legacy?.let { it.prefs.edit().remove(it.key).commit() }
        cache = Loaded(emptyList(), emptyList())
        _version.value++
    }

    private fun sealed(k: String, facts: T): CallFactEntity? {
        val blob = runCatching { keys().seal(encode(facts).toByteArray()) }.getOrNull() ?: return null
        return CallFactEntity(idOf(kind, k, blob), kind, k, startedAt(facts), blob)
    }

    /** Writes [add] in place of [drop] (rows by id), pruned as the table is; the cache follows when it was whole. */
    private fun store(l: Loaded<T>, drop: List<String>, add: List<Pair<CallFactEntity, T>>, now: Long): Boolean {
        val t = runCatching { disk() }.getOrNull() ?: return false
        if (!save(t, drop, add.map { it.first }, now)) return false
        val gone = drop.toSet()
        val next = (add.map { (e, facts) -> Row(e.id, e.lineKey, facts) } + l.rows.filterNot { it.id in gone })
            .filter { kept(it.facts, now) }
            .sortedByDescending { startedAt(it.facts) }
        // Over the cap, the table's own order decides which rows went: read again rather than guess.
        cache = if (l.unread.isEmpty() && next.size <= maxRows) Loaded(next, emptyList()) else null
        _version.value++
        return true
    }

    private fun save(t: Table, drop: Collection<String>, add: List<CallFactEntity>, now: Long): Boolean =
        runCatching { t.write(kind, drop, add, now - keepDays * DAY_MS, maxRows) }.isSuccess

    companion object {
        const val HIDDEN = "hidden"
        const val MAC = "m:"
        private const val DAY_MS = 86_400_000L

        /** The marker row's suffix and id prefix: this kind's preferences rows were copied in. */
        const val MOVED = "#moved"

        /** A row's id: a digest of where it belongs and its sealed bytes (fresh for every seal, so never shared). */
        fun idOf(kind: String, key: String, blob: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256")
            d.update(kind.toByteArray())
            d.update(0)
            d.update(key.toByteArray())
            d.update(0)
            return Hex.encode(d.digest(blob)).take(ID_CHARS)
        }

        private const val ID_CHARS = 32

        /** [t], used off the main thread (see [disk]). */
        fun offMain(t: Table): Table = if (t is OffMain) t else OffMain(t)

        /** The one thread main-thread callers' table work runs on. */
        private val DISK: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "parley-call-facts").apply { isDaemon = true } }
    }
}
