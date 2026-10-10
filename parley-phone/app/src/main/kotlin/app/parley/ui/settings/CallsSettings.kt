package app.parley.ui.settings

import app.parley.ui.Destination
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.AppViewModel
import app.parley.calltime.CallTimePlanner
import app.parley.common.calltime.LimitScope
import app.parley.ui.Routes
import app.parley.ui.calltime.UssdHistoryDialog
import app.parley.ui.calltime.reminderTextInline
import app.parley.ui.MenuRow
import app.parley.common.ux.CallVibration
import app.parley.ui.LinkRow

/**
 * Calls › During calls: "Vibrate during calls", one choice (Off · Ends, swaps and merges · Also when they answer) for
 * the call-event buzz and the buzz when a call connects. Answer and decline always keep their own.
 */
@Composable
fun CallVibrationRow(vm: AppViewModel, icon: ImageVector? = null) {
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    val current = CallVibration.of(config.haptics, config.connectHaptic)
    // In the order of CallVibration.
    val choices = listOf(stringResource(R.string.dc_off), stringResource(R.string.set_call_haptics_changes), stringResource(R.string.set_call_haptics_answer))
    MenuRow(settingTitle("call_haptics"), choices, current.ordinal, icon, stringResource(R.string.set_call_haptics_sub)) { i ->
        val v = CallVibration.entries[i]
        vm.c.calling.update { it.copy(haptics = v.haptics, connectHaptic = v.onConnect) }
    }
}

/** Settings › Calls › Situations: talk-time reminders and limits, with a one-line summary of what's on. */
@Composable
fun CallTimeRow(vm: AppViewModel, open: (Destination) -> Unit, icon: ImageVector? = null) {
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    val global = config.rule(LimitScope.GLOBAL, "")
    val context = LocalContext.current
    val summary = listOfNotNull(
        stringResource(R.string.set_call_time_reminders, reminderTextInline(context, config.reminders.everyMinutes)),
        when {
            global != null -> stringResource(R.string.set_call_time_all_calls, CallTimePlanner.allowanceText(context, global))
            config.rules.isNotEmpty() -> pluralStringResource(R.plurals.set_call_time_limits, config.rules.size, config.rules.size)
            else -> stringResource(R.string.set_call_time_no_limits)
        },
        stringResource(R.string.set_call_time_supervised).takeIf { config.supervised },
    ).joinToString(" · ")
    LinkRow(settingTitle("call_time"), summary, icon) { open(Routes.CallTime) }
}

/** Settings › Keypad: saved USSD replies. */
@Composable
fun UssdRow(vm: AppViewModel, icon: ImageVector? = null) {
    var ussd by remember { mutableStateOf(false) }
    LinkRow(settingTitle("ussd"), settingSummary("ussd"), icon) { ussd = true }
    if (ussd) UssdHistoryDialog(vm) { ussd = false }
}
