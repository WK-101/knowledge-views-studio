package app.parley.blocking

import android.content.Context
import app.parley.R
import app.parley.data.DialWarning

/**
 * Localises the English texts core/data produces for the dial guard ([DialWarning]) and for failed call placement
 * (`PlaceResult.Failed.reason`), at display time. core/data stays free of Android resources; a text this doesn't
 * recognise (an exception message, a warning a feature already localised) is shown as it is.
 */
object DialText {
    private const val SPAM_SUFFIX = ". Scam lines often charge you for calling back."
    private val ruleBody = Regex("^'(.*)' blocks calls from this number\\.$", RegexOption.DOT_MATCHES_ALL)

    fun warning(context: Context, w: DialWarning): DialWarning {
        fun s(id: Int) = context.getString(id)
        return when (w.title) {
            "Premium-rate number" -> w.copy(title = s(R.string.blk_dial_premium_title), body = s(R.string.blk_dial_premium_body))
            "Shared-cost number" -> w.copy(title = s(R.string.blk_dial_shared_title), body = s(R.string.blk_dial_shared_body))
            "Matches your block rule" -> w.copy(
                title = s(R.string.blk_dial_rule_title),
                body = ruleBody.find(w.body)?.let { context.getString(R.string.blk_dial_rule_body, it.groupValues[1]) } ?: w.body,
            )
            "Listed as spam" -> w.copy(
                title = s(R.string.blk_dial_spam_title),
                body = if (w.body.endsWith(SPAM_SUFFIX)) context.getString(R.string.blk_dial_spam_body, w.body.removeSuffix(SPAM_SUFFIX)) else w.body,
            )
            "Don't call back?" -> w.copy(
                title = s(R.string.blk_dial_wangiri_title),
                body = s(if (" from abroad" in w.body) R.string.blk_dial_wangiri_abroad_body else R.string.blk_dial_wangiri_body),
            )
            else -> w
        }
    }

    fun placeFailure(context: Context, reason: String): String = when (reason) {
        "Empty number" -> context.getString(R.string.blk_place_empty)
        "Phone permission missing" -> context.getString(R.string.blk_place_no_permission)
        "Could not place call" -> context.getString(R.string.blk_place_failed)
        "Voicemail unavailable" -> context.getString(R.string.blk_place_voicemail)
        else -> reason
    }
}
