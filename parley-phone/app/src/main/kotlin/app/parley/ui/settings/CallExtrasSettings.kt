package app.parley.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.ScreenRotation
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
import app.parley.common.calls.ScreenAtEar
import app.parley.common.calls.SpeakerDefault
import app.parley.ui.SegmentedGroup
import app.parley.ui.SegmentedGroupScope
import app.parley.ui.SwitchRow
import app.parley.ui.activityViewModel
import app.parley.ui.home.RecentsViewModel
import app.parley.ui.startOrSay

/**
 * Settings › Calls: the way to Reminders (missed-call re-alert) and voicemail. The pocket-dial guard is
 * [PocketGuardRow]; the proximity sensor is on Calls › During calls ([CallSpeakerGroup]) and "Power button ends call" under its Advanced ([CallFeedbackGroup]).
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
 * Calls › During calls › Advanced: the buzz on call events and when a call connects, and Android's "Power button ends
 * call".
 */
@Composable
internal fun CallFeedbackGroup(vm: AppViewModel) {
    val context = LocalContext.current
    // Android has no intent for "Power button ends call" alone: the Accessibility page opens and the row says where to look.
    val powerEnds = remember { powerButtonEndsCall(context) }
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
        linkRow(
            "power_button_ends_call", Icons.Rounded.Accessibility, external = true,
            sub = powerSub,
        ) { context.startOrSay(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
}

/** Calls › During calls: turning the screen off at your ear (off only for broken sensors), a row of the speaker group. */
private fun SegmentedGroupScope.screenAtEarRow(vm: AppViewModel, proximity: ScreenAtEar.Mode, sub: String, choices: List<String>) =
    menuRow("proximity_sensor", choices, proximity.ordinal, Icons.Rounded.Sensors, sub = sub) { i ->
        val m = ScreenAtEar.Mode.entries[i]
        vm.c.callExtras.update { it.copy(proximitySensor = m != ScreenAtEar.Mode.OFF, proximityOnceAnswered = m == ScreenAtEar.Mode.ONCE_ANSWERED) }
    }

/**
 * Calls › During calls › Speaker: "Start calls on speaker" (never, always, or numbers not in your contacts). It only
 * ever replaces the earpiece and never switches an emergency call ([SpeakerOnStart]). The screen at your ear is here too.
 */
@Composable
internal fun CallSpeakerGroup(vm: AppViewModel) {
    val cfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    val proximity = ScreenAtEar.mode(cfg.proximitySensor, cfg.proximityOnceAnswered)
    val proximitySub = stringResource(
        when (proximity) {
            ScreenAtEar.Mode.OFF -> R.string.set_proximity_off
            ScreenAtEar.Mode.DURING_CALLS -> R.string.set_proximity_on
            ScreenAtEar.Mode.ONCE_ANSWERED -> R.string.set_proximity_once_answered_sub
        },
    )
    // In the order of ScreenAtEar.Mode.
    val proximityChoices = listOf(
        stringResource(R.string.set_off), stringResource(R.string.set_proximity_during), stringResource(R.string.set_proximity_once_answered),
    )
    // In the order of SpeakerDefault.
    val choices = listOf(stringResource(R.string.set_speaker_never), stringResource(R.string.set_speaker_always), stringResource(R.string.set_speaker_unknown))
    val sub = stringResource(if (cfg.speakerDefault == SpeakerDefault.OFF) R.string.set_speaker_off_sub else R.string.set_speaker_sub)
    SegmentedGroup(stringResource(R.string.set_group_call_audio)) {
        menuRow("speaker_default", choices, cfg.speakerDefault.ordinal, Icons.AutoMirrored.Rounded.VolumeUp, sub = sub) { i ->
            vm.c.callExtras.update { it.copy(speakerDefault = SpeakerDefault.entries[i]) }
        }
        // One row for "proximity only after answering" too, rather than a second switch.
        screenAtEarRow(vm, proximity, proximitySub, proximityChoices)
    }
}

/** Calls › Answering: "Flip to silence" (off by default), a row of the incoming-calls group. */
@Composable
internal fun FlipToSilenceRow(vm: AppViewModel) {
    val cfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    SwitchRow(settingTitle("flip_to_silence"), stringResource(R.string.set_flip_sub), cfg.flipToSilence, Icons.Rounded.ScreenRotation) { v ->
        vm.c.callExtras.update { it.copy(flipToSilence = v) }
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
