package app.parley.ui.extras

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.CallSource
import app.parley.common.extras.TripMatch
import app.parley.data.extras.ExtrasStore
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.contact.rememberQuickMessenger
import kotlinx.coroutines.delay

/**
 * X2 "Who's in…": type a city (or pick one from your contacts' addresses) and see who's linked to it by address,
 * by a note, or by where their number is from. No location permission: the city is always typed or picked.
 * The last city is remembered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val store = vm.c.extras
    var city by rememberSaveable { mutableStateOf(store.lastTripCity.orEmpty()) }
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val data by produceState<ExtrasStore.TripData?>(null, contacts) {
        value = runCatching { store.tripData(app.parley.data.PhoneEnv.countryIso(context)) }.getOrElse { ExtrasStore.TripData(emptyList(), emptyList()) }
    }
    val choices = remember(data) { data?.let { TripMatch.cityChoices(it.cities, store.lastTripCity) }.orEmpty() }
    val hits = remember(data, city) { data?.let { TripMatch.match(city, it.people) }.orEmpty() }
    val byId = remember(contacts) { contacts.orEmpty().associateBy { it.id } }
    // Remembered once the typing settles (and only when something was found or the field was cleared on purpose).
    LaunchedEffect(city) {
        delay(800)
        if (city.isNotBlank()) store.lastTripCity = city
    }
    val (quick, quickHost) = rememberQuickMessenger(vm)
    quickHost()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.x_trip_title)) },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "field") {
                OutlinedTextField(
                    city, { city = it },
                    label = { Text(stringResource(R.string.x_trip_city)) },
                    leadingIcon = { Icon(Icons.Rounded.LocationCity, null) },
                    trailingIcon = { if (city.isNotEmpty()) IconButton({ city = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.home_clear_search)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    stringResource(R.string.x_trip_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (choices.isNotEmpty()) item(key = "choices") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { c -> FilterChip(TripMatch.normalize(c) == TripMatch.normalize(city), { city = c; keyboard?.hide() }, label = { Text(c) }) }
                }
            }
            when {
                data == null -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                city.isBlank() -> item(key = "empty") {
                    EmptyState(Icons.Rounded.TravelExplore, stringResource(R.string.x_trip_empty_title), stringResource(R.string.x_trip_empty_body), Modifier.padding(top = 16.dp))
                }
                hits.isEmpty() -> item(key = "none") {
                    EmptyState(Icons.Rounded.TravelExplore, stringResource(R.string.x_trip_none, city.trim()), stringResource(R.string.x_trip_none_body), Modifier.padding(top = 16.dp))
                }
                else -> {
                    item(key = "summary") {
                        val names = hits.take(3).joinToString(stringResource(R.string.x_list_separator)) { it.person.name }
                        val more = hits.size - 3
                        Text(
                            if (more > 0) androidx.compose.ui.res.pluralStringResource(R.plurals.x_trip_summary_more, more, city.trim(), names, more)
                            else stringResource(R.string.x_trip_summary, city.trim(), names),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(hits, key = { it.person.id }) { h ->
                        val c = byId[h.person.id]
                        val phone = c?.let { it.phones.firstOrNull { p -> p.isPrimary } ?: it.phones.firstOrNull() }
                        ListItem(
                            modifier = Modifier.clickable { open(Routes.contact(h.person.id)) },
                            leadingContent = { Avatar(h.person.name, c?.photoUri, 40.dp) },
                            headlineContent = { Text(h.person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(reasonText(h), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                if (c != null && phone != null) Row {
                                    IconButton({ vm.requestCall(phone.number, c.displayName, source = CallSource.CONTACT) }) {
                                        Icon(Icons.Rounded.Call, stringResource(R.string.circle_call_who, c.displayName))
                                    }
                                    IconButton({ quick.message(c) }) { Icon(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.circle_message_who, c.displayName)) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun reasonText(h: TripMatch.Hit): String {
    val address = stringResource(R.string.x_trip_reason_address)
    val number = stringResource(R.string.x_trip_reason_number)
    val note = stringResource(R.string.x_trip_reason_note)
    return h.reasons.sortedBy { it.ordinal }.joinToString(stringResource(R.string.main_separator)) { r ->
        when (r) {
            TripMatch.Reason.ADDRESS -> address
            TripMatch.Reason.NUMBER -> number
            TripMatch.Reason.NOTE -> note
        }
    }
}
