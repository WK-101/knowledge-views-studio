package app.parley.messaging

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Public
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.common.MessageDrafts
import app.parley.common.MessengerApp
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.common.PhoneNumbers
import app.parley.container
import app.parley.data.PhoneEnv
import app.parley.data.messaging.MyDetails
import app.parley.ui.Bidi
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry points for "Message or call on…". In-app screens show [ReachSheet]; code outside the app's UI (the
 * in-call screen, notifications) starts [intent], which opens the same sheet over whatever is on screen.
 */
object MessageOn {
    /**
     * Opens the "Message or call on…" sheet for [number] in its own small window. [accountId] is the SIM that handled the call
     * the number comes from, so a national number is read with that SIM's country.
     */
    fun intent(context: Context, number: String, accountId: String? = null): Intent = Intent(context, NumberActionActivity::class.java)
        .setAction(NumberActionActivity.ACTION_MESSAGE_ON)
        .putExtra(NumberActionActivity.EXTRA_NUMBER, number)
        .apply { if (accountId != null) putExtra(NumberActionActivity.EXTRA_ACCOUNT_ID, accountId) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Shows the sheet for [number] from any context (for the in-call screen's caller card). */
    fun open(context: Context, number: String, accountId: String? = null) {
        if (number.isNotBlank()) context.startActivity(intent(context, number, accountId))
    }

    /** The privacy line under the messengers. */
    val PRIVACY_LINE_RES = R.string.msg_privacy_line
}

internal fun countryLabel(code: String): String {
    val name = Locale("", code).displayCountry.ifBlank { code }
    return "$name ($code)"
}

/** Searchable list of every country libphonenumber knows, with its calling code. */
@Composable
fun CountryPickerDialog(selected: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val all = remember { NumberText.regions() }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query) { NumberText.searchRegions(all, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_country_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.msg_search_countries)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.code }) { r ->
                        ListItem(
                            headlineContent = { Text(r.name) },
                            supportingContent = { Text(Bidi.ltr("+${r.callingCode}") + stringResource(R.string.main_separator) + r.code) },
                            trailingContent = if (r.code == selected) ({ Icon(Icons.Rounded.Check, stringResource(R.string.contacts_selected)) }) else null,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(role = Role.Button, onClick = { onPick(r.code) }),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

/** Your name and number for "Send my details". Nothing is read without asking: the number is only a suggestion. */
@Composable
fun MyDetailsDialog(initial: MyDetails, suggestNumber: suspend () -> String?, onDismiss: () -> Unit, onSave: (MyDetails) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var number by rememberSaveable { mutableStateOf(initial.number) }
    LaunchedEffect(Unit) {
        if (number.isEmpty()) suggestNumber()?.let { if (number.isEmpty()) number = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_my_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.msg_my_details_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.msg_your_name)) }, singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.msg_your_number)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onSave(MyDetails(name, number)) }, enabled = name.isNotBlank() || number.isNotBlank()) { Text(stringResource(R.string.main_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

/** "Last messaged via Signal · 2 days ago", from Parley's own record; nothing if never. */
@Composable
fun LastMessagedNote(number: String, modifier: Modifier = Modifier) {
    val store = LocalContext.current.container.messaging
    val all by store.lastMessaged.collectAsStateWithLifecycle()
    val last = remember(all, number) { store.lastMessaged(number) } ?: return
    val ago = DateUtils.getRelativeTimeSpanString(last.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    Text(
        stringResource(R.string.msg_last_messaged, last.label, ago),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
