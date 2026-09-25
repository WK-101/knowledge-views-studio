package app.parley.ui.circle

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import app.parley.R
import app.parley.common.Messenger
import app.parley.common.circle.CircleStatus
import app.parley.common.circle.ContactKind
import app.parley.common.circle.InteractionChannel
import app.parley.common.circle.InteractionType
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.LastContact
import app.parley.common.circle.LogMode
import app.parley.common.circle.RhythmMode

/** Texts of the Circle (R1–R5), in the app language. Warm and blame-free: no counters, no "overdue". */
object CircleText {
    fun status(res: Resources, s: CircleStatus): String = res.getString(
        when (s) {
            CircleStatus.DUE -> R.string.circle_status_due
            CircleStatus.SOON -> R.string.circle_status_soon
            CircleStatus.FINE -> R.string.circle_status_fine
        },
    )

    fun kind(res: Resources, k: ContactKind): String = res.getString(
        when (k) {
            ContactKind.CALL -> R.string.circle_kind_call
            ContactKind.MEET -> R.string.circle_kind_meet
            ContactKind.MESSAGE -> R.string.circle_kind_message
            ContactKind.VIDEO -> R.string.circle_kind_video
            ContactKind.OTHER -> R.string.circle_kind_other
        },
    )

    fun type(res: Resources, t: InteractionType): String = res.getString(
        when (t) {
            InteractionType.MEET -> R.string.circle_type_meet
            InteractionType.MESSAGE -> R.string.circle_type_message
            InteractionType.VIDEO -> R.string.circle_type_video
            InteractionType.OTHER -> R.string.circle_type_other
        },
    )

    fun typeIcon(t: InteractionType): ImageVector = when (t) {
        InteractionType.MEET -> Icons.Rounded.Handshake
        InteractionType.MESSAGE -> Icons.AutoMirrored.Rounded.Chat
        InteractionType.VIDEO -> Icons.Rounded.Videocam
        InteractionType.OTHER -> Icons.Rounded.MoreHoriz
    }

    /** Channel names: app names stay as they are; the rest is translated. */
    fun channel(res: Resources, c: InteractionChannel): String = when (c) {
        InteractionChannel.SMS -> res.getString(R.string.msg_sms)
        InteractionChannel.WHATSAPP -> Messenger.WHATSAPP.label
        InteractionChannel.SIGNAL -> Messenger.SIGNAL.label
        InteractionChannel.TELEGRAM -> Messenger.TELEGRAM.label
        InteractionChannel.OTHER_APP -> res.getString(R.string.circle_channel_other_apps)
        InteractionChannel.VIDEO -> res.getString(R.string.circle_channel_video)
    }

    fun mode(res: Resources, m: LogMode): String = res.getString(
        when (m) {
            LogMode.ALWAYS -> R.string.circle_mode_always
            LogMode.ASK -> R.string.circle_mode_ask
            LogMode.NEVER -> R.string.circle_mode_never
        },
    )

    /** "Last in touch 12 days ago · call", "In touch today · met", or "Not in touch yet". */
    fun last(res: Resources, last: LastContact?, now: Long = System.currentTimeMillis()): String {
        last ?: return res.getString(R.string.circle_never)
        val days = ((now - last.time) / 86_400_000L).coerceAtLeast(0).toInt()
        val kind = kind(res, last.kind)
        return if (days == 0) res.getString(R.string.circle_last_today, kind) else res.getQuantityString(R.plurals.circle_last_days, days, days, kind)
    }

    /** "Every 14 days" or "Natural rhythm · about every 20 days". */
    fun rhythm(res: Resources, everyDays: Int, r: KeepRhythm): String = when {
        r.mode == RhythmMode.NATURAL && r.learnedDays != null -> res.getQuantityString(R.plurals.circle_natural_days, r.learnedDays!!, r.learnedDays!!)
        r.mode == RhythmMode.NATURAL -> res.getQuantityString(R.plurals.circle_natural_learning, everyDays, everyDays)
        else -> res.getQuantityString(R.plurals.circle_every_days, everyDays, everyDays)
    }
}
