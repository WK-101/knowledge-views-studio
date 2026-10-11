package app.parley.ui.home

import app.parley.ui.Destination
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.rememberCoroutineScope
import app.parley.common.KeypadFeedback
import app.parley.common.KeypadKeys
import app.parley.common.KeypadNumberActions
import app.parley.common.T9
import app.parley.common.calls.DialTarget
import app.parley.common.calls.CallReason
import app.parley.ui.menus.ReasonTarget
import app.parley.common.ux.Tips
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import app.parley.AppViewModel
import app.parley.DialResult
import app.parley.common.PhoneIdentity
import app.parley.ui.Routes
import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * What the keypad's buttons do with what's typed ([input], matching [results]): a plain call, a call on one SIM or
 * with a reason, and the typed number's own actions (message it, save it). Dialogs open through [show].
 */
@Suppress("LongParameterList") // The keypad's state at this composition, each piece used.
internal class KeypadActions(
    private val vm: AppViewModel,
    private val keypad: KeypadViewModel,
    private val input: String,
    private val results: List<DialResult>,
    private val open: (Destination) -> Unit,
    private val show: (KeypadDialog) -> Unit,
) {
    fun callResult(r: DialResult) = vm.requestCall(r.number, r.contact?.displayName)

    /** Letters typed on a hardware keyboard are a name search, so Call means the best match. */
    fun isTextSearch() = input.any { it.isLetter() }

    private fun nameFor(target: String) =
        results.firstOrNull { it.contact != null && PhoneIdentity.same(it.number, target, vm.countryIso) }?.contact?.displayName

    fun callNow() {
        val n = input.trim()
        if (n.isEmpty()) {
            // Recall the last dialled number, like most dialers.
            keypad.recallLastNumber()
            return
        }
        if (isTextSearch()) {
            results.firstOrNull()?.let(::callResult)
            return
        }
        // The typed number exactly as typed ('#' codes included), never the top match.
        val target = DialTarget.pick(n, results.firstOrNull()?.number) ?: return
        vm.requestCall(target, nameFor(target))
    }

    fun callWithSim(simId: String) {
        // A SIM segment with nothing typed recalls the last number, like the plain Call pill.
        if (input.isBlank()) {
            keypad.recallLastNumber()
            return
        }
        if (isTextSearch()) {
            results.firstOrNull()?.let { vm.requestCall(it.number, it.contact?.displayName, simId = simId) }
            return
        }
        val target = DialTarget.pick(input, results.firstOrNull()?.number)
        // Same checks as any call (dial guard, allowance, confirm), just without the SIM question.
        if (!target.isNullOrEmpty()) vm.requestCall(target, nameFor(target), simId = simId)
    }

    /** Press and hold Call: "Call with a reason…" for what's typed (nothing typed: nothing to call). */
    fun callWithReason(simId: String?) {
        if (input.isBlank()) return
        val r = if (isTextSearch()) results.firstOrNull() else null
        val target = r?.number ?: DialTarget.pick(input, results.firstOrNull()?.number) ?: return
        if (!CallReason.offered(target)) return
        // Found it: the tip about the long-press has done its job.
        runCatching { vm.c.ux.dismissTip(Tips.CALL_REASON) }
        show(KeypadDialog.Reason(ReasonTarget(target, r?.contact?.displayName ?: nameFor(target), simId)))
    }

    /** What's typed, as the number its actions are for. */
    val typedNumber: String = input.trim()

    /**
     * Actions for the typed number: message it, or save it (as a contact, into one, or for a while). Each shows in one
     * place only: rows in the results when no contact matches, else the chip row at the results' foot.
     */
    val numberActions: KeypadNumberActions.Placement = KeypadNumberActions.place(
        typed = typedNumber,
        textSearch = isTextSearch(),
        serviceCode = PhoneIdentity.isServiceCode(typedNumber),
        contactMatches = results.any { it.contact != null },
        known = results.any { it.contact != null && PhoneIdentity.same(it.number, typedNumber, vm.countryIso) },
    )

    fun runNumberAction(action: KeypadNumberActions.Action) {
        when (action) {
            KeypadNumberActions.Action.MESSAGE_OR_CALL -> show(KeypadDialog.Message(typedNumber))
            KeypadNumberActions.Action.CREATE_CONTACT -> open(Routes.edit(phone = typedNumber))
            KeypadNumberActions.Action.ADD_TO_CONTACT -> open(Routes.pick(typedNumber))
            KeypadNumberActions.Action.SAVE_TEMPORARY -> show(KeypadDialog.SaveTemporary(typedNumber))
        }
    }
}

/**
 * Keys typed on a hardware keypad or keyboard: Call and Enter call (unless the docked Recents list [takes] them), Delete
 * deletes, digits type with their tone, and on a QWERTY keyboard letters search names.
 */
