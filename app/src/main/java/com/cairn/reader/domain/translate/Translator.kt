package com.cairn.reader.domain.translate

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation via ML Kit. Language models are downloaded on first use for a given
 * language pair and then cached, so translation afterwards works fully offline. Everything runs
 * locally — no text is uploaded, and no account is needed. The device's own language is the
 * default target.
 */
@Singleton
class Translator @Inject constructor() {

    /** The device's language if ML Kit supports it, else English. */
    fun defaultTarget(): String =
        TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

    fun displayName(bcp47: String): String =
        runCatching { Locale.forLanguageTag(bcp47).getDisplayLanguage(Locale.getDefault()) }
            .getOrDefault(bcp47).ifBlank { bcp47 }

    /** Identify the (BCP-47) language of [text], or null if undetermined / unsupported. */
    suspend fun detect(text: String): String? = withContext(Dispatchers.IO) {
        val client = LanguageIdentification.getClient()
        try {
            val tag = client.identifyLanguage(text.take(400)).awaitResult()
            if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
        } catch (t: Throwable) {
            null
        } finally {
            client.close()
        }
    }

    /**
     * Translate [text] into [target] (defaults to the device language). Downloads the model pair
     * if needed. Returns the original text unchanged when the source already matches the target.
     */
    suspend fun translate(
        text: String,
        target: String = defaultTarget(),
        requireWifi: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext Result.success("")
        val source = detect(trimmed) ?: return@withContext Result.failure(IllegalStateException("Couldn't detect the language"))
        if (source == target) return@withContext Result.success(text)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val translator = Translation.getClient(options)
        try {
            val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().apply {
                if (requireWifi) requireWifi()
            }.build()
            translator.downloadModelIfNeeded(conditions).awaitUnit()
            val out = translator.translate(text).awaitResult()
            Result.success(out)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            translator.close()
        }
    }
}

/** Bridge a ML Kit Task<T> to a coroutine without pulling in kotlinx-coroutines-play-services. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

private suspend fun com.google.android.gms.tasks.Task<Void>.awaitUnit() {
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(Unit) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
