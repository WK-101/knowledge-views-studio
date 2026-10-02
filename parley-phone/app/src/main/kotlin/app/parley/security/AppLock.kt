package app.parley.security

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import app.parley.common.calls.EmergencyPolicy
import kotlinx.coroutines.flow.first
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import app.parley.container
import app.parley.data.EmergencyNumbers
import app.parley.data.PlaceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Emergency
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.parley.common.AppSettings
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleyDialog
import androidx.lifecycle.lifecycleScope
import app.parley.common.security.DuressMachine
import app.parley.data.security.LockTransitions
import app.parley.data.security.AppPinStore
import app.parley.data.security.Concealment

/**
 * App lock for Parley's own screens. The in-call screen is a separate activity and is never
 * locked, so incoming calls and caller names always show.
 */
object AppLock {
    /** Starts locked; [onStart] clears it once the stored settings say the lock is off. */
    val locked = MutableStateFlow(true)
    private var backgroundAt = 0L
    private var everUnlocked = false

    /**
     * Android 11+: a strong biometric (strong enough to also unlock time-bound Keystore keys, the vault) or the screen
     * lock. The platform's own BiometricPrompt is used (minSdk 29 has it), which keeps AndroidX's biometric library
     * and the AppCompat it brings out of a Compose-only app.
     */
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * Whether the system prompt can run: on Android 11+ for a strong biometric or the screen lock; on Android 10 only
     * for an enrolled biometric (the screen lock alone goes through the keyguard's confirmation, see [authenticate]).
     */
    private fun promptStatus(context: Context): Int {
        val bm = context.getSystemService(BiometricManager::class.java) ?: return BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(AUTHENTICATORS) else bm.canAuthenticate()
    }

    /** A biometric or the screen lock can confirm it's you. */
    fun canAuthenticate(activity: FragmentActivity): Boolean =
        promptStatus(activity) == BiometricManager.BIOMETRIC_SUCCESS ||
            (Build.VERSION.SDK_INT < 30 && activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true)

    /** The settings seen last, so leaving the app can lock without waiting for storage. */
    @Volatile
    private var lastSettings: AppSettings? = null

    /**
     * Leaving Parley. With "lock immediately" the lock engages now, so the screen behind the recents thumbnail
     * and the next return are already locked; longer timeouts are decided in [onStart].
     */
    fun onStop(settings: AppSettings? = null) {
        backgroundAt = SystemClock.elapsedRealtime()
        val s = settings ?: lastSettings ?: return
        if (s.appLock && s.lockAfterMinutes <= 0) engage()
    }

    /** Call before the first frame of a returning activity (it only reads memory), so content never flashes. */
    fun onStart(settings: AppSettings) {
        lastSettings = settings
        promptOnShow = true
        if (!settings.appLock) {
            locked.value = false
            return
        }
        val away = SystemClock.elapsedRealtime() - backgroundAt
        if (!everUnlocked || away >= settings.lockAfterMinutes * 60_000L) engage()
    }

    /**
     * The screen went off. With the app lock on, what was opened for the session (private contacts' details) is
     * forgotten at once, whatever the timeout: the lock itself still engages as the timeout says.
     */
    fun onScreenOff() {
        if (lastSettings?.appLock == true) onLock?.invoke()
    }

    /** Every lock goes through here, so what was opened for the session is always forgotten with it. */
    private fun engage() {
        locked.value = true
        onLock?.invoke()
        onEngaged?.invoke()
    }

    /** Runs whenever Parley locks: what was opened for the session (private contacts' details) is forgotten. */
    @Volatile var onLock: (() -> Unit)? = null

    /**
     * Runs when the lock itself engages (not when the screen only goes off): a duress session ends here, while what it
     * hides stays hidden until the real Parley PIN (I21).
     */
    @Volatile var onEngaged: (() -> Unit)? = null

    fun lockNow() = engage()

    /**
     * Whether the lock screen asks for the fingerprint as soon as it shows. Off after "Lock now": you locked
     * Parley on purpose, so it waits for you to tap Unlock.
     */
    @Volatile
    var promptOnShow = true
        private set

    /** "Lock now" from Parley's own menu. */
    fun lockNowByUser() {
        promptOnShow = false
        engage()
    }

    private fun unlocked() {
        locked.value = false
        everUnlocked = true
    }

    /**
     * The fingerprint or screen lock succeeded. It opens Parley unless a Parley PIN is set: then only a PIN does, with
     * or without a duress PIN (I21, M7: "use your fingerprint" would undo the duress PIN, and offering it only without
     * one would say which). [then] runs after the decision either way: the confirmation itself succeeded.
     */
    private fun unlockedByDevice(activity: FragmentActivity, then: () -> Unit) {
        val pins = activity.container.appPin
        fun decide(summary: AppPinStore.Summary) {
            DuressMachine.otherUnlock(Concealment.state.value, pinRequired = !summary.deviceUnlocks)?.let { next ->
                Concealment.move(next)
                unlocked()
            }
            then()
        }
        val known = pins.summary.value
        if (known != null) decide(known) else activity.lifecycleScope.launch { decide(pins.load()) }
    }

    /**
     * I21: a PIN typed on the lock screen. The Parley PIN opens everything; the duress PIN opens a duress session, which
     * looks the same. [onResult] gets the attempt (a wrong PIN, or how long to wait) after Parley has opened.
     */
    fun unlockWithPin(activity: FragmentActivity, pin: String, onResult: (AppPinStore.Attempt) -> Unit) {
        val c = activity.container
        activity.lifecycleScope.launch {
            val attempt = c.appPin.check(pin)
            if (LockTransitions.pinEntered(c, attempt)) unlocked()
            onResult(attempt)
        }
    }

    /**
     * Shows the system prompt. Failed attempts are allowed; only cancel/error keeps the lock. It lets the user in
     * without asking only when the phone has no screen lock at all (the app lock can't work then, and mustn't trap
     * anyone). When the biometric stack reports anything else (hardware busy or unknown, an update required), the
     * screen lock is confirmed instead.
     */
    fun authenticate(activity: FragmentActivity, title: String? = null, onResult: (Boolean) -> Unit = {}) {
        val status = promptStatus(activity)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            val km = activity.getSystemService(KeyguardManager::class.java)
            if (km?.isDeviceSecure != true) {
                unlockedByDevice(activity) { onResult(true) }
                return
            }
            Log.w("AppLock", "Biometric prompt unavailable ($status); confirming the screen lock instead")
            return confirmCredential(activity, title ?: activity.getString(R.string.lock_unlock_parley)) { ok ->
                if (ok) {
                    VaultSession.markAuthenticated()
                    unlockedByDevice(activity) { onResult(true) }
                } else {
                    onResult(false)
                }
            }
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title ?: activity.getString(R.string.lock_unlock_parley))
            .apply {
                // The screen lock is always offered too, so no negative button (the system shows "Use PIN" etc.).
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(AUTHENTICATORS) else setDeviceCredentialAllowed(true)
            }
            .build()
        prompt.authenticate(
            CancellationSignal(), ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    VaultSession.markAuthenticated()
                    unlockedByDevice(activity) { onResult(true) }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
    }

    /**
     * Unlocks the vault's time-bound Keystore key. On Android 10 BiometricPrompt can only offer weak biometrics
     * with the device credential, and weak biometrics don't unlock Keystore keys, so it confirms the screen lock
     * through the keyguard instead.
     */
    fun authenticateForVault(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) return authenticate(activity, activity.getString(R.string.lock_unlock_private), onResult)
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (km?.isDeviceSecure != true) {
            onResult(true)
            return
        }
        confirmCredential(activity, activity.getString(R.string.lock_unlock_private)) { ok ->
            if (ok) VaultSession.markAuthenticated()
            onResult(ok)
        }
    }

