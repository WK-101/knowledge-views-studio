package app.parley.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.data.ContactDetails
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Contact page › Settings for this contact: whether the call screen shows this person's photo and call-screen picture.
 * Default follows Settings › Calls › "Show contact photo on the call screen"; Show and Hide override it. Works for
 * private contacts too (kept under their Parley key, so it follows conversions).
 */
@Composable
fun CallPhotoRow(vm: AppViewModel, d: ContactDetails) {
    val key = d.lookupKey
    if (key.isEmpty()) return
    val version by vm.c.people.backgrounds.version.collectAsStateWithLifecycle()
    val settings by vm.c.settings.settings.collectAsStateWithLifecycle()
    val choice = remember(key, version) { vm.c.people.backgrounds.photoChoice(key) }
    var open by remember { mutableStateOf(false) }
    val current = when (choice) {
        null -> stringResource(if (settings.showCallerPhoto) R.string.callphoto_default_shown else R.string.callphoto_default_hidden)
        true -> stringResource(R.string.callphoto_show)
        false -> stringResource(R.string.callphoto_hide)
    }
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = stringResource(R.string.callphoto_change)) { open = true },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Rounded.AccountCircle, null) },
            headlineContent = { Text(stringResource(R.string.callphoto_title)) },
            supportingContent = { Text(current) },
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(null to R.string.callphoto_default, true to R.string.callphoto_show, false to R.string.callphoto_hide).forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = if (value == choice) ({ Icon(Icons.Rounded.Check, null) }) else null,
                    onClick = {
                        open = false
                        vm.viewModelScope.launch(Dispatchers.IO) { vm.c.people.backgrounds.setPhotoChoice(key, value) }
                    },
                )
            }
        }
    }
}
