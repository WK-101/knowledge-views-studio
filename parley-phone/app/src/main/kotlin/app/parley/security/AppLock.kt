package app.parley.security

import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.parley.common.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * App lock for Parley's own screens. The in-call screen is a separate activity and is never
 * locked, so incoming calls and caller names always show.
 */
object AppLock {
    /** Starts locked; [onStart] clears it once the stored settings say the lock is off. */
    val locked = MutableStateFlow(true)
    private var backgroundAt = 0L
    private var everUnlocked = false

    /** Strong enough to also unlock time-bound Keystore keys (vault). */
    val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun onStop() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onStart(settings: AppSettings) {
        if (!settings.appLock) {
            locked.value = false
            return
        }
        val away = SystemClock.elapsedRealtime() - backgroundAt
        if (!everUnlocked || away >= settings.lockAfterMinutes * 60_000L) locked.value = true
    }

    fun lockNow() {
        locked.value = true
    }

    /** Shows the system prompt. Failed attempts are allowed; only cancel/error keeps the lock. */
    fun authenticate(activity: FragmentActivity, title: String = "Unlock Parley", onResult: (Boolean) -> Unit = {}) {
        if (!canAuthenticate(activity)) {
            // No screen lock set up: the app lock can't work, don't trap the user.
            locked.value = false
            everUnlocked = true
            onResult(true)
            return
        }
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked.value = false
                    everUnlocked = true
                    VaultSession.markAuthenticated()
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    fun applySecureFlag(activity: FragmentActivity, secure: Boolean) {
        if (secure) activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Tracks when the user last proved presence (for vault details). */
object VaultSession {
    private var authAt = 0L
    fun markAuthenticated() {
        authAt = SystemClock.elapsedRealtime()
    }
    fun recentlyAuthenticated(windowMs: Long = 5 * 60_000L) = authAt > 0 && SystemClock.elapsedRealtime() - authAt < windowMs
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Parley is locked", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Incoming calls still show normally.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Button(onUnlock) { Text("Unlock") }
        }
    }
}
