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
import androidx.compose.ui.platform.LocalResources
import android.content.res.Resources
import app.parley.telecom.R
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

internal fun audioButton(res: Resources, audio: AudioUi): AudioButton {
    val speakerOn = audio.current?.type == RouteType.SPEAKER
    return if (!audio.hasExternal) {
        val speaker = res.getString(R.string.audio_route_speaker)
        AudioButton(speaker, speaker, isToggle = true, on = speakerOn)
    } else {
        val name = audio.current?.let { routeName(res, it) } ?: res.getString(R.string.audio_button)
        val spoken = res.getString(R.string.audio_output_spoken, audio.current?.let { routeName(res, it) } ?: res.getString(R.string.audio_output_choose))
        AudioButton(name, spoken, isToggle = false, on = audio.current?.type != RouteType.EARPIECE)
    }
}

internal fun routeName(res: Resources, r: AudioRoute): String = when (r.type) {
    RouteType.EARPIECE -> res.getString(R.string.audio_route_phone)
    RouteType.SPEAKER -> res.getString(R.string.audio_route_speaker)
    RouteType.WIRED -> r.name.takeIf { it.isNotBlank() && !it.equals("wired headset", true) } ?: res.getString(R.string.audio_route_wired)
    RouteType.BLUETOOTH -> r.name.ifBlank { res.getString(R.string.audio_route_bluetooth) }
    RouteType.STREAMING -> r.name.ifBlank { res.getString(R.string.audio_route_other) }
}

private fun routeKind(res: Resources, r: AudioRoute): String? = when (r.type) {
    RouteType.EARPIECE -> res.getString(R.string.audio_route_earpiece)
    RouteType.SPEAKER -> null
    RouteType.WIRED -> res.getString(R.string.audio_route_wired).takeIf { routeName(res, r) != it }
    RouteType.BLUETOOTH -> res.getString(R.string.audio_route_bluetooth)
    RouteType.STREAMING -> res.getString(R.string.audio_route_streaming)
}

/** Every route the call supports; each Bluetooth device by name (CallEndpoint on Android 14+). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRouteSheet(audio: AudioUi, onDismiss: () -> Unit) {
    val res = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(res.getString(R.string.audio_output), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        val order = listOf(RouteType.BLUETOOTH, RouteType.WIRED, RouteType.EARPIECE, RouteType.SPEAKER, RouteType.STREAMING)
        audio.routes.sortedBy { order.indexOf(it.type) }.forEach { r ->
            val selected = audio.current?.key == r.key
            ListItem(
                headlineContent = { Text(routeName(res, r)) },
                supportingContent = routeKind(res, r)?.let { { Text(it) } },
                leadingContent = { Icon(routeIcon(r), null) },
                trailingContent = { if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { CallManager.setRoute(r); onDismiss() },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
