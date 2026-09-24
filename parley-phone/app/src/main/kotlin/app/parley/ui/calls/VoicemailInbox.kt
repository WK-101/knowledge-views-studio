package app.parley.ui.calls

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.calls.PlayerState
import app.parley.calls.VoicemailPlayer
import app.parley.common.TextSearch
import app.parley.common.calls.VoicemailFiles
import app.parley.data.calls.Voicemail
import app.parley.data.calls.VoicemailRepository
import app.parley.data.calls.VoicemailState
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.avatarSize
import app.parley.ui.common.Format
import kotlinx.coroutines.launch

/**
 * The voicemail inbox (V1), shown in Recents under the "Voicemail" chip: every voicemail Android's voicemail store
 * holds, with playback (speaker or earpiece, seek), transcription, mark heard, call back, share and delete.
 */
@Composable
fun VoicemailInbox(vm: AppViewModel, query: String) {
    val context = LocalContext.current
    val state by vm.c.voicemail.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val player = remember { VoicemailPlayer(context) { v -> if (!v.heard) scope.launch { vm.c.voicemail.markHeard(listOf(v.id)) } } }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(Unit) { vm.c.voicemail.refresh() }
    val playing by player.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<Voicemail?>(null) }
    val sims by vm.sims.collectAsStateWithLifecycle()
    val simLabels = remember(sims) { if (sims.size > 1) sims.associate { it.id to it.label } else emptyMap() }
    val items = remember(state.items, query) {
        if (query.isBlank()) state.items
        else state.items.filter { v -> TextSearch.matches(query, vm.contactFor(v.number)?.displayName ?: v.number, listOf(v.number)) }
    }

    Column(Modifier.fillMaxWidth()) {
        VoicemailNote(vm, state)
        if (state.loaded && state.available && items.isEmpty()) {
            EmptyState(Icons.Rounded.Voicemail, if (query.isBlank()) "No voicemail" else "Nothing here", modifier = Modifier.padding(top = 32.dp))
        }
        items.forEach { v ->
            VoicemailRow(
                vm, v, simLabels[v.accountId], expanded = open == v.id, playing = playing.takeIf { it.id == v.id },
                onToggle = { open = if (open == v.id) null else v.id },
                onPlay = { player.toggle(v) },
                onSeek = player::seek,
                onSpeaker = player::setSpeaker,
                speaker = playing.speaker,
                onHeard = { scope.launch { vm.c.voicemail.markHeard(listOf(v.id), !v.heard) } },
                onShare = {
                    scope.launch {
                        val file = vm.c.voicemail.copyForSharing(v)
                        if (file == null) {
                            vm.toast(if (v.hasAudio) "Couldn't share this voicemail" else "The audio hasn't been downloaded yet")
                            return@launch
                        }
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        val send = Intent(Intent.ACTION_SEND).setType(v.mimeType ?: "audio/*").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(send, "Share voicemail")) }
                    }
                },
                onDelete = { confirmDelete = v },
                onDownload = {
                    vm.toast(if (vm.c.voicemail.requestDownload(v)) "Asked your voicemail app to download it" else "No voicemail app to ask")
                },
            )
        }
    }

    confirmDelete?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this voicemail?") },
            text = { Text("It's also deleted from your carrier's voicemail box the next time your voicemail app syncs, when it supports that.") },
            confirmButton = {
                TextButton({
                    confirmDelete = null
                    if (playing.id == v.id) player.stop()
                    scope.launch { vm.toast(if (vm.c.voicemail.delete(v)) "Voicemail deleted" else "Couldn't delete it") }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** The honest note: where voicemails come from, what's wrong with visual voicemail, and the ways to reach it. */
@Composable
private fun VoicemailNote(vm: AppViewModel, state: VoicemailState) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, null, Modifier.padding(end = 12.dp, top = 2.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (state.loaded && !state.available) {
                        "Parley can show your voicemail only while it's your default phone app."
                    } else {
                        "Parley shows voicemails your carrier's voicemail app or Android has already downloaded; it can't sync without internet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.sources.mapNotNull { it.problem }.distinct().forEach { p ->
                Text(p, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.sources.firstOrNull { it.quotaTotal != null && it.quotaUsed != null }?.let { s ->
                Text("Mailbox: ${s.quotaUsed} of ${s.quotaTotal} messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ vm.callVoicemail() }, { Text("Call voicemail") }, leadingIcon = { Icon(Icons.Rounded.Call, null, Modifier.size(18.dp)) })
                AssistChip(
                    { runCatching { context.startActivity(Intent(VoicemailRepository.CONFIGURE_ACTION)) }.onFailure { vm.toast("No voicemail settings on this phone") } },
                    { Text("Voicemail settings") },
                    leadingIcon = { Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp)) },
                )
                // The carrier's visual voicemail app's own settings, when it publishes them.
                state.sources.firstNotNullOfOrNull { it.settingsUri }?.let { uri ->
                    AssistChip(
                        { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { vm.toast("Couldn't open it") } },
                        { Text("Carrier voicemail app") },
                        leadingIcon = { Icon(Icons.Rounded.Voicemail, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoicemailRow(
    vm: AppViewModel,
    v: Voicemail,
    sim: String?,
    expanded: Boolean,
    playing: PlayerState?,
    speaker: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onHeard: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val contact = remember(v.number) { vm.contactFor(v.number) }
    val title = contact?.displayName ?: v.number.takeIf { it.isNotBlank() }?.let { Format.number(it, vm.countryIso) } ?: "Private number"
    Column {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = if (expanded) "Collapse" else "Show player", onClick = onToggle),
            colors = if (expanded) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) else ListItemDefaults.colors(),
            leadingContent = {
                Box {
                    Avatar(title, contact?.photoUri, avatarSize())
                    if (!v.heard) {
                        Box(
                            Modifier.size(12.dp).align(Alignment.TopEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                                .semantics { contentDescription = "New" },
                        )
                    }
                }
            },
            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (!v.heard) FontWeight.Bold else null) },
            supportingContent = {
                Column {
                    Text(
                        listOfNotNull(Format.shortWhen(context, v.date), VoicemailFiles.clock(v.durationSec * 1000), sim, if (!v.hasAudio) "not downloaded" else null)
                            .joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (!expanded) v.transcription?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
            },
            trailingContent = {
                IconButton(onPlay, enabled = v.hasAudio) {
                    Icon(if (playing?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing?.playing == true) "Pause" else "Play voicemail from $title")
                }
            },
        )
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                if (v.hasAudio) {
                    val duration = (playing?.durationMs?.takeIf { it > 0 } ?: (v.durationSec * 1000)).coerceAtLeast(1)
                    val position = playing?.positionMs ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(onPlay) {
                            Icon(if (playing?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing?.playing == true) "Pause" else "Play")
                        }
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = position.coerceIn(0, duration).toFloat(), onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..duration.toFloat(),
                            enabled = playing != null, modifier = Modifier.weight(1f).semantics { contentDescription = "Position" },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(VoicemailFiles.clock(position) + " / " + VoicemailFiles.clock(duration), style = MaterialTheme.typography.labelMedium)
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.padding(vertical = 4.dp)) {
                        SegmentedButton(speaker, { onSpeaker(true) }, SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(18.dp)) }) { Text("Speaker") }
                        SegmentedButton(!speaker, { onSpeaker(false) }, SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Rounded.PhoneInTalk, null, Modifier.size(18.dp)) }) { Text("Earpiece") }
                    }
                    playing?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text(
                        "The audio of this voicemail isn't on the phone yet. Your voicemail app downloads it over mobile data or Wi-Fi; Parley can't.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                v.transcription?.let {
                    Text("Transcription", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (v.number.isNotBlank()) {
                        AssistChip({ vm.requestCall(v.number, contact?.displayName) }, { Text("Call back") }, leadingIcon = { Icon(Icons.Rounded.Call, null, Modifier.size(18.dp)) })
                    }
                    AssistChip(
                        onHeard, { Text(if (v.heard) "Mark as new" else "Mark heard") },
                        leadingIcon = { Icon(if (v.heard) Icons.Rounded.MarkEmailUnread else Icons.Rounded.MarkEmailRead, null, Modifier.size(18.dp)) },
                    )
                    if (v.hasAudio) AssistChip(onShare, { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)) })
                    else AssistChip(onDownload, { Text("Download") }, leadingIcon = { Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp)) })
                    AssistChip(onDelete, { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)) })
                }
            }
        }
    }
}

