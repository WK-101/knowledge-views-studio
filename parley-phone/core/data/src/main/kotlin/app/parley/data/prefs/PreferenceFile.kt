package app.parley.data.prefs

import android.content.Context
import android.content.SharedPreferences
import app.parley.common.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Typed, read-only preferences: what [PreferenceFile.data] hands out. */
open class Preferences internal constructor(internal val values: Map<String, Any>) {
    /** A preference's name and type. */
    class Key<T> internal constructor(val name: String) {
        override fun equals(other: Any?) = other is Key<*> && other.name == name
        override fun hashCode() = name.hashCode()
        override fun toString() = name
    }

    @Suppress("UNCHECKED_CAST") // reason: a key's type is fixed by the code that names it, as in the file it reads
    operator fun <T> get(key: Key<T>): T? = values[key.name] as T?

    operator fun contains(key: Key<*>): Boolean = key.name in values

    fun asMap(): Map<Key<*>, Any> = values.mapKeys { Key<Any>(it.key) }

    override fun equals(other: Any?) = other is Preferences && other.values == values
    override fun hashCode() = values.hashCode()
}

/** The preferences being changed inside [PreferenceFile.edit]. */
class MutablePreferences internal constructor(private val map: MutableMap<String, Any>) : Preferences(map) {
    operator fun <T : Any> set(key: Key<T>, value: T) {
        map[key.name] = if (value is Set<*>) value.toSet() else value
    }

    fun remove(key: Key<*>) {
        map.remove(key.name)
    }
}

fun booleanPreferencesKey(name: String) = Preferences.Key<Boolean>(name)
fun intPreferencesKey(name: String) = Preferences.Key<Int>(name)
fun longPreferencesKey(name: String) = Preferences.Key<Long>(name)
fun stringPreferencesKey(name: String) = Preferences.Key<String>(name)
fun stringSetPreferencesKey(name: String) = Preferences.Key<Set<String>>(name)

/**
 * A small typed settings file, kept in SharedPreferences like Parley's other stores: read once (off the main thread),
 * served from memory, and each [edit] written through with `commit()`, which syncs the file to disk before it returns.
 * Edits run one at a time, so a read-change-write never loses another edit.
 *
 * It replaced Jetpack DataStore (and the protobuf library it brings along) for the last three settings files. The
 * first open moves a DataStore file left by an older Parley ([legacy]) into it once, then deletes that file.
 */
class PreferenceFile internal constructor(private val open: () -> SharedPreferences, private val legacy: File?) {
    constructor(context: Context, name: String) : this(
        { context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE) },
        File(context.applicationContext.filesDir, "datastore/$name.preferences_pb"),
    )

    private val lock = Mutex()
    private val state = MutableStateFlow<Preferences?>(null)
    private var prefs: SharedPreferences? = null

    /** The stored preferences, then each change. */
    val data: Flow<Preferences> = flow {
        if (state.value == null) lock.withLock { loaded() }
        emitAll(state.filterNotNull())
    }

    /** Changes the stored preferences in [transform] and writes them; returns what was stored. */
    suspend fun edit(transform: suspend (MutablePreferences) -> Unit): Preferences = lock.withLock {
        val file = loaded()
        val next = MutablePreferences(HashMap(state.value?.values.orEmpty()))
        transform(next)
        val snapshot = Preferences(HashMap(next.values))
        if (snapshot != state.value) {
            withContext(Dispatchers.IO) {
                val e = file.edit().clear()
                snapshot.values.forEach { (k, v) -> e.put(k, v) }
                if (!e.commit()) throw IOException("Settings could not be written")
            }
            state.value = snapshot
        }
        snapshot
    }

    /** Opens the file (once, under [lock]), moving an older DataStore file into it first. */
    private suspend fun loaded(): SharedPreferences = prefs ?: withContext(Dispatchers.IO) {
        val p = open()
        legacy?.takeIf { it.exists() }?.let { old ->
            // Moved only into an empty file: if deleting the old one failed after a move, it must not undo later changes.
            // An old file that can't be read stays where it is, so nothing is lost for good.
            val moved = if (p.all.isEmpty()) catching { DataStoreFile.read(old.readBytes()) }.getOrNull() else emptyMap()
            if (moved != null) {
                val e = p.edit()
                moved.forEach { (k, v) -> e.put(k, v) }
                if (!e.commit()) throw IOException("Settings could not be moved")
                old.delete()
            }
        }
        state.value = Preferences(HashMap(p.all.filterValues { it != null }.mapValues { it.value!! }))
        p
    }.also { prefs = it }

    private fun SharedPreferences.Editor.put(k: String, v: Any) {
        @Suppress("UNCHECKED_CAST") // reason: only string sets are ever stored as sets
        when (v) {
            is Boolean -> putBoolean(k, v)
            is Int -> putInt(k, v)
            is Long -> putLong(k, v)
            is Float -> putFloat(k, v)
            is String -> putString(k, v)
            is Set<*> -> putStringSet(k, v as Set<String>)
            else -> error("Unsupported preference type for $k")
        }
    }
}
