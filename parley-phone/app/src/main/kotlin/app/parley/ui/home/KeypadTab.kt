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
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.Bidi
import app.parley.ui.ForceLtr
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import app.parley.common.calls.PressOrder
import app.parley.messaging.ReachSheet
import app.parley.messaging.ReachTarget
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

/** `*#06#`: Android only shows the IMEI to the system, so Parley explains where to find it. */
private const val IMEI_CODE = "*#06#"

/** Height of the typed number's action chips at the foot of the results (outside the keypad panel). */
private val NUMBER_ACTIONS_HEIGHT = 56.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeypadTab(vm: AppViewModel, open: (String) -> Unit, searchQuery: String? = null, dock: KeypadDock? = null) {
    // Search from the header: contacts by name or number, in place of the keypad until the search closes.
    // What's typed, its results, the SIM and the keypad's actions live in KeypadViewModel; this draws the keypad.
    val keypad: KeypadViewModel = app.parley.ui.activityViewModel()
    if (searchQuery != null) {
        KeypadContactSearch(vm, keypad, searchQuery, open)
        return
    }
    val context = LocalContext.current
    val input by keypad.input.collectAsStateWithLifecycle()
    val results by keypad.results.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    LaunchedEffect(sims.size) { keypad.simCount.value = sims.size }
    val layout by keypad.layout.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var unassigned by remember { mutableStateOf<Int?>(null) }
    var messageOn by remember { mutableStateOf<String?>(null) }
    var imeiSheet by remember { mutableStateOf(false) }
    var saveTemporary by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val res = LocalResources.current
    /** Result row focused with the D-pad; Call/Enter calls it. */
    var focusedResult by remember { mutableStateOf<DialResult?>(null) }

    // Phones with a hardware keypad or keyboard type on it; the on-screen keypad starts hidden.
    val configuration = LocalConfiguration.current
    val hasHardwareKeys = hardwareKeysAvailable(configuration)
    val qwerty = configuration.keyboard == Configuration.KEYBOARD_QWERTY && hasHardwareKeys
    var showKeypad by rememberSaveable(hasHardwareKeys) { mutableStateOf(!hasHardwareKeys) }

    val tone = remember {
        try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (_: Exception) { null }
    }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    /** The system "Dial pad tones" setting and the ringer mode, read on every press (they can change any time). */
    fun toneAllowed(): Boolean = app.parley.common.KeypadFeedback.playTone(
        appSetting = settings.dialpadTones,
        systemDialpadTones = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1 }.getOrDefault(true),
        ringerNormal = audioManager?.ringerMode?.let { it == AudioManager.RINGER_MODE_NORMAL } ?: true,
    )

    // The number is an editable field (cursor, selection, paste) that never opens the on-screen keyboard.
    val field = rememberTextFieldState(input)
    LaunchedEffect(input) { if (field.text.toString() != input) field.setTextAndPlaceCursorAtEnd(input) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { if (it != keypad.input.value) keypad.input.value = it } }
    LaunchedEffect(input) { if (input == IMEI_CODE) imeiSheet = true }

    fun insert(text: String) = field.insertAtCursor(text)

    // Typing on a hardware keypad while the docked keypad is folded unfolds it (the number shows there).
    fun unfold() { dock?.let { if (!it.expanded) it.onExpandedChange(true) } }

    fun press(c: Char) {
        unfold()
        insert(c.toString())
        if (settings.dialpadHaptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (toneAllowed()) dtmfTone[c]?.let { tone?.startTone(it, KEY_TONE_MS) }
    }

    // On-screen keys start their tone on touch and hold it until release (at least 150 ms). Only the key that
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
    /**
     * A long-press replaces the digit its own touch typed ([typedThisTouch]); TalkBack's long click typed nothing,
     * so nothing is deleted then.
     */
    fun longPress(typedThisTouch: Boolean, action: () -> Unit) {
        if (typedThisTouch) field.deleteBeforeCursor()
        action()
    }
    // A key can leave the screen mid-press (keypad hidden, tab changed): its tone must not keep playing.
    DisposableEffect(Unit) { onDispose { toneToken.incrementAndGet(); runCatching { tone?.stopTone() } } }

    fun callResult(r: DialResult) = vm.requestCall(r.number, r.contact?.displayName)

    /** Letters typed on a hardware keyboard are a name search, so Call means the best match. */
    fun isTextSearch() = input.any { it.isLetter() }

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
        val target = app.parley.common.calls.DialTarget.pick(n, results.firstOrNull()?.number) ?: return
        vm.requestCall(target, results.firstOrNull { it.contact != null && PhoneNumbers.same(it.number, target, vm.countryIso) }?.contact?.displayName)
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
        val target = app.parley.common.calls.DialTarget.pick(input, results.firstOrNull()?.number)
        // Same checks as any call (dial guard, allowance, confirm), just without the SIM question.
        if (!target.isNullOrEmpty()) vm.requestCall(target, results.firstOrNull { it.contact != null && PhoneNumbers.same(it.number, target, vm.countryIso) }?.contact?.displayName, simId = simId)
    }

    // A D-pad focus inside the docked Recents list (only shown while nothing is typed).
    var recentsHasFocus by remember { mutableStateOf(false) }
    // Typing replaces the list, and its focus goes with it.
    val typed = input.isNotEmpty()
    LaunchedEffect(typed) { if (typed) recentsHasFocus = false }
    fun keypadTakes(key: app.parley.common.KeypadKeys.Key) = app.parley.common.KeypadKeys.keypadTakes(
        key, docked = dock != null, expanded = dock?.expanded ?: true, recentsFocused = recentsHasFocus && input.isEmpty(),
    )

    fun onKey(e: KeyEvent): Boolean {
        val native = e.nativeKeyEvent
        val down = e.type == KeyEventType.KeyDown
        if (native.isCtrlPressed || native.isMetaPressed) return false
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_CALL -> {
                if (!keypadTakes(app.parley.common.KeypadKeys.Key.CALL)) return false
                if (down) focusedResult?.let(::callResult) ?: callNow()
                return true
            }
            AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Docked: Enter on a focused Recents row (or with the keypad folded) belongs to that row.
                if (!keypadTakes(app.parley.common.KeypadKeys.Key.ENTER)) return false
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
            qwerty && (ch.isLetter() || (ch == ' ' && isTextSearch())) -> { if (down) { unfold(); insert(ch.toString()) }; true }
            else -> false
        }
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // Actions for the typed number: message it, or save it (as a contact, into one, or for a while).
    val typedNumber = input.trim()
    val showNumberActions = typedNumber.isNotEmpty() && !isTextSearch() && !PhoneNumbers.isServiceCode(typedNumber)

    // Docked at the foot of Recents, the panel folds away (scrolling the list, a swipe down on its handle) and a
    // keypad button brings it back; the panel's height still never changes while typing.
    // The fold follows the finger and springs open or folded (see DockFoldState).
    val density = LocalDensity.current
    val fold = remember { DockFoldState(dock?.expanded ?: true, scope, density) }
    val latestDock by androidx.compose.runtime.rememberUpdatedState(dock)
    fold.onSettle = { open -> latestDock?.let { if (it.expanded != open) it.onExpandedChange(open) } }
    // The home screen's state (Back, dial intents, typing on a hardware keypad) moves the fold too.
    LaunchedEffect(dock?.expanded) { dock?.let { if (fold.target != it.expanded) fold.animateTo(it.expanded) } }
    val panelShown = dock == null || fold.value > 0f || fold.dragging
    val panelOpen = dock?.expanded ?: true

    // The SIM a plain Call would use for what's typed (remembered, a label's, else the default), shown on its segment.
    val preferredSim by keypad.preferredSim.collectAsStateWithLifecycle()

    val resultsArea: @Composable (Modifier) -> Unit = { areaModifier ->
        Box(if (dock != null) areaModifier.nestedScroll(fold.listConnection) else areaModifier) {
            if (input.isEmpty() && dock != null) {
                // Nothing typed: the recent calls, as on the Recents tab.
                Column(Modifier.fillMaxSize()) {
                    app.parley.ui.common.CoachMark(app.parley.common.ux.Tips.DOCKED_KEYPAD, stringResource(R.string.home_tip_docked_keypad), enabled = panelOpen)
                    Box(Modifier.weight(1f).onFocusChanged { recentsHasFocus = it.hasFocus }) { dock.idle() }
                }
            } else if (input.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(if (qwerty) R.string.keypad_hint_qwerty else R.string.keypad_hint_t9),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    PasteChip(vm.countryIso) { text -> field.setTextAndPlaceCursorAtEnd(text) }
                    // Long-press 2-9 for speed dial, told once.
                    app.parley.ui.common.CoachMark(
                        app.parley.common.ux.Tips.KEYPAD_SPEED_DIAL, stringResource(R.string.ux_tip_speed_dial),
                        enabled = showKeypad, action = stringResource(R.string.ux_tip_set_up), onAction = { open(Routes.SPEED_DIAL) },
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (showNumberActions) NUMBER_ACTIONS_HEIGHT else 0.dp)) {
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
                                headlineContent = { Text(stringResource(R.string.reach_message_or_call_on)) },
                                supportingContent = { Text(stringResource(R.string.reach_apps_line)) },
                                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                                modifier = Modifier.clickable { messageOn = input },
                            )
                        }
                    }
                }
            }
            // The number's actions sit at the foot of the results, above the keypad panel, never inside it: the
            // panel is anchored to the bottom, so anything appearing in it while typing would push the keys up under
            // the user's finger. The panel's height now never changes while typing (portrait, landscape, hardware keys).
            if (showNumberActions) {
                val known = results.any { it.contact != null && PhoneNumbers.same(it.number, typedNumber, vm.countryIso) }
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(NUMBER_ACTIONS_HEIGHT)) {
                    Box(contentAlignment = Alignment.Center) {
                        NumberActionChips(
                            canSave = !known && typedNumber.count { it.isDigit() } >= 3,
                            onMessage = { messageOn = typedNumber },
                            onAdd = { open(Routes.edit(phone = typedNumber)) },
                            onTemporary = { saveTemporary = typedNumber },
                            onAddToExisting = { open(Routes.pick(typedNumber)) },
                        )
                    }
                }
            }
        }
    }

    val hideKeypadLabel = stringResource(R.string.keypad_hide)
    val toggleLabel = stringResource(if (showKeypad) R.string.keypad_hide else R.string.keypad_show)
    // The keypad button left of the pill: folds the docked keypad; hides the keys on the Keypad tab (and with a
    // hardware keypad, where typing goes on without them).
    val onToggle: () -> Unit = if (dock != null && !hasHardwareKeys) ({ dock.onExpandedChange(false) }) else ({ showKeypad = !showKeypad })
    // The docked keys share one press order, so rolling between keys never swaps digits.
    val pressOrder = remember { PressOrder() }
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            // TalkBack users fold the docked keypad with an action (the handle is also a button).
            modifier = if (dock != null) panelModifier.semantics { customActions = listOf(CustomAccessibilityAction(hideKeypadLabel) { dock.onExpandedChange(false); true }) } else panelModifier,
        ) {
            Column(Modifier.fillMaxWidth()) {
                // The handle stays on top (outside the panel's own scroll) and drags the fold.
                if (dock != null) DockHandle(hideKeypadLabel, fold) { dock.onExpandedChange(false) }
                Column(
                    // With large text or a short screen the docked panel scrolls inside its own height, so it never
                    // covers the list. K3: a drag down past its top folds the keypad (panelConnection).
                    Modifier.fillMaxWidth()
                        .then(if (dock != null) Modifier.nestedScroll(fold.panelConnection).verticalScroll(rememberScrollState()) else Modifier)
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Number display. K1: only the number; backspace moved to the bottom row, beside the Call pill.
                    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                        // The number reads left to right in every language.
                        ForceLtr { NumberField(field, vm.countryIso, Modifier.fillMaxWidth()) }
                    }
                    // 1 2 3 stays left to right in right-to-left languages, like every phone keypad.
                    if (showKeypad) ForceLtr { Column { keys.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                row.forEach { (digit, letters) ->
                                    val d = digit[0]
                                    val token = remember { intArrayOf(0) }
                                    DialKey(
                                        digit, letters, localLetters(layout, d),
                                        modifier = Modifier.weight(1f),
                                        // In the docked panel a drag on a key folds the keypad, so a key waits for
                                        // the touch to settle (100 ms) and a downward swipe never types a digit.
                                        deferPress = dock != null,
                                        pressOrder = pressOrder,
                                        onPress = { token[0] = keyDown(d) },
                                        onRelease = { after -> keyUp(token[0], after) },
                                        onLong = when (d) {
                                            '0' -> ({ typed -> longPress(typed) { insert("+") } })
                                            '1' -> ({ typed -> longPress(typed) { vm.callVoicemail() } })
                                            in '2'..'9' -> ({ typed -> longPress(typed) { keypad.speedDial(d - '0', onCall = { n, label -> vm.requestCall(n, label) }) { unassigned = d - '0' } } })
                                            '*' -> ({ typed -> longPress(typed) { insert(",") } })
                                            '#' -> ({ typed -> longPress(typed) { insert(";") } })
                                            else -> null
                                        },
                                    )
                                }
                            }
                        }
                    } }
                    Spacer(Modifier.height(4.dp))
                    // A fixed-height row whose pill depends only on the SIMs, never on what is typed.
                    KeypadBottomRow(
                        vm, sims, preferredSim,
                        hasInput = input.isNotEmpty(),
                        keypadShown = showKeypad,
                        toggleLabel = if (dock != null && !hasHardwareKeys) hideKeypadLabel else toggleLabel,
                        onToggle = onToggle,
                        onCall = ::callNow,
                        onCallWith = ::callWithSim,
                        onDelete = { field.deleteBeforeCursor() },
                        onClear = { field.clearText() },
                    )
                }
            }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().focusRequester(rootFocus).onPreviewKeyEvent(::onKey).focusable()) {
        // Docked on a wide landscape screen, the keypad sits beside the list instead of under it.
        val beside = dock != null && maxWidth > maxHeight && maxWidth >= 560.dp
        val maxPanel = if (dock != null) maxHeight * (if (beside) 1f else 0.62f) else androidx.compose.ui.unit.Dp.Unspecified
        if (beside) {
            Row(Modifier.fillMaxSize()) {
                resultsArea(Modifier.weight(1f).fillMaxSize())
                // Side by side, the panel slides aside as it folds.
                if (panelShown) panel(Modifier.align(Alignment.Bottom).foldable(fold, horizontal = true).width(360.dp).heightIn(max = maxPanel))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                resultsArea(Modifier.weight(1f).fillMaxWidth())
                // The panel keeps its full layout and slides down behind the edge as it folds (no squeezed keys).
                if (panelShown) panel(if (dock != null) Modifier.foldable(fold, horizontal = false).heightIn(max = maxPanel) else Modifier)
            }
        }
        if (dock != null) {
            val fabNumber = input.takeIf { it.isNotEmpty() && !isTextSearch() }
            DockedKeypadButton(
                visible = !fold.target || (fold.dragging && fold.value < 0.35f),
                fold = fold,
                number = fabNumber?.let { Bidi.ltr(Format.number(it, vm.countryIso)) },
                badge = fabNumber?.let { app.parley.common.calls.CallPill.badge(it) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp + if (showNumberActions) NUMBER_ACTIONS_HEIGHT else 0.dp),
            ) { dock.onExpandedChange(true) }
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
    messageOn?.let { n -> ReachSheet(ReachTarget.Number(n), onDismiss = { messageOn = null }, onCall = { num -> vm.requestCall(num) }) }
    saveTemporary?.let { n ->
        app.parley.ui.temporary.SaveTemporaryDialog(
            number = Format.number(n, vm.countryIso),
            suggestedName = app.parley.messaging.TemporaryContact.suggestedName(n, null, vm.countryIso.uppercase()),
            onDismiss = { saveTemporary = null },
        ) { name, days, deleteHistory, visible ->
            saveTemporary = null
            keypad.saveTemporary(n, name, days, deleteHistory, visible) { saved ->
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

/** Second row of letters on a key for the chosen alphabet; none for Latin only. */
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
    digit: String, letters: String, local: String, modifier: Modifier = Modifier, deferPress: Boolean = false,
    pressOrder: PressOrder? = null,
    onPress: () -> Unit, onRelease: (afterMs: Long) -> Unit, onLong: ((typedThisTouch: Boolean) -> Unit)?,
) {
    val fontScale = LocalDensity.current.fontScale
    // Large, light digits with the letters (or the long-press character) beneath, no key backgrounds.
    val digitSize = ((if (digit == "*") 38f else 34f) * minOf(fontScale, 1.5f) / fontScale).sp
    val res = LocalResources.current
    // What shows under the digit: the Latin letters, or the key's long-press character (",", "+", ";").
    val under = when (digit) {
        "*" -> ","
        "#" -> ";"
        else -> letters
    }
    Column(
        modifier
            .padding(horizontal = 4.dp)
            .heightIn(min = 66.dp)
            // Typed and sounded on touch; slide off to cancel the long-press; keys roll over.
            // A soft round ripple around the key's centre instead of a filled key shape.
            .keypadKey(
                onPress = onPress, onToneStop = onRelease, onLongPress = onLong, longPressLabel = longPressLabel(res, digit),
                deferPress = deferPress, indication = keyRipple,
                // Digits keep their order, and only a downward drag (a fold) drops a waiting press.
                pressOrder = pressOrder, foldDownOnly = deferPress,
            )
            .padding(vertical = 2.dp)
            .semantics { contentDescription = keyDescription(res, digit, letters) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(digit, fontSize = digitSize, lineHeight = digitSize, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
        val subStyle = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp)
        if (digit == "1") {
            Icon(Icons.Rounded.Voicemail, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (under.isNotEmpty() || local.isEmpty()) {
            Text(under, style = subStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
        if (local.isNotEmpty()) {
            Text(local, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
        }
    }
}

/** The keys' press feedback: a round ripple, a little wider than the digit and its letters. */
private val keyRipple = androidx.compose.material3.ripple(bounded = false, radius = 38.dp)

private fun longPressLabel(res: android.content.res.Resources, digit: String): String? = when (digit) {
    "0" -> res.getString(R.string.keypad_long_plus)
    "1" -> res.getString(R.string.keypad_long_voicemail)
    "*" -> res.getString(R.string.keypad_long_pause)
    "#" -> res.getString(R.string.keypad_long_wait)
    else -> if (digit[0] in '2'..'9') res.getString(R.string.home_speed_dial) else null
}

/** What TalkBack reads for a key: "2, A B C", "1, voicemail", "star", "pound". */
private fun keyDescription(res: android.content.res.Resources, digit: String, letters: String): String = when (digit) {
    "*" -> res.getString(app.parley.ui.R.string.ui_key_star)
    "#" -> res.getString(app.parley.ui.R.string.ui_key_pound)
    "1" -> res.getString(R.string.keypad_key_voicemail)
    else -> if (letters.isEmpty()) digit else res.getString(R.string.keypad_key_letters, digit, letters.toList().joinToString(" "))
}

/**
 * A keypad result. Main rows show the contact with the matched letters highlighted and the number that will be
 * dialled ("Mobile · Primary · 06 12…"); [DialResult.secondary] rows list the contact's other numbers.
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
        AssistChip(onClick = onMessage, label = { Text(stringResource(R.string.reach_message_or_call)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Chat, null) })
        if (canSave) {
            AssistChip(onClick = onAdd, label = { Text(stringResource(R.string.keypad_add_to_contacts)) }, leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) })
            AssistChip(onClick = onTemporary, label = { Text(stringResource(R.string.keypad_save_temporary)) }, leadingIcon = { Icon(Icons.Rounded.AutoDelete, null) })
            AssistChip(onClick = onAddToExisting, label = { Text(stringResource(R.string.keypad_add_to_existing)) }, leadingIcon = { Icon(Icons.Rounded.PersonAddAlt, null) })
        }
    }
}

/** Keypad search from the header: every contact (and visible private contact) matching a name or number. */
@Composable
private fun KeypadContactSearch(vm: AppViewModel, keypad: KeypadViewModel, query: String, open: (String) -> Unit) {
    val q = query.trim()
    // Searched in the view model over names folded once per contacts change, off the main thread.
    LaunchedEffect(query) { keypad.searchQuery.value = query }
    if (q.isEmpty()) {
        app.parley.ui.EmptyState(Icons.Rounded.Search, stringResource(R.string.home_search_contacts), stringResource(R.string.keypad_search_body))
        return
    }
    val result by keypad.search.collectAsStateWithLifecycle()
    val r = result ?: return
    val found = r.contacts
    val foundVault = r.vault
    // The results of the query before this one stay up while the new search runs.
    if (found.isEmpty() && foundVault.isEmpty() && r.query == q) {
        // No match: offer to save what was typed as a new contact.
        app.parley.ui.EmptyState(
            Icons.Rounded.Search, stringResource(R.string.keypad_no_match, q),
            action = stringResource(R.string.keypad_create_contact),
            onAction = { open(if (q.any { it.isLetter() }) Routes.edit(name = q) else Routes.edit(phone = q)) },
        )
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
