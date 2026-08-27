package com.todocompanion.app.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Wraps the app in a biometric / device-credential gate when app-lock is enabled. Fully local —
 * uses the platform BiometricManager; no network, no account. Re-locks whenever the app is stopped.
 */
/** Pick the strongest authenticator class the device can actually satisfy: Class-3 (STRONG) biometric or
 *  device credential first, then Class-2 (WEAK), then credential alone. Returns null only when the device
 *  has NO lock at all — the one case we can't enforce. (Upgrades the old WEAK-only default per the audit.) */
private fun pickAuthenticators(context: android.content.Context): Int? {
    val bm = BiometricManager.from(context)
    val options = listOf(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    )
    return options.firstOrNull { bm.canAuthenticate(it) == BiometricManager.BIOMETRIC_SUCCESS }
}

@Composable
fun AppLockGate(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    // If we can't host a prompt (shouldn't happen — MainActivity is a FragmentActivity), fail open rather
    // than trapping the user out of their own data.
    if (activity == null) { content(); return }

    val authenticators = remember { pickAuthenticators(context) }
    // No device lock at all → nothing to authenticate against. We can't enforce a gate, but rather than
    // silently passing we show the lock screen with guidance so the user knows their opt-in isn't active.
    if (authenticators == null) { NoCredentialLockScreen(onOpen = { }, content = content); return }

    var unlocked by remember { mutableStateOf(false) }
    var attempting by remember { mutableStateOf(false) }

    fun prompt() {
        if (attempting) return
        attempting = true
        val executor = ContextCompat.getMainExecutor(context)
        val bp = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                attempting = false; unlocked = true
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                attempting = false
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock ToDo Companion")
            .setSubtitle("Verify it's you to continue")
            .setAllowedAuthenticators(authenticators)
            .build()
        runCatching { bp.authenticate(info) }.onFailure { attempting = false }
    }

    // Re-lock on stop; prompt on (re)start while locked.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> unlocked = false
                Lifecycle.Event.ON_START -> if (!unlocked) prompt()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { if (!unlocked) prompt() }

    if (unlocked) content() else LockScreen(onUnlock = { prompt() })
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("Verify it's you to open ToDo Companion.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)) { Text("Unlock") }
        }
    }
}

/** Shown when app-lock is on but the device has no screen lock to authenticate against. Rather than
 *  silently passing (the old fail-open), it tells the user their opt-in can't be enforced yet. */
@Composable
private fun NoCredentialLockScreen(onOpen: () -> Unit, content: @Composable () -> Unit) {
    var opened by remember { mutableStateOf(false) }
    if (opened) { content(); return }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("App lock needs a screen lock", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Set a device PIN, pattern, password, or biometric in system Settings for this lock to take effect. Until then the app can't be secured.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { onOpen(); opened = true }, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)) { Text("Open anyway") }
        }
    }
}
