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
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    // Replay guard: a bounded, FIFO-evicting set of seen nonces. Deliberately clock-INDEPENDENT — an earlier
    // timestamp window rejected valid requests whenever the phone and the browser's device clocks differed.
    // TLS + the per-device key already authenticate each request; this only blocks an exact in-session replay.
    private val seenNonces: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(NONCE_INIT, NONCE_LOAD, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                    size > NONCE_MAX
            },
        ),
    )
    private val aeadCache = ConcurrentHashMap<String, ByteArray>() // keyB64 → derived AEAD key

    fun start() {
        if (engine != null) return
        val keyStore = WebTls.keyStore(appContext)
        val env = applicationEngineEnvironment {
            sslConnector(
                keyStore = keyStore,
                keyAlias = WebTls.ALIAS,
                keyStorePassword = { WebTls.PASSWORD.toCharArray() },
                privateKeyPassword = { WebTls.PASSWORD.toCharArray() },
            ) {
                host = "0.0.0.0"
                this.port = this@WebServer.port
            }
            module { configureServer() }
        }
        engine = embeddedServer(Netty, env).start(wait = false)
    }

    fun stop() {
        runCatching { engine?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS) }
        engine = null
    }

    private fun Application.configureServer() {
        install(WebSockets) {
            // Server-side ping keeps the socket warm and detects dead clients (Ktor default is DISABLED).
            pingPeriodMillis = WS_PING_SEC * 1000
            timeoutMillis = WS_TIMEOUT_SEC * 1000
            maxFrameSize = WS_MAX_FRAME
            masking = false
        }
        routing {
            get("/health") { call.respondText(appContext.packageName) }
            webSocket("/live") { handleLive() }
            post("/api") { call.handleApi() }
            get("/{path...}") { call.serveStatic() }
        }
    }

    // ---- live channel (WebSocket) --------------------------------------------------------------------

    /**
     * The live-change channel. Replaces the long-poll: a persistent WebSocket whose fate is isolated from
     * `/api` traffic (no shared HTTP/2 stream to reset). Auth reuses the exact `/api` crypto — the first
     * frame is a sealed `hello` carrying the client's last-seen version cursor; the server trial-decrypts it
     * against the live client set, replay-checks the nonce, sends an immediate catch-up if it already moved
     * past the cursor, then pushes a sealed version snapshot on every [ChangeHub] tick. App-level ping/pong
     * lets the browser detect a silently-dead socket (onclose often doesn't fire on Wi-Fi drop).
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun DefaultWebSocketServerSession.handleLive() {
        val host = call.request.host()
        if (!RequestGuard.hostAllowed(host) ||
            !RequestGuard.originAllowed(host, call.request.port(), call.request.headers["Origin"])
        ) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "forbidden")); return
        }
        val firstText = (runCatching { incoming.receive() }.getOrNull() as? Frame.Text)?.readText()
            ?: return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no hello"))
        val match = matchClient(firstText)
            ?: return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        val (client, plaintext) = match
        val key = aeadKeyFor(client)
        ClientPresence.mark(client.id)
        val hello = runCatching { BridgeCodec.decodeString<LiveFrame>(plaintext) }.getOrNull()
        if (hello == null || hello.t != "hello" || !freshAndUnseen(hello.nonce)) {
            return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad hello"))
        }

        suspend fun sealedSend(frame: LiveFrame) =
            send(Frame.Text(CryptoBox.sealText(key, BridgeCodec.encodeString(frame))))

        var lastSent = changeHub.snapshot()
        if (changeHub.isNewer(lastSent, hello.since)) sealedSend(LiveFrame(t = "tick", versions = lastSent))

        val pushJob = launch {
            changeHub.ticks.collect {
                val now = changeHub.snapshot()
                if (changeHub.isNewer(now, lastSent)) {
                    lastSent = now
                    runCatching { sealedSend(LiveFrame(t = "tick", versions = now)) }
                }
            }
        }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val msg = runCatching { CryptoBox.openText(key, frame.readText()) }.getOrNull() ?: continue
                val f = runCatching { BridgeCodec.decodeString<LiveFrame>(msg) }.getOrNull() ?: continue
                if (f.t == "ping") runCatching { sealedSend(LiveFrame(t = "pong")) }
            }
        } finally {
            pushJob.cancel()
        }
    }

    // ---- API ----------------------------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught") // an edge endpoint must map any failure to a status, never crash
    private suspend fun io.ktor.server.application.ApplicationCall.handleApi() {
        // Use Ktor's request.host()/port() — they resolve the authority from HTTP/1.1's Host header AND
        // HTTP/2's :authority pseudo-header (Netty negotiates HTTP/2 over TLS, where there is no Host header,
        // so reading the raw header returned null and 403'd every call).
        val host = request.host()
        val port = request.port()
        if (!RequestGuard.hostAllowed(host)) {
            respond(HttpStatusCode.Forbidden, "forbidden_host:${host.ifBlank { "none" }}")
            return
        }
        if (!RequestGuard.originAllowed(host, port, request.headers["Origin"])) {
            respond(HttpStatusCode.Forbidden, "forbidden_origin:${request.headers["Origin"] ?: "none"}")
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

        if (!freshAndUnseen(req.nonce)) { respond(HttpStatusCode.Forbidden, "replay"); return }

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

    private fun freshAndUnseen(nonce: String): Boolean = nonce.isNotBlank() && seenNonces.add(nonce)

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
        const val NONCE_MAX = 4096 // bound the replay-guard set; FIFO eviction drops the oldest
        const val NONCE_INIT = 512
        const val NONCE_LOAD = 0.75f
        const val WS_PING_SEC = 20L
        const val WS_TIMEOUT_SEC = 15L
        const val WS_MAX_FRAME = 1L * 1024 * 1024
        const val STOP_GRACE_MS = 300L
        const val STOP_TIMEOUT_MS = 1000L
        const val AWAIT_MS = 25_000L
        const val KIND_QUERY = "query"
        const val KIND_MUTATE = "mutate"
        const val KIND_AWAIT = "await"
        const val KIND_HELLO = "hello"
    }
}
