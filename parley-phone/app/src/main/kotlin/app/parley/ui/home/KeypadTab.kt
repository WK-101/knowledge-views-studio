package app.parley.ui.home

import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.DialResult
import app.parley.common.PhoneNumbers
import app.parley.ui.Avatar
import app.parley.ui.CallColors
import app.parley.ui.MatchStyle
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.highlight

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeypadTab(vm: AppViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    val input by vm.dialInput.collectAsStateWithLifecycle()
    val results by vm.dialResults.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var unassigned by remember { mutableStateOf<Int?>(null) }

    val tone = remember {
        try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (_: Exception) { null }
    }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val systemTones = remember { Settings.System.getInt(context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1 }

    fun press(c: Char) {
        vm.dialInput.value = input + c
        if (settings.dialpadHaptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (settings.dialpadTones && systemTones) dtmfTone[c]?.let { tone?.startTone(it, 120) }
    }

    fun callNow() {
        val n = input.trim()
        if (n.isEmpty()) {
            // Recall the last dialled number, like most dialers.
            vm.c.callLog.calls.value?.firstOrNull { it.type == app.parley.common.CallType.OUTGOING }?.let { vm.dialInput.value = it.number }
            return
        }
        vm.requestCall(n, results.firstOrNull { it.contact != null && PhoneNumbers.same(it.number, n, vm.countryIso) }?.contact?.displayName)
    }

    Column(Modifier.fillMaxSize()) {
        // Results
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (input.isEmpty()) {
                Text(
                    "Type a number or letters of a name (T9)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), reverseLayout = false) {
                    items(results, key = { (it.contact?.id?.toString() ?: "n") + it.number }) { r -> DialResultRow(r, vm.countryIso) { vm.requestCall(r.number, r.contact?.displayName) } }
                    if (results.none { it.contact != null } && input.length >= 3) {
                        item {
                            ListItem(
                                headlineContent = { Text("Create new contact") },
                                leadingContent = { Icon(Icons.Rounded.PersonAdd, null) },
                                modifier = Modifier.clickable { open(Routes.edit(phone = input)) },
                            )
                            ListItem(
                                headlineContent = { Text("Add to a contact") },
                                leadingContent = { Icon(Icons.Rounded.PersonAdd, null) },
                                modifier = Modifier.clickable { open(Routes.pick(input)) },
                            )
                            ListItem(
                                headlineContent = { Text("Send message") },
                                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) },
                                modifier = Modifier.clickable { Intents.sms(context, input) },
                            )
                        }
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Number display
                Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(48.dp))
                    Text(
                        Format.number(input, vm.countryIso).ifEmpty { input },
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = if (input.length > 14) 24.sp else 32.sp),
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Number: $input" },
                    )
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                            enabled = input.isNotEmpty(),
                            onClick = { vm.dialInput.value = input.dropLast(1) },
                            onLongClick = { vm.dialInput.value = "" },
                        ).semantics { contentDescription = "Delete" },
                        contentAlignment = Alignment.Center,
                    ) { if (input.isNotEmpty()) Icon(Icons.AutoMirrored.Rounded.Backspace, null) }
                }
                keys.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { (digit, letters) ->
                            val d = digit[0]
                            DialKey(
                                digit, letters,
                                onClick = { press(d) },
                                onLong = when (d) {
                                    '0' -> ({ vm.dialInput.value = input + "+" })
                                    '1' -> ({ vm.callVoicemail() })
                                    in '2'..'9' -> ({ vm.speedDial(d - '0') { unassigned = d - '0' } })
                                    '*' -> ({ vm.dialInput.value = input + "," })
                                    '#' -> ({ vm.dialInput.value = input + ";" })
                                    else -> null
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (sims.size >= 2 && input.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        sims.take(2).forEach { sim ->
                            CallButton(label = sim.label) { vm.place(input.trim(), sim.id) }
                        }
                    }
                } else {
                    CallButton(label = null, onClick = ::callNow)
                }
            }
        }
    }

    unassigned?.let { key ->
        AlertDialog(
            onDismissRequest = { unassigned = null },
            title = { Text("Speed dial $key is empty") },
            text = { Text("Assign a number to key $key to call it with a long-press.") },
            confirmButton = { TextButton({ unassigned = null; open(Routes.SPEED_DIAL) }) { Text("Set up") } },
            dismissButton = { TextButton({ unassigned = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(digit: String, letters: String, onClick: () -> Unit, onLong: (() -> Unit)?) {
    Column(
        Modifier
            .size(width = 96.dp, height = 64.dp)
            .clip(RoundedCornerShape(32.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .semantics { contentDescription = digit + if (letters.isNotEmpty()) " $letters" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(digit, fontSize = 30.sp, fontWeight = FontWeight.Normal)
        if (digit == "1") {
            Icon(Icons.Rounded.Voicemail, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(letters, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CallButton(label: String?, onClick: () -> Unit) {
    Row(
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(CallColors.Accept).clickable(onClick = onClick)
            .padding(horizontal = if (label != null) 20.dp else 40.dp)
            .semantics { contentDescription = if (label != null) "Call with $label" else "Call" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (label != null) Icons.Rounded.SimCard else Icons.Rounded.Call, null, tint = Color.White)
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun DialResultRow(r: DialResult, countryIso: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val c = r.contact
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(c?.displayName ?: r.number, c?.photoUri, 40.dp) },
        headlineContent = {
            if (c != null) Text(highlight(c.displayName, r.match.nameRanges, MatchStyle), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(Format.number(r.number, countryIso))
        },
        supportingContent = {
            if (c != null) {
                val p = c.phones.firstOrNull { it.number == r.number }
                Text(listOfNotNull(p?.let { Format.phoneType(context.resources, it.type, it.label) }, Format.number(r.number, countryIso)).joinToString(" · "))
            } else {
                Text("Recent")
            }
        },
        trailingContent = { Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.primary) },
    )
}
