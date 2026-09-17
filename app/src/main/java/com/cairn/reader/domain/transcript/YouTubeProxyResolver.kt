package com.cairn.reader.domain.transcript

import android.content.Context
import com.cairn.reader.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves YouTube captions (and, for on-device speech-to-text, an audio stream) through public
 * **Piped** / **Invidious** API instances — the same mechanism LibreTube and Clipious use.
 *
 * Why this is the reliable path: since 2024 YouTube gates a caption track's signed `baseUrl` behind a
 * per-session proof-of-origin token (PoToken) minted by the player's BotGuard JS. A plain HTTP GET —
 * or even a `fetch()` from inside a real watch page — gets an empty 200, because the token isn't on
 * the URL. A Piped/Invidious instance runs the full extractor server-side (it solves the token) and
 * re-serves the caption track through its own proxy, so the app only ever fetches a plain WebVTT file
 * from the instance. Nothing about the user's device or account is involved; it's exactly what a
 * privacy front-end does.
 *
 * Instances come and go, so we (a) ship a broad default list, (b) refresh it from the projects' own
 * public instance lists when reachable (cached to disk with a TTL), and (c) rotate through them,
 * sticking to whichever answered last. Every stage is logged to [AppLog] so a field failure is
 * diagnosable from Settings → Diagnostics.
 */
