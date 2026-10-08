// The pages and their enum live together.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PhoneForwarded
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.SettingsPhone
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.AnswerGesture
import app.parley.common.SettingPlace
import app.parley.common.calls.NetworkName
import app.parley.common.catching
import app.parley.common.ux.CallScreenBackground
import app.parley.common.ux.DefaultAppFeature
import app.parley.ui.calls.DefaultAppNote
import app.parley.ui.Destination
import app.parley.ui.LinkRow
import app.parley.ui.LocalHighlightKey
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.history.HistoryRoutes
import app.parley.ui.situations.AddSituationRow
import app.parley.ui.situations.SituationRow
import app.parley.ui.situations.SituationRoutes
import app.parley.ui.situations.canAdd
import app.parley.ui.startOrSay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import app.parley.ui.ConfirmDialog
import app.parley.ui.SwitchRow
import kotlinx.coroutines.launch

/**
 * Settings › Calls' own pages, so the Calls page itself stays a short list. [place] is where Settings search finds
 * their settings; Situations mostly links to screens of their own, which search opens directly.
 */
enum class CallsSubPage(val place: SettingPlace, val title: Int, val summary: Int, val icon: ImageVector) {
    ANSWERING(SettingPlace.CALLS_ANSWERING, R.string.set_calls_answering_title, R.string.set_calls_answering_summary, Icons.Rounded.PhoneInTalk),
    DURING(SettingPlace.CALLS_DURING, R.string.set_calls_during_title, R.string.set_calls_during_summary, Icons.Rounded.Call),
    SIMS(SettingPlace.CALLS_SIMS, R.string.set_calls_sims_title, R.string.set_calls_sims_summary, Icons.Rounded.SimCard),
    SITUATIONS(SettingPlace.CALLS_SITUATIONS, R.string.set_calls_situations_title, R.string.set_calls_situations_summary, Icons.Rounded.Tune),
    ;

    companion object {
        /** The page by name; an unknown name (an old link) opens Answering. */
        fun of(name: String): CallsSubPage = entries.firstOrNull { it.name == name } ?: ANSWERING

        /** The page holding the settings of [place], or null when the place isn't one of these pages. */
        fun at(place: SettingPlace): CallsSubPage? = entries.firstOrNull { it.place == place }
    }
}

/** Settings › Calls: one row per page of its own. */
@Composable
internal fun CallsSubPageLinks(open: (Destination) -> Unit) {
    SegmentedGroup {
        CallsSubPage.entries.forEach { p ->
            item("calls_${p.name.lowercase()}") {
                LinkRow(stringResource(p.title), stringResource(p.summary), p.icon) { open(CallsRoutes.Page(p.name)) }
            }
        }
    }
}

/** One of Calls' own pages; [focus] is a setting to scroll to and highlight (from search). */
@Composable
internal fun CallsSubPageScreen(vm: AppViewModel, page: CallsSubPage, focus: String?, back: () -> Unit, open: (Destination) -> Unit) {
    CompositionLocalProvider(LocalHighlightKey provides focus) {
        SettingsScaffold(stringResource(page.title), back) {
            // Answering and During calls happen on Parley's call screen: said in place when it isn't the phone app.
            if (page == CallsSubPage.ANSWERING || page == CallsSubPage.DURING) DefaultAppNote(vm, DefaultAppFeature.CALL_SCREEN)
            when (page) {
                CallsSubPage.ANSWERING -> AnsweringPage(vm, open)
                CallsSubPage.DURING -> DuringCallsPage(vm)
                CallsSubPage.SIMS -> SimsCarrierPage(open)
                CallsSubPage.SITUATIONS -> SituationsPage(vm, open)
            }
        }
    }
}

/** Calls › Answering: how a call is answered and what the incoming screen shows, auto-answer and RTT. */
@Composable
private fun AnsweringPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val unknownTonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            set { it.copy(unknownRingtone = uri?.toString()) }
        }
    }
    val gestures = listOf(stringResource(R.string.set_answer_swipe), stringResource(R.string.set_answer_tap))
    val backgrounds = listOf(
        stringResource(R.string.set_call_background_caller), stringResource(R.string.set_call_background_plain),
        stringResource(R.string.set_call_background_poster),
    )
    val sameAsUsual = stringResource(R.string.set_same_as_usual)
    // The ringtone's title comes from the media provider: read it off the main thread.
    val toneName by produceState<String?>(null, s.unknownRingtone) {
        value = s.unknownRingtone?.let { u ->
            withContext(Dispatchers.IO) {
                runCatching { RingtoneManager.getRingtone(context, Uri.parse(u))?.getTitle(context) }.getOrNull()
            }
        }
    }
    SegmentedGroup(stringResource(R.string.set_group_incoming)) {
        choiceRow("answer_gesture", gestures, s.answerGesture.ordinal, Icons.Rounded.TouchApp) { i ->
            set { it.copy(answerGesture = AnswerGesture.entries[i]) }
        }
        linkRow("unknown_ringtone", Icons.Rounded.MusicNote, sub = toneName ?: sameAsUsual) {
            unknownTonePicker.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.unknownRingtone?.let(Uri::parse)),
            )
        }
        switchRow("caller_photo", s.showCallerPhoto, Icons.Rounded.AccountCircle) { v -> set { it.copy(showCallerPhoto = v) } }
        item("network_names") { NetworkNamesRow(vm) }
    }
    AdvancedSection {
        SegmentedGroup {
            // The caller's colour at the top of the call screen, none, or a contact's picture as a poster; a contact's own
            // picture shows with every choice.
            choiceRow("call_background", backgrounds, s.callBackground.ordinal, Icons.Rounded.Palette) { i ->
                set { it.copy(callBackground = CallScreenBackground.entries[i]) }
            }
            item("flip_to_silence") { FlipToSilenceRow(vm) }
        }
        // Auto-answer and the haptic caller ID.
        CallerRingGroup(vm, open)
        // RTT (real-time text): Answer with RTT and Android's TTY and RTT settings.
        RttSettingsGroup(vm)
    }
}

