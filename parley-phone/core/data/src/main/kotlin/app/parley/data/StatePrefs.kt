package app.parley.data

import android.content.Context
import android.content.SharedPreferences
import app.parley.common.storage.PersistentStores
import app.parley.common.storage.StoreKind
import java.io.File

/**
 * Parley's small bookkeeping flags (when a job last ran, which tip was seen, which migration is done) in two
 * preferences files instead of one each: `local_state`, and `call_path_state` for what the ring path reads. Each file
 * Android opens costs a loader thread, a map kept in memory and a file of its own, and a cold start used to parse about
 * fifteen of them.
 *
 * Each store keeps its own name and keys: [open] gives it a view of the shared file where its keys are stored as
 * "<name>.<key>" ([KEY_SEPARATOR]), so two stores with the same key never meet, and a `clear()` clears only its own.
 * Which file holds which store is in [PersistentStores] (a preferences store whose `location` is the shared file).
 *
 * What a store kept in its own file before is copied in once, on first [open]: every entry and a marker in one
 * `commit()` (written whole, so a crash leaves the copy made or not made), then the old file is deleted. A copy found
 * made is never made again, and the old file is deleted again if a crash came before that.
 */
object StatePrefs {
    const val LOCAL = "local_state"
    const val CALL_PATH = "call_path_state"

    /** Between a store's name and its key in the shared file (what the telecom module writes directly). */
    const val KEY_SEPARATOR = "."

    /** A store's view of its shared file, its old file copied in first. */
    fun open(context: Context, name: String): SharedPreferences {
        val group = groupOf(name)
        val shared = shared(context, group)
        moveIn(context, shared, name)
        return KeyedPrefs(shared, name + KEY_SEPARATOR)
    }

    /**
     * Copies every store of [group] that still has its own file. The app runs this for [CALL_PATH] as it starts, before
     * any call can ring: the telecom module reads that file directly, with the keys [open] would give it.
     */
    fun moveAll(context: Context, group: String) {
        val shared = shared(context, group)
        members(group).forEach { moveIn(context, shared, it) }
    }

    /** The stores kept in [group]. */
    fun members(group: String): List<String> =
        PersistentStores.of(StoreKind.PREFS).filter { it.location == group }.map { it.name }

    private fun groupOf(name: String): String =
        requireNotNull(PersistentStores.find(StoreKind.PREFS, name)?.location?.takeIf { it == LOCAL || it == CALL_PATH }) {
            "$name isn't kept in a shared state file (PersistentStores)"
        }

    private fun shared(context: Context, group: String): SharedPreferences = when (group) {
        LOCAL -> context.applicationContext.getSharedPreferences("local_state", Context.MODE_PRIVATE)
        else -> context.applicationContext.getSharedPreferences("call_path_state", Context.MODE_PRIVATE)
    }

    private fun moveIn(context: Context, shared: SharedPreferences, name: String) {
        val marker = MOVED + name
        if (shared.contains(marker)) {
            // Copied before; a crash may have come before the old file went (a look at the folder, no file opened).
            if (File(context.applicationContext.dataDir, "shared_prefs/$name.xml").exists()) context.applicationContext.deleteSharedPreferences(name)
            return
        }
        synchronized(this) {
            if (shared.contains(marker)) return
            val app = context.applicationContext
            val old = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val e = shared.edit()
            old.all.forEach { (k, v) -> put(e, name + KEY_SEPARATOR + k, v) }
            e.putBoolean(marker, true)
            // On the disk before the old file goes; if it couldn't be written, the old file stays and is tried again.
            if (!e.commit()) return
        }
        context.applicationContext.deleteSharedPreferences(name)
    }

    @Suppress("UNCHECKED_CAST") // A string set is the only set preferences hold.
    private fun put(e: SharedPreferences.Editor, key: String, v: Any?) {
        when (v) {
            is String -> e.putString(key, v)
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is Long -> e.putLong(key, v)
            is Float -> e.putFloat(key, v)
            is Set<*> -> e.putStringSet(key, v as Set<String>)
            else -> Unit
        }
    }

    /** Marks a store whose old file was copied in ("#" never starts a store's own key, which all have a name first). */
    private const val MOVED = "#moved."
}

/** [base]'s entries whose keys start with [prefix], as if they were a preferences file of their own. */
internal class KeyedPrefs(private val base: SharedPreferences, private val prefix: String) : SharedPreferences {
    private fun k(key: String?) = prefix + key

    override fun getAll(): Map<String, *> = base.all.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    override fun getString(key: String?, defValue: String?): String? = base.getString(k(key), defValue)
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = base.getStringSet(k(key), defValues)
    override fun getInt(key: String?, defValue: Int) = base.getInt(k(key), defValue)
    override fun getLong(key: String?, defValue: Long) = base.getLong(k(key), defValue)
    override fun getFloat(key: String?, defValue: Float) = base.getFloat(k(key), defValue)
    override fun getBoolean(key: String?, defValue: Boolean) = base.getBoolean(k(key), defValue)
    override fun contains(key: String?) = base.contains(k(key))
    override fun edit(): SharedPreferences.Editor = Editor(base.edit())

    private val listeners = HashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val mine = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(prefix)) listener.onSharedPreferenceChanged(this, key?.removePrefix(prefix))
        }
        // Preferences hold listeners weakly; this view holds its own wrappers until they are unregistered.
        synchronized(listeners) { listeners[listener] = mine }
        base.registerOnSharedPreferenceChangeListener(mine)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }?.let(base::unregisterOnSharedPreferenceChangeListener)
    }

    private inner class Editor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { e.putString(k(key), value) }
        override fun putStringSet(key: String?, values: Set<String>?) = apply { e.putStringSet(k(key), values) }
        override fun putInt(key: String?, value: Int) = apply { e.putInt(k(key), value) }
        override fun putLong(key: String?, value: Long) = apply { e.putLong(k(key), value) }
        override fun putFloat(key: String?, value: Float) = apply { e.putFloat(k(key), value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { e.putBoolean(k(key), value) }
        override fun remove(key: String?) = apply { e.remove(k(key)) }

        /** This store's keys only, as they are now (call it before any put in the same edit, as with a file of its own). */
        override fun clear() = apply { base.all.keys.filter { it.startsWith(prefix) }.forEach { e.remove(it) } }
        override fun commit() = e.commit()
        override fun apply() = e.apply()
    }
}
