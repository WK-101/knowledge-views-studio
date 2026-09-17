package com.cairn.reader.domain.transcript

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches existing captions/transcripts for a piece of media over the network, then parses them
 * on-device via [CaptionParsers]. This is the "captions" transcription source — it never sends any
 * audio anywhere; it only downloads a text track a publisher already produced:
 *
 *  • YouTube — asks YouTube's own InnerTube `player` endpoint (the same one the apps use) for the
 *    caption-track list, then downloads the chosen timedtext track. No Data API key, no HTML scraping
 *    of the watch page (which hits consent walls and breaks whenever the markup shifts). A watch-page
 *    fallback with a consent cookie covers the rare miss.
 *  • Podcasts / web pages — downloads a `<podcast:transcript>` URL, a plain .vtt/.srt/.json, or a
 *    caption `<track>` discovered in an HTML5 video page.
 *
 * Blocking by design; callers run it on [kotlinx.coroutines.Dispatchers.IO].
 */
@Singleton
class CaptionFetcher @Inject constructor(private val client: OkHttpClient) {

    /** Download and parse a transcript from a direct URL (a `<podcast:transcript>`, .vtt, .srt, JSON). */
    fun fetchFromUrl(url: String, mime: String? = null, kind: TranscriptSourceKind = TranscriptSourceKind.CAPTION_FILE): Transcript? {
        val body = get(url) ?: return null
        val cues = CaptionParsers.parse(body, mime ?: url)
        if (cues.isNotEmpty()) return Transcript(cues, source = kind)
        // Not a caption file itself — maybe an HTML page that references one (a <track>, a .vtt link).
        return discoverInHtml(url, body)?.let { fetchFromUrl(it, kind = kind) }
    }

    /** Fetch YouTube captions for a video URL or bare id. Prefers a manually-authored track in the
     *  requested language, then any track in it, then auto-generated, then the first available. */
    fun fetchYouTube(urlOrId: String, preferLang: String = "en"): Transcript? {
        val id = youtubeVideoId(urlOrId) ?: urlOrId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return null
        // Ask each InnerTube client in turn; the ANDROID client usually answers without a consent wall.
        val tracks = innerTubeTracks(id, ANDROID_CTX, ANDROID_UA)
            ?: innerTubeTracks(id, WEB_CTX, WEB_UA)
            ?: watchPageTracks(id)
            ?: return null
        if (tracks.isEmpty()) return null
        val chosen = tracks.firstOrNull { it.lang == preferLang && !it.asr }
            ?: tracks.firstOrNull { it.lang == preferLang }
            ?: tracks.firstOrNull { it.lang.startsWith(preferLang) }
            ?: tracks.firstOrNull { !it.asr }
            ?: tracks.first()
        return fetchTimedText(chosen.baseUrl, chosen.lang)
    }

    /** True if [url] looks like a YouTube video we can fetch captions for. */
    fun isYouTube(url: String?): Boolean = url != null && youtubeVideoId(url) != null

    private data class CaptionTrack(val baseUrl: String, val lang: String, val asr: Boolean)

