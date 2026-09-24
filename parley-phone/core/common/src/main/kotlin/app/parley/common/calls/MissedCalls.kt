package app.parley.common.calls

import java.util.Locale

/** One unseen missed call from the call log. [key] identifies the caller's line (see PhoneNumbers.lineKey). */
data class MissedCall(val number: String, val date: Long, val accountId: String?, val hidden: Boolean, val key: String)

/** Unseen missed calls from one caller (V2): one notification each, with a count. */
data class MissedCaller(
    val key: String,
    val number: String,
    val hidden: Boolean,
    val count: Int,
    val latest: Long,
    val first: Long,
    /** SIM of the latest call. */
    val accountId: String?,
)

object MissedCalls {
    /** Group unseen missed calls by caller, the most recent caller first. Hidden numbers form one group. */
    fun group(calls: List<MissedCall>): List<MissedCaller> =
        calls.groupBy { if (it.hidden || it.number.isBlank()) HIDDEN else it.key }
            .map { (k, list) ->
                val latest = list.maxBy { it.date }
                MissedCaller(k, latest.number, k == HIDDEN, list.size, latest.date, list.minOf { it.date }, latest.accountId)
            }
            .sortedByDescending { it.latest }

    /** The notification title for one caller: "Missed call" or "3 missed calls". */
    fun title(count: Int): String = if (count <= 1) "Missed call" else "$count missed calls"

    /** The group summary title: "5 missed calls from 3 people". */
    fun summaryTitle(total: Int, callers: Int): String =
        if (callers <= 1) title(total) else "$total missed calls from $callers callers"

    /** Callers shown as their own notification; the rest are only counted in the summary. */
    const val MAX_CHILDREN = 6
    const val HIDDEN = "hidden"
}

/** File names and labels for voicemail audio (V1). */
object VoicemailFiles {
    /** A file extension for a voicemail's MIME type, so the shared file opens in other apps. */
    fun extensionFor(mime: String?): String = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
        "audio/amr" -> "amr"
        "audio/amr-wb" -> "awb"
        "audio/mp4", "audio/m4a", "audio/aac", "audio/x-m4a" -> "m4a"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/3gpp" -> "3gp"
        else -> "amr"
    }

    /** "voicemail-2026-09-24-1432.amr" from a local date-time. */
    fun shareName(year: Int, month: Int, day: Int, hour: Int, minute: Int, mime: String?): String =
        "voicemail-%04d-%02d-%02d-%02d%02d.%s".format(Locale.ROOT, year, month, day, hour, minute, extensionFor(mime))

    /** "0:07", "12:45". */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        return "%d:%02d".format(s / 60, s % 60) // locale-ok: shown to the user
    }
}
