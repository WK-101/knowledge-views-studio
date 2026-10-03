package app.parley.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.RecentFilter
import app.parley.common.StartTab
import app.parley.ui.Destination
import app.parley.ui.LinkRow
import app.parley.ui.SegmentedGroup
import app.parley.ui.SwitchRow
import app.parley.ui.activityViewModel
import app.parley.ui.home.RecentsViewModel

/**
 * Settings › Calls: the way to Reminders (missed-call re-alert) and voicemail. The pocket-dial guard is
 * [PocketGuardRow]; the proximity sensor switch and "Power button ends call" are on Calls › During calls ([CallFeedbackGroup]).
 */
@Composable
internal fun CallExtrasGroups(vm: AppViewModel, open: (Destination) -> Unit) {
    val recents: RecentsViewModel = activityViewModel()
    val voicemailSub = stringResource(R.string.set_voicemail_sub)
    SegmentedGroup(stringResource(R.string.set_group_missed_voicemail)) {
        // "Remind me of missed calls" is on Reminders, with every other kind.
        remindersLinkRow(open)
        linkRow("voicemail", Icons.Rounded.Voicemail, sub = voicemailSub) {
            recents.filter.value = RecentFilter.VOICEMAIL
            vm.navigate(NavEvent.Tab(StartTab.RECENTS))
        }
    }
}

/** Settings › Calls › Before you call: a favourite, the widget or a shortcut asks first while the phone is covered. */
@Composable
internal fun PocketGuardRow(vm: AppViewModel) {
    val cfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    SwitchRow(
        settingTitle("pocket_guard"), stringResource(R.string.set_pocket_guard_sub), cfg.pocketGuard, Icons.Rounded.PhonelinkLock,
    ) { v -> vm.c.callExtras.update { it.copy(pocketGuard = v) } }
}

/** Settings › Calls › Situations: menu memory, a page of its own. */
@Composable
internal fun PhoneMenusRow(vm: AppViewModel, open: (Destination) -> Unit) {
    val cfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    val menusSub = stringResource(if (cfg.rememberMenuKeys) R.string.phone_menus_on else R.string.phone_menus_off)
    LinkRow(settingTitle("phone_menus"), menusSub, Icons.Rounded.Dialpad) { open(CallsRoutes.PhoneMenus) }
}

/**
 * Calls › During calls: the buzz on call events and when a call connects, the proximity sensor (only for broken
 * sensors) and Android's "Power button ends call".
 */
@Composable
internal fun CallFeedbackGroup(vm: AppViewModel) {
    val context = LocalContext.current
    val cfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    // Android has no intent for "Power button ends call" alone: the Accessibility page opens and the row says where to look.
    val powerEnds = remember { powerButtonEndsCall(context) }
    val proximitySub = stringResource(if (cfg.proximitySensor) R.string.set_proximity_on else R.string.set_proximity_off)
    val powerSub = listOfNotNull(
        when (powerEnds) {
            true -> stringResource(R.string.set_on)
            false -> stringResource(R.string.set_off)
            null -> null
        },
        stringResource(R.string.set_power_button_sub),
    ).joinToString(". ")
    SegmentedGroup(stringResource(R.string.set_group_call_feedback)) {
        item("call_haptics") { CallHapticsRow(vm, Icons.Rounded.Vibration) }
        item("connect_haptic") { ConnectHapticRow(vm, Icons.Rounded.Vibration) }
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
