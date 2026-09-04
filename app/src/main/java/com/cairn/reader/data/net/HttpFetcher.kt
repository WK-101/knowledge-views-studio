package com.cairn.reader.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
            val body = if (notModified) {
                null
            } else {
                response.peekBody(maxBytes).string()
            }
            FetchResult(
                status = response.code,
                body = body,
                notModified = notModified,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                finalUrl = response.request.url.toString(),
                contentType = response.header("Content-Type"),
            )
        }
    }
}
