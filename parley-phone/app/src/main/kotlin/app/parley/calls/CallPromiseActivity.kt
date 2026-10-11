package app.parley.calls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.lifecycleScope
import app.parley.R
import app.parley.common.calls.CallbackBy
import app.parley.common.calls.CallbackWatch
import app.parley.common.calls.PromiseChoice
import app.parley.common.calls.PromiseOption
import app.parley.common.calls.PromiseTimes
import app.parley.common.catching
import app.parley.container
import app.parley.security.LockedActivity
import app.parley.telecom.ui.RemindTimes
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.ParleyTheme
import app.parley.ui.Spacing
import app.parley.ui.SwitchRow
import app.parley.ui.common.ProvideAppKit
import app.parley.ui.systemMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Date
import java.util.TimeZone

/**
 * "I'll call you at…" (Promise a time) and "They'll call me back" for one number, as a small sheet: from a declined
 * call, the missed-call reminder sheet, the post-call card and Parley's own "Remind me to call". It names no one, so it
 * needs no more than the app's own lock. Promising saves the To call item first, then opens the messaging app with the
 * time written out; Parley sends nothing itself.
 */
class CallPromiseActivity : LockedActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() } ?: return finish()
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val callback = intent.getStringExtra(EXTRA_MODE) == MODE_CALLBACK
        // Secure until the settings say otherwise.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            val settings = container.settings.current()
            if (!settings.secureScreen) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val now = System.currentTimeMillis()
            val mine = ZoneId.systemDefault()
            val theirs = CallPromises.theirZone(this@CallPromiseActivity, number, accountId)
            val options = PromiseTimes.options(now, mine, theirs, CallPromises.freeAt(container, now))
            setContent {
                ParleyTheme(settings.themeMode, settings.amoledBlack, settings.dynamicColor, settings.density) {
                    ProvideAppKit {
                        ParleySheet(
                            onDismissRequest = { finish() },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            title = stringResource(if (callback) R.string.callback_title else R.string.promise_title),
                        ) {
                            if (callback) {
                                CallbackChoices(now) { by, any -> expect(number, accountId, by, any) }
                            } else {
                                PromiseChoices(options, now, PromiseTimes.saidIn(theirs, mine, now)) { at, saidIn -> promise(number, accountId, at, saidIn) }
                            }
                            Spacer(Modifier.navigationBarsPadding().padding(bottom = Spacing.l))
                        }
                    }
                }
            }
        }
    }

    /** A choice was made (once; a second tap while it saves does nothing). */
    private var picking = false

    private fun promise(number: String, accountId: String?, at: Long, saidIn: ZoneId?) {
        if (picking) return
        picking = true
        val app = applicationContext
        lifecycleScope.launch {
            // Saved first, so the reminder exists whether or not the message is sent.
            val text = withContext(NonCancellable) { CallPromises.promise(app, number, accountId, at, saidIn) }
            if (text == null) {
                systemMessage(app, getString(R.string.to_call_remind_failed))
            } else {
                systemMessage(app, getString(R.string.promise_set, RemindTimes.whenText(app, at)))
                CallReasons.textFirst(this@CallPromiseActivity, number, text)
            }
            finish()
        }
    }

    private fun expect(number: String, accountId: String?, by: Long, anyCaller: Boolean) {
        if (picking) return
        picking = true
        val app = applicationContext
        lifecycleScope.launch {
            val set = withContext(NonCancellable) {
                catching { CallPromises.expectCallback(app, container, number, accountId, by, anyCaller) }.getOrDefault(false)
            }
            val said = if (set) getString(R.string.callback_set, RemindTimes.whenText(app, by)) else getString(R.string.to_call_remind_failed)
            systemMessage(app, said, long = set)
            finish()
        }
    }

    companion object {
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_MODE = "mode"
        private const val MODE_CALLBACK = "callback"

        /** "I'll call you at…" for [number]; [callback]: "They'll call me back" instead. */
        fun intent(context: Context, number: String, accountId: String?, callback: Boolean = false): Intent =
            Intent(context, CallPromiseActivity::class.java)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                .apply { if (callback) putExtra(EXTRA_MODE, MODE_CALLBACK) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** The promise times: each as it will be said to them, with why it's offered (and your own time when theirs differs). */
@Composable
private fun PromiseChoices(options: List<PromiseOption>, now: Long, saidIn: ZoneId?, onPick: (Long, ZoneId?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (saidIn != null) {
        val f = DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(saidIn) }
        Text(
            stringResource(R.string.promise_their_clock, f.format(Date(now))), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
        )
    }
    options.forEach { o ->
        val said = CallPromises.message(context, o.at, now, saidIn)
        ParleyListItem(
            headlineContent = { Text(said) },
            supportingContent = { Text(promiseWhy(context, o, saidIn)) },
            leadingContent = { Icon(Icons.Rounded.Schedule, null) },
            modifier = Modifier.clickable(role = Role.Button) { onPick(o.at, saidIn) },
        )
    }
}

/** "In about an hour", "When your Situation ends", "Early evening for them · 23:00 for you". */
private fun promiseWhy(context: Context, o: PromiseOption, saidIn: ZoneId?): String {
    val why = context.getString(
        when (o.choice) {
            PromiseChoice.WHEN_FREE -> R.string.promise_why_free
            PromiseChoice.IN_AN_HOUR -> R.string.promise_why_hour
            PromiseChoice.THIS_EVENING -> R.string.promise_why_evening
            PromiseChoice.THEIR_EVENING -> R.string.promise_why_their_evening
            PromiseChoice.TOMORROW_MORNING -> R.string.promise_why_morning
        },
    )
    return if (saidIn == null) why else context.getString(R.string.promise_why_yours, why, RemindTimes.whenText(context, o.at))
}

/** By when they'll call, and whether numbers nobody saved may ring meanwhile. */
@Composable
private fun CallbackChoices(now: Long, onPick: (Long, Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var any by rememberSaveable { mutableStateOf(false) }
    Text(
        stringResource(R.string.callback_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
    )
    SwitchRow(stringResource(R.string.callback_any_number), stringResource(R.string.callback_any_number_body), any) { any = it }
    CallbackWatch.choices(now, ZoneId.systemDefault()).forEach { (by, at) ->
        ParleyListItem(
            headlineContent = { Text(stringResource(byLabel(by))) },
            supportingContent = {
                Text(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL))
            },
            leadingContent = { Icon(Icons.Rounded.Event, null) },
            modifier = Modifier.clickable(role = Role.Button) { onPick(at, any) },
        )
    }
}

private fun byLabel(by: CallbackBy): Int = when (by) {
    CallbackBy.TODAY -> R.string.callback_by_today
    CallbackBy.TOMORROW -> R.string.callback_by_tomorrow
    CallbackBy.FRIDAY -> R.string.callback_by_friday
    CallbackBy.IN_A_WEEK -> R.string.callback_by_week
}
