package app.parley.security

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import app.parley.R
import app.parley.common.security.PinRules
import app.parley.common.security.PinVerdict
import app.parley.container
import app.parley.ui.ParleyDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The lock screen's PIN field. The Parley PIN and the duress PIN behave exactly alike here (same wait, same
 * screen after), so nobody watching can tell which was typed. Wrong PINs wait as [app.parley.common.security.PinBackoff]
 * says; the field is never kept in saved state. With a Parley PIN only a PIN opens Parley, duress PIN or not, so
 * this screen is the same either way (no fingerprint button that comes and goes).
 */
@Composable
internal fun PinUnlock(autoFocus: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val state = remember { PinUnlockState() }
    LaunchedEffect(Unit) { state.waitFor(activity.container.appPin.waitNow()) }
    LaunchedEffect(state.waitUntil) { state.tick() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus, state.waiting) { if (autoFocus && !state.waiting) runCatching { focus.requestFocus() } }
    val submit = { state.submit(activity) }
    OutlinedTextField(
        state.pin, state::type,
        Modifier.focusRequester(focus).widthIn(max = 280.dp),
        enabled = state.editable,
        singleLine = true,
        label = { Text(stringResource(R.string.pin_field_label)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        isError = state.wrong && !state.waiting,
        textStyle = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
        supportingText = pinMessage(state)?.let { m -> { Text(m, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
    )
    Spacer(Modifier.height(8.dp))
    Button(submit, enabled = state.canSubmit) {
        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.lock_unlock))
    }
}

/** What the field says under it: the wait's countdown, or that the PIN didn't work. */
@Composable
private fun pinMessage(state: PinUnlockState): String? = when {
    state.waiting -> stringResource(R.string.pin_wait, DateUtils.formatElapsedTime((state.waitUntil - state.now + 999) / 1000))
    state.wrong -> stringResource(R.string.pin_wrong)
    else -> null
}

/** The lock screen's PIN field: the digits (memory only), a check running, a wrong try and the wait (elapsed time). */
private class PinUnlockState {
    var pin by mutableStateOf("")
    var busy by mutableStateOf(false)
    var wrong by mutableStateOf(false)
    var waitUntil by mutableLongStateOf(0L)
    var now by mutableLongStateOf(SystemClock.elapsedRealtime())

    val waiting: Boolean get() = waitUntil > now
    val editable: Boolean get() = !busy && !waiting
    val canSubmit: Boolean get() = editable && PinRules.valid(pin)

    fun type(v: String) {
        pin = PinRules.normalize(v)
        wrong = false
    }

    fun waitFor(ms: Long) {
        if (ms > 0) waitUntil = SystemClock.elapsedRealtime() + ms
    }

    suspend fun tick() {
        while (SystemClock.elapsedRealtime() < waitUntil) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
        now = SystemClock.elapsedRealtime()
    }

    fun submit(activity: ComponentActivity) {
        if (!canSubmit) return
        busy = true
        wrong = false
        AppLock.unlockWithPin(activity, pin) { a ->
            busy = false
            pin = ""
            if (a.verdict == PinVerdict.WRONG) {
                wrong = true
                waitFor(a.waitMs)
            }
        }
    }
}

/**
 * Asks for a new PIN twice. [check] says why a PIN can't be used (null: it can), before the second step; [save] stores
 * it and says whether that worked. The digits live only in this dialog's memory.
 */
@Composable
internal fun NewPinDialog(
    title: String,
    body: String,
    check: suspend (String) -> String?,
    save: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    SensitiveScreen()
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(first) { runCatching { focus.requestFocus() } }

    fun next() {
        if (busy) return
        if (!PinRules.valid(pin)) {
            error = res.getString(R.string.pin_invalid)
            return
        }
        val typed = pin
        busy = true
        scope.launch {
            val f = first
            when {
                f == null -> {
                    val problem = check(typed)
                    if (problem != null) {
                        error = problem
                    } else {
                        first = typed
                        error = null
                    }
                    pin = ""
                }
                f != typed -> {
                    error = res.getString(R.string.pin_mismatch)
                    first = null
                    pin = ""
                }
                save(typed) -> onDismiss()
                else -> error = res.getString(R.string.pin_not_saved)
            }
            busy = false
        }
    }

    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (first == null) title else stringResource(R.string.pin_confirm_title)) },
        text = {
            Column {
                if (first == null) Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    pin, { v -> pin = PinRules.normalize(v); error = null },
                    Modifier.focusRequester(focus),
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.pin_field_new)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { next() }),
                    isError = error != null,
                    textStyle = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
                    supportingText = error?.let { e -> { Text(e, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
                )
            }
        },
        confirmButton = {
            Button(::next, enabled = !busy && PinRules.valid(pin)) {
                Text(stringResource(if (first == null) R.string.pin_next else R.string.pin_save))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}
