package app.parley.messaging

import android.content.res.Resources
import app.parley.R
import app.parley.common.MessengerApp
import app.parley.common.MessengerLinks

/** Messaging texts that core/common decides in English, in the user's language (L1). */
object MessagingText {
    /** Why a chat link can't be built for [e164], or null when it can ([MessengerLinks.unavailableReason]). */
    fun unavailable(res: Resources, e164: String?): String? = when (MessengerLinks.unavailable(e164)) {
        MessengerLinks.Unavailable.NO_COUNTRY_CODE -> res.getString(R.string.msg_needs_country_code)
        MessengerLinks.Unavailable.INCOMPLETE -> res.getString(R.string.msg_incomplete_number)
        null -> null
    }

    /** "Send my details" text ([app.parley.common.MessageDrafts.myDetails]), or null with neither name nor number. */
    fun myDetails(res: Resources, name: String?, number: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() }
        val num = number?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            n != null && num != null -> res.getString(R.string.msg_details_name_number, n, num)
            n != null -> res.getString(R.string.msg_details_name, n)
            num != null -> res.getString(R.string.msg_details_number, num)
            else -> null
        }
    }

    /** "3 of 12", or "Done" at the end ([app.parley.common.messaging.IntroQueue.progress]). */
    fun introProgress(res: Resources, q: app.parley.common.messaging.IntroQueue): String =
        if (q.finished) res.getString(R.string.main_done) else res.getString(R.string.intro_progress, q.index + 1, q.targets.size)

    /** "Opened 9 chats · skipped 2" ([app.parley.common.messaging.IntroQueue.summary]). */
    fun introSummary(res: Resources, q: app.parley.common.messaging.IntroQueue): String = buildList {
        add(res.getQuantityString(R.plurals.intro_opened, q.opened.size, q.opened.size))
        if (q.skipped.isNotEmpty()) add(res.getQuantityString(R.plurals.intro_skipped, q.skipped.size, q.skipped.size))
        val left = if (q.stopped) q.targets.size - q.index - (if (q.index in q.opened || q.index in q.skipped) 1 else 0) else 0
        if (left > 0) add(res.getQuantityString(R.plurals.intro_not_reached, left, left))
    }.joinToString(res.getString(R.string.main_separator))

    /** "Never" or "After 30 days" ([app.parley.common.MessagedRecord.expiryLabel]). */
    fun expiryLabel(res: Resources, days: Int): String =
        if (days <= 0) res.getString(R.string.rec_never) else res.getQuantityString(R.plurals.rec_after_days, days, days)

    /** "Install or enable WhatsApp" ([MessengerLinks.unavailableMessage]). */
    fun installOrEnable(res: Resources, app: MessengerApp): String = res.getString(R.string.msg_install_or_enable, app.label)
}
