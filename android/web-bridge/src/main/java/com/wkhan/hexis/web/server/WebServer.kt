package com.wkhan.hexis.web.server

import android.content.Context
import android.util.Log

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataQuery
import com.wkhan.hexis.web.bridge.DataBridgeClient
import com.wkhan.hexis.web.crypto.CryptoBox
import com.wkhan.hexis.web.pairing.ClientPresence
import com.wkhan.hexis.web.pairing.WebClient

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.host
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

import java.util.concurrent.ConcurrentHashMap

/**
 * The local web server. Binds 0.0.0.0 (so the computer can reach it) but every data endpoint is gated by
 * the app-layer AES-GCM: a request body only decrypts if the caller holds the pairing key, so possession
 * is authorization and traffic is confidential + authenticated **even over plain HTTP**. Also enforces a
 * Host-header + Origin allow-list (DNS-rebinding / cross-site defense) and a timestamp+nonce replay guard.
 *
 * The SPA is served same-origin from the module's `resources/web/` so there is no CORS and no mixed
 * content. The server holds no data — `/api` forwards to the core via [DataBridgeClient].
 */
class WebServer(
    private val appContext: Context,
    private val port: Int,
    private val clients: () -> List<WebClient>,
    private val dataClient: DataBridgeClient,
    private val changeHub: ChangeHub,
) {
    @Volatile private var engine: ApplicationEngine? = null
    private val seenNonces = ConcurrentHashMap<String, Long>()
    private val aeadCache = ConcurrentHashMap<String, ByteArray>() // keyB64 → derived AEAD key

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = false)
    }

    fun stop() {
        runCatching { engine?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS) }
        engine = null
    }

    private fun Application.module() {
        routing {
            get("/health") { call.respondText(appContext.packageName) }
            post("/api") { call.handleApi() }
            get("/{path...}") { call.serveStatic() }
        }
    }

    // ---- API ----------------------------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught") // an edge endpoint must map any failure to a status, never crash
    private suspend fun io.ktor.server.application.ApplicationCall.handleApi() {
        if (!hostAllowed() || !originAllowed()) {
            respond(HttpStatusCode.Forbidden, "forbidden")
            return
        }
        val body = runCatching { receiveText() }.getOrNull()
        if (body.isNullOrBlank()) { respond(HttpStatusCode.BadRequest, "empty"); return }

        // Identify the caller by trying each live client's key: a body only opens under the key of a client
        // that still exists (revoked / expired clients can't be matched → locked out instantly).
        val match = matchClient(body)
        if (match == null) { respond(HttpStatusCode.Unauthorized, "unauthorized"); return }
        val (client, plaintext) = match
        val key = aeadKeyFor(client)
        ClientPresence.mark(client.id)

        val req = try {
            BridgeCodec.decodeString<ApiRequest>(plaintext)
        } catch (t: Throwable) {
            respond(HttpStatusCode.BadRequest, "malformed"); return
        }

        if (!freshAndUnseen(req.ts, req.nonce)) { respond(HttpStatusCode.Forbidden, "replay"); return }

        // A read-only client (a share link) may read and long-poll, never mutate.
        if (client.readOnly && req.kind == KIND_MUTATE) {
            respondText(CryptoBox.sealText(key, BridgeCodec.encodeString(ApiResponse(ok = false, error = "read_only"))))
            return
        }

        // "hello" lets the browser learn its own access (read-only?) so it can hide edit controls up front.
        if (req.kind == KIND_HELLO) {
            val hello = BridgeCodec.encodeString(HelloInfo(readOnly = client.readOnly, name = client.name))
            respondText(CryptoBox.sealText(key, BridgeCodec.encodeString(ApiResponse(ok = true, dataJson = hello))))
            return
        }

        // "await" is a long-poll over the live-change hub — it holds the request until a change or timeout,
        // reusing this same end-to-end-encrypted channel instead of a separate (weaker) SSE socket.
        if (req.kind == KIND_AWAIT) {
            val since = decodeVersions(req.paramsJson)
            val now = changeHub.await(since, AWAIT_MS)
            val api = ApiResponse(ok = true, dataJson = now?.let { BridgeCodec.encodeString(it) })
            respondText(CryptoBox.sealText(key, BridgeCodec.encodeString(api)))
            return
        }

        val resp = when (req.kind) {
            KIND_QUERY -> dataClient.query(DataQuery(req.domain, req.op, req.cursor, req.limit, req.paramsJson))
            KIND_MUTATE -> dataClient.mutate(DataMutation(req.domain, req.op, req.payloadJson))
            else -> null
        }
        val api = if (resp == null) {
            ApiResponse(ok = false, error = "bad_kind")
        } else {
            ApiResponse(ok = resp.ok, dataJson = resp.payloadJson, error = resp.error?.let { it.message ?: it.type.name })
        }
        // Encrypt the response with the matched client's key so only that paired browser can read it.
        respondText(CryptoBox.sealText(key, BridgeCodec.encodeString(api)))
    }

    /** First live client whose key opens [body]; also returns the decrypted plaintext (so we open once). */
    private fun matchClient(body: String): Pair<WebClient, String>? {
        for (client in clients()) {
            val plaintext = runCatching { CryptoBox.openText(aeadKeyFor(client), body) }.getOrNull()
            if (plaintext != null) return client to plaintext
        }
        return null
    }

    private fun aeadKeyFor(client: WebClient): ByteArray =
        aeadCache.getOrPut(client.keyB64) { client.aeadKey() }

    private fun decodeVersions(json: String): Map<String, Long> =
        runCatching { BridgeCodec.decodeString<Map<String, Long>>(json) }.getOrDefault(emptyMap())

    private fun freshAndUnseen(ts: Long, nonce: String): Boolean {
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - ts) > REPLAY_WINDOW_MS) return false
        pruneNonces(now)
        return seenNonces.putIfAbsent(nonce, now) == null
    }

    private fun pruneNonces(now: Long) {
        if (seenNonces.size < NONCE_PRUNE_AT) return
        seenNonces.entries.removeAll { now - it.value > REPLAY_WINDOW_MS }
    }

    private fun io.ktor.server.application.ApplicationCall.hostAllowed(): Boolean {
        // Reject a Host that isn't a raw IP / localhost / *.local — the rebinding attacker's Host survives
        // the rebind, so this blocks a public page from driving our server via a rebound hostname.
        val host = request.host().substringBefore(':').lowercase()
        return host == "localhost" ||
            host.endsWith(".local") ||
            host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) ||
            host.contains(':') // IPv6 literal
    }

    private fun io.ktor.server.application.ApplicationCall.originAllowed(): Boolean {
        // A same-origin SPA fetch sends no Origin (same-origin GET/POST) or an Origin equal to our own host.
        val origin = request.headers["Origin"] ?: return true
        val host = request.host()
        return origin.endsWith("://$host") || origin.endsWith("//$host") ||
            origin.substringAfter("://", "").substringBefore('/') == host
    }

    // ---- static SPA (served from resources/web/) -----------------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private suspend fun io.ktor.server.application.ApplicationCall.serveStatic() {
        val raw = parameters.getAll("path")?.joinToString("/").orEmpty()
        val path = if (raw.isBlank() || raw.endsWith("/")) "index.html" else raw
        val resource = "web/${path.trimStart('/')}"
        val bytes = try {
            javaClass.classLoader?.getResourceAsStream(resource)?.use { it.readBytes() }
        } catch (t: Throwable) {
            Log.w(TAG, "static read failed: $resource", t)
            null
        }
        if (bytes == null) {
            // SPA fallback: unknown non-asset path → index.html.
            val index = javaClass.classLoader?.getResourceAsStream("web/index.html")?.use { it.readBytes() }
            if (index == null) respond(HttpStatusCode.NotFound, "not found")
            else respondBytes(index, ContentType.Text.Html)
            return
        }
        respondBytes(bytes, contentTypeFor(path))
    }

    private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "")) {
        "html" -> ContentType.Text.Html
        "js" -> ContentType.Text.JavaScript
        "css" -> ContentType.Text.CSS
        "json", "webmanifest" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "ico" -> ContentType("image", "x-icon")
        else -> ContentType.Application.OctetStream
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respond(status: HttpStatusCode, msg: String) =
        respondText(msg, status = status)

    private companion object {
        const val TAG = "WebServer"
        const val REPLAY_WINDOW_MS = 60_000L
        const val NONCE_PRUNE_AT = 256
        const val STOP_GRACE_MS = 300L
        const val STOP_TIMEOUT_MS = 1000L
        const val AWAIT_MS = 25_000L
        const val KIND_QUERY = "query"
        const val KIND_MUTATE = "mutate"
        const val KIND_AWAIT = "await"
        const val KIND_HELLO = "hello"
    }
}
