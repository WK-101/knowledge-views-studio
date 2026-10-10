package app.parley.ui.drive

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.AutoAnswer
import app.parley.common.calls.CarDevice
import app.parley.common.calls.DriveProfile
import app.parley.common.calls.DriveProfileConfig
import app.parley.data.Permissions
import app.parley.telecom.CarAudio
import app.parley.ui.LinkRow
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.SwitchRow
import app.parley.ui.rowColors
import app.parley.ui.startOrSay

/**
 * Settings › Calls › Drive profile: mark the car's Bluetooth, then choose what happens while it is connected.
 * The list shows paired devices (Android 12+, with "Nearby devices") and the Bluetooth audio devices connected now,
 * so on Android 10 and 11 the car is picked while connected to it. No location, nothing in the background.
 */
@Composable
fun DriveProfileScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val store = vm.c.driveProfile
    val cfg by store.config.collectAsStateWithLifecycle()
    // Re-read the devices when the screen comes back (from Bluetooth settings, the permission dialog, the car).
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val needsPermission = remember(refresh) { Build.VERSION.SDK_INT >= 31 && !Permissions.has(context, Manifest.permission.BLUETOOTH_CONNECT) }
    val rows = remember(cfg, refresh) { DriveProfile.devices(cfg, pairedDevices(context), CarAudio.connected(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    fun set(f: (DriveProfileConfig) -> DriveProfileConfig) = store.update(transform = f)

    SettingsScaffold(stringResource(R.string.set_drive_profile_title), back) {
        Text(
            stringResource(R.string.drive_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        SegmentedGroup(stringResource(R.string.drive_group_car)) {
            rows.forEach { row -> item("car_${row.device.address}") { DeviceRow(row) { on -> set { DriveProfile.mark(it, row.device, on) } } } }
            if (needsPermission) {
                // Matched by name alone, a common name ("Car Multimedia") could be any car or headphones.
                val common = cfg.cars.firstOrNull { DriveProfile.genericName(it.name) }
                item("nearby") {
                    LinkRow(
                        stringResource(if (common != null) R.string.drive_nearby_needed else R.string.drive_allow_nearby),
                        if (common != null) stringResource(R.string.drive_nearby_needed_sub, common.name) else stringResource(R.string.drive_allow_nearby_sub),
                        Icons.Rounded.BluetoothSearching,
                    ) {
                        permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
            }
            if (rows.isEmpty() && !needsPermission) {
                item("none") { ParleyListItem(colors = rowColors(), headlineContent = { Text(stringResource(R.string.drive_no_devices)) }) }
            }
            item("bluetooth") {
                LinkRow(
                    stringResource(R.string.drive_open_bluetooth), stringResource(R.string.drive_open_bluetooth_sub), Icons.Rounded.Bluetooth, external = true,
                ) {
                    context.startOrSay(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }
        }
        WhileConnected(cfg, ::set)
    }
}

/** A device with a checkbox: "Use My Golf as my car", and whether it is connected now or no longer paired. */
@Composable
private fun DeviceRow(row: DriveProfile.DeviceRow, onMark: (Boolean) -> Unit) {
    val desc = stringResource(R.string.drive_mark_desc, row.device.name)
    val status = when {
        row.connected -> stringResource(R.string.drive_connected_now)
        row.marked && !row.paired -> stringResource(R.string.drive_not_paired)
        else -> null
    }
    ParleyListItem(
        modifier = Modifier.toggleable(row.marked, role = Role.Checkbox, onValueChange = onMark).semantics { contentDescription = desc },
        colors = rowColors(),
        leadingContent = { Icon(if (row.marked) Icons.Rounded.DirectionsCar else Icons.Rounded.Bluetooth, null) },
        headlineContent = { Text(row.device.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = status?.let { { Text(it) } },
        trailingContent = { Checkbox(row.marked, onCheckedChange = null) },
    )
}

/** What happens while the car is connected; off (and explained) until a car is marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhileConnected(cfg: DriveProfileConfig, set: ((DriveProfileConfig) -> DriveProfileConfig) -> Unit) {
    val on = cfg.enabled
    val answerSub = stringResource(R.string.drive_answer_sub)
    SegmentedGroup(stringResource(R.string.drive_group_while)) {
        if (!on) item("mark_first") { ParleyListItem(colors = rowColors(), headlineContent = { Text(stringResource(R.string.drive_mark_first)) }) }
        item("announce") {
            SwitchRow(
                stringResource(R.string.drive_announce), stringResource(R.string.drive_announce_sub), cfg.announce, Icons.Rounded.RecordVoiceOver, on,
            ) { v -> set { it.copy(announce = v) } }
        }
        item("answer_favourites") {
            SwitchRow(stringResource(R.string.drive_answer_favourites), answerSub, cfg.answerFavourites, Icons.Rounded.Star, on) { v ->
                set { it.copy(answerFavourites = v) }
            }
        }
        item("answer_chosen") {
            SwitchRow(
                stringResource(R.string.drive_answer_chosen), stringResource(R.string.drive_answer_chosen_sub), cfg.answerChosen, Icons.Rounded.People, on,
            ) { v -> set { it.copy(answerChosen = v) } }
        }
        if (cfg.answerFavourites || cfg.answerChosen) {
            item("answer_after") {
                ParleyListItem(
                    colors = rowColors(),
                    headlineContent = { Text(stringResource(R.string.drive_answer_after), modifier = Modifier.semantics { heading() }) },
                    supportingContent = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            AutoAnswer.SECONDS_CHOICES.forEach { s ->
                                FilterChip(
                                    selected = cfg.answerSeconds == s,
                                    enabled = on,
                                    onClick = { set { it.copy(answerSeconds = s) } },
                                    label = { Text(pluralStringResource(R.plurals.set_auto_answer_seconds, s, s)) },
                                )
                            }
                        }
                    },
                )
            }
        }
        item("silence_unknown") {
            SwitchRow(
                stringResource(R.string.drive_silence_unknown), stringResource(R.string.drive_silence_unknown_sub), cfg.silenceUnknown,
                Icons.Rounded.NotificationsOff, on,
            ) { v -> set { it.copy(silenceUnknown = v) } }
        }
        item("replies") { ParleyListItem(colors = rowColors(), headlineContent = { Text(stringResource(R.string.drive_replies_note)) }) }
    }
}

/** Bonded Bluetooth devices by name and address; empty without "Nearby devices" (Android 12+) or on Android 10–11. */
@SuppressLint("MissingPermission") // Checked just before; a refusal is caught.
private fun pairedDevices(context: Context): List<CarDevice> {
    if (Build.VERSION.SDK_INT < 31 || !Permissions.has(context, Manifest.permission.BLUETOOTH_CONNECT)) return emptyList()
    return runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
            .mapNotNull { d -> d.address?.let { a -> CarDevice(a, d.name?.takeIf { it.isNotBlank() } ?: a) } }
    }.getOrDefault(emptyList())
}

/** "On for My Golf", or "Off" (Settings › Calls). */
@Composable
internal fun driveSummary(cfg: DriveProfileConfig): String {
    if (!cfg.enabled) return stringResource(R.string.set_off)
    return stringResource(R.string.drive_summary_cars, cfg.cars.joinToString(stringResource(R.string.main_separator)) { it.name })
}
