package com.cairn.reader.domain.lookup

import com.cairn.reader.util.coRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One sense of a word: its part of speech, the definition, and an example if the source has one. */
data class WordSense(val partOfSpeech: String, val definition: String, val example: String?)

/** A dictionary + thesaurus result for a single word. */
data class DictionaryEntry(
    val word: String,
    val phonetic: String?,
    val senses: List<WordSense>,
    val synonyms: List<String>,
    val antonyms: List<String>,
)

/**
 * Looks a word up online (no bundled dictionary, so it fails cleanly offline and only the single
 * looked-up word is ever sent). Two key-less sources are tried in turn for reliability:
 *   1. dictionaryapi.dev — rich (definitions + synonyms/antonyms), but a community service that is
 *      frequently down or rate-limited;
 *   2. Wiktionary's Wikimedia REST API — very reliable, definitions + examples (HTML, which we strip).
 * Whichever answers first wins, so a lookup keeps working even when dictionaryapi.dev is unavailable.
 *
 * A small in-memory cache makes repeat look-ups instant, and a dedicated short-timeout HTTP client
 * keeps a single lookup snappy instead of inheriting the 45s article-fetch timeout.
 */
@Singleton
class DictionaryRepository @Inject constructor(
    client: OkHttpClient,
) {
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    // Bounded LRU cache of recent successful look-ups, keyed by the normalized word.
    private val cache = object : LinkedHashMap<String, DictionaryEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DictionaryEntry>?) = size > 200
    }

    suspend fun define(raw: String): Result<DictionaryEntry> = withContext(Dispatchers.IO) {
        // Only a single word makes sense for a dictionary; take the first token of a selection.
        val word = raw.trim().split(Regex("\\s+")).firstOrNull()
            ?.trim { !it.isLetterOrDigit() && it != '-' && it != '\'' }
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext Result.failure(IllegalArgumentException("No word to look up"))

        synchronized(cache) { cache[word] }?.let { return@withContext Result.success(it) }

        // 1. dictionaryapi.dev (rich), then 2. Wiktionary (reliable fallback).
        primaryDefine(word).recoverCatching { wiktionaryDefine(word).getOrThrow() }
            .onSuccess { entry -> synchronized(cache) { cache[word] = entry } }
    }

    private fun primaryDefine(word: String): Result<DictionaryEntry> {
        val url = "https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(word, "UTF-8")
        val body = get(url) ?: return Result.failure(IOException("No definition found for “$word”"))
        return parse(body)
    }

    /** Wikimedia's Wiktionary REST definition endpoint — returns HTML-in-JSON, so tags are stripped. */
    private fun wiktionaryDefine(word: String): Result<DictionaryEntry> {
        val url = "https://en.wiktionary.org/api/rest_v1/page/definition/" + URLEncoder.encode(word, "UTF-8")
        val body = get(url) ?: return Result.failure(IOException("No definition found for “$word”"))
        val root = runCatching { org.json.JSONObject(body) }.getOrNull()
            ?: return Result.failure(IOException("No definition found"))
        val en = root.optJSONArray("en") ?: return Result.failure(IOException("No English definition for “$word”"))
        val senses = ArrayList<WordSense>()
        for (i in 0 until en.length()) {
            val group = en.optJSONObject(i) ?: continue
            val pos = group.optString("partOfSpeech")
            val defs = group.optJSONArray("definitions") ?: continue
            for (d in 0 until defs.length()) {
                val def = defs.optJSONObject(d) ?: continue
                val text = stripHtml(def.optString("definition")).takeIf { it.isNotBlank() } ?: continue
                val example = def.optJSONArray("examples")?.optString(0)?.let { stripHtml(it) }?.takeIf { it.isNotBlank() }
                if (senses.size < 12) senses += WordSense(pos, text, example)
            }
        }
        if (senses.isEmpty()) return Result.failure(IOException("No definition found for “$word”"))
        return Result.success(DictionaryEntry(word = word, phonetic = null, senses = senses, synonyms = emptyList(), antonyms = emptyList()))
    }

    private fun get(url: String): String? = coRunCatching {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Strip HTML tags + collapse entities/whitespace from a Wiktionary definition fragment. */
    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parse(body: String): Result<DictionaryEntry> {
        val arr = JSONArray(body)
        if (arr.length() == 0) return Result.failure(IOException("No definition found"))
        val word = arr.getJSONObject(0).optString("word").ifBlank { return Result.failure(IOException("No definition")) }
        var phonetic: String? = arr.getJSONObject(0).optString("phonetic").takeIf { it.isNotBlank() }
        val senses = ArrayList<WordSense>()
        val syn = LinkedHashSet<String>()
        val ant = LinkedHashSet<String>()

        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            if (phonetic == null) {
                val phs = entry.optJSONArray("phonetics")
                if (phs != null) for (p in 0 until phs.length()) {
                    val t = phs.getJSONObject(p).optString("text")
                    if (t.isNotBlank()) { phonetic = t; break }
                }
            }
            val meanings = entry.optJSONArray("meanings") ?: continue
            for (m in 0 until meanings.length()) {
                val meaning = meanings.getJSONObject(m)
                val pos = meaning.optString("partOfSpeech")
                collectStrings(meaning.optJSONArray("synonyms"), syn)
                collectStrings(meaning.optJSONArray("antonyms"), ant)
                val defs = meaning.optJSONArray("definitions") ?: continue
                for (d in 0 until defs.length()) {
                    val def = defs.getJSONObject(d)
                    val text = def.optString("definition").takeIf { it.isNotBlank() } ?: continue
                    if (senses.size < 12) {
                        senses += WordSense(pos, text, def.optString("example").takeIf { it.isNotBlank() })
                    }
                    collectStrings(def.optJSONArray("synonyms"), syn)
                    collectStrings(def.optJSONArray("antonyms"), ant)
                }
            }
        }
        if (senses.isEmpty()) return Result.failure(IOException("No definition found"))
        return Result.success(
            DictionaryEntry(
                word = word,
                phonetic = phonetic,
                senses = senses,
                synonyms = syn.take(12),
                antonyms = ant.take(12),
            ),
        )
    }

    private fun collectStrings(arr: JSONArray?, into: MutableSet<String>) {
        if (arr == null) return
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { into.add(it) }
    }
}
