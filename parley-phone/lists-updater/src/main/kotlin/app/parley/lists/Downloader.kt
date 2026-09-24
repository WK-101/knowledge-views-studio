package app.parley.lists

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Plain HTTPS GETs of static files. No cookies, no query built from anything on the phone, no identifiers:
 * the request is the same for every user. Redirects are followed only to other HTTPS addresses.
 */
object Downloader {
    sealed interface Result {
        class Ok(val bytes: ByteArray, val etag: String?, val lastModified: String?) : Result
        data object NotModified : Result
        data object NotFound : Result
        class Failed(val reason: String) : Result
    }

    private const val USER_AGENT = "ParleyLists/1.0"

    fun get(res: android.content.res.Resources, url: String, maxBytes: Long, etag: String? = null, lastModified: String? = null): Result {
        var current = url
        repeat(5) {
            if (!current.startsWith("https://", ignoreCase = true)) return Result.Failed(res.getString(R.string.lists_err_https))
            val c = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return Result.Failed(res.getString(R.string.lists_err_link))
            }
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 30_000
                c.readTimeout = 60_000
                c.useCaches = false
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept-Encoding", "gzip")
                etag?.let { c.setRequestProperty("If-None-Match", it) }
                lastModified?.let { c.setRequestProperty("If-Modified-Since", it) }
                val code = c.responseCode
                when {
                    code in 300..399 && code != 304 -> {
                        val loc = c.getHeaderField("Location") ?: return Result.Failed(res.getString(R.string.lists_err_redirect))
                        current = URL(URL(current), loc).toString()
                        return@repeat
                    }
                    code == 304 -> return Result.NotModified
                    code == 404 || code == 410 -> return Result.NotFound
                    code !in 200..299 -> return Result.Failed(res.getString(R.string.lists_err_status, code))
                }
                val declared = c.contentLengthLong
                if (declared > maxBytes) return Result.Failed(res.getString(R.string.lists_err_too_large))
                val raw = c.inputStream
                val input = if ("gzip".equals(c.contentEncoding, ignoreCase = true)) GZIPInputStream(raw) else raw
                val out = ByteArrayOutputStream()
                input.use { s ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return Result.Failed(res.getString(R.string.lists_err_too_large))
                        out.write(buf, 0, n)
                    }
                }
                return Result.Ok(out.toByteArray(), c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"))
            } catch (e: IOException) {
                return Result.Failed(res.getString(R.string.lists_err_network, e.javaClass.simpleName))
            } finally {
                c.disconnect()
            }
        }
        return Result.Failed(res.getString(R.string.lists_err_redirects))
    }
}
