package app.parley.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.RecentFilter
import app.parley.common.StartTab
import app.parley.common.calls.MissedReAlert
import app.parley.ui.SegmentedGroup

/**
 * Settings › Calls additions of v3.1: the pocket-dial guard (V8), missed-call re-alert (V3), voicemail (V1), the
 * proximity sensor switch (V6) and the link to Android's "Power button ends call" (V10).
 */
@Composable
internal fun CallExtrasGroups(vm: AppViewModel) {
    val context = LocalContext.current
    val cfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    // Android has no intent for "Power button ends call" alone: the Accessibility page opens and the row says where to look.
    val powerEnds = remember { powerButtonEndsCall(context) }
    SegmentedGroup("Missed calls and voicemail") {
        val choices = MissedReAlert.CHOICES
        menuRow(
            "missed_realert", choices.map { if (it == 0) "Off" else "Every $it minutes" }, choices.indexOf(cfg.missedReAlertMinutes).coerceAtLeast(0),
            Icons.Rounded.NotificationsActive,
            sub = if (cfg.missedReAlertMinutes == 0) entry("missed_realert").summary
            else "Every ${cfg.missedReAlertMinutes} min for up to 3 hours, never during Do Not Disturb. Stops when you open Recents.",
        ) { i -> vm.c.callExtras.update { it.copy(missedReAlertMinutes = choices[i]) } }
        linkRow("voicemail", Icons.Rounded.Voicemail, sub = "In Recents › Voicemail") {
            vm.recentFilter.value = RecentFilter.VOICEMAIL
            vm.navigate(NavEvent.Tab(StartTab.RECENTS))
        }
    }
    SegmentedGroup("During calls") {
        switchRow(
            "pocket_guard", cfg.pocketGuard, Icons.Rounded.PhonelinkLock,
            sub = "A favourite, the direct-dial widget or a shortcut asks first while the proximity sensor is covered",
        ) { v -> vm.c.callExtras.update { it.copy(pocketGuard = v) } }
        switchRow(
            "proximity_sensor", cfg.proximitySensor, Icons.Rounded.Sensors,
            sub = if (cfg.proximitySensor) "The screen turns off at your ear so your cheek can't press buttons. Turn off if the sensor is broken or the screen goes dark in your pocket."
            else "Off: the screen stays on during earpiece calls. Be careful not to press buttons with your cheek.",
        ) { v -> vm.c.callExtras.update { it.copy(proximitySensor = v) } }
        linkRow(
            "power_button_ends_call", Icons.Rounded.Accessibility, external = true,
            sub = listOfNotNull(
                when (powerEnds) {
                    true -> "On"
                    false -> "Off"
                    null -> null
                },
                "Opens Android's Accessibility settings: look for “Power button ends call” (under System controls or Interaction controls on some phones).",
            ).joinToString(". "),
        ) { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
    }
}

/** Android's "Power button ends call" (a secure setting: 2 = hang up); null when it can't be read. */
private fun powerButtonEndsCall(context: Context): Boolean? = runCatching {
    when (Settings.Secure.getInt(context.contentResolver, "incall_power_button_behavior", -1)) {
        2 -> true
        1 -> false
        else -> null
    }
}.getOrNull()
