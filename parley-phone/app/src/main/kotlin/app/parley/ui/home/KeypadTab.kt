package app.parley.ui.home

import app.parley.ui.Destination
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import app.parley.R
import app.parley.common.KeypadKeys
import app.parley.common.calls.CallPill
import app.parley.ui.Bidi
import app.parley.ui.ForceLtr
import app.parley.ui.activityViewModel
import app.parley.ui.avatarSize
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.DialResult
import app.parley.common.calls.PressOrder
import app.parley.ui.Avatar
import app.parley.ui.PrivateMarked
import app.parley.ui.MatchStyle
import app.parley.ui.common.Format
import app.parley.ui.highlight
import app.parley.ui.ParleyListItem

internal val keys = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

/** `*#06#`: Android only shows the IMEI to the system, so Parley explains where to find it. */
internal const val IMEI_CODE = "*#06#"

/** Height of the typed number's action chips at the foot of the results (outside the keypad panel). */
internal val NUMBER_ACTIONS_HEIGHT = 56.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeypadTab(vm: AppViewModel, open: (Destination) -> Unit, searchQuery: String? = null, dock: KeypadDock? = null) {
    // Search from the header: contacts by name or number, in place of the keypad until the search closes.
    // What's typed, its results, the SIM and the keypad's actions live in KeypadViewModel; this draws the keypad.
    val keypad: KeypadViewModel = activityViewModel()
    if (searchQuery != null) {
        KeypadContactSearch(vm, keypad, searchQuery, open)
        return
    }
    val input by keypad.input.collectAsStateWithLifecycle()
    val results by keypad.results.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    LaunchedEffect(sims.size) { keypad.simCount.value = sims.size }
    val layout by keypad.layout.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    // One dialog at a time: a speed-dial key not set up, a call reason, message, save for a while, the IMEI.
    var dialog by remember { mutableStateOf<KeypadDialog?>(null) }
    val scope = rememberCoroutineScope()

    /** Result row focused with the D-pad; Call/Enter calls it. */
    var focusedResult by remember { mutableStateOf<DialResult?>(null) }

    // Phones with a hardware keypad or keyboard type on it; the on-screen keypad starts hidden.
    val configuration = LocalConfiguration.current
    val hasHardwareKeys = hardwareKeysAvailable(configuration)
    val qwerty = configuration.keyboard == Configuration.KEYBOARD_QWERTY && hasHardwareKeys
    var showKeypad by rememberSaveable(hasHardwareKeys) { mutableStateOf(!hasHardwareKeys) }
    val tones = rememberDialTones()

    // The number is an editable field (cursor, selection, paste) that never opens the on-screen keyboard.
    val field = rememberTextFieldState(input)
    LaunchedEffect(input) { if (field.text.toString() != input) field.setTextAndPlaceCursorAtEnd(input) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { if (it != keypad.input.value) keypad.input.value = it } }
    LaunchedEffect(input) { if (input == IMEI_CODE) dialog = KeypadDialog.Imei }

    fun insert(text: String) = field.insertAtCursor(text)

    // Typing on a hardware keypad while the docked keypad is folded unfolds it (the number shows there).
    fun unfold() { dock?.let { if (!it.expanded) it.onExpandedChange(true) } }

    fun press(c: Char) {
        unfold()
        insert(c.toString())
        if (settings.dialpadHaptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        tones.play(c, settings.dialpadTones)
    }

    // On-screen keys start their tone on touch and hold it until release (DialTones).
    fun keyDown(c: Char): Int {
        insert(c.toString())
        if (settings.dialpadHaptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        return tones.start(c, settings.dialpadTones)
    }

    /**
     * A long-press replaces the digit its own touch typed ([typedThisTouch]); TalkBack's long click typed nothing,
     * so nothing is deleted then.
     */
    fun longPress(typedThisTouch: Boolean, action: () -> Unit) {
        if (typedThisTouch) field.deleteBeforeCursor()
        action()
    }

    val actions = KeypadActions(vm, keypad, input, results, open) { dialog = it }

    // A D-pad focus inside the docked Recents list (only shown while nothing is typed).
    var recentsHasFocus by remember { mutableStateOf(false) }
    // Typing replaces the list, and its focus goes with it.
    val typed = input.isNotEmpty()
    LaunchedEffect(typed) { if (typed) recentsHasFocus = false }
    val keyInput = KeypadKeyInput(
        field, input, qwerty,
        takes = { key ->
            KeypadKeys.keypadTakes(key, docked = dock != null, expanded = dock?.expanded ?: true, recentsFocused = recentsHasFocus && input.isEmpty())
        },
        call = { focusedResult?.let(actions::callResult) ?: actions.callNow() },
        press = ::press,
        type = { ch ->
            unfold()
            insert(ch.toString())
        },
        textSearch = actions::isTextSearch,
    )

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // Docked at the foot of Recents, the panel folds away (scrolling the list, a swipe down on the panel) and a
    // keypad button brings it back; the panel's height still never changes while typing.
    // The fold follows the finger and springs open or folded (see DockFoldState).
    val density = LocalDensity.current
    val fold = remember { DockFoldState(dock?.expanded ?: true, scope, density) }
    val latestDock by rememberUpdatedState(dock)
    fold.onSettle = { open -> latestDock?.let { if (it.expanded != open) it.onExpandedChange(open) } }
    // The home screen's state (Back, dial intents, typing on a hardware keypad) moves the fold too.
    LaunchedEffect(dock?.expanded) { dock?.let { if (fold.target != it.expanded) fold.animateTo(it.expanded) } }
    val panelShown = dock == null || fold.value > 0f || fold.dragging

    // The SIM a plain Call would use for what's typed (remembered, a label's, else the default), shown on its segment.
    val preferredSim by keypad.preferredSim.collectAsStateWithLifecycle()

    val resultsArea: @Composable (Modifier) -> Unit = { areaModifier ->
        KeypadResults(
            vm, open, dock, fold, input, results, actions, qwerty, showKeypad, areaModifier,
            onRecentsFocus = { recentsHasFocus = it },
            onResultFocus = { r, focused -> if (focused) focusedResult = r else if (focusedResult == r) focusedResult = null },
            onPaste = { text -> field.setTextAndPlaceCursorAtEnd(text) },
        )
    }

    val hideKeypadLabel = stringResource(R.string.keypad_hide)
    val toggleLabel = stringResource(if (showKeypad) R.string.keypad_hide else R.string.keypad_show)
    // The keypad button left of the pill: folds the docked keypad; hides the keys on the Keypad tab (and with a
    // hardware keypad, where typing goes on without them).
    val onToggle: () -> Unit = if (dock != null && !hasHardwareKeys) ({ dock.onExpandedChange(false) }) else ({ showKeypad = !showKeypad })
    // The docked keys share one press order, so rolling between keys never swaps digits.
    val pressOrder = remember { PressOrder() }
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        KeypadPanel(dock, fold, hideKeypadLabel, panelModifier) {
            // Number display. Only the number; backspace moved to the bottom row, beside the Call pill.
            Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                // The number reads left to right in every language.
                ForceLtr { NumberField(field, vm.countryIso, Modifier.fillMaxWidth()) }
            }
            if (showKeypad) {
                KeypadKeyGrid(layout, deferPress = dock != null, pressOrder = pressOrder, keyDown = ::keyDown, keyUp = tones::stop) { d, typedThisTouch ->
                    when (d) {
                        '0' -> longPress(typedThisTouch) { insert("+") }
                        '1' -> longPress(typedThisTouch) { vm.callVoicemail() }
                        in '2'..'9' -> longPress(typedThisTouch) {
                            keypad.speedDial(d - '0', onCall = { n, label -> vm.requestCall(n, label) }) { dialog = KeypadDialog.SpeedDialEmpty(d - '0') }
                        }
                        '*' -> longPress(typedThisTouch) { insert(",") }
                        '#' -> longPress(typedThisTouch) { insert(";") }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // A fixed-height row whose pill depends only on the SIMs, never on what is typed.
            KeypadBottomRow(
                vm, sims, preferredSim,
                hasInput = input.isNotEmpty(),
                keypadShown = showKeypad,
                toggleLabel = if (dock != null && !hasHardwareKeys) hideKeypadLabel else toggleLabel,
                onToggle = onToggle,
                onCall = actions::callNow,
                onCallWith = actions::callWithSim,
                onDelete = { field.deleteBeforeCursor() },
                onClear = { field.clearText() },
                onCallLongPress = actions::callWithReason,
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(rootFocus).onPreviewKeyEvent(keyInput::onKey).focusable()) {
        // Docked on a wide landscape screen, the keypad sits beside the list instead of under it.
        val beside = dock != null && maxWidth > maxHeight && maxWidth >= 560.dp
        val maxPanel = if (dock != null) maxHeight * (if (beside) 1f else 0.62f) else Dp.Unspecified
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
            val fabNumber = input.takeIf { it.isNotEmpty() && !actions.isTextSearch() }
            DockedKeypadButton(
                visible = !fold.target || (fold.dragging && fold.value < 0.35f),
                fold = fold,
                number = fabNumber?.let { Bidi.ltr(Format.number(it, vm.countryIso)) },
                badge = fabNumber?.let { CallPill.badge(it) },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp + if (actions.numberActions.chips.isNotEmpty()) NUMBER_ACTIONS_HEIGHT else 0.dp),
            ) { dock.onExpandedChange(true) }
        }
    }

    KeypadDialogHost(vm, keypad, dialog, field, scope, open) { dialog = null }
}

/**
 * A keypad result. Main rows show the contact with the matched letters highlighted and the number that will be
 * dialled ("Mobile · Primary · 06 12…"); [DialResult.secondary] rows list the contact's other numbers.
 */
@Composable
internal fun DialResultRow(r: DialResult, countryIso: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val resources = LocalResources.current
    val c = r.contact
    val phone = c?.phones?.firstOrNull { it.number == r.number }
    val type = phone?.let { Format.phoneType(resources, it.type, it.label) }
    // Formatted once per number, not on every recomposition while the results change under each keystroke.
    val shown = remember(r.number, countryIso) { Bidi.ltr(Format.number(r.number, countryIso)) }
    if (r.secondary) {
        ParleyListItem(
            modifier = modifier.clickable(onClick = onClick),
            leadingContent = { Spacer(Modifier.width(40.dp)) },
            headlineContent = { Text(shown, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = type?.let { { Text(it) } },
            trailingContent = {
                Icon(
                    Icons.Rounded.Call,
                    stringResource(R.string.action_call_named, listOfNotNull(c?.displayName, type).joinToString(" ")),
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
        return
    }
    ParleyListItem(
        modifier = modifier.clickable(onClick = onClick),
        // A private contact (negative id): the lock on the photo, as in Contacts.
        leadingContent = { PrivateMarked((c?.id ?: 0L) < 0) { Avatar(c?.displayName ?: r.number, c?.photoUri, avatarSize()) } },
        headlineContent = {
            if (c != null) Text(highlight(c.displayName, r.match.nameRanges, MatchStyle), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(shown)
        },
        supportingContent = {
            if (c != null) {
                Text(
                    listOfNotNull(
                        type, if (r.primary) stringResource(R.string.keypad_primary) else null, shown,
                    ).joinToString(stringResource(R.string.main_separator)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(stringResource(R.string.keypad_recent))
            }
        },
        trailingContent = { Icon(Icons.Rounded.Call, stringResource(R.string.action_call), tint = MaterialTheme.colorScheme.primary) },
    )
}
