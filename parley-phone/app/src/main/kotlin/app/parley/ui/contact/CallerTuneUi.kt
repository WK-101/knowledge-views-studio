package app.parley.ui.contact

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.parley.R
import app.parley.common.calls.CallerTune
import app.parley.common.ux.Tips
import app.parley.data.DataContainer
import app.parley.ui.ParleyDialog
import app.parley.ui.Spacing
import app.parley.ui.common.CoachMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sonic caller ID: tunes made from a name ([CallerTune]) kept as small WAV files in Parley's own storage and handed to
 * ringers through Parley's FileProvider.
 *
 * Who reads the file: a device contact's ringtone (Contacts.CUSTOM_RINGTONE) is played by Telecom, which runs as the
 * system user and may read any provider, exported or not; if its own player fails, Android hands the tone to System
 * UI's ringtone player, which gets a read grant here (re-granted at start, since grants end with a reboot). Private
 * contacts and labels are rung by Parley's own ringer, which reads its own files. The file name is a hash, never the
 * person's name. A tune no ringtone uses any more loses its grants and its file ([sweep]: at start and when one is
 * replaced).
 */
internal object CallerTunes {
    private const val DIR = "tunes"

    /** The two players that may open a device contact's ringtone outside Parley. */
    private val READERS = listOf("com.android.server.telecom", "com.android.systemui")

    private fun authority(context: Context) = context.packageName + ".files"

    /** Writes the tune for [name] / [variant] (once; the same pair always gives the same file) and returns its URI. */
    suspend fun save(context: Context, name: String, variant: Int): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, CallerTune.fileName(name, variant))
            if (!file.exists()) {
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeBytes(CallerTune.wav(CallerTune.render(CallerTune.compose(name, variant))))
                check(tmp.renameTo(file))
            }
            FileProvider.getUriForFile(context, authority(context), file).also { grant(context, it) }
        }.getOrNull()
    }

    /** Whether [uri] is one of Parley's tunes (its title is then "Tune made for …", not the file's hash). */
    fun isOurs(context: Context, uri: String?): Boolean {
        val u = uri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return u.scheme == "content" && u.authority == authority(context) && u.pathSegments.firstOrNull() == DIR
    }

    /** Read grants for every kept tune again (they don't survive a reboot). Cheap: a directory listing. */
    fun regrant(context: Context) {
        val files = File(context.filesDir, DIR).listFiles { f -> f.name.endsWith(".wav") } ?: return
        files.forEach { f -> runCatching { grant(context, FileProvider.getUriForFile(context, authority(context), f)) } }
    }

    /**
     * Clears out tunes no ringtone uses any more (one was replaced, a contact or label was deleted): their read grants
     * are revoked and the files deleted. [inUse]: every ringtone set on device contacts, private contacts and labels;
     * null when one of those couldn't be read, and then nothing is touched. Returns how many went.
     */
    fun prune(context: Context, inUse: Collection<String>?): Int {
        if (inUse == null) return 0
        val dir = File(context.filesDir, DIR)
        val names = dir.list()?.toList() ?: return 0
        var gone = 0
        CallerTune.unused(names, inUse).forEach { name ->
            val file = File(dir, name)
            // The URI the grants were made for (FileProvider's "tunes" root), built without touching the file.
            revoke(context, Uri.Builder().scheme("content").authority(authority(context)).appendPath(DIR).appendPath(name).build())
            if (file.delete()) gone++
        }
        return gone
    }

    /** [prune] with what Parley knows is in use now (device contacts, private contacts, labels). Off the main thread. */
    suspend fun sweep(c: DataContainer): Int = withContext(Dispatchers.IO) {
        val contacts = runCatching { c.contacts.customRingtones() }.getOrNull()
        val private = runCatching { c.vault.ringtonesNow() }.getOrNull()
        val labels = runCatching { c.peoplePrefs.current().labelRingtones.values }.getOrNull()
        val inUse = if (contacts == null || private == null || labels == null) null else contacts + private + labels
        runCatching { prune(c.appContext, inUse) }.getOrDefault(0)
    }

    private fun revoke(context: Context, uri: Uri) = READERS.forEach { pkg ->
        runCatching { context.revokeUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun grant(context: Context, uri: Uri) = READERS.forEach { pkg ->
        // A package this phone doesn't have (or can't see) just doesn't get one.
        runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

/** Plays a rendered tune once, at the ring volume (like a ringtone picker), from memory: nothing is written to try it. */
internal class TunePreview {
    private var track: AudioTrack? = null

    fun play(samples: ShortArray) {
        stop()
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CallerTune.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
                .also { t ->
                    t.write(samples, 0, samples.size)
                    t.play()
                }
        }.getOrNull()
    }

    fun stop() {
        track?.let { t -> runCatching { t.stop() }; runCatching { t.release() } }
        track = null
    }
}

/** "Make a ringtone for Ana": the row on a contact's or a label's page, with the feature's one-time tip under it. */
@Composable
internal fun CallerTuneRow(name: String, summary: String, onUse: (Uri) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        CallerTuneListItem(name, summary) { open = true }
        CoachMark(Tips.CALLER_TUNE, stringResource(R.string.caller_tune_tip))
    }
    if (open) CallerTuneDialog(name, onUse = { open = false; onUse(it) }, onDismiss = { open = false })
}

@Composable
private fun CallerTuneListItem(name: String, summary: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Rounded.Audiotrack, null) },
        headlineContent = { Text(stringResource(R.string.caller_tune_make, name)) },
        supportingContent = { Text(summary) },
    )
}

