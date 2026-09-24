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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
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
    val choices = MissedReAlert.CHOICES
    val choiceLabels = choices.map { if (it == 0) stringResource(R.string.set_off) else pluralStringResource(R.plurals.set_every_minutes, it, it) }
    val reAlertSub = if (cfg.missedReAlertMinutes == 0) null else stringResource(R.string.set_missed_realert_on, cfg.missedReAlertMinutes)
    val voicemailSub = stringResource(R.string.set_voicemail_sub)
    val pocketSub = stringResource(R.string.set_pocket_guard_sub)
    val proximitySub = stringResource(if (cfg.proximitySensor) R.string.set_proximity_on else R.string.set_proximity_off)
    val powerSub = listOfNotNull(
        when (powerEnds) {
            true -> stringResource(R.string.set_on)
            false -> stringResource(R.string.set_off)
            null -> null
        },
        stringResource(R.string.set_power_button_sub),
    ).joinToString(". ")
    SegmentedGroup(stringResource(R.string.set_group_missed_voicemail)) {
        menuRow(
            "missed_realert", choiceLabels, choices.indexOf(cfg.missedReAlertMinutes).coerceAtLeast(0),
            Icons.Rounded.NotificationsActive,
            sub = reAlertSub,
        ) { i -> vm.c.callExtras.update { it.copy(missedReAlertMinutes = choices[i]) } }
        linkRow("voicemail", Icons.Rounded.Voicemail, sub = voicemailSub) {
            vm.recentFilter.value = RecentFilter.VOICEMAIL
            vm.navigate(NavEvent.Tab(StartTab.RECENTS))
        }
    }
    SegmentedGroup(stringResource(R.string.set_group_during_calls)) {
        switchRow(
            "pocket_guard", cfg.pocketGuard, Icons.Rounded.PhonelinkLock,
            sub = pocketSub,
        ) { v -> vm.c.callExtras.update { it.copy(pocketGuard = v) } }
        switchRow(
            "proximity_sensor", cfg.proximitySensor, Icons.Rounded.Sensors,
            sub = proximitySub,
        ) { v -> vm.c.callExtras.update { it.copy(proximitySensor = v) } }
        linkRow(
            "power_button_ends_call", Icons.Rounded.Accessibility, external = true,
            sub = powerSub,
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
