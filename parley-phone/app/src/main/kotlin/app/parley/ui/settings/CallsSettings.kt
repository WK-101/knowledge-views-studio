package app.parley.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.calltime.CallTimePlanner
import app.parley.common.calltime.LimitScope
import app.parley.ui.Routes
import app.parley.ui.calltime.UssdHistoryDialog
import app.parley.ui.calltime.reminderText

/** Settings › Calls: haptics on call events. */
@Composable
fun CallHapticsRow(vm: AppViewModel, icon: ImageVector? = null) {
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    SwitchRow(
        entry("call_haptics").title,
        "When a call connects, ends, is swapped or merged, and before a time limit. Not in silent mode.",
        config.haptics, icon,
    ) { v -> vm.c.calling.update { it.copy(haptics = v) } }
}

/** Settings › Call time: reminders and limits, with a one-line summary of what's on. */
@Composable
fun CallTimeRow(vm: AppViewModel, open: (String) -> Unit, icon: ImageVector? = null) {
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    val global = config.rule(LimitScope.GLOBAL, "")
    val summary = listOfNotNull(
        "Reminders: " + reminderText(config.reminders.everyMinutes).lowercase(),
        when {
            global != null -> "all calls: " + CallTimePlanner.allowanceText(global)
            config.rules.isNotEmpty() -> "${config.rules.size} limits"
            else -> "no limits"
        },
        "supervised".takeIf { config.supervised },
    ).joinToString(" · ")
    LinkRow(entry("call_time").title, summary, icon) { open(Routes.CALL_TIME) }
}

/** Settings › Keypad: saved USSD replies. */
@Composable
fun UssdRow(vm: AppViewModel, icon: ImageVector? = null) {
    var ussd by remember { mutableStateOf(false) }
    LinkRow(entry("ussd").title, entry("ussd").summary, icon) { ussd = true }
    if (ussd) UssdHistoryDialog(vm) { ussd = false }
}
