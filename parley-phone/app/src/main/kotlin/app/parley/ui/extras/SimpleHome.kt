package app.parley.ui.extras

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.UiEvent
import app.parley.common.StartTab
import app.parley.common.calls.CallSource
import app.parley.common.extras.SimpleSetup
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.CallColors
import app.parley.ui.ForceLtr

/**
 * X4: the simple home, shown instead of the tabs while simple mode is on. Big photo tiles (up to 3 × 3), each asks
 * "Call Ana?" first; a large keypad; nothing else to get lost in. Leaving needs a long press on "Leave", a
 * confirmation, and the app lock's check when the app lock is on. The app lock and "Hide screen content" apply here
 * as everywhere (MainActivity draws this inside them).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SimpleHome(vm: AppViewModel) {
    val context = LocalContext.current
    val res = LocalResources.current
    val cfg by vm.c.extras.simple.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tiles = remember(cfg.people, contacts) { SimpleSetup.resolve(cfg.people, contacts.orEmpty()) }
    var keypad by rememberSaveable { mutableStateOf(false) }
    var digits by rememberSaveable { mutableStateOf("") }
    var calling by remember { mutableStateOf<Pair<String, String>?>(null) }
    var askExit by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Links into the app still work where they make sense here: a number to dial opens the keypad with it.
    LaunchedEffect(Unit) {
        vm.navEvents.collect { e ->
            when {
                e is NavEvent.Tab && e.tab == StartTab.KEYPAD -> { keypad = true; e.dial?.let { d -> digits = d } }
                e is NavEvent.Route && e.route == ExtrasRoutes.SIMPLE_IMPORT -> snackbar.showSnackbar(res.getString(R.string.x_simple_leave_to_import))
                else -> Unit
            }
        }
    }
    LaunchedEffect(Unit) { vm.uiEvents.collect { e -> if (e is UiEvent.Message) snackbar.showSnackbar(e.text) } }
    BackHandler(enabled = keypad) { keypad = false }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        android.text.format.DateFormat.format(android.text.format.DateFormat.getBestDateTimePattern(res.configuration.locales[0], "EEEEdMMMM"), System.currentTimeMillis()).toString(),
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f),
                    )
                    // Press and hold, so a stray tap never leaves simple mode.
                    val leave = stringResource(R.string.x_simple_leave)
                    app.parley.ui.common.CoachMarkAnchor(app.parley.common.ux.Tips.SIMPLE_LEAVE, stringResource(R.string.x_simple_leave_hold)) {
                    Text(
                        leave,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClickLabel = leave,
                                onClick = { vm.toast(res.getString(R.string.x_simple_leave_hold)) },
                                onLongClick = { askExit = true },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (keypad) {
                    SimpleKeypad(digits, { digits = it }, onClose = { keypad = false }) { calling = digits to digits }
                } else {
                    TileGrid(tiles, Modifier.weight(1f)) { name, number -> calling = name to number }
                    if (cfg.showKeypad) {
                        Spacer(Modifier.height(12.dp))
                        Button({ keypad = true }, Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(24.dp)) {
                            Icon(Icons.Rounded.Dialpad, null, Modifier.size(32.dp))
                            Spacer(Modifier.size(12.dp))
                            Text(stringResource(R.string.x_simple_keypad_open), fontSize = 24.sp)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
        }
    }

    calling?.let { (name, number) ->
        AlertDialog(
            onDismissRequest = { calling = null },
            title = { Text(stringResource(R.string.x_simple_call_q, name), style = MaterialTheme.typography.headlineMedium) },
            confirmButton = {
                Button(
                    { calling = null; vm.requestCall(number, name.takeIf { it != number }, skipConfirm = true, source = CallSource.CONTACT) },
                    colors = ButtonDefaults.buttonColors(containerColor = CallColors.Accept),
                    modifier = Modifier.height(64.dp),
                ) {
                    Icon(Icons.Rounded.Call, null, Modifier.size(28.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.x_simple_call), fontSize = 22.sp)
                }
            },
            dismissButton = { TextButton({ calling = null }, Modifier.height(64.dp)) { Text(stringResource(R.string.dc_cancel), fontSize = 20.sp) } },
        )
    }
    if (askExit) AlertDialog(
        onDismissRequest = { askExit = false },
        title = { Text(stringResource(R.string.x_simple_leave_q)) },
        text = { Text(stringResource(R.string.x_simple_leave_body)) },
        confirmButton = {
            TextButton({
                askExit = false
                val act = context as? androidx.fragment.app.FragmentActivity
                if (settings.appLock && act != null) {
                    app.parley.security.AppLock.authenticate(act, res.getString(R.string.x_simple_leave_q)) { ok -> if (ok) vm.c.extras.updateSimple { it.copy(enabled = false) } }
                } else {
                    vm.c.extras.updateSimple { it.copy(enabled = false) }
                }
            }) { Text(stringResource(R.string.x_simple_leave)) }
        },
        dismissButton = { TextButton({ askExit = false }) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

/** Up to nine big tiles filling the space: photo (or initials) and name. */
@Composable
private fun TileGrid(tiles: List<SimpleSetup.Resolved>, modifier: Modifier, onCall: (String, String) -> Unit) {
    if (tiles.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.x_simple_no_people), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        }
        return
    }
    val (rows, cols) = SimpleSetup.grid(tiles.size)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in 0 until cols) {
                    val t = tiles.getOrNull(r * cols + c)
                    Box(Modifier.weight(1f).fillMaxSize()) { if (t != null) Tile(t, onCall) }
                }
            }
        }
    }
}

@Composable
private fun Tile(t: SimpleSetup.Resolved, onCall: (String, String) -> Unit) {
    val label = stringResource(R.string.circle_call_who, t.person.name)
    Surface(
        onClick = { onCall(t.person.name, t.person.number) },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxSize().semantics { onClick(label) { onCall(t.person.name, t.person.number); true } },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
            val side = minOf(maxWidth, maxHeight - 40.dp).coerceAtLeast(40.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Avatar(t.person.name, t.contact?.photoUri, side)
                Text(
                    t.person.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The larger keypad: big keys, big digits, a big green Call button. */
@Composable
private fun SimpleKeypad(digits: String, onDigits: (String) -> Unit, onClose: () -> Unit, onCall: () -> Unit) = Column(Modifier.fillMaxSize()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose, Modifier.size(56.dp)) { Icon(Icons.Rounded.Close, stringResource(R.string.x_simple_keypad_close), Modifier.size(32.dp)) }
        ForceLtr {
            Text(
                Bidi.ltr(digits), style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
        }
        IconButton({ onDigits(digits.dropLast(1)) }, Modifier.size(56.dp), enabled = digits.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.x_simple_keypad_delete), Modifier.size(32.dp))
        }
    }
    ForceLtr {
        Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("123", "456", "789", "*0#").forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        // Hold 0 for +, like the regular keypad.
                        Key(k.toString(), Modifier.weight(1f).fillMaxSize(), onLong = if (k == '0') ({ onDigits(digits + "+") }) else null) { onDigits(digits + k) }
                    }
                }
            }
        }
    }
    Button(
        onCall, enabled = digits.isNotBlank(), modifier = Modifier.fillMaxWidth().height(80.dp), shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CallColors.Accept),
    ) {
        Icon(Icons.Rounded.Call, null, Modifier.size(36.dp))
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.x_simple_call), fontSize = 26.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(k: String, modifier: Modifier, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(role = Role.Button, onLongClick = onLong, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(k, fontSize = 40.sp, style = MaterialTheme.typography.displaySmall) }
}
