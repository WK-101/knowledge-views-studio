package app.parley.ui.calls

import android.content.res.Resources
import app.parley.R
import app.parley.common.calls.AnswerRoute
import app.parley.common.calls.DndState
import app.parley.common.calls.NoRing
import app.parley.common.calls.RingExplainer
import app.parley.common.calls.RingFacts
import app.parley.common.calls.RingOutcome
import app.parley.common.calls.RingerMode
import app.parley.common.calls.RingtoneSource

/** "Why did my phone ring, or not?" (V9) in the user's language; the decisions are [RingExplainer]'s. */
object RingText {
    /** One line for a missed-call notification, or null when nothing explains the silence. */
    fun whyNoRing(res: Resources, f: RingFacts?, screeningVerdict: String? = null): String? = when (val r = RingExplainer.noRing(f, screeningVerdict)) {
        null -> null
        is NoRing.Silenced -> {
            val s = r.reason
            val prefixes = listOf("Silenced", "Blocked", res.getString(app.parley.telecom.R.string.call_silenced), res.getString(R.string.ring_prefix_blocked))
            if (prefixes.any { s.startsWith(it, ignoreCase = true) }) s else res.getString(R.string.ring_silenced_reason, s.replaceFirstChar { it.lowercase() })
        }
        NoRing.DndTotalSilence -> res.getString(R.string.ring_why_dnd_total)
        NoRing.DndAlarms -> res.getString(R.string.ring_why_dnd_alarms)
        NoRing.Dnd -> res.getString(R.string.ring_why_dnd)
        NoRing.PhoneSilent -> res.getString(R.string.ring_why_silent)
        NoRing.PhoneVibrate -> res.getString(R.string.ring_why_vibrate)
        NoRing.VolumeZero -> res.getString(R.string.ring_why_volume_zero)
        is NoRing.ShortRing -> res.getQuantityString(R.plurals.ring_why_short, r.seconds.toInt(), r.seconds.toInt())
    }

    /** Every fact, one line each (same order as [RingExplainer.lines]). */
    fun lines(res: Resources, f: RingFacts): List<String> = buildList {
        add(
            when (f.dnd) {
                DndState.OFF -> res.getString(R.string.ring_dnd_off)
                DndState.PRIORITY -> when (f.dndAllowsCalls) {
                    true -> res.getString(R.string.ring_dnd_priority_some)
                    false -> res.getString(R.string.ring_dnd_priority_none)
                    null -> res.getString(R.string.ring_dnd_priority)
                }
                DndState.ALARMS -> res.getString(R.string.ring_dnd_alarms)
                DndState.TOTAL_SILENCE -> res.getString(R.string.ring_dnd_total)
                DndState.UNKNOWN -> res.getString(R.string.ring_dnd_unknown)
            },
        )
        val ringer = when (f.ringer) {
            RingerMode.NORMAL -> {
                val volume = f.ringVolume
                val max = f.ringVolumeMax
                if (volume != null && max != null && max > 0) res.getString(R.string.ring_ringer_sound_volume, volume, max)
                else res.getString(R.string.ring_ringer_sound)
            }
            RingerMode.VIBRATE -> res.getString(R.string.ring_ringer_vibrate)
            RingerMode.SILENT -> res.getString(R.string.ring_ringer_silent)
            RingerMode.UNKNOWN -> res.getString(R.string.ring_ringer_unknown)
        }
        add(if (f.ringLoud) res.getString(R.string.ring_raised, ringer) else ringer)
        f.vibrate?.let { add(res.getString(if (it) R.string.ring_vibrate_on else R.string.ring_vibrate_off)) }
        add(res.getString(R.string.ring_ringtone, ringtoneText(res, f)))
        f.silencedBy?.let { add(res.getString(R.string.ring_kept_quiet, it.replaceFirstChar { c -> c.lowercase() })) }
        if (f.ringMillis > 0) add(res.getString(R.string.ring_rang_for, seconds(res, f.ringMillis)))
        add(outcomeText(res, f))
    }

    private fun ringtoneText(res: Resources, f: RingFacts): String = when (f.ringtone) {
        RingtoneSource.DEFAULT -> res.getString(R.string.ring_tone_default)
        RingtoneSource.CONTACT -> res.getString(R.string.ring_tone_contact)
        RingtoneSource.SYSTEM -> res.getString(R.string.ring_tone_system)
        RingtoneSource.RULE -> f.ringtoneDetail?.let { res.getString(R.string.ring_tone_rule_named, it) } ?: res.getString(R.string.ring_tone_rule)
        RingtoneSource.LABEL -> f.ringtoneDetail?.let { res.getString(R.string.ring_tone_label_named, it) } ?: res.getString(R.string.ring_tone_label)
        RingtoneSource.UNKNOWN_CALLER -> res.getString(R.string.ring_tone_unknown_caller)
        RingtoneSource.REPEAT -> res.getString(R.string.ring_tone_repeat)
        RingtoneSource.LIKELY_SPAM -> res.getString(R.string.ring_tone_spam)
        RingtoneSource.NONE -> res.getString(R.string.ring_tone_none)
    }

    fun outcomeText(res: Resources, f: RingFacts): String = when (f.outcome) {
        RingOutcome.ANSWERED -> when (f.answeredRoute) {
            AnswerRoute.BLUETOOTH -> res.getString(R.string.ring_answered_on, f.answeredDevice?.takeIf { it.isNotBlank() } ?: res.getString(app.parley.telecom.R.string.audio_route_bluetooth))
            AnswerRoute.WIRED -> f.answeredDevice?.takeIf { it.isNotBlank() }?.let { res.getString(R.string.ring_answered_on, it) } ?: res.getString(R.string.ring_answered_wired)
            AnswerRoute.SPEAKER -> res.getString(R.string.ring_answered_speaker)
            AnswerRoute.EARPIECE -> res.getString(R.string.ring_answered_phone)
            AnswerRoute.OTHER -> f.answeredDevice?.let { res.getString(R.string.ring_answered_on, it) } ?: res.getString(R.string.ring_answered)
            null -> res.getString(R.string.ring_answered)
        }
        RingOutcome.ANSWERED_ELSEWHERE -> res.getString(R.string.ring_answered_elsewhere)
        RingOutcome.MISSED -> res.getString(R.string.ring_not_answered)
        RingOutcome.DECLINED -> res.getString(R.string.ring_declined)
        RingOutcome.BLOCKED -> res.getString(R.string.ring_rejected_rules)
        RingOutcome.UNKNOWN -> res.getString(R.string.ring_outcome_unknown)
    }

    private fun seconds(res: Resources, ms: Long): String {
        val s = ((ms + 500) / 1000).toInt()
        return if (s < 60) res.getString(R.string.ring_seconds, s) else res.getString(R.string.ring_min_seconds, s / 60, s % 60)
    }
}
