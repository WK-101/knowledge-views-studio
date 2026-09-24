package app.parley.ui.onboarding

import android.Manifest
import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.parley.R

@Composable
fun OnboardingScreen(vm: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()

    fun finish() {
        scope.launch { vm.c.settings.update { it.copy(onboardingDone = true) } }
        vm.refreshEnvironment()
        onDone()
    }

    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshEnvironment()
        if (vm.isDefaultDialer.value) finish() else vm.toast(res.getString(R.string.onb_set_later))
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.onb_tagline), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Promise(Icons.Rounded.WifiOff, stringResource(R.string.onb_offline_title), stringResource(R.string.onb_offline_text))
            Promise(Icons.Rounded.Block, stringResource(R.string.onb_spam_title), stringResource(R.string.onb_spam_text))
            Promise(Icons.Rounded.Code, stringResource(R.string.onb_open_title), stringResource(R.string.onb_open_text))
            Promise(Icons.Rounded.Phone, stringResource(R.string.onb_prompt_title), stringResource(R.string.onb_prompt_text))
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val rm = context.getSystemService(RoleManager::class.java)
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                        role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    } else {
                        finish()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.onb_set_default)) }
            TextButton(
                onClick = {
                    perms.launch(
                        arrayOf(
                            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
                        ),
                    )
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.onb_not_now)) }
        }
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
