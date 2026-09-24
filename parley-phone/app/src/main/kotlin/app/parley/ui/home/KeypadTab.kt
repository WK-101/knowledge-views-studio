package app.parley.ui.home

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.view.KeyEvent as AndroidKeyEvent
import android.view.textclassifier.TextClassifier
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.Bidi
import app.parley.ui.ForceLtr
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonAddAlt
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.DialResult
import app.parley.common.DialText
import app.parley.common.KeypadLayout
import app.parley.common.NumberText
import app.parley.common.PhoneNumbers
import app.parley.messaging.MessageOnSheet
import app.parley.ui.Avatar
import app.parley.ui.CallColors
import app.parley.ui.MatchStyle
import app.parley.ui.Routes
import app.parley.ui.keypadKey
import app.parley.ui.common.Format
import app.parley.ui.highlight
import kotlinx.coroutines.awaitCancellation

private val keys = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

private val dtmfTone = mapOf(
    '0' to ToneGenerator.TONE_DTMF_0, '1' to ToneGenerator.TONE_DTMF_1, '2' to ToneGenerator.TONE_DTMF_2,
    '3' to ToneGenerator.TONE_DTMF_3, '4' to ToneGenerator.TONE_DTMF_4, '5' to ToneGenerator.TONE_DTMF_5,
    '6' to ToneGenerator.TONE_DTMF_6, '7' to ToneGenerator.TONE_DTMF_7, '8' to ToneGenerator.TONE_DTMF_8,
    '9' to ToneGenerator.TONE_DTMF_9, '*' to ToneGenerator.TONE_DTMF_S, '#' to ToneGenerator.TONE_DTMF_P,
)

/** Tone length for keys typed on a hardware keypad (on-screen keys hold theirs while pressed). */
private const val KEY_TONE_MS = 150

