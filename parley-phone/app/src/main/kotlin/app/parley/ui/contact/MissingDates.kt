package app.parley.ui.contact

import android.provider.ContactsContract.CommonDataKinds.Event
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.data.ContactDetails
import app.parley.data.EventItem
import kotlinx.coroutines.launch

/** C4: whether the contact page has a birthday or anniversary slot to offer. */
fun hasMissingDates(d: ContactDetails): Boolean =
    d.events.none { it.type == Event.TYPE_BIRTHDAY } || d.events.none { it.type == Event.TYPE_ANNIVERSARY }

/**
 * C4: "Add birthday?" and "Add anniversary?" chips on the contact page when those dates are empty. The date is saved
 * straight into the system contact (an Event row, like the editor writes), so it's never kept only in Parley and
 * other apps, sync and backups see it. [onSaved] reloads the page.
 */
@Composable
fun MissingDateChips(vm: AppViewModel, d: ContactDetails, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    var picking by remember { mutableStateOf<Int?>(null) }
    val missing = listOf(Event.TYPE_BIRTHDAY, Event.TYPE_ANNIVERSARY).filter { t -> d.events.none { it.type == t } }
    if (missing.isEmpty()) return
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        missing.forEach { type ->
            val birthday = type == Event.TYPE_BIRTHDAY
            AssistChip(
                onClick = { picking = type },
                label = { Text(stringResource(if (birthday) R.string.ux_add_birthday else R.string.ux_add_anniversary)) },
                leadingIcon = { Icon(if (birthday) Icons.Rounded.Cake else Icons.Rounded.Favorite, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
    picking?.let { type ->
        EventDateDialog("", onDismiss = { picking = null }) { date ->
            picking = null
            scope.launch {
                val saved = runCatching {
                    vm.c.contacts.save(d, d.copy(events = d.events + EventItem(date = date, type = type)), account = null, photo = null, removePhoto = false)
                }.getOrNull()
                if (saved == null) vm.toast(res.getString(R.string.ux_date_not_saved)) else onSaved()
            }
        }
    }
}
