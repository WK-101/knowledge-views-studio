package app.parley.common.calls

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Do Not Disturb as Android reported it when the call started ringing (NotificationManager interruption filter). */
enum class DndState { OFF, PRIORITY, ALARMS, TOTAL_SILENCE, UNKNOWN }

enum class RingerMode { NORMAL, VIBRATE, SILENT, UNKNOWN }

/** Which ringtone the call used (V9). */
enum class RingtoneSource {
    /** The phone's default ringtone, played by Android. */
    DEFAULT,

    /** The contact's own ringtone, played by Android. */
    CONTACT,

    /** Android played it, and Parley couldn't tell whether it was the default or the contact's tone. */
    SYSTEM,

    /** A blocking/allow rule's tone, played by Parley. */
    RULE,

    /** The ringtone of one of the caller's labels, played by Parley. */
    LABEL,

    /** Parley's "Ringtone for unknown callers". */
    UNKNOWN_CALLER,

    /** The repeat-caller tone. */
    REPEAT,

    /** The likely-spam tone. */
    LIKELY_SPAM,

    /** Nothing played: silenced by screening, an allowance, or "Ignore". */
    NONE,
}

/** How the call ended, from the ring's point of view. */
enum class RingOutcome { ANSWERED, ANSWERED_ELSEWHERE, MISSED, DECLINED, BLOCKED, UNKNOWN }

/** Where the audio went when the call was answered. */
enum class AnswerRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED, OTHER }

/**
 * Ring-side facts about one incoming call (V9): what Android's ringer was set to, which tone played and where the
 * call was answered. Stored next to the screening trace so "Why did my phone ring, or not?" has both halves.
 * Nothing here is sent anywhere.
 */
@Serializable
data class RingFacts(
    /** Wall-clock time the call started ringing. */
    val startedAt: Long,
    val ringMillis: Long = 0,
    val dnd: DndState = DndState.UNKNOWN,
    /** In priority mode: whether Do Not Disturb lets some calls through (null when unknown or not in priority mode). */
    val dndAllowsCalls: Boolean? = null,
    val ringer: RingerMode = RingerMode.UNKNOWN,
    /** Ring volume and its maximum when the call arrived (volume 0 in normal mode rings silently). */
    val ringVolume: Int? = null,
    val ringVolumeMax: Int? = null,
    /** "Vibrate for calls" (null when unknown). */
    val vibrate: Boolean? = null,
    val ringtone: RingtoneSource = RingtoneSource.SYSTEM,
    /** Name of the rule or label whose tone played. */
    val ringtoneDetail: String? = null,
    /** Why Parley kept it quiet ("Silenced: off hours", "Ignored"), or null. */
    val silencedBy: String? = null,
    /** Parley raised the volume ("Ring loud"). */
    val ringLoud: Boolean = false,
    val outcome: RingOutcome = RingOutcome.UNKNOWN,
    val answeredRoute: AnswerRoute? = null,
    /** Bluetooth device or headset name ("Car kit"). */
    val answeredDevice: String? = null,
) {
    /** Would Android's ringer have been audible? False = it couldn't ring out loud. */
    val audible: Boolean
        get() = silencedBy == null && ringtone != RingtoneSource.NONE && ringer == RingerMode.NORMAL &&
            (ringVolume == null || ringVolume > 0) && (dnd == DndState.OFF || dnd == DndState.UNKNOWN || (dnd == DndState.PRIORITY && dndAllowsCalls == true))
}

/** Compact JSON for [RingFacts] lists; unknown fields are ignored so older rows keep reading. */
object RingFactsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val list = ListSerializer(RingFacts.serializer())

    fun encode(items: List<RingFacts>): String = json.encodeToString(list, items)

    fun decode(text: String?): List<RingFacts> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(list, text)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Why a call didn't ring (V9), as data: the app words it in the user's language (L1). */
sealed interface NoRing {
    /** Parley kept it quiet; [reason] is the stored reason or the screening verdict. */
    data class Silenced(val reason: String) : NoRing
    data object DndTotalSilence : NoRing
    data object DndAlarms : NoRing
    data object Dnd : NoRing
    data object PhoneSilent : NoRing
    data object PhoneVibrate : NoRing
    data object VolumeZero : NoRing
    data class ShortRing(val seconds: Long) : NoRing
}

/** Plain-language lines for "Why did my phone ring, or not?" (V9). */
object RingExplainer {
    /** Why [f] didn't ring, or null when nothing explains the silence. */
    fun noRing(f: RingFacts?, screeningVerdict: String? = null): NoRing? {
        val silenced = f?.silencedBy ?: screeningVerdict?.takeIf { it.isNotBlank() }
        if (silenced != null) return NoRing.Silenced(silenced)
        f ?: return null
        return when {
            f.dnd == DndState.TOTAL_SILENCE -> NoRing.DndTotalSilence
            f.dnd == DndState.ALARMS -> NoRing.DndAlarms
            f.dnd == DndState.PRIORITY && f.dndAllowsCalls != true -> NoRing.Dnd
            f.ringer == RingerMode.SILENT -> NoRing.PhoneSilent
            f.ringer == RingerMode.VIBRATE -> NoRing.PhoneVibrate
            f.ringer == RingerMode.NORMAL && f.ringVolume == 0 -> NoRing.VolumeZero
            f.ringMillis in 1 until SHORT_RING_MS -> NoRing.ShortRing((f.ringMillis + 999) / 1000)
            else -> null
        }
    }

