package app.parley.telecom.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.parley.telecom.AudioRoute
import app.parley.telecom.AudioUi
import app.parley.telecom.CallManager
import app.parley.telecom.RouteType

/**
 * The adaptive audio button (A5): with no headset it is a plain Speaker toggle; with Bluetooth or a wired
 * headset it names the current route and opens [AudioRouteSheet].
 */
internal data class AudioButton(val label: String, val spoken: String, val isToggle: Boolean, val on: Boolean)

internal fun audioButton(audio: AudioUi): AudioButton {
    val speakerOn = audio.current?.type == RouteType.SPEAKER
    return if (!audio.hasExternal) {
        AudioButton("Speaker", "Speaker", isToggle = true, on = speakerOn)
    } else {
        val name = audio.current?.let { routeName(it) } ?: "Audio"
        AudioButton(name, "Audio output, ${audio.current?.let { routeName(it) } ?: "choose"}", isToggle = false, on = audio.current?.type != RouteType.EARPIECE)
    }
}

internal fun routeName(r: AudioRoute): String = when (r.type) {
    RouteType.EARPIECE -> "Phone"
    RouteType.SPEAKER -> "Speaker"
    RouteType.WIRED -> r.name.takeIf { it.isNotBlank() && !it.equals("wired headset", true) } ?: "Wired headset"
    RouteType.BLUETOOTH -> r.name.ifBlank { "Bluetooth" }
    RouteType.STREAMING -> r.name.ifBlank { "Other device" }
}

private fun routeKind(r: AudioRoute): String? = when (r.type) {
    RouteType.EARPIECE -> "Earpiece"
    RouteType.SPEAKER -> null
    RouteType.WIRED -> if (routeName(r) != "Wired headset") "Wired headset" else null
    RouteType.BLUETOOTH -> "Bluetooth"
    RouteType.STREAMING -> "Streaming"
}

/** Every route the call supports; each Bluetooth device by name (CallEndpoint on Android 14+). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRouteSheet(audio: AudioUi, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Audio output", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        val order = listOf(RouteType.BLUETOOTH, RouteType.WIRED, RouteType.EARPIECE, RouteType.SPEAKER, RouteType.STREAMING)
        audio.routes.sortedBy { order.indexOf(it.type) }.forEach { r ->
            val selected = audio.current?.key == r.key
            ListItem(
                headlineContent = { Text(routeName(r)) },
                supportingContent = routeKind(r)?.let { { Text(it) } },
                leadingContent = { Icon(routeIcon(r), null) },
                trailingContent = { if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { CallManager.setRoute(r); onDismiss() },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
