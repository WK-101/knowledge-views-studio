package app.parley.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.BuildConfigInfo
import app.parley.R
import app.parley.common.SettingsCategory
import app.parley.common.ux.WhatsNew
import app.parley.ui.Routes

/** This build's version code, and whether this version is the first one installed on the device. */
private fun versionInfo(context: Context): Pair<Int, Boolean> = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.longVersionCode.toInt() to (info.firstInstallTime == info.lastUpdateTime)
}.getOrDefault(0 to true)

/**
 * U6: "What's new" once per update, as a card at the top of home that the user dismisses (never a screen in the
 * way). The layout promise comes first: an update never changes the tab order, the start tab or the call list;
 * anything new arrives switched off and "Try it" opens where it can be turned on.
 */
@Composable
fun WhatsNewCard(vm: AppViewModel, open: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ux by vm.c.ux.state.collectAsStateWithLifecycle()
    val (version, fresh) = remember { versionInfo(context) }
    val decision = WhatsNew.decide(ux.whatsNewSeen, version, fresh)
    // A fresh install has nothing "new": remember this version quietly.
    LaunchedEffect(decision) { if (decision == WhatsNew.Decision.MARK_SEEN) vm.c.ux.setWhatsNewSeen(version) }
    if (decision != WhatsNew.Decision.SHOW) return
    // S1/S2 (v3.3): the combine options are offered once, here, and only switched on from Settings (never automatically).
    val settings by vm.settings.collectAsStateWithLifecycle()
    val offerLayout = app.parley.common.ux.Tips.LAYOUT_OFFER !in ux.seenTips && !settings.surfaces.merged
    fun seen() {
        vm.c.ux.setWhatsNewSeen(version)
        if (offerLayout) vm.c.ux.dismissTip(app.parley.common.ux.Tips.LAYOUT_OFFER)
    }
    Card(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.ux_whats_new_title, BuildConfigInfo.versionName(context)), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.ux_whats_new_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            if (offerLayout) {
                Text(stringResource(R.string.surf_whats_new_layout), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
                TextButton({ seen(); open(Routes.settingsPage(SettingsCategory.APPEARANCE, "calls_layout")) }) { Text(stringResource(R.string.surf_whats_new_layout_action)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ seen(); open(Routes.settingsPage(SettingsCategory.APPEARANCE, "nav_tabs")) }) { Text(stringResource(R.string.ux_whats_new_try)) }
                TextButton(::seen) { Text(stringResource(R.string.ux_tip_got_it)) }
            }
        }
    }
}
