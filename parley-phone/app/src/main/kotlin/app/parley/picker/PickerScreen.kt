package app.parley.picker

import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.common.TextSearch
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.parley.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    kind: PickKind,
    multiple: Boolean,
    title: String?,
    excludeContactId: Long?,
    onCancel: () -> Unit,
    onPicked: (List<Pick>) -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Pick>() }
    val items by produceState<List<Pick>?>(null, kind) {
        value = withContext(Dispatchers.IO) { loadPicks(context, kind, res) }.filter { it.contactId != excludeContactId }
    }
    val shown = items.orEmpty().filter { TextSearch.matches(query, it.title, listOfNotNull(it.subtitle), listOfNotNull(it.subtitle)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title ?: when (kind) {
                            PickKind.CONTACT -> if (multiple) stringResource(R.string.picker_choose_contacts) else stringResource(R.string.picker_choose_contact)
                            PickKind.PHONE -> stringResource(R.string.picker_choose_phone)
                            PickKind.EMAIL -> stringResource(R.string.picker_choose_email)
                            PickKind.POSTAL -> stringResource(R.string.picker_choose_address)
                        },
                    )
                },
                navigationIcon = { IconButton(onCancel) { Icon(Icons.Rounded.Close, stringResource(R.string.dc_cancel)) } },
                actions = {
                    if (multiple) Button({ onPicked(selected.toList()) }, enabled = selected.isNotEmpty(), modifier = Modifier.padding(end = 8.dp)) {
                        Text(stringResource(R.string.picker_done_n, selected.size))
                    }
                },
            )
        },
    ) { p ->
        Box(Modifier.padding(p).fillMaxSize().imePadding()) {
            val list = items
            if (list == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@Box
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    OutlinedTextField(
                        query, { query = it }, placeholder = { Text(stringResource(R.string.picker_search)) }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                // U5: a search with no result can be cleared; an empty list just says so.
                if (shown.isEmpty()) item {
                    if (query.isNotBlank()) {
                        EmptyState(
                            Icons.Rounded.Search, stringResource(R.string.ux_empty_no_match, query),
                            action = stringResource(R.string.ux_empty_clear_search), onAction = { query = "" },
                        )
                    } else {
                        EmptyState(Icons.Rounded.Search, stringResource(R.string.picker_nothing))
                    }
                }
                items(shown, key = { it.uri.toString() }) { pick ->
                    val checked = pick in selected
                    ListItem(
                        modifier = Modifier.clickable {
                            if (multiple) {
                                if (checked) selected.remove(pick) else selected.add(pick)
                            } else {
                                onPicked(listOf(pick))
                            }
                        },
                        leadingContent = { Avatar(pick.title, pick.photoUri, 40.dp) },
                        headlineContent = { Text(pick.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = pick.subtitle?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                        trailingContent = if (multiple) ({ Checkbox(checked, null) }) else null,
                    )
                }
            }
        }
    }
}

private fun loadPicks(context: android.content.Context, kind: PickKind, res: android.content.res.Resources): List<Pick> {
    val cr = context.contentResolver
    val out = ArrayList<Pick>()
    try {
        when (kind) {
            PickKind.CONTACT -> cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                null, null, ContactsContract.Contacts.SORT_KEY_PRIMARY,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(2) ?: continue
                    out += Pick(id, ContactsContract.Contacts.getLookupUri(id, c.getString(1) ?: ""), name, null, c.getString(3))
                }
            }
            else -> {
                val (uri, valueCol, typeCol, labelCol) = when (kind) {
                    PickKind.PHONE -> listOf(Phone.CONTENT_URI.toString(), Phone.NUMBER, Phone.TYPE, Phone.LABEL)
                    PickKind.EMAIL -> listOf(Email.CONTENT_URI.toString(), Email.ADDRESS, Email.TYPE, Email.LABEL)
                    else -> listOf(StructuredPostal.CONTENT_URI.toString(), StructuredPostal.FORMATTED_ADDRESS, StructuredPostal.TYPE, StructuredPostal.LABEL)
                }
                val base = android.net.Uri.parse(uri)
                cr.query(
                    base,
                    arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY, valueCol, typeCol, labelCol, ContactsContract.Data.PHOTO_THUMBNAIL_URI),
                    null, null, ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val value = c.getString(3) ?: continue
                        val type = c.getInt(4)
                        val label = c.getString(5)
                        val typeLabel = when (kind) {
                            PickKind.PHONE -> Phone.getTypeLabel(res, type, label)
                            PickKind.EMAIL -> Email.getTypeLabel(res, type, label)
                            else -> StructuredPostal.getTypeLabel(res, type, label)
                        }
                        out += Pick(
                            contactId = c.getLong(1),
                            uri = ContentUris.withAppendedId(base, c.getLong(0)),
                            title = c.getString(2) ?: value,
                            subtitle = "$typeLabel · $value",
                            photoUri = c.getString(6),
                        )
                    }
                }
            }
        }
    } catch (_: SecurityException) {
    }
    return out
}