@Singleton
class YouTubeProxyResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    appClient: OkHttpClient,
) {
    // A dedicated client with the app's UA/Accept interceptor dropped (an instance proxy doesn't want
    // Cairn's headers) and tight per-request timeouts so a dead instance is abandoned quickly.
    private val client: OkHttpClient = appClient.newBuilder().apply {
        interceptors().clear()
        connectTimeout(8, TimeUnit.SECONDS)
        readTimeout(15, TimeUnit.SECONDS)
        callTimeout(20, TimeUnit.SECONDS)
    }.build()

    @Volatile private var pipedHosts: List<String> = DEFAULT_PIPED
    @Volatile private var invidiousHosts: List<String> = DEFAULT_INVIDIOUS
    @Volatile private var lastGoodPiped: String? = null
    @Volatile private var lastGoodInvidious: String? = null
    @Volatile private var refreshedAtMs = 0L

    // A tiny single-entry cache of a parsed /streams response, so a caption fetch immediately followed
    // by an audio-URL fetch for the same video doesn't hit the instance twice.
    private data class StreamsCache(val videoId: String, val host: String, val json: JSONObject, val atMs: Long)
    @Volatile private var streamsCache: StreamsCache? = null

    /** Captions for [videoId] as parsed cues, via the first instance that answers — or null. */
    suspend fun captions(videoId: String, preferLang: String = "en"): Transcript? = withContext(Dispatchers.IO) {
        ensureInstances()
        fetchViaPiped(videoId, preferLang) ?: fetchViaInvidious(videoId, preferLang)
    }

    /**
     * A directly-fetchable audio (or muxed audio+video) stream URL for on-device transcription of an
     * un-captioned video. The instance proxies the stream, so it needs no PoToken and Android's
     * [android.media.MediaExtractor] can open it over HTTP. Prefers a small audio-only track, else the
     * lowest-resolution muxed stream (its audio track is extracted). Null when no instance answers.
     */
    suspend fun audioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        ensureInstances()
        for (host in ordered(pipedHosts, lastGoodPiped)) {
            val json = streamsFor(videoId, host) ?: continue
            val url = pickAudioUrl(json)
            if (url != null) {
                lastGoodPiped = host
                AppLog.diag("yt-proxy: audio via $host (${url.take(60)}…)")
                return@withContext url
            }
        }
        AppLog.w("yt-proxy: no audio stream from any instance for $videoId")
        null
    }

    // ── Piped ────────────────────────────────────────────────────────────────────────────────────

    private fun fetchViaPiped(videoId: String, preferLang: String): Transcript? {
        for (host in ordered(pipedHosts, lastGoodPiped)) {
            val json = streamsFor(videoId, host) ?: continue
            val subs = json.optJSONArray("subtitles") ?: JSONArray()
            if (subs.length() == 0) { AppLog.diag("yt-proxy: $host has no subtitles for $videoId"); continue }
            val chosen = pickSubtitle(subs, preferLang) ?: continue
            val rawUrl = chosen.optString("url").takeIf { it.isNotBlank() } ?: continue
            val body = get(forceVtt(rawUrl)) ?: continue
            val cues = CaptionParsers.parse(body, "vtt").ifEmpty { CaptionParsers.parse(body) }
            if (cues.isNotEmpty()) {
                lastGoodPiped = host
                AppLog.diag("yt-proxy(piped): $host lang=${chosen.optString("code")} → ${cues.size} cues")
                return Transcript(cues, chosen.optString("code").ifBlank { preferLang }, TranscriptSourceKind.YOUTUBE_CAPTIONS)
            }
        }
        return null
    }

    /** GET `/streams/{id}` from [host], with the single-entry cache in front of it. */
    private fun streamsFor(videoId: String, host: String): JSONObject? {
        streamsCache?.let { if (it.videoId == videoId && it.host == host && System.currentTimeMillis() - it.atMs < STREAMS_TTL_MS) return it.json }
        val body = get("https://$host/streams/$videoId") ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // Piped answers a failed extraction with {"error": "..."} and HTTP 500; skip those.
        if (json.has("error") && !json.has("subtitles")) { AppLog.diag("yt-proxy: $host error=${json.optString("error").take(60)}"); return null }
        streamsCache = StreamsCache(videoId, host, json, System.currentTimeMillis())
        return json
    }

    /** Prefer a human-authored track in the wanted language, then auto in it, then its family, then
     *  English, then anything — mirroring [CaptionFetcher]'s ordering. */
    private fun pickSubtitle(subs: JSONArray, lang: String): JSONObject? {
        val list = (0 until subs.length()).mapNotNull { subs.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() }
        fun code(o: JSONObject) = o.optString("code")
        fun auto(o: JSONObject) = o.optBoolean("autoGenerated", false)
        return list.firstOrNull { code(it) == lang && !auto(it) }
            ?: list.firstOrNull { code(it) == lang }
            ?: list.firstOrNull { code(it).startsWith(lang) }
            ?: list.firstOrNull { code(it).startsWith("en") && !auto(it) }
            ?: list.firstOrNull { code(it).startsWith("en") }
            ?: list.firstOrNull { !auto(it) }
            ?: list.firstOrNull()
    }

    /** Prefer a compact audio-only m4a/mp4 track; else the lowest-resolution muxed (audio+video)
     *  stream, whose audio track [android.media.MediaExtractor] can still pull. */
    private fun pickAudioUrl(json: JSONObject): String? {
        val audio = json.optJSONArray("audioStreams") ?: JSONArray()
        val audioList = (0 until audio.length()).mapNotNull { audio.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() }
        val m4a = audioList.filter {
            val fmt = (it.optString("format") + it.optString("mimeType")).lowercase()
            fmt.contains("m4a") || fmt.contains("mp4")
        }.minByOrNull { it.optInt("bitrate", Int.MAX_VALUE) }
        (m4a ?: audioList.minByOrNull { it.optInt("bitrate", Int.MAX_VALUE) })?.let { return it.optString("url") }

        // No audio-only stream (some instances omit them): fall back to the smallest muxed video.
        val video = json.optJSONArray("videoStreams") ?: JSONArray()
        val muxed = (0 until video.length()).mapNotNull { video.optJSONObject(it) }
            .filter { !it.optBoolean("videoOnly", true) && it.optString("url").isNotBlank() }
            .filter { (it.optString("format") + it.optString("mimeType")).lowercase().let { f -> f.contains("mp4") || f.contains("webm") } }
        // Quality strings like "360p"/"720p": pick the smallest by leading number.
        return muxed.minByOrNull { it.optString("quality").takeWhile { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
            ?.optString("url")
    }

    // ── Invidious ────────────────────────────────────────────────────────────────────────────────

    private fun fetchViaInvidious(videoId: String, preferLang: String): Transcript? {
        for (host in ordered(invidiousHosts, lastGoodInvidious)) {
            val listBody = get("https://$host/api/v1/captions/$videoId") ?: continue
            val root = runCatching { JSONObject(listBody) }.getOrNull() ?: continue // Anubis-walled hosts return HTML
            val caps = root.optJSONArray("captions") ?: continue
            if (caps.length() == 0) continue
            val list = (0 until caps.length()).mapNotNull { caps.optJSONObject(it) }
            val chosen = list.firstOrNull { it.optString("languageCode") == preferLang }
                ?: list.firstOrNull { it.optString("languageCode").startsWith(preferLang) }
                ?: list.firstOrNull { it.optString("languageCode").startsWith("en") }
                ?: list.firstOrNull() ?: continue
            val rel = chosen.optString("url").takeIf { it.isNotBlank() } ?: continue
            val capUrl = if (rel.startsWith("http")) rel else "https://$host$rel"
            val body = get(capUrl) ?: continue // Invidious serves WebVTT here
            val cues = CaptionParsers.parse(body, "vtt").ifEmpty { CaptionParsers.parse(body) }
            if (cues.isNotEmpty()) {
                lastGoodInvidious = host
                AppLog.diag("yt-proxy(invidious): $host lang=${chosen.optString("languageCode")} → ${cues.size} cues")
                return Transcript(cues, chosen.optString("languageCode").ifBlank { preferLang }, TranscriptSourceKind.YOUTUBE_CAPTIONS)
            }
        }
        return null
    }

    // ── instance list: default → disk cache → live refresh ─────────────────────────────────────────

    /** Warm the instance lists without ever making the caller wait on the network: load the disk
     *  cache inline (a fast local read), and if a network refresh is due kick it off on a background
     *  thread. The first-ever caption fetch therefore proceeds immediately on the built-in defaults
     *  (whose first entry is the last known-good instance) and a fresher list applies to later calls. */
    private fun ensureInstances() {
        if (System.currentTimeMillis() - refreshedAtMs < REFRESH_TTL_MS) return
        synchronized(this) {
            if (System.currentTimeMillis() - refreshedAtMs < REFRESH_TTL_MS) return
            val cacheFile = File(context.filesDir, CACHE_NAME)
            val cacheFresh = cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < REFRESH_TTL_MS
            if (cacheFresh) {
                loadCache(cacheFile)?.let { (piped, invidious) ->
                    if (piped.isNotEmpty()) pipedHosts = merge(piped, DEFAULT_PIPED)
                    if (invidious.isNotEmpty()) invidiousHosts = merge(invidious, DEFAULT_INVIDIOUS)
                    refreshedAtMs = System.currentTimeMillis()
                    return
                }
            }
            // Cache missing/stale: refresh in the background so we don't block this fetch. Mark refreshed
            // now so a burst of fetches spawns only one worker; a failed refresh just leaves defaults.
            refreshedAtMs = System.currentTimeMillis()
            Thread {
                val piped = refreshPiped()
                val invidious = refreshInvidious()
                if (piped.isNotEmpty()) pipedHosts = merge(piped, DEFAULT_PIPED)
                if (invidious.isNotEmpty()) invidiousHosts = merge(invidious, DEFAULT_INVIDIOUS)
                if (piped.isNotEmpty() || invidious.isNotEmpty()) runCatching { saveCache(cacheFile, piped, invidious) }
                AppLog.diag("yt-proxy: instances refreshed piped=${pipedHosts.size} invidious=${invidiousHosts.size}")
            }.apply { isDaemon = true; name = "yt-proxy-instances" }.start()
        }
    }

    private fun refreshPiped(): List<String> {
        val body = get("https://piped-instances.kavin.rocks/") ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("api_url") }
            .mapNotNull { hostOf(it) }
    }

    private fun refreshInvidious(): List<String> {
        val body = get("https://api.invidious.io/instances.json") ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val pair = arr.optJSONArray(i) ?: continue
            val info = pair.optJSONObject(1) ?: continue
            if (info.optString("type") == "https" && info.optBoolean("api", false)) {
                hostOf(info.optString("uri"))?.let { out.add(it) }
            }
        }
        return out
    }

    private fun loadCache(f: File): Pair<List<String>, List<String>>? = runCatching {
        val o = JSONObject(f.readText())
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        arr("piped") to arr("invidious")
    }.getOrNull()

    private fun saveCache(f: File, piped: List<String>, invidious: List<String>) {
        val o = JSONObject()
            .put("piped", JSONArray(piped))
            .put("invidious", JSONArray(invidious))
        f.writeText(o.toString())
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    /** Put [preferred] (the instance that answered last) first, then the rest, capped to [MAX_TRIES]. */
    private fun ordered(hosts: List<String>, preferred: String?): List<String> {
        val rest = hosts.filter { it != preferred }
        return (listOfNotNull(preferred) + rest).take(MAX_TRIES)
    }

    /** Force a caption URL to WebVTT: swap an existing `fmt=` value, else append `&fmt=vtt`. */
    private fun forceVtt(url: String): String = when {
        url.contains(Regex("[?&]fmt=")) -> url.replace(Regex("([?&]fmt=)[^&]*"), "$1vtt")
        url.contains('?') -> "$url&fmt=vtt"
        else -> "$url?fmt=vtt"
    }

    private fun merge(primary: List<String>, defaults: List<String>): List<String> =
        (primary + defaults).distinct()

    private fun hostOf(urlOrHost: String): String? {
        val s = urlOrHost.trim().ifBlank { return null }
        return runCatching { java.net.URI(if (s.contains("://")) s else "https://$s").host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun get(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json, text/vtt, */*").build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_TRIES = 6            // instances to try before giving up on a source
        const val STREAMS_TTL_MS = 120_000L
        const val REFRESH_TTL_MS = 24 * 60 * 60 * 1000L
        const val CACHE_NAME = "yt_proxy_instances.json"

        // Hostnames only (the client adds https://). Ordered roughly by observed reliability; the list
        // self-updates at runtime, so this is just a warm start that also covers an offline first run.
        val DEFAULT_PIPED = listOf(
            "api.piped.private.coffee",
            "pipedapi.kavin.rocks",
            "pipedapi.adminforge.de",
            "api.piped.yt",
            "pipedapi.leptons.xyz",
            "pipedapi.reallyaweso.me",
            "pipedapi.ducks.party",
            "piapi.ggtyler.dev",
            "pipedapi.darkness.services",
            "pipedapi.orangenet.cc",
            "pipedapi.nosebs.ru",
            "pipedapi.r4fo.com",
        )
        val DEFAULT_INVIDIOUS = listOf(
            "invidious.nerdvpn.de",
            "inv.nadeko.net",
            "yewtu.be",
            "invidious.jing.rocks",
            "iv.melmac.space",
            "invidious.privacyredirect.com",
            "invidious.f5.si",
            "id.420129.xyz",
            "invidious.reallyaweso.me",
            "iv.datura.network",
        )
    }
}