@Suppress("LongParameterList") // The keypad's state at this composition and what each key does.
internal class KeypadKeyInput(
    private val field: TextFieldState,
    private val input: String,
    private val qwerty: Boolean,
    private val takes: (KeypadKeys.Key) -> Boolean,
    private val call: () -> Unit,
    private val press: (Char) -> Unit,
    private val type: (Char) -> Unit,
    private val textSearch: () -> Boolean,
) {
    fun onKey(e: KeyEvent): Boolean {
        val native = e.nativeKeyEvent
        val down = e.type == KeyEventType.KeyDown
        if (native.isCtrlPressed || native.isMetaPressed) return false
        command(native.keyCode, down)?.let { return it }
        val ch = native.getUnicodeChar(native.metaState).takeIf { it > 0 }?.toChar() ?: return false
        return character(ch, down)
    }

    /** Call, Enter and the two Deletes: whether the keypad took the key, or null for any other key. */
    private fun command(keyCode: Int, down: Boolean): Boolean? = when (keyCode) {
        AndroidKeyEvent.KEYCODE_CALL -> callKey(KeypadKeys.Key.CALL, down)
        // Docked: Enter on a focused Recents row (or with the keypad folded) belongs to that row.
        AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> callKey(KeypadKeys.Key.ENTER, down)
        AndroidKeyEvent.KEYCODE_DEL -> {
            if (input.isNotEmpty() && down) field.deleteBeforeCursor()
            input.isNotEmpty()
        }
        AndroidKeyEvent.KEYCODE_FORWARD_DEL -> {
            if (down) field.deleteAfterCursor()
            input.isNotEmpty()
        }
        else -> null
    }

    private fun callKey(key: KeypadKeys.Key, down: Boolean): Boolean {
        if (!takes(key)) return false
        if (down) call()
        return true
    }

    /** A digit (with * # +) types and sounds; on a QWERTY keyboard letters search names as text, a space separates words. */
    private fun character(ch: Char, down: Boolean): Boolean {
        val digit = T9.asciiDigit(ch)
        return when {
            digit != null || ch == '*' || ch == '#' || ch == '+' -> {
                if (down) press(digit ?: ch)
                true
            }
            qwerty && (ch.isLetter() || (ch == ' ' && textSearch())) -> {
                if (down) type(ch)
                true
            }
            else -> false
        }
    }
}

private val dtmfTone = mapOf(
    '0' to ToneGenerator.TONE_DTMF_0, '1' to ToneGenerator.TONE_DTMF_1, '2' to ToneGenerator.TONE_DTMF_2,
    '3' to ToneGenerator.TONE_DTMF_3, '4' to ToneGenerator.TONE_DTMF_4, '5' to ToneGenerator.TONE_DTMF_5,
    '6' to ToneGenerator.TONE_DTMF_6, '7' to ToneGenerator.TONE_DTMF_7, '8' to ToneGenerator.TONE_DTMF_8,
    '9' to ToneGenerator.TONE_DTMF_9, '*' to ToneGenerator.TONE_DTMF_S, '#' to ToneGenerator.TONE_DTMF_P,
)

/** Tone length for keys typed on a hardware keypad (on-screen keys hold theirs while pressed). */
private const val KEY_TONE_MS = 150

/**
 * The keys' DTMF tones, when the app setting, the system "Dial pad tones" setting and the ringer mode allow (read on
 * every press: they can change any time). On-screen keys start their tone on touch and hold it until release (at least
 * 150 ms); only the key that started the current tone may stop it, so rolling over to the next key doesn't cut it.
 */
internal class DialTones(private val context: Context, private val scope: CoroutineScope) {
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_DTMF, 70)
    } catch (_: Exception) {
        null
    }
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val token = AtomicInteger()

    private fun allowed(appSetting: Boolean): Boolean = KeypadFeedback.playTone(
        appSetting = appSetting,
        systemDialpadTones = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1 }.getOrDefault(true),
        ringerNormal = audioManager?.ringerMode?.let { it == AudioManager.RINGER_MODE_NORMAL } ?: true,
    )

    /** A key typed on a hardware keypad: a short tone. */
    fun play(c: Char, appSetting: Boolean) {
        if (allowed(appSetting)) dtmfTone[c]?.let { tone?.startTone(it, KEY_TONE_MS) }
    }

    /** An on-screen key went down: its tone starts and holds; the token lets only this key [stop] it. */
    fun start(c: Char, appSetting: Boolean): Int {
        if (allowed(appSetting)) dtmfTone[c]?.let { tone?.startTone(it) }
        return token.incrementAndGet()
    }

    fun stop(token: Int, afterMs: Long) {
        scope.launch {
            if (afterMs > 0) delay(afterMs)
            if (token == this@DialTones.token.get()) tone?.stopTone()
        }
    }

    /** A key can leave the screen mid-press (keypad hidden, tab changed): its tone must not keep playing. */
    fun release() {
        token.incrementAndGet()
        runCatching { tone?.stopTone() }
        tone?.release()
    }
}

@Composable
internal fun rememberDialTones(): DialTones {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tones = remember { DialTones(context, scope) }
    DisposableEffect(tones) { onDispose { tones.release() } }
    return tones
}