/**
 * "Remember names from the network" (off by default). Turning it off stops keeping and showing them at once, then asks
 * about the names already kept, if there are any: Delete, or Keep for later (also what dismissing the question does).
 * The names kept are the store's (with the copies set aside for an undo) and those kept with screened calls in the
 * blocking log; Delete clears both. When they can't be read the question is asked anyway, and it is not asked once the
 * setting is back on.
 */
@Composable
private fun NetworkNamesRow(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    var askDelete by remember { mutableStateOf(false) }
    // The latest choice made here: DataStore may not have the new value yet when the names have been looked at.
    var wantOn by remember { mutableStateOf<Boolean?>(null) }
    SwitchRow(settingTitle("network_names"), settingSummary("network_names"), s.rememberNetworkNames, Icons.Rounded.Badge) { on ->
        wantOn = on
        set { it.copy(rememberNetworkNames = on) }
        if (on) {
            askDelete = false
        } else {
            scope.launch {
                val kept = withContext(Dispatchers.IO) {
                    val store = catching { vm.c.networkNames.hasAny() }.getOrNull()
                    val log = catching { vm.c.blocks.hasCallerNames() }.getOrNull()
                    if (store == true || log == true) true else if (store == null || log == null) null else false
                }
                askDelete = NetworkName.askToDelete(kept, stillOff = wantOn == false)
            }
        }
    }
    if (askDelete) {
        ConfirmDialog(
            title = stringResource(R.string.set_network_names_off_title),
            text = stringResource(R.string.set_network_names_off_text),
            confirmLabel = stringResource(R.string.set_network_names_delete),
            dismissLabel = stringResource(R.string.set_network_names_keep),
            destructive = true,
            onConfirm = {
                askDelete = false
                // Turned back on while the question showed: the names are wanted again.
                if (wantOn == false) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            catching { vm.c.networkNames.clear() }
                            catching { vm.c.blocks.clearCallerNames() }
                        }
                        vm.toast(res.getString(R.string.set_network_names_deleted))
                    }
                }
            },
            onDismiss = { askDelete = false },
        )
    }
}

/** Calls › During calls: vibration, the screen at your ear, the power button, and notes before and after calls. */
@Composable
private fun DuringCallsPage(vm: AppViewModel) {
    // "Start calls on speaker" and the screen at your ear.
    CallSpeakerGroup(vm)
    AdvancedSection {
        CallFeedbackGroup(vm)
        // The memory prompt, notes on the lock screen and the pre-call peek.
        MemorySettingsGroup(vm)
    }
}

/** Calls › SIMs & carrier: each SIM's plan minutes and options, Android's calling accounts and the carrier's settings. */
@Composable
private fun SimsCarrierPage(open: (Destination) -> Unit) {
    val context = LocalContext.current
    SegmentedGroup {
        linkRow("sims", Icons.Rounded.SimCard) { open(HistoryRoutes.Sims()) }
        linkRow("sim_accounts", Icons.Rounded.SettingsPhone, external = true) { context.startOrSay(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) }
        linkRow("carrier_settings", Icons.AutoMirrored.Rounded.PhoneForwarded, external = true) {
            context.startOrSay(Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS))
        }
    }
}

/**
 * Calls › Situations: the Situations themselves (one tap sets a moment; the first row is where search lands), then
 * helpers, the drive profile, phone menus and call time (once a category of its own), each a screen of its own.
 */
@Composable
private fun SituationsPage(vm: AppViewModel, open: (Destination) -> Unit) {
    Text(
        stringResource(R.string.set_calls_situations_intro), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
    )
    val situations by vm.c.situations.list.collectAsStateWithLifecycle()
    val now by vm.c.situations.state.collectAsStateWithLifecycle()
    SegmentedGroup(stringResource(R.string.sit_group)) {
        // Search for Situations lands on the first one.
        situations.firstOrNull()?.let { first -> item("situations") { SituationRow(vm, first, now, open) } }
        situations.drop(1).forEach { s -> item("situation_${s.id}") { SituationRow(vm, s, now, open) } }
        if (canAdd(situations)) item("situation_add") { AddSituationRow(vm, open) }
    }
    // Rescue call: a believable call to leave a moment, here beside the moments themselves.
    SegmentedGroup {
        item("rescue_call") {
            LinkRow(stringResource(R.string.rescue_title), stringResource(R.string.rescue_row_sub), Icons.Rounded.PhoneInTalk) {
                open(SituationRoutes.RescueCall)
            }
        }
    }
    // Helpers to bring into a call.
    FamilySafetyCallsGroup(vm, open)
    // The drive profile.
    OnTheRoadGroup(vm, open)
    SegmentedGroup {
        item("phone_menus") { PhoneMenusRow(vm, open) }
        item("call_time") { CallTimeRow(vm, open, Icons.Rounded.Timer) }
    }
}