/** `*#06#`: Android only shows the IMEI to the system, so Parley explains where to find it (K5). */
private const val IMEI_CODE = "*#06#"

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeypadTab(vm: AppViewModel, open: (String) -> Unit, searchQuery: String? = null) {
    // Search from the header: contacts by name or number, in place of the keypad until the search closes.
    if (searchQuery != null) {
        KeypadContactSearch(vm, searchQuery, open)
        return
    }
    val context = LocalContext.current
    val input by vm.dialInput.collectAsStateWithLifecycle()
    val results by vm.dialResults.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val layout by vm.keypadLayout.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var unassigned by remember { mutableStateOf<Int?>(null) }
    var messageOn by remember { mutableStateOf<String?>(null) }
    var imeiSheet by remember { mutableStateOf(false) }
    var saveTemporary by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val res = LocalResources.current
    /** Result row focused with the D-pad; Call/Enter calls it. */
    var focusedResult by remember { mutableStateOf<DialResult?>(null) }

    // K7: phones with a hardware keypad or keyboard type on it; the on-screen keypad starts hidden.
    val configuration = LocalConfiguration.current
    val hasHardwareKeys = hardwareKeysAvailable(configuration)
    val qwerty = configuration.keyboard == Configuration.KEYBOARD_QWERTY && hasHardwareKeys
    var showKeypad by rememberSaveable(hasHardwareKeys) { mutableStateOf(!hasHardwareKeys) }

    val tone = remember {
        try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (_: Exception) { null }
    }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    /** F22: the system "Dial pad tones" setting and the ringer mode, read on every press (they can change any time). */
    fun toneAllowed(): Boolean = app.parley.common.KeypadFeedback.playTone(
        appSetting = settings.dialpadTones,
        systemDialpadTones = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1 }.getOrDefault(true),
        ringerNormal = audioManager?.ringerMode?.let { it == AudioManager.RINGER_MODE_NORMAL } ?: true,
    )

    // K4: the number is an editable field (cursor, selection, paste) that never opens the on-screen keyboard.
    val field = rememberTextFieldState(input)
    LaunchedEffect(input) { if (field.text.toString() != input) field.setTextAndPlaceCursorAtEnd(input) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { if (it != vm.dialInput.value) vm.dialInput.value = it } }
    LaunchedEffect(input) { if (input == IMEI_CODE) imeiSheet = true }

    fun insert(text: String) = field.insertAtCursor(text)

    fun press(c: Char) {
        insert(c.toString())
        if (settings.dialpadHaptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (toneAllowed()) dtmfTone[c]?.let { tone?.startTone(it, KEY_TONE_MS) }
    }

    // V7: on-screen keys start their tone on touch and hold it until release (at least 150 ms). Only the key that
    // started the current tone may stop it, so rolling over to the next key doesn't cut that key's tone.
    val toneToken = remember { java.util.concurrent.atomic.AtomicInteger() }
    fun keyDown(c: Char): Int {
        insert(c.toString())
        if (settings.dialpadHaptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (toneAllowed()) dtmfTone[c]?.let { tone?.startTone(it) }
        return toneToken.incrementAndGet()
    }
    fun keyUp(token: Int, afterMs: Long) {
        scope.launch {
            if (afterMs > 0) kotlinx.coroutines.delay(afterMs)
            if (token == toneToken.get()) tone?.stopTone()
        }
    }
    /** A long-press replaces the digit its touch already typed. */
    fun longPress(action: () -> Unit) {
        field.deleteBeforeCursor()
        action()
    }

    fun callResult(r: DialResult) = vm.requestCall(r.number, r.contact?.displayName)

    /** Letters typed on a hardware keyboard are a name search, so Call means the best match. */
    fun isTextSearch() = input.any { it.isLetter() }

    fun callNow() {
        val n = input.trim()
        if (n.isEmpty()) {
            // A7: recall the last dialled number, like most dialers.
            vm.recallLastNumber()
            return
        }
        if (isTextSearch()) {
            results.firstOrNull()?.let(::callResult)
            return
        }
        vm.requestCall(n, results.firstOrNull { it.contact != null && PhoneNumbers.same(it.number, n, vm.countryIso) }?.contact?.displayName)
    }

    fun callWithSim(simId: String) {
        val target = if (isTextSearch()) results.firstOrNull()?.number else input.trim()
        // Same checks as any call (dial guard, allowance, confirm), just without the SIM question.
        if (!target.isNullOrEmpty()) vm.requestCall(target, results.firstOrNull { it.contact != null && PhoneNumbers.same(it.number, target, vm.countryIso) }?.contact?.displayName, simId = simId)
    }

    fun onKey(e: KeyEvent): Boolean {
        val native = e.nativeKeyEvent
        val down = e.type == KeyEventType.KeyDown
        if (native.isCtrlPressed || native.isMetaPressed) return false
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_CALL -> { if (down) focusedResult?.let(::callResult) ?: callNow(); return true }
            AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) focusedResult?.let(::callResult) ?: callNow()
                return true
            }
            AndroidKeyEvent.KEYCODE_DEL -> {
                if (input.isEmpty()) return false
                if (down) field.deleteBeforeCursor()
                return true
            }
            AndroidKeyEvent.KEYCODE_FORWARD_DEL -> {
                if (down) field.deleteAfterCursor()
                return input.isNotEmpty()
            }
        }
        val ch = native.getUnicodeChar(native.metaState).takeIf { it > 0 }?.toChar() ?: return false
        val digit = app.parley.common.T9.asciiDigit(ch)
        return when {
            digit != null || ch == '*' || ch == '#' || ch == '+' -> { if (down) press(digit ?: ch); true }
            // QWERTY: letters search names as text; space separates words.
            qwerty && (ch.isLetter() || (ch == ' ' && isTextSearch())) -> { if (down) insert(ch.toString()); true }
            else -> false
        }
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().focusRequester(rootFocus).onPreviewKeyEvent(::onKey).focusable()) {
        // Results
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (input.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(if (qwerty) R.string.keypad_hint_qwerty else R.string.keypad_hint_t9),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    PasteChip(vm.countryIso) { text -> field.setTextAndPlaceCursorAtEnd(text) }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { (it.contact?.id?.toString() ?: "n") + it.number }) { r ->
                        DialResultRow(
                            r, vm.countryIso,
                            modifier = Modifier.onFocusChanged { s ->
                                if (s.isFocused) focusedResult = r else if (focusedResult == r) focusedResult = null
                            },
                        ) { callResult(r) }
                    }
                    if (results.none { it.contact != null } && input.length >= 3 && !isTextSearch()) {
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.keypad_create_contact)) },
                                leadingContent = { Icon(Icons.Rounded.PersonAdd, null) },
                                modifier = Modifier.clickable { open(Routes.edit(phone = input)) },
                            )
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.recents_add_to_contact)) },
                                leadingContent = { Icon(Icons.Rounded.PersonAdd, null) },
                                modifier = Modifier.clickable { open(Routes.pick(input)) },
                            )
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.missed_message_on)) },
                                supportingContent = { Text(stringResource(R.string.keypad_message_apps)) },
                                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                                modifier = Modifier.clickable { messageOn = input },
                            )
                        }
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Number display
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasHardwareKeys) {
                        IconButton({ showKeypad = !showKeypad }) {
                            Icon(Icons.Rounded.Dialpad, stringResource(if (showKeypad) R.string.keypad_hide else R.string.keypad_show))
                        }
                    } else {
                        Spacer(Modifier.width(48.dp))
                    }
                    // L3: the number reads left to right in every language.
                    ForceLtr { NumberField(field, vm.countryIso, Modifier.weight(1f)) }
                    val deleteLabel = stringResource(R.string.main_delete)
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                            enabled = input.isNotEmpty(),
                            onClick = { field.deleteBeforeCursor() },
                            onLongClick = { field.clearText() },
                        ).semantics { contentDescription = deleteLabel },
                        contentAlignment = Alignment.Center,
                    ) { if (input.isNotEmpty()) Icon(Icons.AutoMirrored.Rounded.Backspace, null) }
                }
                // L3: 1 2 3 stays left to right in right-to-left languages, like every phone keypad.
                if (showKeypad) ForceLtr { Column { keys.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { (digit, letters) ->
                                val d = digit[0]
                                val token = remember { intArrayOf(0) }
                                DialKey(
                                    digit, letters, localLetters(layout, d),
                                    modifier = Modifier.weight(1f),
                                    onPress = { token[0] = keyDown(d) },
                                    onRelease = { after -> keyUp(token[0], after) },
                                    onLong = when (d) {
                                        '0' -> ({ longPress { insert("+") } })
                                        '1' -> ({ longPress { vm.callVoicemail() } })
                                        in '2'..'9' -> ({ longPress { vm.speedDial(d - '0') { unassigned = d - '0' } } })
                                        '*' -> ({ longPress { insert(",") } })
                                        '#' -> ({ longPress { insert(";") } })
                                        else -> null
                                    },
                                )
                            }
                        }
                    }
                } }
                Spacer(Modifier.height(8.dp))
                // Actions for the typed number: message it (M2), or save it (as a contact, into one, or for a while).
                val typedNumber = input.trim()
                if (typedNumber.isNotEmpty() && !isTextSearch() && !PhoneNumbers.isServiceCode(typedNumber)) {
                    val known = results.any { it.contact != null && PhoneNumbers.same(it.number, typedNumber, vm.countryIso) }
                    NumberActionChips(
                        canSave = !known && typedNumber.count { it.isDigit() } >= 3,
                        onMessage = { messageOn = typedNumber },
                        onAdd = { open(Routes.edit(phone = typedNumber)) },
                        onTemporary = { saveTemporary = typedNumber },
                        onAddToExisting = { open(Routes.pick(typedNumber)) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (sims.size >= 2 && input.isNotEmpty()) {
                        sims.take(2).forEach { sim -> app.parley.ui.history.SimPlanBadge(vm, sim.id) { CallButton(label = sim.label) { callWithSim(sim.id) } } }
                    } else {
                        CallButton(label = null, onClick = ::callNow)
                    }
                }
            }
        }
    }

    unassigned?.let { key ->
        AlertDialog(
            onDismissRequest = { unassigned = null },
            title = { Text(stringResource(R.string.keypad_speed_empty_title, key)) },
            text = { Text(stringResource(R.string.keypad_speed_empty_body, key)) },
            confirmButton = { TextButton({ unassigned = null; open(Routes.SPEED_DIAL) }) { Text(stringResource(R.string.keypad_set_up)) } },
            dismissButton = { TextButton({ unassigned = null }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
    messageOn?.let { n -> MessageOnSheet(n, onDismiss = { messageOn = null }) }
    saveTemporary?.let { n ->
        app.parley.ui.temporary.SaveTemporaryDialog(
            number = Format.number(n, vm.countryIso),
            suggestedName = app.parley.messaging.TemporaryContact.suggestedName(n, null, vm.countryIso.uppercase()),
            onDismiss = { saveTemporary = null },
        ) { name, days, deleteHistory, visible ->
            saveTemporary = null
            scope.launch {
                val saved = app.parley.ui.temporary.TemporaryContactActions.save(vm, n, name, days, deleteHistory, visible)
                if (saved != null) {
                    field.clearText()
                    vm.toast(res.getQuantityString(if (saved.private) R.plurals.caller_saved_private_days else R.plurals.caller_saved_days, days, days))
                } else {
                    vm.toast(res.getString(R.string.keypad_save_failed))
                }
            }
        }
    }
    if (imeiSheet) {
        ImeiSheet(onDismiss = {
            imeiSheet = false
            if (field.text.toString() == IMEI_CODE) field.clearText()
        })
    }
}

/** Whether a hardware keypad (flip phones) or keyboard (QWERTY phones) is available and open. */
private fun hardwareKeysAvailable(c: Configuration): Boolean =
    (c.keyboard == Configuration.KEYBOARD_12KEY || c.keyboard == Configuration.KEYBOARD_QWERTY) &&
        c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES

/** Second row of letters on a key for the chosen alphabet (K6); none for Latin only. */
private fun localLetters(layout: KeypadLayout, digit: Char): String = layout.lettersFor(digit)

private fun TextFieldState.insertAtCursor(text: String) = edit {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    replace(start, end, text)
    selection = TextRange(start + text.length)
}

private fun TextFieldState.deleteBeforeCursor() = edit {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    when {
        start != end -> { delete(start, end); selection = TextRange(start) }
        start > 0 -> { delete(start - 1, start); selection = TextRange(start - 1) }
    }
}

private fun TextFieldState.deleteAfterCursor() = edit {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    when {
        start != end -> { delete(start, end); selection = TextRange(start) }
        start < length -> delete(start, start + 1)
    }
}

/** Pasted or cut text keeps only what can be dialled. */
private object DialInputFilter : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val text = asCharSequence().toString()
        // Deleting (cut) needs no cleaning, and keeps letters typed on a hardware keyboard for name search.
        if (text.length < originalText.length) return
        val clean = DialText.sanitize(text)
        if (clean != text) {
            replace(0, length, clean)
            selection = TextRange(clean.length)
        }
    }
}

/** Shows the number formatted for the country ("06 12 34 56 78") while editing the plain digits. */
private class FormattedNumber(private val countryIso: String) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val raw = asCharSequence().toString()
        if (raw.length < 4 || raw.any { !(it.isDigit() || it == '+') }) return
        val formatted = PhoneNumberUtils.formatNumber(raw, countryIso) ?: return
        val inserts = DialText.formattingInserts(raw, formatted) ?: return
        var offset = 0
        for ((at, ch) in inserts) {
            insert(at + offset, ch.toString())
            offset++
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NumberField(state: TextFieldState, countryIso: String, modifier: Modifier) {
    val long = state.text.length > 14
    val style = MaterialTheme.typography.headlineMedium.copy(
        fontSize = if (long) 24.sp else 32.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val output = remember(countryIso) { FormattedNumber(countryIso) }
    val numberLabel = stringResource(R.string.keypad_number_description)
    // Tapping places the cursor and long-press offers Paste, but the on-screen keyboard never opens: the keypad
    // below (or a hardware keypad) is the keyboard.
    InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
        BasicTextField(
            state = state,
            modifier = modifier.semantics { contentDescription = numberLabel.format(state.text) },
            textStyle = style,
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = DialInputFilter,
            outputTransformation = output,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, showKeyboardOnFocus = false),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

private enum class ClipHint { NONE, TEXT, NUMBER }

/**
 * Whether the clipboard may hold a number, without reading it: only the clip description is checked (no
 * "pasted from your clipboard" notice on Android 12+). The text is read only when the chip is tapped.
 */
@Composable
private fun PasteChip(countryIso: String, onPaste: (String) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val cm = remember { context.getSystemService(ClipboardManager::class.java) }
    var hint by remember { mutableStateOf(ClipHint.NONE) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) hint = clipHint(cm) }
    DisposableEffect(cm) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { hint = clipHint(cm) }
        cm?.addPrimaryClipChangedListener(listener)
        onDispose { cm?.removePrimaryClipChangedListener(listener) }
    }
    if (hint == ClipHint.NONE) return
    AssistChip(
        modifier = Modifier.padding(top = 12.dp),
        onClick = {
            val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull().orEmpty()
            val number = NumberText.find(text, countryIso).firstOrNull()?.raw?.let(DialText::sanitize)
            if (number.isNullOrEmpty()) Toast.makeText(context, res.getString(R.string.keypad_no_clip_number), Toast.LENGTH_SHORT).show()
            else onPaste(number)
        },
        label = { Text(stringResource(if (hint == ClipHint.NUMBER) R.string.keypad_paste_number else R.string.keypad_paste)) },
        leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
    )
}

private fun clipHint(cm: ClipboardManager?): ClipHint = try {
    val d = if (cm?.hasPrimaryClip() == true) cm.primaryClipDescription else null
    when {
        d == null || !d.hasMimeType("text/*") -> ClipHint.NONE
        Build.VERSION.SDK_INT >= 31 && d.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE ->
            if (d.getConfidenceScore(TextClassifier.TYPE_PHONE) >= 0.5f) ClipHint.NUMBER else ClipHint.NONE
        else -> ClipHint.TEXT
    }
} catch (_: Exception) {
    ClipHint.NONE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImeiSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.keypad_imei_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.keypad_imei_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button({
                runCatching { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                onDismiss()
            }) { Text(stringResource(R.string.keypad_open_about)) }
            TextButton(onDismiss) { Text(stringResource(R.string.main_close)) }
        }
    }
}

/**
 * One keypad key: the digit, its Latin letters, and optionally a second row with the chosen alphabet's letters.
 * The key grows with the system font size (the digit up to 1.5×, the letters fully) so both letter rows stay
 * readable at 200 %.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    digit: String, letters: String, local: String, modifier: Modifier = Modifier,
    onPress: () -> Unit, onRelease: (afterMs: Long) -> Unit, onLong: (() -> Unit)?,
) {
    val fontScale = LocalDensity.current.fontScale
    val digitSize = (30f * minOf(fontScale, 1.5f) / fontScale).sp
    val res = LocalResources.current
    Column(
        modifier
            .padding(horizontal = 4.dp)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(32.dp))
            // V7: typed and sounded on touch; slide off to cancel the long-press; keys roll over.
            .keypadKey(onPress = onPress, onToneStop = onRelease, onLongPress = onLong, longPressLabel = longPressLabel(res, digit))
            .padding(vertical = 4.dp)
            .semantics { contentDescription = keyDescription(res, digit, letters) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(digit, fontSize = digitSize, lineHeight = digitSize, fontWeight = FontWeight.Normal)
        if (digit == "1") {
            Icon(Icons.Rounded.Voicemail, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (letters.isNotEmpty() || local.isEmpty()) {
            Text(letters, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
        if (local.isNotEmpty()) {
            Text(local, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
        }
    }
}

private fun longPressLabel(res: android.content.res.Resources, digit: String): String? = when (digit) {
    "0" -> res.getString(R.string.keypad_long_plus)
    "1" -> res.getString(R.string.keypad_long_voicemail)
    "*" -> res.getString(R.string.keypad_long_pause)
    "#" -> res.getString(R.string.keypad_long_wait)
    else -> if (digit[0] in '2'..'9') res.getString(R.string.home_speed_dial) else null
}

/** What TalkBack reads for a key: "2, A B C", "1, voicemail", "star", "pound" (A12). */
private fun keyDescription(res: android.content.res.Resources, digit: String, letters: String): String = when (digit) {
    "*" -> res.getString(app.parley.ui.R.string.ui_key_star)
    "#" -> res.getString(app.parley.ui.R.string.ui_key_pound)
    "1" -> res.getString(R.string.keypad_key_voicemail)
    else -> if (letters.isEmpty()) digit else res.getString(R.string.keypad_key_letters, digit, letters.toList().joinToString(" "))
}

@Composable
private fun CallButton(label: String?, onClick: () -> Unit) {
    val callLabel = if (label != null) stringResource(R.string.keypad_call_with, label) else stringResource(R.string.main_call)
    Row(
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(CallColors.Accept).clickable(onClick = onClick)
            .padding(horizontal = if (label != null) 20.dp else 40.dp)
            .semantics { contentDescription = callLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (label != null) Icons.Rounded.SimCard else Icons.Rounded.Call, null, tint = Color.White)
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * A keypad result. Main rows show the contact with the matched letters highlighted and the number that will be
 * dialled ("Mobile · Primary · 06 12…"); [DialResult.secondary] rows list the contact's other numbers (K3).
 */
@Composable
private fun DialResultRow(r: DialResult, countryIso: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val resources = LocalResources.current
    val c = r.contact
    val phone = c?.phones?.firstOrNull { it.number == r.number }
    val type = phone?.let { Format.phoneType(resources, it.type, it.label) }
    if (r.secondary) {
        ListItem(
            modifier = modifier.clickable(onClick = onClick),
            leadingContent = { Spacer(Modifier.width(40.dp)) },
            headlineContent = { Text(Bidi.ltr(Format.number(r.number, countryIso)), style = MaterialTheme.typography.bodyLarge) },
            supportingContent = type?.let { { Text(it) } },
            trailingContent = { Icon(Icons.Rounded.Call, stringResource(R.string.main_call_who, listOfNotNull(c?.displayName, type).joinToString(" ")), tint = MaterialTheme.colorScheme.primary) },
        )
        return
    }
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(c?.displayName ?: r.number, c?.photoUri, 40.dp) },
        headlineContent = {
            if (c != null) Text(highlight(c.displayName, r.match.nameRanges, MatchStyle), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(Bidi.ltr(Format.number(r.number, countryIso)))
        },
        supportingContent = {
            if (c != null) {
                Text(listOfNotNull(type, if (r.primary) stringResource(R.string.keypad_primary) else null, Bidi.ltr(Format.number(r.number, countryIso))).joinToString(stringResource(R.string.main_separator)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(stringResource(R.string.keypad_recent))
            }
        },
        trailingContent = { Icon(Icons.Rounded.Call, stringResource(R.string.main_call), tint = MaterialTheme.colorScheme.primary) },
    )
}

/** Chips under the number: Message, and when the number isn't saved yet, the ways to save it. */
@Composable
private fun NumberActionChips(canSave: Boolean, onMessage: () -> Unit, onAdd: () -> Unit, onTemporary: () -> Unit, onAddToExisting: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(onClick = onMessage, label = { Text(stringResource(R.string.main_message)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Chat, null) })
        if (canSave) {
            AssistChip(onClick = onAdd, label = { Text(stringResource(R.string.keypad_add_to_contacts)) }, leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) })
            AssistChip(onClick = onTemporary, label = { Text(stringResource(R.string.keypad_save_temporary)) }, leadingIcon = { Icon(Icons.Rounded.AutoDelete, null) })
            AssistChip(onClick = onAddToExisting, label = { Text(stringResource(R.string.keypad_add_to_existing)) }, leadingIcon = { Icon(Icons.Rounded.PersonAddAlt, null) })
        }
    }
}

/** Keypad search from the header: every contact (and visible private contact) matching a name or number. */
@Composable
private fun KeypadContactSearch(vm: AppViewModel, query: String, open: (String) -> Unit) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val q = query.trim()
    if (q.isEmpty()) {
        app.parley.ui.EmptyState(Icons.Rounded.Search, stringResource(R.string.home_search_contacts), stringResource(R.string.keypad_search_body))
        return
    }
    val found = remember(contacts, q) { contacts.orEmpty().filter { app.parley.common.TextSearch.matches(q, it.displayName, it.phones.map { p -> p.number }) } }
    val foundVault = remember(vault, q, settings.hideVault) {
        if (settings.hideVault) emptyList() else vault.filter { app.parley.common.TextSearch.matches(q, it.name, it.numbers) }
    }
    if (found.isEmpty() && foundVault.isEmpty()) {
        app.parley.ui.EmptyState(Icons.Rounded.Search, stringResource(R.string.keypad_no_match, q))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(foundVault, key = { "v" + it.id }) { v ->
            ListItem(
                modifier = Modifier.clickable { open(Routes.vault(v.id)) },
                leadingContent = { app.parley.ui.Avatar(v.name, null, app.parley.ui.avatarSize()) },
                headlineContent = { Text("\uD83D\uDD12 " + v.name) },
                supportingContent = v.numbers.firstOrNull()?.let { n -> { Text(Bidi.ltr(Format.number(n, vm.countryIso))) } },
                trailingContent = v.numbers.firstOrNull()?.let { n ->
                    { IconButton({ vm.requestCall(n, v.name) }) { Icon(Icons.Rounded.Call, stringResource(R.string.main_call_who, v.name), tint = MaterialTheme.colorScheme.primary) } }
                },
            )
        }
        items(found, key = { it.id }) { c ->
            ContactRow(c, actions = true, onCall = { n -> vm.requestCall(n, c.displayName) }) { open(Routes.contact(c.id)) }
        }
    }
}
