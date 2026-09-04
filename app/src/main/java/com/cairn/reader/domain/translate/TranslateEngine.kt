package com.cairn.reader.domain.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation via ML Kit. The article text never leaves the phone — only the
 * language model is downloaded from Google once per language, then translation runs
 * locally and offline. Translators are cached per source→target pair.
 */
@Singleton
class TranslateEngine @Inject constructor() {

    private val languageId = LanguageIdentification.getClient()
    private val translators = ConcurrentHashMap<String, Translator>()

    val targetLanguage: String = TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

    /** Translate [text] into the device language; returns the original if it's already in
     *  that language or the language can't be handled. */
    suspend fun translate(text: String): String {
        if (text.isBlank()) return text
        val sourceTag = runCatching { languageId.identifyLanguage(text).await() }.getOrNull()
        if (sourceTag == null || sourceTag == "und") return text
        val source = TranslateLanguage.fromLanguageTag(sourceTag) ?: return text
        if (source == targetLanguage) return text

        val translator = translatorFor(source, targetLanguage)
        return runCatching {
            translator.downloadModelIfNeeded().await()
            translator.translate(text).await()
        }.getOrDefault(text)
    }

    private fun translatorFor(source: String, target: String): Translator {
        val key = "$source>$target"
        return translators.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        }
    }
}

/** Await a Play Services [Task] from a coroutine without pulling in the play-services-tasks
 *  coroutine integration. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
