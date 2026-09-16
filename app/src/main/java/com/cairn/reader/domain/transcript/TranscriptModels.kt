package com.cairn.reader.domain.transcript

/**
 * A timed transcript: an ordered list of [cues], each a span of text with a start/end offset into
 * the media. Produced either by fetching existing captions (YouTube timedtext, a podcast's
 * `<podcast:transcript>`, a plain .vtt/.srt) or, where available, by on-device speech-to-text.
 * Entirely on-device once fetched — Cairn stores the cues and never re-contacts the source to read.
 */
data class Transcript(
    val cues: List<TranscriptCue>,
    val language: String? = null,
    /** Where this transcript came from, for provenance in the UI (e.g. "YouTube captions"). */
    val source: TranscriptSourceKind = TranscriptSourceKind.UNKNOWN,
) {
    val isEmpty: Boolean get() = cues.isEmpty()
    val durationMs: Long get() = cues.maxOfOrNull { it.endMs } ?: 0L
    val plainText: String get() = cues.joinToString("\n") { it.text }
}

/** One caption line: [text] shown between [startMs] and [endMs] (milliseconds into the media). */
data class TranscriptCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** A "m:ss" (or "h:mm:ss") clock label for a millisecond offset, for cue timestamps and annotations. */
fun formatTimestamp(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** How a transcript was obtained, kept for honest provenance labelling in the UI. */
enum class TranscriptSourceKind {
    YOUTUBE_CAPTIONS,   // YouTube timedtext
    PODCAST_TRANSCRIPT, // Podcasting 2.0 <podcast:transcript>
    CAPTION_FILE,       // a plain .vtt / .srt URL
    ON_DEVICE,          // on-device speech-to-text (Whisper)
    UNKNOWN,
}
