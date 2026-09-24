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

@Composable
fun OnboardingScreen(vm: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun finish() {
        scope.launch { vm.c.settings.update { it.copy(onboardingDone = true) } }
        vm.refreshEnvironment()
        onDone()
    }

    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshEnvironment()
        if (vm.isDefaultDialer.value) finish() else vm.toast("You can set this later in Settings")
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("Parley", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("Your phone, contacts and calls — in one private app.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Promise(Icons.Rounded.WifiOff, "Works without internet", "Parley has no internet permission. Nothing you store or call leaves your phone.")
            Promise(Icons.Rounded.Block, "Spam blocking with your rules", "Block hidden numbers, prefixes and patterns — decided on your phone.")
            Promise(Icons.Rounded.Code, "Open source, no ads, no accounts", "Free software under GPL-3.0. No trackers, ever.")
            Promise(Icons.Rounded.Phone, "One system prompt", "Setting Parley as your phone app gives it everything it needs — no permission maze.")
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
            ) { Text("Set as default phone app") }
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
            ) { Text("Not now — just use contacts and calling") }
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
