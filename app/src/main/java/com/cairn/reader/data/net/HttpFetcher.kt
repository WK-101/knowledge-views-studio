package com.cairn.reader.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

data class FetchResult(
    val status: Int,
    val body: String?,
    val notModified: Boolean,
    val etag: String?,
    val lastModified: String?,
    val finalUrl: String,
    val contentType: String?,
) {
    val isSuccess: Boolean get() = status in 200..299 || notModified
}

/**
 * Thin OkHttp wrapper with conditional-GET support (ETag / Last-Modified) so unchanged
 * feeds return 304 and cost nothing. Bodies are capped to avoid OOM on huge pages.
 */
@Singleton
class HttpFetcher @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
        maxBytes: Long = 8L * 1024 * 1024,
    ): FetchResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        if (!lastModified.isNullOrBlank()) builder.header("If-Modified-Since", lastModified)

        client.newCall(builder.build()).execute().use { response ->
            val notModified = response.code == 304
            val contentType = response.header("Content-Type")
            val body = if (notModified) {
                null
            } else {
                // Decode by detected charset (BOM → HTTP header → XML prolog / <meta charset>
                // → UTF-8) rather than OkHttp's .string(), which defaults to UTF-8 and silently
                // mojibakes the many feeds/pages that declare a legacy charset only in their prolog.
                BodyDecoder.decode(response.peekBody(maxBytes).bytes(), contentType)
            }
            FetchResult(
                status = response.code,
                body = body,
                notModified = notModified,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                finalUrl = response.request.url.toString(),
                contentType = contentType,
            )
        }
    }


    /** Raw bytes for a binary resource (used to cache article images for the offline copy),
     *  paired with the reported content type. Null on any failure or an oversized body. */
    suspend fun fetchBytes(
        url: String,
        maxBytes: Long = 5L * 1024 * 1024,
    ): Pair<ByteArray, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type")
                val bytes = response.peekBody(maxBytes).bytes()
                if (bytes.isEmpty()) null else bytes to contentType
            }
        }.getOrNull()
    }

}

/**
 * Charset-aware decoding of a fetched body. Pure and dependency-free so it can be unit-tested
 * directly. Resolution order: byte-order mark → HTTP Content-Type charset → in-document
 * declaration (XML prolog `encoding=` / HTML `<meta charset>`) → UTF-8.
 */
internal object BodyDecoder {
    // <?xml version="1.0" encoding="ISO-8859-1"?>
    private val DECL_ENCODING = Regex("""encoding=["']([A-Za-z0-9._\-]+)["']""", RegexOption.IGNORE_CASE)
    // <meta charset="..."> or <meta http-equiv=... content="...; charset=...">
    private val DECL_META_CHARSET = Regex("""<meta[^>]+charset=["']?([A-Za-z0-9._\-]+)""", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray, contentTypeHeader: String?): String {
        if (bytes.isEmpty()) return ""
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        charsetFromContentType(contentTypeHeader)?.let { return String(bytes, it) }
        // Sniff the head as Latin-1 (each byte -> one char, lossless) so the regex sees the raw bytes.
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        (DECL_ENCODING.find(head)?.groupValues?.get(1) ?: DECL_META_CHARSET.find(head)?.groupValues?.get(1))
            ?.let { safeCharset(it) }?.let { return String(bytes, it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun charsetFromContentType(header: String?): Charset? =
        header?.let { Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.let { safeCharset(it) }

    private fun safeCharset(name: String): Charset? =
        runCatching { Charset.forName(name.trim().trim('"', '\'')) }.getOrNull()
}
