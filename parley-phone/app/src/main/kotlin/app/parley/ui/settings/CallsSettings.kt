package app.parley.ui.settings

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
import app.parley.ui.calltime.reminderText
import app.parley.ui.calltime.reminderTextInline

/** Settings › Calls: haptics on call events. */
@Composable
fun CallHapticsRow(vm: AppViewModel, icon: ImageVector? = null) {
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    SwitchRow(
        settingTitle("call_haptics"),
        stringResource(R.string.set_call_haptics_sub),
        config.haptics, icon,
    ) { v -> vm.c.calling.update { it.copy(haptics = v) } }
}

/** P7: Settings › Calls: the buzz when a call connects (answer and decline always have their own). */
@Composable
fun ConnectHapticRow(vm: AppViewModel, icon: ImageVector? = null) {
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    SwitchRow(
        settingTitle("connect_haptic"), settingSummary("connect_haptic"),
        config.haptics && config.connectHaptic, icon, enabled = config.haptics,
    ) { v -> vm.c.calling.update { it.copy(connectHaptic = v) } }
}

/** Settings › Call time: reminders and limits, with a one-line summary of what's on. */
@Composable
fun CallTimeRow(vm: AppViewModel, open: (String) -> Unit, icon: ImageVector? = null) {
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
    LinkRow(settingTitle("call_time"), summary, icon) { open(Routes.CALL_TIME) }
}

/** Settings › Keypad: saved USSD replies. */
@Composable
fun UssdRow(vm: AppViewModel, icon: ImageVector? = null) {
    var ussd by remember { mutableStateOf(false) }
    LinkRow(settingTitle("ussd"), settingSummary("ussd"), icon) { ussd = true }
    if (ussd) UssdHistoryDialog(vm) { ussd = false }
}