    /** Ask InnerTube's player endpoint for the caption track list (proper JSON, no HTML scraping). */
    private fun innerTubeTracks(id: String, contextJson: String, ua: String): List<CaptionTrack>? {
        val payload = """{"context":$contextJson,"videoId":"$id"}"""
        val body = try {
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=$INNERTUBE_KEY&prettyPrint=false")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", ua)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/json")
                .build()
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) {
            null
        } ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val list = root.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return null
        val out = ArrayList<CaptionTrack>(list.length())
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val base = t.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
            val lang = t.optString("languageCode")
            val asr = t.optString("kind") == "asr"
            out.add(CaptionTrack(base, lang, asr))
        }
        return out
    }

    /** Last-resort watch-page scrape (consent cookie + en), extracting the captionTracks JSON array. */
    private fun watchPageTracks(id: String): List<CaptionTrack>? {
        val html = get("https://www.youtube.com/watch?v=$id&hl=en", youtube = true) ?: return null
        // Pull the JSON array out of "...captionTracks":[ {...},{...} ] by bracket-matching (robust to
        // nested arrays inside a track's name.runs, which a lazy regex would truncate on).
        val marker = "\"captionTracks\":"
        val at = html.indexOf(marker)
        if (at < 0) return null
        val start = html.indexOf('[', at + marker.length)
        if (start < 0) return null
        var depth = 0
        var end = -1
        for (i in start until html.length) {
            when (html[i]) { '[' -> depth++; ']' -> { depth--; if (depth == 0) { end = i; break } } }
        }
        if (end < 0) return null
        val arr = runCatching { org.json.JSONArray(html.substring(start, end + 1)) }.getOrNull() ?: return null
        val out = ArrayList<CaptionTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val base = t.optString("baseUrl").takeIf { it.isNotBlank() }?.replace("\\u0026", "&") ?: continue
            out.add(CaptionTrack(base, t.optString("languageCode"), t.optString("kind") == "asr"))
        }
        return out
    }

    /** Download a timedtext track. The default response is srv1 XML; if that's empty, ask for json3. */
    private fun fetchTimedText(baseUrl: String, lang: String): Transcript? {
        val xml = get(baseUrl, youtube = true)
        val fromXml = xml?.let { CaptionParsers.parseTimedTextXml(it) }.orEmpty()
        if (fromXml.isNotEmpty()) return Transcript(fromXml, language = lang, source = TranscriptSourceKind.YOUTUBE_CAPTIONS)
        val sep = if (baseUrl.contains('?')) '&' else '?'
        val json = get("$baseUrl${sep}fmt=json3", youtube = true) ?: return null
        val fromJson = CaptionParsers.parseJson3(json)
        return if (fromJson.isEmpty()) null else Transcript(fromJson, language = lang, source = TranscriptSourceKind.YOUTUBE_CAPTIONS)
    }

    /** Find a caption track referenced by an HTML page: an HTML5 `<track kind=captions src=..>` or a
     *  bare .vtt/.srt link. Returns an absolute URL to fetch, or null. */
    private fun discoverInHtml(pageUrl: String, html: String): String? {
        if (!html.contains("<", ignoreCase = true)) return null
        val track = Regex("<track[^>]*kind=[\"'](?:captions|subtitles)[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .find(html)?.value
            ?: Regex("<track[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
                .map { it.value }.firstOrNull { it.contains("captions", true) || it.contains("subtitles", true) }
        val src = track?.let { Regex("src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?: Regex("[\"'](https?://[^\"']+\\.(?:vtt|srt))[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: return null
        return absolutize(pageUrl, src)
    }

    private fun absolutize(pageUrl: String, ref: String): String = when {
        ref.startsWith("http", true) -> ref
        ref.startsWith("//") -> "https:$ref"
        ref.startsWith("/") -> runCatching { val u = java.net.URI(pageUrl); "${u.scheme}://${u.host}$ref" }.getOrDefault(ref)
        else -> runCatching { java.net.URI(pageUrl).resolve(ref).toString() }.getOrDefault(ref)
    }

    /** Extract an 11-char YouTube video id from the usual URL shapes, or null. */
    fun youtubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/embed/([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/shorts/([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/live/([A-Za-z0-9_-]{11})"),
        )
        for (p in patterns) p.find(url)?.let { return it.groupValues[1] }
        return null
    }

    private fun get(url: String, youtube: Boolean = false): String? = try {
        val req = Request.Builder().url(url).apply {
            if (youtube) {
                header("User-Agent", WEB_UA)
                header("Accept-Language", "en-US,en;q=0.9")
                // Bypass the EU "before you continue" consent interstitial that has no caption data.
                header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            }
        }.build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        // The public InnerTube key shipped in YouTube's own web player; no account, no quota.
        const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        const val ANDROID_UA = "com.google.android.youtube/19.09.37 (Linux; U; Android 13) gzip"
        const val ANDROID_CTX = """{"client":{"clientName":"ANDROID","clientVersion":"19.09.37","androidSdkVersion":33,"hl":"en","gl":"US"}}"""
        const val WEB_CTX = """{"client":{"clientName":"WEB","clientVersion":"2.20240101.00.00","hl":"en","gl":"US"}}"""
    }
}
