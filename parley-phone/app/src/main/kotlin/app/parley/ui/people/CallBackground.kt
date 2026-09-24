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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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

/** What the editor will do with the call-screen background on save. */
sealed interface BackgroundChange {
    data object None : BackgroundChange
    data object Remove : BackgroundChange
    data class Set(val uri: Uri) : BackgroundChange
}

/** Applies an editor's pending background change once the contact is saved. */
suspend fun AppViewModel.applyBackground(lookupKey: String, change: BackgroundChange) {
    if (lookupKey.isEmpty()) return
    when (change) {
        BackgroundChange.None -> Unit
        BackgroundChange.Remove -> c.people.backgrounds.clear(lookupKey)
        is BackgroundChange.Set -> if (!c.people.backgrounds.set(lookupKey, change.uri)) toast(c.appContext.getString(R.string.ppl_bg_failed))
    }
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
        Text(stringResource(R.string.ppl_bg_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Preview(shown, version)
            Column {
                Text(stringResource(R.string.ppl_bg_text), style = MaterialTheme.typography.bodySmall)
                Row {
                    OutlinedButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (shown == null) stringResource(R.string.ppl_bg_choose) else stringResource(R.string.ppl_bg_change))
                    }
                    if (shown != null) TextButton({ onChange(if (change is BackgroundChange.Set && current == null) BackgroundChange.None else BackgroundChange.Remove) }) { Text(stringResource(R.string.ppl_bg_remove)) }
                }
            }
        }
    }
}

@Composable
private fun Preview(uri: String?, version: Int) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(null, uri, version) { value = uri?.let { PhotoCache.load(context, it, 256)?.asImageBitmap() } }
    val m = Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(12.dp))
    val b = img
    if (b != null) Image(b, stringResource(R.string.ppl_bg_desc), m, contentScale = ContentScale.Crop) else Icon(Icons.Rounded.Wallpaper, null, Modifier.size(64.dp))
}

/** Contact page › Settings: shows whether a call-screen background is set; tapping opens the editor. */
@Composable
fun CallBackgroundInfoRow(vm: AppViewModel, d: ContactDetails, onEdit: () -> Unit) {
    val version by vm.c.people.backgrounds.version.collectAsStateWithLifecycle()
    val set = remember(d.lookupKey, version) { vm.c.people.backgrounds.forLookupKey(d.lookupKey) != null }
    ListItem(
        modifier = Modifier.clickable(onClick = onEdit),
        leadingContent = { Icon(Icons.Rounded.Wallpaper, null) },
        headlineContent = { Text(if (set) stringResource(R.string.ppl_bg_custom) else stringResource(R.string.ppl_bg_default)) },
        supportingContent = { Text(stringResource(R.string.ppl_bg_info)) },
    )
}
