package app.parley.security

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.common.catching
import app.parley.common.security.PinRules
import app.parley.common.security.PinVerdict
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.security.AppPinStore
import app.parley.data.security.Concealment
import app.parley.data.security.LockTransitions
import app.parley.ui.ParleyDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Confirm it's you" inside Parley (turning the app lock off, applying restored safety settings, deleting everything,
 * showing a safe word…). With a Parley PIN set it asks for that PIN: the phone's fingerprint or screen lock would let
 * anyone who knows the phone's code (or a sleeping partner's finger) past a confirmation the Parley PIN is meant to
 * guard, and it would undo a duress PIN. Without a Parley PIN it is the system prompt, as before.
 */
object PinConfirm {
    /** A confirmation waiting for its PIN; [PinConfirmHost] shows it. */
    class Request(val title: String, val onResult: (Boolean) -> Unit)

    private val _request = MutableStateFlow<Request?>(null)
    val request = _request.asStateFlow()

    fun ask(title: String, onResult: (Boolean) -> Unit) {
        _request.value?.onResult?.invoke(false)
        _request.value = Request(title, onResult)
    }

    internal fun finish(r: Request, ok: Boolean) {
        if (_request.value === r) _request.value = null
        r.onResult(ok)
    }

    /**
     * Whether a PIN is asked for now: a Parley PIN is set as the screens see it (in a duress session where someone
     * turned the PIN "off", the screens show none, so neither does this).
     */
    fun asksPin(shown: AppPinStore.Summary?, real: AppPinStore.Summary?): Boolean = (shown ?: real)?.pinSet ?: true

    /**
     * A right PIN in a confirmation counts like one on the lock screen when it changes what is hidden: the duress PIN
     * typed after a normal unlock starts the hiding, the Parley PIN typed in a duress session ends it. Otherwise
     * nothing else changes (no vault closed, no session reset) for a plain confirmation.
     */
    internal suspend fun accepted(c: DataContainer, attempt: AppPinStore.Attempt) {
        val hiding = Concealment.hiding
        val switches = (attempt.verdict == PinVerdict.DURESS && !hiding) || (attempt.verdict == PinVerdict.NORMAL && hiding)
        if (switches) LockTransitions.pinEntered(c, attempt)
    }
}

/** Shows [PinConfirm]'s waiting confirmation; placed once in each activity that confirms things. */
@Composable
fun PinConfirmHost() {
    val r by PinConfirm.request.collectAsStateWithLifecycle()
    r?.let { PinConfirmDialog(it) }
}

/** The confirmation's field: the digits (memory only), a check running, a wrong try and the wait (elapsed time). */
private class PinConfirmState {
    var pin by mutableStateOf("")
    var busy by mutableStateOf(false)
    var wrong by mutableStateOf(false)
    var waitUntil by mutableLongStateOf(0L)
    var now by mutableLongStateOf(SystemClock.elapsedRealtime())

    val waiting: Boolean get() = waitUntil > now
    val canSubmit: Boolean get() = !busy && !waiting && PinRules.valid(pin)

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

    /** Checks the PIN; a right one (either PIN) confirms, as on the lock screen. */
    suspend fun check(c: DataContainer, r: PinConfirm.Request) {
        busy = true
        val a = c.appPin.check(pin)
        busy = false
        pin = ""
        if (a.verdict == PinVerdict.WRONG) {
            wrong = true
            waitFor(a.waitMs)
        } else {
            PinConfirm.accepted(c, a)
            PinConfirm.finish(r, true)
        }
    }
}

@Composable
private fun confirmMessage(state: PinConfirmState): String? = when {
    state.waiting -> stringResource(R.string.pin_wait, DateUtils.formatElapsedTime((state.waitUntil - state.now + 999) / 1000))
    state.wrong -> stringResource(R.string.pin_wrong)
    else -> null
}

@Composable
private fun PinConfirmDialog(r: PinConfirm.Request) {
    SensitiveScreen()
    val activity = LocalActivity.current as? FragmentActivity ?: return
    val c = activity.container
    val scope = rememberCoroutineScope()
    val state = remember(r) { PinConfirmState() }
    LaunchedEffect(r) { state.waitFor(c.appPin.waitNow()) }
    LaunchedEffect(state.waitUntil) { state.tick() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.waiting) { if (!state.waiting) catching { focus.requestFocus() } }
    fun submit() {
        if (state.canSubmit) scope.launch { state.check(c, r) }
    }
    ParleyDialog(
        onDismissRequest = { PinConfirm.finish(r, false) },
        title = { Text(r.title) },
        text = {
            Column {
                Text(stringResource(R.string.pin_confirm_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    state.pin, { v -> state.pin = PinRules.normalize(v); state.wrong = false },
                    Modifier.focusRequester(focus),
                    enabled = !state.busy && !state.waiting,
                    singleLine = true,
                    label = { Text(stringResource(R.string.pin_field_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isError = state.wrong && !state.waiting,
                    textStyle = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
                    supportingText = confirmMessage(state)?.let { m -> { Text(m, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
                )
            }
        },
        confirmButton = { TextButton(::submit, enabled = state.canSubmit) { Text(stringResource(R.string.pin_confirm_button)) } },
        dismissButton = { TextButton({ PinConfirm.finish(r, false) }) { Text(stringResource(R.string.dc_cancel)) } },
    )
}
