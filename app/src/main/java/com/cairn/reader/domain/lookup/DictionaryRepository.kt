package com.cairn.reader.domain.lookup

import com.cairn.reader.data.net.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
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
 * Looks a word up in the free, key-less Dictionary API (dictionaryapi.dev), which returns both
 * definitions and thesaurus data (synonyms / antonyms). Online-only — there is no bundled
 * dictionary — so it fails cleanly when offline, and only the single looked-up word is sent.
 */
@Singleton
class DictionaryRepository @Inject constructor(
    private val fetcher: HttpFetcher,
) {
    suspend fun define(raw: String): Result<DictionaryEntry> = withContext(Dispatchers.IO) {
        // Only a single word makes sense for a dictionary; take the first token of a selection.
        val word = raw.trim().split(Regex("\\s+")).firstOrNull()
            ?.trim { !it.isLetterOrDigit() && it != '-' && it != '\'' }
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext Result.failure(IllegalArgumentException("No word to look up"))

        val url = "https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(word, "UTF-8")
        val res = runCatching { fetcher.fetch(url) }.getOrNull()
        if (res == null || !res.isSuccess || res.body.isNullOrBlank()) {
            return@withContext Result.failure(IOException("No definition found for “$word”"))
        }
        runCatching { parse(res.body!!) }.getOrElse { Result.failure(it) }
    }

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
