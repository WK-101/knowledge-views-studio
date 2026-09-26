package app.parley.ui.qr

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.qr.QrParser
import app.parley.common.qr.QrPayload
import app.parley.common.qr.QrText
import app.parley.ui.SegmentedGroup
import app.parley.ui.settings.SettingsScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Q2: the "Scan QR" screen, reached from Contacts, the keypad, My card, Settings, the launcher and the tile. */
object QrRoutes {
    const val SCAN = "qrscan"
}

fun NavGraphBuilder.qrRoutes(vm: AppViewModel, nav: NavController) {
    composable(QrRoutes.SCAN) { QrScanScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
}

private sealed interface ScanState {
    data object Idle : ScanState
    data object Busy : ScanState
    data object NothingFound : ScanState
    data object Unreadable : ScanState
    data class Several(val texts: List<String>) : ScanState
}

/**
 * Q1: reads a QR code without the camera permission. "Take a photo" asks the phone's camera app for one picture
 * (written to Parley's cache and deleted once read), "Pick an image" uses the system photo picker (no storage
 * permission), a picture can be shared to Parley, and "Paste" reads the clipboard only when tapped. Decoding is
 * offline (ZXing). Nothing found is answered with tips; several codes let you pick one; one code opens its sheet.
 */
@Composable
fun QrScanScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    var payload by remember { mutableStateOf<QrPayload?>(null) }
    // Survives the trip to the camera app (and the activity being recreated meanwhile).
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    // The capture pending when this screen was (re)created, read before the launcher can deliver its result and
    // clear photoPath: the stale-photo cleanup below must never delete it while it's being read.
    val pendingAtStart = remember { photoPath }

    fun show(texts: List<String>) {
        state = if (texts.size > 1) ScanState.Several(texts) else ScanState.Idle
        if (texts.size == 1) payload = QrParser.parse(texts[0])
    }

    fun scan(uri: Uri, afterwards: () -> Unit = {}) {
        state = ScanState.Busy
        payload = null
        scope.launch {
            val outcome = try {
                QrScanner.scan(context, uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                QrScanner.Outcome.Unreadable
            } finally {
                afterwards()
            }
            when (outcome) {
                is QrScanner.Outcome.Found -> show(outcome.texts)
                QrScanner.Outcome.NothingFound -> state = ScanState.NothingFound
                QrScanner.Outcome.Unreadable -> state = ScanState.Unreadable
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = photoPath
        photoPath = null
        if (path == null) return@rememberLauncherForActivityResult
        val file = File(path)
        if (ok && file.length() > 0) {
            // The temporary photo is deleted as soon as it has been read.
            scan(Uri.fromFile(file)) { QrScanner.deletePhoto(file) }
        } else {
            QrScanner.deletePhoto(file)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { scan(it) } }

    // A picture shared to Parley.
    val shared by QrInbox.image.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        shared?.let { uri ->
            QrInbox.image.value = null
            scan(uri)
        }
    }
    // Photos left behind by a scan that was interrupted.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { QrScanner.clearStalePhotos(context, pendingAtStart ?: photoPath) } }

    fun takePhoto() {
        val (file, uri) = runCatching { QrScanner.newPhoto(context) }.getOrNull() ?: return
        photoPath = file.absolutePath
        try {
            camera.launch(uri)
        } catch (_: ActivityNotFoundException) {
            photoPath = null
            vm.toast(res.getString(R.string.qs_no_camera_app))
        } catch (_: SecurityException) {
            photoPath = null
            vm.toast(res.getString(R.string.qs_no_camera_app))
        }
    }

    fun paste() {
        val clip = runCatching { context.getSystemService(android.content.ClipboardManager::class.java).primaryClip }.getOrNull()
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            vm.toast(res.getString(R.string.qs_clipboard_empty))
        } else {
            state = ScanState.Idle
            payload = QrParser.parse(text)
        }
    }

    SettingsScaffold(stringResource(R.string.qs_title), back) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.qs_intro), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Button(::takePhoto, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = state != ScanState.Busy) {
                Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp))
                Text("  " + stringResource(R.string.qs_take_photo))
            }
            OutlinedButton(
                { runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { vm.toast(res.getString(R.string.qs_no_app)) } },
                Modifier.fillMaxWidth().padding(top = 8.dp), enabled = state != ScanState.Busy,
            ) {
                Icon(Icons.Rounded.Image, null, Modifier.size(18.dp))
                Text("  " + stringResource(R.string.qs_pick_image))
            }
            OutlinedButton(::paste, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = state != ScanState.Busy) {
                Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                Text("  " + stringResource(R.string.qs_paste))
            }
            Text(
                stringResource(R.string.qs_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
            )
        }
        when (val s = state) {
            ScanState.Busy -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(stringResource(R.string.qs_reading), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            ScanState.NothingFound, ScanState.Unreadable -> NothingFound(s == ScanState.Unreadable, ::takePhoto)
            is ScanState.Several -> SegmentedGroup(pluralStringResource(R.plurals.qs_found_several, s.texts.size, s.texts.size)) {
                s.texts.forEach { t ->
                    item {
                        val p = remember(t) { QrParser.parse(t) }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(QrLabels.icon(p), null) },
                            headlineContent = { Text(QrLabels.kind(res, p)) },
                            supportingContent = { Text(QrText.shown(t, 120, keepLines = false), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { payload = p },
                        )
                    }
                }
            }
            ScanState.Idle -> Unit
        }
    }
    payload?.let { p -> QrResultSheet(vm, p, onDismiss = { payload = null }, open = open) }
}

@Composable
private fun NothingFound(unreadable: Boolean, retry: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.SearchOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(if (unreadable) R.string.qs_unreadable_title else R.string.qs_nothing_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                stringResource(if (unreadable) R.string.qs_unreadable_body else R.string.qs_nothing_body),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
            )
            listOf(R.string.qs_tip_fill, R.string.qs_tip_light, R.string.qs_tip_steady, R.string.qs_tip_screen).forEach { tip ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Lightbulb, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(tip), style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(retry, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.qs_try_again)) }
        }
    }
}
