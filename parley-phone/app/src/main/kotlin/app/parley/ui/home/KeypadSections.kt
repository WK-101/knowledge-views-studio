package app.parley.ui.home

import app.parley.ui.Destination
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.ux.Tips
import app.parley.ui.ForceLtr
import app.parley.ui.common.CoachMark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.DialResult
import app.parley.common.KeypadLayout
import app.parley.common.calls.PressOrder
import app.parley.ui.Routes
import app.parley.ui.ParleyFabDefaults
import app.parley.ui.ParleyShapes
import app.parley.ui.topOnly
import androidx.compose.foundation.layout.ColumnScope

/**
 * Above the keypad: nothing typed, the docked Recents list (or a hint and Paste on the Keypad tab); typed, the matches,
 * then the typed number's actions as rows (no contact matches) or as chips at the results' foot.
 */
@Composable
internal fun KeypadResults(
    vm: AppViewModel,
    open: (Destination) -> Unit,
    dock: KeypadDock?,
    fold: DockFoldState,
    input: String,
    results: List<DialResult>,
    actions: KeypadActions,
    qwerty: Boolean,
    showKeypad: Boolean,
    modifier: Modifier,
    onRecentsFocus: (Boolean) -> Unit,
    onResultFocus: (DialResult, focused: Boolean) -> Unit,
    onPaste: (String) -> Unit,
) {
    val numberActions = actions.numberActions
    val showNumberActions = numberActions.chips.isNotEmpty()
    // Docked and folded, the keypad button sits at the bottom end: rows and chips keep clear of it.
    val fabRoom = if (dock != null && !dock.expanded) ParleyFabDefaults.Reserve else 0.dp
    Box(if (dock != null) modifier.nestedScroll(fold.listConnection) else modifier) {
        if (input.isEmpty() && dock != null) {
            // Nothing typed: the recent calls, as on the Recents tab.
            Column(Modifier.fillMaxSize()) {
                CoachMark(Tips.DOCKED_KEYPAD, stringResource(R.string.home_tip_docked_keypad), enabled = dock.expanded)
                Box(Modifier.weight(1f).onFocusChanged { onRecentsFocus(it.hasFocus) }) { dock.idle() }
            }
        } else if (input.isEmpty()) {
            KeypadHint(vm.countryIso, qwerty, showKeypad, open, onPaste, Modifier.align(Alignment.Center))
        } else {
            val chipsRoom = if (showNumberActions) NUMBER_ACTIONS_HEIGHT else 0.dp
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = maxOf(chipsRoom, fabRoom))) {
                items(
                    results, key = { (it.contact?.id?.toString() ?: "n") + it.number },
                    contentType = { if (it.secondary) "secondary" else "result" },
                ) { r ->
                    DialResultRow(r, vm.countryIso, modifier = Modifier.onFocusChanged { s -> onResultFocus(r, s.isFocused) }) { actions.callResult(r) }
                }
                // No contact matches: the list is free, so the actions are full rows (as in most dialers) and
                // the chip row stays hidden.
                // A typed number no contact has, which Parley remembers ("you deleted Plumber Mike in March").
                if (numberActions.rows.isNotEmpty()) {
                    item(key = "memory") { app.parley.ui.memory.KeypadNumberMemory(vm, actions.typedNumber, open) }
                }
                items(numberActions.rows, key = { "action-" + it.name }) { action ->
                    NumberActionRow(action) { actions.runNumberAction(action) }
                }
            }
        }
        // With contacts matching, the actions sit at the foot of the results, above the keypad panel, never
        // inside it: the panel is anchored to the bottom, so anything appearing in it while typing would push the
        // keys up under the user's finger. The panel's height never changes while typing (portrait, landscape,
        // hardware keys).
        if (showNumberActions) {
            Surface(
                color = MaterialTheme.colorScheme.surface, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(NUMBER_ACTIONS_HEIGHT),
            ) {
                Box(Modifier.padding(end = fabRoom), contentAlignment = Alignment.Center) {
                    NumberActionChips(numberActions.chips, actions::runNumberAction)
                }
            }
        }
    }
}

/** Nothing typed on the Keypad tab: how to type, Paste when the clipboard may hold a number, and two tips told once. */
@Composable
private fun KeypadHint(countryIso: String, qwerty: Boolean, showKeypad: Boolean, open: (Destination) -> Unit, onPaste: (String) -> Unit, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(if (qwerty) R.string.keypad_hint_qwerty else R.string.keypad_hint_t9),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        PasteChip(countryIso, onPaste)
        // Long-press 2-9 for speed dial, told once.
        CoachMark(
            Tips.KEYPAD_SPEED_DIAL, stringResource(R.string.ux_tip_speed_dial),
            enabled = showKeypad, action = stringResource(R.string.action_set_up), onAction = { open(Routes.SpeedDial) },
        )
        // Press and hold Call for a reason, told once (after the speed-dial tip).
        CoachMark(Tips.CALL_REASON, stringResource(R.string.reason_tip), enabled = showKeypad)
    }
}

/**
 * One panel for the Keypad tab and the keypad docked in Recents: same padding, number field, keys and Call row, so the
 * number and keys sit at the same place in both. The docked one has no grab handle (it would add height above the
 * number): a swipe down anywhere on the panel folds it instead.
 */
@Composable
internal fun KeypadPanel(dock: KeypadDock?, fold: DockFoldState, hideKeypadLabel: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = ParleyShapes.sheet.topOnly(),
        // TalkBack users fold the docked keypad with an action; the keypad button beside the Call pill folds it too.
        modifier = if (dock != null) modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(hideKeypadLabel) { dock.onExpandedChange(false); true })
        } else modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                // With large text or a short screen the docked panel scrolls inside its own height, so it never
                // covers the list. A drag down past its top folds the keypad (panelConnection).
                Modifier.fillMaxWidth()
                    .then(if (dock != null) Modifier.nestedScroll(fold.panelConnection).verticalScroll(rememberScrollState()) else Modifier)
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

/**
 * The twelve keys. 1 2 3 stays left to right in right-to-left languages, like every phone keypad. In the docked panel
 * ([deferPress]) a drag on a key folds the keypad, so a key waits for the touch to settle (100 ms) and a downward swipe
 * never types a digit. [onLong] gets the key and whether its own touch typed it.
 */
@Composable
internal fun KeypadKeyGrid(
    layout: KeypadLayout,
    deferPress: Boolean,
    pressOrder: PressOrder,
    keyDown: (Char) -> Int,
    keyUp: (token: Int, afterMs: Long) -> Unit,
    onLong: (Char, typedThisTouch: Boolean) -> Unit,
) {
    ForceLtr {
        Column {
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { (digit, letters) ->
                        val d = digit[0]
                        val token = remember { intArrayOf(0) }
                        DialKey(
                            digit, letters, localLetters(layout, d),
                            modifier = Modifier.weight(1f),
                            deferPress = deferPress,
                            pressOrder = pressOrder,
                            onPress = { token[0] = keyDown(d) },
                            onRelease = { after -> keyUp(token[0], after) },
                            onLong = { typed -> onLong(d, typed) },
                        )
                    }
                }
            }
        }
    }
}
