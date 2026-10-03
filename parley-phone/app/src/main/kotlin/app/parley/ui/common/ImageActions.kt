package app.parley.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.photo.ImageFiles
import app.parley.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Save and Share for one picture, from [rememberImageActions]. [message] says how the last one went, in place. */
@Stable
class ImageActions internal constructor(
    val save: () -> Unit,
    val share: () -> Unit,
    private val messageState: () -> String?,
) {
    val message: String? get() = messageState()
}

/** ACTION_CREATE_DOCUMENT with the picture's own type ([ActivityResultContracts.CreateDocument] fixes one type). */
private class CreateImageDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

/**
 * Save and Share for [image] (null: nothing to offer, as when [image] is still loading). Null when they aren't
 * offered: a private contact's picture while discreet mode hides private contacts. A private contact's picture asks
 * for the private contacts' unlock each time before it leaves Parley; its shared copy is deleted when the share
 * screen returns. Call it outside a Dialog's content, so its launchers belong to the screen.
 */
@Composable
fun rememberImageActions(vm: AppViewModel, image: ExportableImage?): ImageActions? {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current by rememberUpdatedState(image)
    var message by remember { mutableStateOf<String?>(null) }
    // What a save in progress will write, read once before "Save to" opens (read again if Parley was stopped meanwhile).
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var shared by remember { mutableStateOf<Uri?>(null) }
    val fallback = stringResource(R.string.img_file_fallback)

    val saver = rememberLauncherForActivityResult(CreateImageDocument()) { target ->
        val img = current
        if (target == null || img == null) {
            pending = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val bytes = pending ?: img.read(context)
            pending = null
            val ok = bytes != null && ImageExport.save(context, target, bytes)
            message = res.getString(if (ok) R.string.img_saved else R.string.img_save_failed)
        }
    }
    val sharer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // A private contact's decrypted copy goes as soon as the share screen returns; others wait for the sweep, so
        // an app still reading one isn't cut off.
        if (current?.private == true) ImageExport.forget(context, shared)
        shared = null
    }

    if (image == null || (image.private && settings.hideVault)) return null

    fun withBytes(then: suspend (ByteArray, ImageFiles.Format) -> Unit) {
        val img = current ?: return
        withPictureBytes(vm, context, scope, img, onUnreadable = { message = res.getString(R.string.img_unreadable) }, then = then)
    }

    return ImageActions(
        save = {
            withBytes { bytes, format ->
                pending = bytes
                val name = ImageFiles.fileName(current?.name, format, fallback)
                runCatching { saver.launch(name to format.mime) }.onFailure {
                    pending = null
                    message = res.getString(R.string.main_no_app)
                }
            }
        },
        share = {
            withBytes { bytes, format ->
                val img = current ?: return@withBytes
                val uri = ImageExport.shareFile(context, bytes, ImageFiles.fileName(img.name, format, fallback), img.private)
                shared = uri
                runCatching { sharer.launch(ImageExport.shareIntent(uri, format.mime, res.getString(R.string.img_share_title))) }.onFailure {
                    ImageExport.forget(context, uri)
                    shared = null
                    message = res.getString(R.string.main_no_app)
                }
            }
        },
        messageState = { message },
    )
}

/**
 * Reads [img] and hands its bytes and format to [then], after the unlock a private contact's picture needs (and only
 * while discreet mode doesn't hide private contacts). [onUnreadable] when the bytes aren't a picture.
 */
private fun withPictureBytes(
    vm: AppViewModel,
    context: Context,
    scope: CoroutineScope,
    img: ExportableImage,
    onUnreadable: () -> Unit,
    then: suspend (ByteArray, ImageFiles.Format) -> Unit,
) {
    val go = {
        scope.launch {
            val bytes = img.read(context)
            val format = bytes?.let(ImageFiles::detect)
            if (bytes == null || format == null) onUnreadable() else then(bytes, format)
        }
        Unit
    }
    if (!img.private) return go()
    val activity = context.fragmentActivity() ?: return
    AppLock.authenticateForVault(activity) { ok -> if (ok && !vm.settings.value.hideVault) go() }
}

/** What a picture's [kind][ExportableImage.Kind] hands out, in one line for the viewer (null: nothing to say). */
@Composable
private fun kindNote(kind: ExportableImage.Kind): String? = when (kind) {
    ExportableImage.Kind.ORIGINAL -> stringResource(R.string.img_note_original)
    ExportableImage.Kind.ANDROID_COPY -> stringResource(R.string.img_note_android_copy)
    ExportableImage.Kind.PRIVATE_COPY -> stringResource(R.string.img_note_private_copy)
    ExportableImage.Kind.CALL_PICTURE -> stringResource(R.string.img_note_call_picture)
    ExportableImage.Kind.GENERATED -> null
}

/**
 * The full-screen viewers' bar: Save and Share over a dark veil at the bottom, with a line saying what the file
 * will be (the original, or the largest copy there is) and, once tried, how it went. Nothing when [actions] is null.
 */
@Composable
fun ImageViewerBar(actions: ImageActions?, image: ExportableImage?, modifier: Modifier = Modifier) {
    if (actions == null || image == null) return
    val kind = image.kind
    Column(
        modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val note = actions.message ?: kindNote(kind)
        note?.let {
            Text(
                it, color = Color.White.copy(alpha = 0.87f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(actions.save) {
                Icon(Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.img_save), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            FilledTonalButton(actions.share) {
                Icon(Icons.Rounded.Share, null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.img_share), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

/** Save image and Share image under a picture in a dialog (the QR codes), with how the last one went. */
@Composable
fun ImageActionButtons(actions: ImageActions, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(actions.save) {
                Icon(Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.img_save), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            OutlinedButton(actions.share) {
                Icon(Icons.Rounded.Share, null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.img_share), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
        actions.message?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * [ImageActionButtons] for a code that opens only with a passcode (secure QR, invitations, simple mode's setup): the
 * saved or shared picture holds the code alone, so the line under it says to send the passcode another way.
 */
@Composable
fun CodeImageActions(actions: ImageActions, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ImageActionButtons(actions)
        Text(
            stringResource(R.string.img_code_apart), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A QR code (or another picture Parley made) to save or share as a PNG named [name]. */
fun generatedImage(name: String, bitmap: Bitmap, private: Boolean = false) =
    ExportableImage(name, ExportableImage.Kind.GENERATED, private) { ImageExport.png(bitmap) }