    /** One line for a missed-call notification, or null when nothing explains the silence ("Silenced: off hours"). English. */
    fun whyNoRing(f: RingFacts?, screeningVerdict: String? = null): String? = when (val r = noRing(f, screeningVerdict)) {
        null -> null
        is NoRing.Silenced -> r.reason.let { s -> if (s.startsWith("Silenced", ignoreCase = true) || s.startsWith("Blocked", ignoreCase = true)) s else "Silenced: ${lower(s)}" }
        NoRing.DndTotalSilence -> "Didn't ring: Do Not Disturb (total silence)"
        NoRing.DndAlarms -> "Didn't ring: Do Not Disturb (alarms only)"
        NoRing.Dnd -> "Didn't ring: Do Not Disturb"
        NoRing.PhoneSilent -> "Didn't ring: phone on silent"
        NoRing.PhoneVibrate -> "Vibrate only: phone on vibrate"
        NoRing.VolumeZero -> "Didn't ring: ring volume at 0"
        is NoRing.ShortRing -> "Rang for ${r.seconds} s only"
    }

    /** Every fact, one line each, for the number history and the blocked-log detail. */
    fun lines(f: RingFacts): List<String> = buildList {
        add(
            when (f.dnd) {
                DndState.OFF -> "Do Not Disturb: off"
                DndState.PRIORITY -> "Do Not Disturb: priority only" + when (f.dndAllowsCalls) {
                    true -> " (some calls allowed)"
                    false -> " (calls not allowed)"
                    null -> ""
                }
                DndState.ALARMS -> "Do Not Disturb: alarms only"
                DndState.TOTAL_SILENCE -> "Do Not Disturb: total silence"
                DndState.UNKNOWN -> "Do Not Disturb: unknown"
            },
        )
        val volume = if (f.ringVolume != null && f.ringVolumeMax != null && f.ringVolumeMax > 0) " · volume ${f.ringVolume}/${f.ringVolumeMax}" else ""
        add(
            when (f.ringer) {
                RingerMode.NORMAL -> "Ringer: sound$volume"
                RingerMode.VIBRATE -> "Ringer: vibrate"
                RingerMode.SILENT -> "Ringer: silent"
                RingerMode.UNKNOWN -> "Ringer: unknown"
            } + if (f.ringLoud) " · raised to full volume" else "",
        )
        f.vibrate?.let { add(if (it) "Vibrate for calls: on" else "Vibrate for calls: off") }
        add("Ringtone: " + ringtoneText(f))
        f.silencedBy?.let { add("Kept quiet by Parley: " + lower(it)) }
        if (f.ringMillis > 0) add("Rang for " + seconds(f.ringMillis))
        add(outcomeText(f))
    }

    fun ringtoneText(f: RingFacts): String = when (f.ringtone) {
        RingtoneSource.DEFAULT -> "the phone's default"
        RingtoneSource.CONTACT -> "the contact's own"
        RingtoneSource.SYSTEM -> "played by Android"
        RingtoneSource.RULE -> f.ringtoneDetail?.let { "rule '$it'" } ?: "a rule's tone"
        RingtoneSource.LABEL -> f.ringtoneDetail?.let { "label '$it'" } ?: "a label's tone"
        RingtoneSource.UNKNOWN_CALLER -> "the unknown-caller tone"
        RingtoneSource.REPEAT -> "the repeat-caller tone"
        RingtoneSource.LIKELY_SPAM -> "the likely-spam tone"
        RingtoneSource.NONE -> "none"
    }

    fun outcomeText(f: RingFacts): String = when (f.outcome) {
        RingOutcome.ANSWERED -> "Answered" + when (f.answeredRoute) {
            AnswerRoute.BLUETOOTH -> " on " + (f.answeredDevice?.takeIf { it.isNotBlank() } ?: "Bluetooth")
            AnswerRoute.WIRED -> " on " + (f.answeredDevice?.takeIf { it.isNotBlank() } ?: "a wired headset")
            AnswerRoute.SPEAKER -> " on speaker"
            AnswerRoute.EARPIECE -> " on this phone"
            AnswerRoute.OTHER -> f.answeredDevice?.let { " on $it" } ?: ""
            null -> ""
        }
        RingOutcome.ANSWERED_ELSEWHERE -> "Answered on another device"
        RingOutcome.MISSED -> "Not answered"
        RingOutcome.DECLINED -> "Declined"
        RingOutcome.BLOCKED -> "Rejected by your blocking rules"
        RingOutcome.UNKNOWN -> "Outcome unknown"
    }

    /** The facts stored for a call at [time] (the call log's date), within [windowMs]. Newest match wins. */
    fun matchFor(all: List<RingFacts>, time: Long, windowMs: Long = MATCH_WINDOW_MS): RingFacts? =
        all.filter { kotlin.math.abs(it.startedAt - time) <= windowMs }.minByOrNull { kotlin.math.abs(it.startedAt - time) }

    private fun seconds(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
    }

    private fun lower(s: String) = s.replaceFirstChar { it.lowercase() }

    /** A missed call that rang less than this was probably hung up before the phone could be reached. */
    const val SHORT_RING_MS = 4_000L
    const val MATCH_WINDOW_MS = 2 * 60_000L
}
