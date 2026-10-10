package app.parley.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.ui.Destination
import app.parley.ui.SegmentedGroup
import app.parley.ui.drive.DriveRoutes
import app.parley.ui.drive.driveSummary

/** Settings › Calls › On the road: the drive profile, a screen of its own. */
@Composable
internal fun OnTheRoadGroup(vm: AppViewModel, open: (Destination) -> Unit) {
    val drive by vm.c.driveProfile.config.collectAsStateWithLifecycle()
    val driveSub = driveSummary(drive)
    SegmentedGroup(stringResource(R.string.set_group_on_the_road)) {
        linkRow("drive_profile", Icons.Rounded.DirectionsCar, sub = driveSub) { open(DriveRoutes.Profile) }
    }
}

/**
 * SIMs & plan minutes › Abroad: assisted dialling and the local-SIM suggestion, both on by default since they
 * only ever act while a SIM is abroad.
 */
@Composable
fun AbroadSettingsGroup(vm: AppViewModel) {
    val roaming by vm.c.roaming.config.collectAsStateWithLifecycle()
    SegmentedGroup(stringResource(R.string.set_group_abroad)) {
        switchRow("assisted_dialling", roaming.assistedDialling, Icons.Rounded.Public) { v -> vm.c.roaming.update { it.copy(assistedDialling = v) } }
        switchRow("local_sim_hint", roaming.localSimHint, Icons.Rounded.SimCard) { v -> vm.c.roaming.update { it.copy(localSimHint = v) } }
    }
}
