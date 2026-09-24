package app.parley.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.calltime.CallTimePlanner
import app.parley.common.calltime.LimitScope
import app.parley.ui.Routes
import app.parley.ui.calltime.UssdHistoryDialog
import app.parley.ui.calltime.reminderText

/** Call-time, haptics and USSD rows of Settings › Calls. */
@Composable
fun CallsSettingsRows(vm: AppViewModel, open: (String) -> Unit) {
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    var ussd by remember { mutableStateOf(false) }
    Column {
        SwitchRow("Vibrate on call events", "When a call connects, ends, is swapped or merged, and before a time limit. Not in silent mode.", config.haptics) { v ->
            vm.c.calling.update { it.copy(haptics = v) }
        }
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
        LinkRow("Call time: reminders & limits", summary) { open(Routes.CALL_TIME) }
        LinkRow("USSD replies", "Balance checks and other codes like *100#, kept on this phone") { ussd = true }
    }
    if (ussd) UssdHistoryDialog(vm) { ussd = false }
}