/** Play the tune, try others, then use one: it's written to a file only once it's chosen. */
@Composable
internal fun CallerTuneDialog(name: String, onUse: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var variant by rememberSaveable { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val preview = remember { TunePreview() }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    // "Try another" is pressed to hear it, so a new variant plays as soon as it's rendered.
    var autoplay by remember { mutableStateOf(false) }
    val samples by produceState<ShortArray?>(null, name, variant) {
        value = null
        value = withContext(Dispatchers.Default) { CallerTune.render(CallerTune.compose(name, variant)) }
    }
    fun play(s: ShortArray) {
        preview.play(s)
        playing = true
    }
    LaunchedEffect(samples) {
        val s = samples ?: return@LaunchedEffect
        if (autoplay) { autoplay = false; play(s) }
    }
    // The button turns back into Play when the tune ends.
    LaunchedEffect(playing, variant) {
        if (!playing) return@LaunchedEffect
        delay(CallerTune.compose(name, variant).lengthMs + 100)
        // A static track stays "playing" at its end, so it's stopped here.
        preview.stop()
        playing = false
    }
    ParleyDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Audiotrack, null) },
        title = { Text(stringResource(R.string.caller_tune_title, name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.caller_tune_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(Spacing.m))
                // Which tune this is, read out when it changes.
                Text(
                    stringResource(R.string.caller_tune_variant, variant + 1),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.size(Spacing.s))
                TuneButtons(
                    playing = playing, ready = samples != null,
                    onPlay = {
                        val ready = samples
                        if (playing) { preview.stop(); playing = false } else if (ready != null) play(ready)
                    },
                    onAnother = { preview.stop(); playing = false; autoplay = true; variant++ },
                )
                if (failed) {
                    Text(
                        stringResource(R.string.caller_tune_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    saving = true
                    preview.stop()
                    scope.launch {
                        val uri = CallerTunes.save(context, name, variant)
                        saving = false
                        if (uri != null) onUse(uri) else failed = true
                    }
                },
                enabled = !saving,
            ) { Text(stringResource(R.string.caller_tune_use)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

/** Play / Stop and Try another, wrapping under each other at large font sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuneButtons(playing: Boolean, ready: Boolean, onPlay: () -> Unit, onAnother: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FilledTonalButton(onClick = onPlay, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(if (playing) R.string.caller_tune_stop else R.string.caller_tune_play))
        }
        OutlinedButton(onClick = onAnother, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.caller_tune_another))
        }
    }
}
