package app.parley.telecom.ui

import android.annotation.SuppressLint
import android.telecom.TelecomManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.common.AnswerGesture
import app.parley.common.Verification
import app.parley.telecom.AudioRoute
import app.parley.telecom.AudioUi
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.RouteType
import app.parley.ui.Avatar
import app.parley.ui.CallColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InCallScreen(
    calls: List<CallUi>,
    audio: AudioUi,
    ended: CallUi?,
    answerGesture: AnswerGesture,
    quickReplies: List<String>,
    keypadOpen: Boolean,
    onKeypad: (Boolean) -> Unit,
    onAddCall: () -> Unit,
    onOpenContact: (CallUi) -> Unit,
) {
    val live = calls.filter { it.isLive }
    val primary = live.firstOrNull { it.state == CallState.RINGING }
        ?: live.firstOrNull { it.state == CallState.ACTIVE }
        ?: live.firstOrNull { it.state == CallState.DIALING || it.state == CallState.CONNECTING || it.state == CallState.SELECT_ACCOUNT }
        ?: live.firstOrNull()
    val others = live.filter { it.id != primary?.id }
    val shown = primary ?: ended ?: calls.firstOrNull()

    var routeSheet by remember { mutableStateOf(false) }
    var replySheet by remember { mutableStateOf(false) }
    var manageSheet by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<String?>(null) }

    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.surface, scheme.surface))),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            others.forEach { other -> OtherCallBanner(other, onSwap = { primary?.let { CallManager.swap(it.id) } }) }
            Spacer(Modifier.height(if (others.isEmpty()) 48.dp else 16.dp))
            if (shown != null) {
                CallerHeader(shown, primary == null, onOpenContact, compact = keypadOpen && primary?.state != CallState.RINGING)
            }
            Spacer(Modifier.weight(1f))

            when {
                primary == null -> Text(
                    ended?.disconnectReason ?: "Call ended",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 64.dp),
                )
                primary.state == CallState.RINGING -> IncomingControls(
                    call = primary,
                    gesture = answerGesture,
                    hasActiveCall = others.any { it.state == CallState.ACTIVE },
                    onMessage = { replySheet = true },
                )
                primary.state == CallState.SELECT_ACCOUNT -> SimPicker(primary)
                else -> {
                    AnimatedContent(keypadOpen, label = "keypad") { open ->
                        if (open) {
                            DtmfKeypad(onKey = { CallManager.playDtmf(primary.id, it) }, onClose = { onKeypad(false) })
                        } else {
                            ControlGrid(
                                call = primary,
                                others = others,
                                audio = audio,
                                onKeypad = { onKeypad(true) },
                                onAudio = { if (audio.hasExternal) routeSheet = true else CallManager.toggleSpeaker() },
                                onAddCall = onAddCall,
                                onManage = { manageSheet = true },
                            )
                        }
                    }
                    TextButton(onClick = { noteFor = primary.id }) { Text("Add a note") }
                    Spacer(Modifier.height(8.dp))
                    EndCallButton { CallManager.hangup(primary.id) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    noteFor?.let { id ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            title = { Text("Note about this call") },
            text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, minLines = 3, placeholder = { Text("Only stored on this phone") }) },
            confirmButton = { TextButton({ if (text.isNotBlank()) CallManager.saveNote(id, text.trim()); noteFor = null }) { Text("Save") } },
            dismissButton = { TextButton({ noteFor = null }) { Text("Cancel") } },
        )
    }

    val postDial = primary?.postDialWait
    if (postDial != null) {
        AlertDialog(
            onDismissRequest = { CallManager.postDialContinue(primary.id, false) },
            title = { Text("Send tones?") },
            text = { Text(postDial) },
            confirmButton = { TextButton({ CallManager.postDialContinue(primary.id, true) }) { Text("Send") } },
            dismissButton = { TextButton({ CallManager.postDialContinue(primary.id, false) }) { Text("Cancel") } },
        )
    }

    if (routeSheet) {
        ModalBottomSheet(onDismissRequest = { routeSheet = false }) {
            Text("Audio output", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            audio.routes.forEach { r ->
                ListItem(
                    headlineContent = { Text(r.name) },
                    leadingContent = { Icon(routeIcon(r), null) },
                    trailingContent = { if (audio.current?.key == r.key) Icon(Icons.Rounded.Verified, "Selected", tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { CallManager.setRoute(r); routeSheet = false },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (replySheet && primary != null) {
        ModalBottomSheet(onDismissRequest = { replySheet = false }) {
            Text("Reply with a message", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            quickReplies.forEach { msg ->
                ListItem(
                    headlineContent = { Text(msg) },
                    modifier = Modifier.clickable { CallManager.reject(primary.id, msg); replySheet = false },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val conference = live.firstOrNull { it.isConference }
    if (manageSheet && conference != null) {
        ModalBottomSheet(onDismissRequest = { manageSheet = false }) {
            Text("Conference call", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            conference.children.forEach { child ->
                ListItem(
                    headlineContent = { Text(child.title) },
                    supportingContent = { child.number?.takeIf { child.name != null }?.let { Text(it) } },
                    leadingContent = { Avatar(child.title, child.photoUri, 40.dp) },
                    trailingContent = {
                        Row {
                            if (child.canSeparate) TextButton({ CallManager.separate(child.id); manageSheet = false }) { Text("Private") }
                            if (child.canDisconnectChild) TextButton({ CallManager.hangup(child.id) }) { Text("End", color = CallColors.Decline) }
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CallerHeader(call: CallUi, ended: Boolean, onOpenContact: (CallUi) -> Unit, compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) {
            Avatar(call.title, call.photoUri, size = 112.dp, modifier = Modifier.clickable(enabled = !call.hidden) { onOpenContact(call) })
            Spacer(Modifier.height(20.dp))
        }
        Text(
            call.title,
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = listOfNotNull(call.label, call.number?.takeIf { call.name != null }).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        StatusLine(call, ended)
        if (!compact && (call.note != null || call.lastCall != null)) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    call.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
                    call.lastCall?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer) }
                }
            }
        }
        if (call.unknown && call.state == CallState.RINGING) {
            Text("Not in your contacts", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            call.accountLabel?.let { Chip(Icons.Rounded.SimCard, it) }
            when (call.verification) {
                Verification.PASSED -> Chip(Icons.Rounded.Verified, "Verified number")
                Verification.FAILED -> Chip(Icons.Rounded.Warning, "Possibly spoofed", warn = true)
                Verification.NOT_VERIFIED -> Unit
            }
            if (call.isEmergency) Chip(Icons.Rounded.Warning, "Emergency call", warn = true)
        }
    }
}

@Composable
private fun StatusLine(call: CallUi, ended: Boolean) {
    val text = when {
        ended -> call.disconnectReason ?: "Call ended"
        call.silenced -> "Silenced by your blocking rules"
        call.state == CallState.RINGING -> "Incoming call"
        call.state == CallState.DIALING || call.state == CallState.CONNECTING || call.state == CallState.NEW -> "Calling…"
        call.state == CallState.HOLDING -> "On hold"
        call.state == CallState.SELECT_ACCOUNT -> "Choose a SIM"
        call.state == CallState.DISCONNECTING -> "Ending…"
        call.state == CallState.ACTIVE -> null
        else -> ""
    }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    } else {
        CallTimer(call.connectTimeMillis)
    }
}

@Composable
private fun CallTimer(connectTime: Long) {
    val elapsed by produceState(0L, connectTime) {
        while (true) {
            value = if (connectTime > 0) (System.currentTimeMillis() - connectTime) / 1000 else 0
            delay(500)
        }
    }
    val h = elapsed / 3600
    val m = (elapsed % 3600) / 60
    val s = elapsed % 60
    val txt = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    Text(txt, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { contentDescription = "Call duration $txt" })
}

@Composable
private fun Chip(icon: ImageVector, text: String, warn: Boolean = false) {
    val color = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun OtherCallBanner(call: CallUi, onSwap: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(20.dp)).clickable(enabled = call.state == CallState.HOLDING, onClick = onSwap),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(call.title, call.photoUri, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(call.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (call.state) {
                        CallState.HOLDING -> "On hold · tap to switch"
                        CallState.ACTIVE -> "Active"
                        CallState.RINGING -> "Incoming"
                        else -> "Connecting…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (call.state == CallState.HOLDING) Icon(Icons.Rounded.SwapCalls, "Switch calls")
        }
    }
}

@Composable
private fun ControlGrid(
    call: CallUi,
    others: List<CallUi>,
    audio: AudioUi,
    onKeypad: () -> Unit,
    onAudio: () -> Unit,
    onAddCall: () -> Unit,
    onManage: () -> Unit,
) {
    val speakerOn = audio.current?.type == RouteType.SPEAKER
    val audioIcon = audio.current?.let { routeIcon(it) } ?: Icons.AutoMirrored.Rounded.VolumeUp
    val audioLabel = if (audio.hasExternal) audio.current?.name ?: "Audio" else "Speaker"
    val canSwap = others.any { it.state == CallState.HOLDING } || call.canSwap
    val controls = buildList {
        add(ControlSpec(Icons.Rounded.MicOff, if (audio.muted) "Unmute" else "Mute", audio.muted, call.canMute) { CallManager.setMuted(!audio.muted) })
        add(ControlSpec(Icons.Rounded.Dialpad, "Keypad", false, true, onKeypad))
        add(ControlSpec(audioIcon, audioLabel, speakerOn || (audio.hasExternal && audio.current?.type != RouteType.EARPIECE), audio.routes.isNotEmpty(), onAudio))
        add(ControlSpec(Icons.Rounded.Pause, if (call.state == CallState.HOLDING) "Resume" else "Hold", call.state == CallState.HOLDING, call.canHold) { CallManager.toggleHold(call.id) })
        when {
            call.canMerge -> add(ControlSpec(Icons.Rounded.CallMerge, "Merge", false, true) { CallManager.merge(call.id) })
            canSwap -> add(ControlSpec(Icons.Rounded.SwapCalls, "Swap", false, true) { CallManager.swap(call.id) })
            else -> add(ControlSpec(Icons.Rounded.Add, "Add call", false, others.isEmpty(), onAddCall))
        }
        if (call.isConference) add(ControlSpec(Icons.Rounded.Groups, "Manage", false, true, onManage))
        else if (call.canMerge || canSwap) add(ControlSpec(Icons.Rounded.Add, "Add call", false, true, onAddCall))
        else add(ControlSpec(Icons.Rounded.PhoneInTalk, "Contacts", false, true, onAddCall))
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        controls.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { ControlButton(it) }
            }
        }
    }
}

private data class ControlSpec(val icon: ImageVector, val label: String, val active: Boolean, val enabled: Boolean, val onClick: () -> Unit)

@Composable
private fun ControlButton(spec: ControlSpec) {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Box(
            Modifier
                .size(width = 80.dp, height = 64.dp)
                .clip(RoundedCornerShape(if (spec.active) 20.dp else 32.dp))
                .background(if (spec.active) scheme.primary else scheme.surfaceContainerHigh)
                .clickable(enabled = spec.enabled, role = Role.Button, onClick = spec.onClick)
                .semantics {
                    contentDescription = spec.label
                    stateDescription = if (spec.active) "On" else "Off"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                spec.icon, null,
                tint = when {
                    !spec.enabled -> scheme.onSurface.copy(alpha = 0.38f)
                    spec.active -> scheme.onPrimary
                    else -> scheme.onSurface
                },
            )
        }
        Text(
            spec.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp),
            color = if (spec.enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EndCallButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 160.dp, height = 72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(CallColors.Decline)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "End call" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
@Composable
private fun SimPicker(call: CallUi) {
    val context = LocalContext.current
    val accounts = remember {
        try {
            val tm = context.getSystemService(TelecomManager::class.java)
            tm.callCapablePhoneAccounts.map { it.id to (tm.getPhoneAccount(it)?.label?.toString() ?: it.id) }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Call with", style = MaterialTheme.typography.titleMedium)
        accounts.forEach { (id, label) ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { CallManager.selectAccount(call.id, id) },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SimCard, null)
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        TextButton(onClick = { CallManager.hangup(call.id) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
    }
}

@Composable
private fun DtmfKeypad(onKey: (Char) -> Unit, onClose: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(typed.takeLast(20), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.height(40.dp))
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { c ->
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(role = Role.Button) { typed += c; onKey(c) }
                            .semantics { contentDescription = c.toString() },
                        contentAlignment = Alignment.Center,
                    ) { Text(c.toString(), style = MaterialTheme.typography.headlineSmall) }
                }
            }
        }
        TextButton(onClose) { Text("Hide keypad") }
    }
}

fun routeIcon(r: AudioRoute): ImageVector = when (r.type) {
    RouteType.BLUETOOTH -> Icons.Rounded.Bluetooth
    RouteType.WIRED -> Icons.Rounded.Headset
    RouteType.SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp
    RouteType.EARPIECE -> Icons.Rounded.PhoneInTalk
    RouteType.STREAMING -> Icons.Rounded.PhoneInTalk
}
