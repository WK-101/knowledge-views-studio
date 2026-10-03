package app.parley.jobs

import android.content.Context
import app.parley.R
import app.parley.common.ExplainedFailure
import app.parley.common.UserError

/** The words for what went wrong ([UserError]), never the exception's own message. */
object UserErrorText {
    fun of(context: Context, error: Throwable): String {
        val id = when (UserError.of(error)) {
            UserError.EXPLAINED -> return explained(error)
            UserError.NO_SPACE -> R.string.user_error_no_space
            UserError.FILE_GONE -> R.string.user_error_file_gone
            UserError.NO_ACCESS -> R.string.user_error_no_access
            UserError.LOCKED -> R.string.user_error_locked
            UserError.DAMAGED -> R.string.user_error_damaged
            UserError.UNKNOWN -> R.string.user_error_unknown
        }
        return context.getString(id)
    }

    private fun explained(error: Throwable): String {
        var e: Throwable? = error
        while (e != null && e !is ExplainedFailure) e = e.cause
        return e?.message.orEmpty()
    }
}