    /** The keyguard's own "confirm your PIN, pattern or password" screen. False when it can't be shown. */
    private fun confirmCredential(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        @Suppress("DEPRECATION")
        val intent = activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, null)
        if (intent == null) {
            onResult(false)
            return
        }
        var launcher: ActivityResultLauncher<Intent>? = null
        launcher = activity.activityResultRegistry.register("confirm-credential-${SystemClock.elapsedRealtime()}", ActivityResultContracts.StartActivityForResult()) { r ->
            launcher?.unregister()
            onResult(r.resultCode == Activity.RESULT_OK)
        }
        launcher.launch(intent)
    }

    /**
     * What the window may show outside Parley: with the app lock on, the recents thumbnail is blank (Android 13+
     * skips it; before that FLAG_SECURE blanks it while locked or [leaving]), and "Hide screen content" keeps
     * FLAG_SECURE on all the time.
     */
    fun protectWindow(activity: Activity, settings: AppSettings, leaving: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!settings.appLock)
        val hideWhileLocked = settings.appLock && (locked.value || (leaving && Build.VERSION.SDK_INT < 33))
        applySecureFlag(activity, settings.secureScreen || hideWhileLocked)
    }

    fun applySecureFlag(activity: Activity, secure: Boolean) {
        if (secure) activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/**
 * Runs a vault operation; if the vault key needs a fresh unlock, asks for it once and retries.
 * Other failures go to [onError].
 */
fun CoroutineScope.launchVault(activity: FragmentActivity?, onError: (Exception) -> Unit, block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultCrypto.LockedException) {
            if (activity == null) return@launch onError(e)
            AppLock.authenticateForVault(activity) { ok ->
                if (!ok) return@authenticateForVault onError(e)
                launch {
                    try {
                        block()
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (e2: Exception) {
                        onError(e2)
                    }
                }
            }
        } catch (e: Exception) {
            onError(e)
        }
    }
}

