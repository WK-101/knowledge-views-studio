package com.cairn.reader.domain.lookup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import com.cairn.reader.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Optional downloadable offline dictionary (a public-domain Webster's 1913, ~22 MB as JSON). Kept OUT
 * of the encrypted user database and its backups — it's reference data, not the user's content — in a
 * plain SQLite file under app-private storage, so a keyed lookup is instant and the pack can be
 * dropped without touching anything else. The base APK stays lean (nothing is bundled); the pack is
 * fetched once, on the user's explicit request, and after that "Define" works fully offline.
 *
 * The source is a single JSON object of `{ "word": "definition", … }`. We stream it with [JsonReader]
 * (never holding the 22 MB in memory) into `INSERT OR REPLACE` batches inside one transaction, then
 * write a marker row so a cancelled or failed install never looks complete.
 */
@Singleton
class OfflineDictionary @Inject constructor(
    @ApplicationContext private val context: Context,
    appClient: OkHttpClient,
) {
    // Public-domain Webster's 1913, compact single-file JSON. A one-time download; disclosed in
    // PRIVACY.md. GitHub raw is the primary; the un-compact copy is a same-repo fallback.
    private val sources = listOf(
        "https://raw.githubusercontent.com/matthewreagan/WebstersEnglishDictionary/master/dictionary_compact.json",
        "https://raw.githubusercontent.com/matthewreagan/WebstersEnglishDictionary/master/dictionary.json",
    )
    val approxMb = 22

    // The shared client caps every call at 45s; a 22 MB download over cellular blows past that, so use
    // a variant with no overall call timeout (a generous read timeout still guards a stalled socket).
    private val client: OkHttpClient = appClient.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun dir(): File = File(context.filesDir, "dict").apply { mkdirs() }
    private fun dbFile(): File = File(dir(), "webster.db")
    // A cheap marker (written only after a fully-populated import) so isInstalled() is a file check
    // safe to call on the main thread — no SQLite open just to gate the reader's Define affordance.
    private fun marker(): File = File(dir(), "webster.ok")

    @Volatile private var readDb: SQLiteDatabase? = null

    fun isInstalled(): Boolean = marker().exists() && dbFile().exists()
    fun sizeBytes(): Long = dbFile().takeIf { it.exists() }?.length() ?: 0L

    @Synchronized
    private fun openRead(): SQLiteDatabase? {
        readDb?.let { if (it.isOpen) return it }
        if (!dbFile().exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(dbFile().absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull()?.also { readDb = it }
    }

    private fun entryCount(): Int = runCatching {
        openRead()?.rawQuery("SELECT COUNT(*) FROM dict", null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        } ?: 0
    }.getOrDefault(0)

    /** Look a word up in the installed pack; null if the pack isn't installed or has no such word. */
    suspend fun lookup(word: String): DictionaryEntry? = withContext(Dispatchers.IO) {
        val w = word.trim().lowercase().ifEmpty { return@withContext null }
        val db = openRead() ?: return@withContext null
        runCatching {
            db.rawQuery("SELECT def FROM dict WHERE word = ? LIMIT 1", arrayOf(w)).use { c ->
                if (!c.moveToFirst()) return@withContext null
                val def = c.getString(0)?.trim().orEmpty()
                if (def.isEmpty()) return@withContext null
                DictionaryEntry(
                    word = w,
                    phonetic = null,
                    senses = listOf(WordSense(partOfSpeech = "", definition = def, example = null)),
                    synonyms = emptyList(),
                    antonyms = emptyList(),
                )
            }
        }.getOrNull()
    }

    @Synchronized
    fun delete(): Boolean {
        readDb?.let { runCatching { it.close() } }
        readDb = null
        marker().delete()
        return dbFile().delete().also { dir().listFiles()?.forEach { f -> if (f.name.startsWith("webster")) f.delete() } }
    }

    /**
     * Download the pack and import it into the SQLite store. [onProgress] spans the download
     * (0f..0.6f) then the import (…1f). Returns true only after a fully-populated store is in place;
     * any failure leaves nothing that reads as installed.
     */
    suspend fun install(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        // Close any open read handle so the file can be replaced.
        synchronized(this) { readDb?.let { runCatching { it.close() } }; readDb = null }
        val tmpJson = File(dir(), "download.json.tmp")
        val tmpDb = File(dir(), "webster.db.tmp")
        runCatching { tmpJson.delete(); tmpDb.delete() }
        try {
            if (!downloadTo(tmpJson) { onProgress(it * 0.6f) }) return@withContext false
            coroutineContext.ensureActive()
            importInto(tmpDb, tmpJson) { onProgress(0.6f + it * 0.4f) }
            coroutineContext.ensureActive()
            tmpJson.delete()
            // Atomic-ish swap: only replace the live file once the import fully succeeded.
            val target = dbFile()
            marker().delete()
            target.delete()
            if (!tmpDb.renameTo(target)) {
                tmpDb.copyTo(target, overwrite = true); tmpDb.delete()
            }
            // Marker last: only now does the pack read as installed.
            if (entryCount() > 0) marker().writeText("ok")
            onProgress(1f)
            isInstalled()
        } catch (e: Exception) {
            AppLog.w("offline dictionary install failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            runCatching { tmpJson.delete(); tmpDb.delete() }
            false
        }
    }

    private suspend fun downloadTo(dest: File, onProgress: (Float) -> Unit): Boolean {
        for (url in sources) {
            coroutineContext.ensureActive()
            val ok = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching false
                    val body = resp.body ?: return@runCatching false
                    val total = body.contentLength().takeIf { it > 0 } ?: (approxMb * 1_000_000L)
                    body.byteStream().use { input ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var read: Int; var done = 0L
                            while (input.read(buf).also { read = it } >= 0) {
                                out.write(buf, 0, read); done += read
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (ok && dest.length() > 0) return true
            dest.delete()
        }
        return false
    }

    /** Stream the `{word: definition}` JSON into a fresh SQLite table, batched inside one transaction. */
    private suspend fun importInto(db: File, json: File, onProgress: (Float) -> Unit) {
        val approxEntries = 176_000f // Webster's 1913 has ~176k headwords; only drives the progress bar.
        val sqlite = SQLiteDatabase.openOrCreateDatabase(db, null)
        try {
            sqlite.execSQL("PRAGMA journal_mode=OFF")
            sqlite.execSQL("PRAGMA synchronous=OFF")
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS dict (word TEXT PRIMARY KEY, def TEXT)")
            sqlite.execSQL("DELETE FROM dict")
            val stmt = sqlite.compileStatement("INSERT OR REPLACE INTO dict(word, def) VALUES(?, ?)")
            sqlite.beginTransaction()
            var count = 0
            JsonReader(json.bufferedReader()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName().trim().lowercase()
                    val value = reader.nextString()
                    if (key.isNotEmpty() && value.isNotBlank()) {
                        stmt.clearBindings()
                        stmt.bindString(1, key)
                        stmt.bindString(2, value.trim())
                        stmt.executeInsert()
                        count++
                        if (count % 2000 == 0) {
                            sqlite.setTransactionSuccessful(); sqlite.endTransaction()
                            coroutineContext.ensureActive()
                            onProgress((count / approxEntries).coerceIn(0f, 0.99f))
                            sqlite.beginTransaction()
                        }
                    }
                }
                reader.endObject()
            }
            sqlite.setTransactionSuccessful(); sqlite.endTransaction()
        } finally {
            runCatching { sqlite.close() }
        }
    }
}
