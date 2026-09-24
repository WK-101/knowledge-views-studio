package app.parley.ui.people

import android.content.res.Resources
import app.parley.R
import app.parley.common.people.Handle
import app.parley.common.people.HandleProblem
import app.parley.common.people.HandleService

/** I1: localised texts for messenger handles. Service names that are brands stay as they are. */
object HandleText {
    /** Name of a service in menus: "Matrix", "Signal username", "Other"… */
    fun service(res: Resources, s: HandleService): String = when (s) {
        HandleService.SIGNAL -> res.getString(R.string.handle_signal)
        HandleService.THREEMA -> res.getString(R.string.handle_threema)
        HandleService.SIP -> res.getString(R.string.handle_sip)
        HandleService.OTHER -> res.getString(R.string.handle_other)
        else -> s.label
    }

    /** Label of one handle: the service, or an unknown service's own name ("Jami"), or "Handle". */
    fun label(res: Resources, h: Handle): String =
        if (h.service == HandleService.OTHER) h.customProtocol?.takeIf { it.isNotBlank() } ?: res.getString(R.string.handle_generic)
        else service(res, h.service)

    /** What to type, shown under the field. */
    fun hint(res: Resources, s: HandleService): String = res.getString(
        when (s) {
            HandleService.MATRIX -> R.string.handle_hint_matrix
            HandleService.SIGNAL -> R.string.handle_hint_signal
            HandleService.TELEGRAM -> R.string.handle_hint_telegram
            HandleService.THREEMA -> R.string.handle_hint_threema
            HandleService.DISCORD -> R.string.handle_hint_discord
            HandleService.XMPP -> R.string.handle_hint_xmpp
            HandleService.SIP -> R.string.handle_hint_sip
            HandleService.SKYPE -> R.string.handle_hint_skype
            HandleService.WIRE -> R.string.handle_hint_wire
            HandleService.SESSION -> R.string.handle_hint_session
            HandleService.SIMPLEX -> R.string.handle_hint_simplex
            HandleService.MASTODON -> R.string.handle_hint_mastodon
            HandleService.AIM -> R.string.handle_hint_aim
            HandleService.YAHOO -> R.string.handle_hint_yahoo
            HandleService.QQ -> R.string.handle_hint_qq
            HandleService.ICQ -> R.string.handle_hint_icq
            HandleService.GOOGLE_TALK, HandleService.MSN, HandleService.NETMEETING -> R.string.handle_hint_address
            HandleService.OTHER -> R.string.handle_hint_other
        },
    )

    /** Why a value doesn't look right (shown under the field). Saving is never blocked. */
    fun problem(res: Resources, p: HandleProblem): String = res.getString(
        when (p) {
            HandleProblem.TELEGRAM_NAME -> R.string.handle_problem_telegram
            HandleProblem.THREEMA_ID -> R.string.handle_problem_threema
            HandleProblem.MATRIX_SERVER -> R.string.handle_problem_matrix
            HandleProblem.NEEDS_SERVER -> R.string.handle_problem_server
            HandleProblem.SIGNAL_NAME -> R.string.handle_problem_signal
        },
    )
}
