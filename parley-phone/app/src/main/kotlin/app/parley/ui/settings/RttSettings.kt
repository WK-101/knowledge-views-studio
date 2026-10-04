package app.parley.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.ui.LinkRow
import app.parley.ui.SegmentedGroup
import app.parley.ui.Spacing
import app.parley.ui.startOrSay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Calls › Accessibility (L3): "Answer with RTT", Android's own TTY and RTT settings, and an honest line on
 * where RTT works. RTT itself needs no setting: More › Switch to RTT shows in a call whenever the SIM offers it.
 */
@Composable
internal fun RttSettingsGroup(vm: AppViewModel) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Asking Telecom is a call into the system: off the main thread. Null while unknown.
    val offered by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { rttOffered(context) } }
    val unavailable = stringResource(R.string.set_rtt_unavailable)
    val about = stringResource(R.string.set_rtt_about)
    val systemTitle = stringResource(R.string.set_rtt_system_title)
    val systemSub = stringResource(R.string.set_rtt_system_summary)
    SegmentedGroup(stringResource(R.string.set_group_call_accessibility)) {
        switchRow("answer_rtt", s.answerWithRtt, Icons.Rounded.Keyboard, sub = if (offered == false) unavailable else null) { v ->
            scope.launch { vm.c.settings.update { it.copy(answerWithRtt = v) } }
        }
        item("rtt_system") {
            LinkRow(systemTitle, systemSub, Icons.Rounded.Hearing, external = true) {
                context.startOrSay(Intent(TelecomManager.ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS))
            }
        }
        item("rtt_about") {
            Text(
                about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.listInset, vertical = Spacing.m),
            )
        }
    }
}

/** Whether any of this phone's calling accounts offers RTT now (false when it can't be read). */
@SuppressLint("MissingPermission")
private fun rttOffered(context: Context): Boolean = runCatching {
    val tm = context.getSystemService(TelecomManager::class.java) ?: return@runCatching false
    tm.callCapablePhoneAccounts.any { h -> tm.getPhoneAccount(h)?.hasCapabilities(PhoneAccount.CAPABILITY_RTT) == true }
}.getOrDefault(false)
