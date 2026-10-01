package app.parley.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.ExpectedSource
import app.parley.ui.Destination
import app.parley.ui.LinkRow
import app.parley.ui.SegmentedGroup
import app.parley.ui.family.ExpectedHintsDialog
import app.parley.ui.family.FamilyRoutes

/** Settings › Calls › Family safety: the helpers "Add my helper" can call into a call (I5). */
@Composable
internal fun FamilySafetyCallsGroup(vm: AppViewModel, open: (Destination) -> Unit) {
    val summary by vm.c.familySafety.summary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.c.familySafety.load() }
    val names = summary.helpers.joinToString(stringResource(R.string.main_separator)) { it.name }.ifEmpty { stringResource(R.string.helpers_none) }
    SegmentedGroup(stringResource(R.string.set_group_family_safety)) {
        linkRow("call_helpers", Icons.Rounded.GroupAdd, sub = names) { open(FamilyRoutes.Helpers) }
    }
}

/** Settings › Privacy & security › Family safety: where the safe words are (I4). */
@Composable
internal fun FamilySafetyPrivacyGroup(open: (Destination) -> Unit) {
    SegmentedGroup(stringResource(R.string.set_group_family_safety)) {
        linkRow("family_safe_word", Icons.Rounded.FamilyRestroom) { open(FamilyRoutes.SafeWords) }
    }
}

/** Settings › Blocking & spam › "Expecting a call from your notes" (I7): "Off", or the kinds that are on. */
@Composable
internal fun ExpectedHintsRow(vm: AppViewModel) {
    val summary by vm.c.familySafety.summary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.c.familySafety.load() }
    var editing by rememberSaveable { mutableStateOf(false) }
    val on = listOfNotNull(
        stringResource(R.string.expected_from_note).takeIf { summary.consents[ExpectedSource.NOTE] == true },
        stringResource(R.string.expected_from_to_call).takeIf { summary.consents[ExpectedSource.TO_CALL] == true },
        stringResource(R.string.expected_from_delivery).takeIf { summary.consents[ExpectedSource.DELIVERY_QR] == true },
    )
    LinkRow(
        settingTitle("expected_hints"),
        if (on.isEmpty()) settingSummary("expected_hints") else on.joinToString(stringResource(R.string.main_separator)),
        Icons.Rounded.EventAvailable,
    ) { editing = true }
    if (editing) ExpectedHintsDialog(vm) { editing = false }
}