/** Tracks when the user last proved presence (for vault details). */
object VaultSession {
    private var authAt = 0L

    /** Runs after each successful authentication: the moment a stronger vault key can be put in place. */
    @Volatile var onAuthenticated: (() -> Unit)? = null

    fun markAuthenticated() {
        authAt = SystemClock.elapsedRealtime()
        onAuthenticated?.invoke()
    }
    fun recentlyAuthenticated(windowMs: Long = 5 * 60_000L) = authAt > 0 && SystemClock.elapsedRealtime() - authAt < windowMs
}

/**
 * [emergencyNumber]: an emergency number another app handed to Parley (Android turns a third-party emergency call
 * into a dial request for the phone app). The emergency keypad then opens with it and the unlock prompt doesn't
 * start on its own; while [checkingEmergency], the prompt waits for that answer.
 */
@Composable
fun LockScreen(emergencyNumber: String? = null, checkingEmergency: Boolean = false, onUnlock: () -> Unit) {
    SensitiveScreen()
    val pins = LocalContext.current.container.appPin
    // I21: whether a Parley PIN unlocks (null while the small record is read; nothing is offered until then).
    val pin by pins.summary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { pins.load() }
    val handedOver by rememberUpdatedState(emergencyNumber)
    val checking by rememberUpdatedState(checkingEmergency)
    LaunchedEffect(Unit) {
        snapshotFlow { checking }.first { !it }
        // With a Parley PIN its field takes the focus instead of the system prompt.
        val usesPin = snapshotFlow { pin }.filterNotNull().first().pinSet
        if (AppLock.promptOnShow && handedOver == null && !usesPin) onUnlock()
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).imePadding().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.lock_locked), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.lock_calls_show), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(24.dp))
                val p = pin
                when {
                    p == null -> Spacer(Modifier.height(48.dp))
                    p.pinSet -> PinUnlock(autoFocus = emergencyNumber == null)
                    else -> Button(onUnlock) { Text(stringResource(R.string.lock_unlock)) }
                }
                // Parley is the phone app: its lock must never stand between the user and an emergency call.
                var emergency by remember(emergencyNumber) { mutableStateOf(emergencyNumber != null) }
                TextButton({ emergency = true }, Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Rounded.Emergency, null, Modifier.size(18.dp))
                    Text("  " + stringResource(R.string.lock_emergency_call))
                }
                if (emergency) EmergencyDialog(emergencyNumber.orEmpty()) { emergency = false }
            }
        }
    }
}

/**
 * A keypad that calls only numbers the platform recognises as emergency numbers (Android has no public way to open
 * the system's emergency dialer). The call goes through Telecom on whichever SIM or network can carry it.
 */
@Composable
private fun EmergencyDialog(initial: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf(keypadDigits(initial)) }
    var isEmergency by remember { mutableStateOf(false) }
    LaunchedEffect(number) { isEmergency = withContext(Dispatchers.IO) { EmergencyNumbers.isEmergency(context, number.trim()) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ParleyDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Emergency, null) },
        title = { Text(stringResource(R.string.lock_emergency_call)) },
        text = {
            Column {
                OutlinedTextField(
                    number, { v -> number = keypadDigits(v) },
                    Modifier.focusRequester(focus),
                    singleLine = true,
                    label = { Text(stringResource(R.string.lock_emergency_number)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr),
                )
                Text(
                    stringResource(R.string.lock_emergency_only), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(
                {
                    val n = number.trim()
                    scope.launch {
                        val placed = withContext(Dispatchers.IO) { EmergencyNumbers.isEmergency(context, n) } &&
                            context.container.placer.call(n, null, simResolved = true) is PlaceResult.Placed
                        if (!placed) EmergencyDialer.openSystemDialer(context, n)
                        onDismiss()
                    }
                },
                enabled = isEmergency,
            ) { Text(stringResource(R.string.main_call)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

/** What the emergency keypad accepts, with native digits (e.g. "١١٢") read as ASCII so the check and the call see them. */
private fun keypadDigits(v: String): String = EmergencyPolicy.asciiDigits(v).filter { it in '0'..'9' || it in "+*#" }

object EmergencyDialer {
    /** When Parley can't place the call itself: the system dialer (never Parley's own keypad, which is behind the lock). */
    fun openSystemDialer(context: Context, number: String) {
        val pkg = runCatching { context.getSystemService(TelecomManager::class.java)?.systemDialerPackage }.getOrNull()
            ?.takeIf { it != context.packageName } ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
