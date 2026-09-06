package com.cairn.reader.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tiny WebDAV client — enough to mirror Cairn's backups into a self-hosted folder
 * (Nextcloud, ownCloud, a plain WebDAV share). No third-party account, no cloud service:
 * the user points at their own server and owns the files.
 *
 * Only the verbs a backup needs: PROPFIND (list + reachability test), PUT (upload),
 * GET (download). Auth is HTTP Basic; for Nextcloud an app-password is recommended.
 */
@Singleton
class WebDavClient @Inject constructor(
    private val client: OkHttpClient,
) {
    data class Config(val baseUrl: String, val user: String?, val pass: String?) {
        val configured: Boolean get() = baseUrl.isNotBlank()
    }

    /** Normalize the folder URL to end in a single slash so file paths append cleanly, and refuse
     *  non-HTTPS targets: WebDAV auth is HTTP Basic, so a cleartext URL would leak the credential. */
    private fun dir(base: String): String {
        val b = base.trim()
        require(b.startsWith("https://", ignoreCase = true)) {
            "WebDAV requires an https:// address — credentials must not be sent over cleartext."
        }
        return b.trimEnd('/') + "/"
    }

    private fun Request.Builder.auth(cfg: Config): Request.Builder {
        val u = cfg.user
        if (!u.isNullOrBlank()) header("Authorization", Credentials.basic(u, cfg.pass ?: ""))
        return this
    }

    /** Verify the server is reachable and the credentials work (PROPFIND depth 0 on the folder). */
    suspend fun test(cfg: Config): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(dir(cfg.baseUrl))
                .method("PROPFIND", ByteArray(0).toRequestBody())
                .header("Depth", "0")
                .auth(cfg)
                .build()
            client.newCall(req).execute().use { r ->
                // 207 Multi-Status is the success case; 200/204 also acceptable.
                if (r.code == 207 || r.isSuccessful) Unit
                else error("Server returned HTTP ${r.code}")
            }
        }
    }

    /** Upload [bytes] to [name] inside the configured folder, replacing any existing file. */
    suspend fun put(cfg: Config, name: String, bytes: ByteArray, contentType: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body: RequestBody = bytes.toRequestBody(contentType.toMediaTypeOrNull())
                val req = Request.Builder()
                    .url(dir(cfg.baseUrl) + name)
                    .put(body)
                    .auth(cfg)
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("Upload failed: HTTP ${r.code}")
                    Unit
                }
            }
        }

    /** Stream [name] from the folder to [consume]; returns whatever the consumer produces. */
    suspend fun <T> get(cfg: Config, name: String, consume: (InputStream) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(dir(cfg.baseUrl) + name).get().auth(cfg).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("Download failed: HTTP ${r.code}")
                    val stream = r.body?.byteStream() ?: error("Empty response")
                    consume(stream)
                }
            }
        }

    /** Delete one file from the folder (used to prune old backups). Missing files are not an error. */
    suspend fun delete(cfg: Config, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(dir(cfg.baseUrl) + name).delete().auth(cfg).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful && r.code != 404) error("Delete failed: HTTP ${r.code}")
                Unit
            }
        }
    }

    /** List backup files in the folder, newest-named first. Parses the PROPFIND multistatus for
     *  &lt;d:href&gt; entries and keeps only Cairn's own backup files. */
    suspend fun listBackups(cfg: Config): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(dir(cfg.baseUrl))
                .method("PROPFIND", ByteArray(0).toRequestBody())
                .header("Depth", "1")
                .auth(cfg)
                .build()
            client.newCall(req).execute().use { r ->
                if (r.code != 207 && !r.isSuccessful) error("List failed: HTTP ${r.code}")
                val xml = r.body?.string().orEmpty()
                HREF.findAll(xml)
                    .map { it.groupValues[1] }
                    .map { it.substringAfterLast('/') }
                    .map { java.net.URLDecoder.decode(it, "UTF-8") }
                    .filter { it.startsWith("cairn-backup-") && (it.endsWith(".json") || it.endsWith(".zip")) }
                    .distinct()
                    .sortedDescending()
                    .toList()
            }
        }
    }

    private companion object {
        val HREF = Regex("<[^>]*href[^>]*>([^<]+)</[^>]*href>", RegexOption.IGNORE_CASE)
    }
}
