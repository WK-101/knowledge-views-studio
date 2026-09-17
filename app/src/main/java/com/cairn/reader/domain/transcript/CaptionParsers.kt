package com.cairn.reader.domain.transcript

import org.json.JSONObject

/**
 * Pure, on-device parsers for the common caption/transcript formats. No network, no Android
 * dependencies — every function turns a downloaded string into a list of [TranscriptCue]s, so they
 * are trivially unit-testable and reusable across sources.
 *
 * Supported: WebVTT (.vtt), SubRip (.srt), YouTube timedtext XML, and the Podcasting 2.0 JSON
 * transcript shape. A dispatcher picks the parser from a MIME type / URL / a quick content sniff.
 */
object CaptionParsers {

    /** Parse [body] into cues, choosing the format from [mimeOrUrl] (a content-type or a URL/filename),
     *  falling back to sniffing the body when the hint is ambiguous. Returns [] if nothing parses. */
    fun parse(body: String, mimeOrUrl: String? = null): List<TranscriptCue> {
        val hint = mimeOrUrl?.lowercase()?.trim() ?: ""
        val trimmed = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            hint.contains("vtt") || trimmed.startsWith("WEBVTT") -> parseVtt(body)
            hint.contains("srt") || hint.contains("subrip") -> parseSrt(body)
            hint.contains("json3") || (trimmed.startsWith("{") && trimmed.contains("\"events\"")) -> parseJson3(body)
            hint.contains("json") || trimmed.startsWith("{") -> parseJsonTranscript(body)
            hint.contains("xml") || hint.contains("timedtext") || trimmed.startsWith("<") -> parseTimedTextXml(body)
            trimmed.contains("-->") -> if (trimmed.contains(',')) parseSrt(body) else parseVtt(body)
            else -> emptyList()
        }
    }

    // ── WebVTT & SubRip share a block structure: an optional cue id, a "start --> end" line, then text.
    fun parseVtt(body: String): List<TranscriptCue> = parseCueBlocks(body)
    fun parseSrt(body: String): List<TranscriptCue> = parseCueBlocks(body)

    private fun parseCueBlocks(body: String): List<TranscriptCue> {
        val cues = ArrayList<TranscriptCue>()
        // Split into blocks on blank lines; tolerate CRLF and a leading BOM / "WEBVTT" header.
        val blocks = body.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n[ \t]*\n"))
        for (block in blocks) {
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val arrowIdx = lines.indexOfFirst { it.contains("-->") }
            if (arrowIdx < 0) continue
            val timing = lines[arrowIdx]
            val parts = timing.split("-->")
            if (parts.size < 2) continue
            val start = parseTimestamp(parts[0].trim()) ?: continue
            // The end token may carry VTT cue settings after it ("... position:50%"); take the first token.
            val end = parseTimestamp(parts[1].trim().substringBefore(' ').trim()) ?: continue
            val text = lines.drop(arrowIdx + 1).joinToString("\n") { stripInlineTags(it) }.trim()
            if (text.isNotEmpty() && end >= start) cues.add(TranscriptCue(start, end, text))
        }
        return coalesce(cues)
    }

    /** YouTube timedtext: `<text start="1.23" dur="3.4">line</text>` (or `<p t="1230" d="3400">`). */
    fun parseTimedTextXml(body: String): List<TranscriptCue> {
        val cues = ArrayList<TranscriptCue>()
        // <text ...>...</text>
        Regex("<text([^>]*)>(.*?)</text>", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
            val attrs = m.groupValues[1]
            val start = attrOf(attrs, "start")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            val dur = attrOf(attrs, "dur")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            val text = unescapeXml(stripInlineTags(m.groupValues[2])).trim()
            if (start != null && text.isNotEmpty()) cues.add(TranscriptCue(start, start + dur, text))
        }
        if (cues.isNotEmpty()) return coalesce(cues)
        // srv3 / timedtext v3: <p t="1230" d="3400">line</p>
        Regex("<p([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
            val attrs = m.groupValues[1]
            val start = attrOf(attrs, "t")?.toLongOrNull()
            val dur = attrOf(attrs, "d")?.toLongOrNull() ?: 0L
            val text = unescapeXml(stripInlineTags(m.groupValues[2])).trim()
            if (start != null && text.isNotEmpty()) cues.add(TranscriptCue(start, start + dur, text))
        }
        return coalesce(cues)
    }

    /** Podcasting 2.0 JSON transcript: `{"segments":[{"startTime":..,"endTime":..,"body":".."}]}`. */
    fun parseJsonTranscript(body: String): List<TranscriptCue> {
        val cues = ArrayList<TranscriptCue>()
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val segments = obj.optJSONArray("segments") ?: return emptyList()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONObject(i) ?: continue
            // startTime/endTime are seconds (may be fractional); body carries the text.
            val start = (seg.optDouble("startTime", Double.NaN)).let { if (it.isNaN()) null else (it * 1000).toLong() } ?: continue
            val end = (seg.optDouble("endTime", Double.NaN)).let { if (it.isNaN()) start else (it * 1000).toLong() }
            val text = seg.optString("body").trim()
            if (text.isNotEmpty()) cues.add(TranscriptCue(start, maxOf(end, start), text))
        }
        return coalesce(cues)
    }

    /** YouTube timedtext `fmt=json3`: `{"events":[{"tStartMs":..,"dDurationMs":..,"segs":[{"utf8":".."}]}]}`. */
    fun parseJson3(body: String): List<TranscriptCue> {
        val cues = ArrayList<TranscriptCue>()
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val events = obj.optJSONArray("events") ?: return emptyList()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val segs = e.optJSONArray("segs") ?: continue // style/window events have no segs
            val start = e.optLong("tStartMs", -1L)
            if (start < 0) continue
            val dur = e.optLong("dDurationMs", 0L)
            val text = buildString {
                for (j in 0 until segs.length()) segs.optJSONObject(j)?.optString("utf8")?.let { append(it) }
            }.trim()
            if (text.isNotEmpty()) cues.add(TranscriptCue(start, start + dur, text))
        }
        return coalesce(cues)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Parse "HH:MM:SS.mmm" / "MM:SS.mmm" / SRT "HH:MM:SS,mmm" into milliseconds. */
    fun parseTimestamp(raw: String): Long? {
        val s = raw.replace(',', '.').trim()
        if (s.isEmpty()) return null
        val dot = s.split(".")
        val hms = dot[0].split(":")
        val millis = dot.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        return try {
            val secs = when (hms.size) {
                3 -> hms[0].toLong() * 3600 + hms[1].toLong() * 60 + hms[2].toLong()
                2 -> hms[0].toLong() * 60 + hms[1].toLong()
                1 -> hms[0].toLong()
                else -> return null
            }
            secs * 1000 + millis
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun attrOf(attrs: String, name: String): String? =
        Regex("$name\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    /** Remove inline markup: VTT `<c>`, `<v Speaker>`, timestamp tags `<00:00:01.000>`, and HTML tags. */
    private fun stripInlineTags(s: String): String = s.replace(Regex("<[^>]*>"), "")

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }

    /** Drop empty/zero-length noise and keep cues in start order. */
    private fun coalesce(cues: List<TranscriptCue>): List<TranscriptCue> =
        cues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
}
