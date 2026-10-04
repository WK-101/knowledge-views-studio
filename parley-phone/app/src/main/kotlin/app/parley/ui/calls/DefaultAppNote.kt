package app.parley.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ux.DefaultAppFeature
import app.parley.common.ux.DefaultAppNeeds
import app.parley.data.Permissions
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing

/** What [feature] needs, in words. */
fun defaultAppText(feature: DefaultAppFeature): Int = when (feature) {
    DefaultAppFeature.VOICEMAIL -> R.string.default_app_voicemail
    DefaultAppFeature.CALL_SCREEN -> R.string.default_app_call_screen
    DefaultAppFeature.PRIVATE_CALLER -> R.string.default_app_private_caller
    DefaultAppFeature.CALL_FACTS -> R.string.default_app_call_facts
    DefaultAppFeature.SIM_RULES -> R.string.default_app_sim_rules
    DefaultAppFeature.BLOCKING -> R.string.default_app_blocking
}

/**
 * Said in place, on a feature's own screen or row, when it needs Parley as the default phone app and Parley isn't:
 * one line and "Make Parley the default" (with the by-hand guide when Android refuses without asking). Nothing
 * while Parley is the default. [inset] false drops the side margin, for dialogs and cards.
 */
@Composable
fun DefaultAppNote(vm: AppViewModel, feature: DefaultAppFeature, modifier: Modifier = Modifier, inset: Boolean = true) {
    val context = LocalContext.current
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val screener = remember(isDefault) { feature.screeningIsEnough && Permissions.isCallScreener(context) }
    if (!DefaultAppNeeds.noteShown(feature, isDefault, screener)) return
    val request = rememberDialerRoleRequest { vm.refreshEnvironment() }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = ParleyShapes.card,
        modifier = modifier.fillMaxWidth().padding(horizontal = if (inset) Spacing.listInset else 0.dp, vertical = Spacing.xs),
    ) {
        // Text above the button, so a long line and a large font never squeeze either.
        Column(Modifier.padding(start = Spacing.l, end = Spacing.xs, top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(end = Spacing.s)) {
                Icon(Icons.Rounded.Info, null, Modifier.padding(top = 2.dp, end = Spacing.m).size(20.dp))
                Text(stringResource(defaultAppText(feature)), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(request, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.default_app_make)) }
        }
    }
}
