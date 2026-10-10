package app.parley.ui.people

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.viewModelScope
import app.parley.data.people.CallBackgrounds
import app.parley.ui.ParleyListItem
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.data.ContactDetails
import app.parley.ui.PhotoCache
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleyShapes
import app.parley.ui.ListSectionHeader
import app.parley.common.people.ContactRef
import app.parley.ui.common.ExportableImage
import app.parley.ui.common.ImageExport
import app.parley.ui.contact.PhotoViewer
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

/** What the editor will do with the call-screen background on save. */
sealed interface BackgroundChange {
    data object None : BackgroundChange
    data object Remove : BackgroundChange
    data class Set(val uri: Uri) : BackgroundChange
}

/** Editor › "Call screen": pick, change or remove the picture shown behind this person's calls. */
@Composable
fun CallBackgroundEditor(vm: AppViewModel, lookupKey: String, change: BackgroundChange, onChange: (BackgroundChange) -> Unit) {
    val version by vm.c.people.backgrounds.version.collectAsStateWithLifecycle()
    val current = if (lookupKey.isEmpty()) null else vm.c.people.backgrounds.forLookupKey(lookupKey)
    val shown: String? = when (change) {
        BackgroundChange.None -> current
        BackgroundChange.Remove -> null
        is BackgroundChange.Set -> change.uri.toString()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onChange(BackgroundChange.Set(uri)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListSectionHeader(stringResource(R.string.ppl_bg_title), inset = 0.dp, top = 0.dp, bottom = 0.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Preview(shown, version)
            Column {
                Text(stringResource(R.string.ppl_bg_text), style = MaterialTheme.typography.bodySmall)
                Row {
                    OutlinedButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (shown == null) stringResource(R.string.ppl_bg_choose) else stringResource(R.string.callphoto_change))
                    }
                    if (shown != null) TextButton({ onChange(if (change is BackgroundChange.Set && current == null) BackgroundChange.None else BackgroundChange.Remove) }) { Text(stringResource(R.string.jr_remove)) }
                }
            }
        }
    }
}

@Composable
private fun Preview(uri: String?, version: Int, size: Modifier = Modifier.size(64.dp, 96.dp)) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(null, uri, version) { value = uri?.let { PhotoCache.load(context, it, 256)?.asImageBitmap() } }
    val m = size.clip(ParleyShapes.control)
    val b = img
    if (b != null) Image(
        b, stringResource(R.string.ppl_bg_desc), m, contentScale = ContentScale.Crop,
    ) else Icon(Icons.Rounded.Wallpaper, null, Modifier.size(64.dp))
}

/** The message for a picture that couldn't be used, by what went wrong. */
object CallBackgroundText {
    fun failure(r: CallBackgrounds.SetResult): Int = when (r) {
        CallBackgrounds.SetResult.UNREADABLE -> R.string.ppl_bg_unreadable
        CallBackgrounds.SetResult.NOT_A_PICTURE -> R.string.ppl_bg_not_picture
        CallBackgrounds.SetResult.OK, CallBackgrounds.SetResult.NOT_SAVED -> R.string.ppl_bg_failed
    }
}

/**
 * Contact page › Settings: the call-screen picture. Tapping opens the photo picker and the picture is set at once
 * (no trip through the editor and its Save); the row says whether that worked, and Remove takes it off again.
 */
@Composable
fun CallBackgroundInfoRow(vm: AppViewModel, d: ContactDetails) {
    val res = LocalResources.current
    val version by vm.c.people.backgrounds.version.collectAsStateWithLifecycle()
    val current = remember(d.lookupKey, version) { vm.c.people.backgrounds.forLookupKey(d.lookupKey) }
    val key = d.lookupKey
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && key.isNotEmpty()) {
            vm.viewModelScope.launch {
                val r = vm.c.people.backgrounds.set(key, uri)
                vm.toast(res.getString(if (r == CallBackgrounds.SetResult.OK) R.string.ppl_bg_set else CallBackgroundText.failure(r)))
            }
        }
    }
    val choose = stringResource(if (current == null) R.string.ppl_bg_choose else R.string.callphoto_change)
    // Tapping the picture opens it full screen, with Save and Share; the rest of the row changes it.
    var viewing by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.ppl_bg_desc)
    if (viewing && current != null) {
        val name = stringResource(R.string.img_name_call_picture, d.displayName)
        val export = ExportableImage(name, ExportableImage.Kind.CALL_PICTURE, ContactRef.isPrivateKey(key)) { ctx -> ImageExport.readUri(ctx, current) }
        PhotoViewer(vm, current, export, description) { viewing = false }
    }
    ParleyListItem(
        modifier = Modifier.clickable(enabled = key.isNotEmpty(), onClickLabel = choose) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            if (current != null) {
                Box(
                    Modifier.size(48.dp, 56.dp).clickable(onClickLabel = stringResource(R.string.img_view_call_picture)) { viewing = true },
                    contentAlignment = Alignment.Center,
                ) { Preview(current, version, Modifier.size(40.dp, 56.dp)) }
            } else {
                Icon(Icons.Rounded.Wallpaper, null)
            }
        },
        headlineContent = { Text(if (current != null) stringResource(R.string.ppl_bg_custom) else stringResource(R.string.ppl_bg_default)) },
        supportingContent = { Text(stringResource(if (current != null) R.string.ppl_bg_info_set else R.string.ppl_bg_info_none)) },
        trailingContent = if (current != null) ({
            IconButton({
                vm.viewModelScope.launch {
                    vm.c.people.backgrounds.clear(key)
                    vm.toast(res.getString(R.string.ppl_bg_removed))
                }
            }) { Icon(Icons.Rounded.Close, stringResource(R.string.ppl_bg_remove_desc)) }
        }) else null,
    )
}
