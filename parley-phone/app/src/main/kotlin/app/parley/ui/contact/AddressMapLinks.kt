package app.parley.ui.contact

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.people.MapLinks
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.PostalItem
import app.parley.ui.Clipboard
import app.parley.ui.ParleyDialog
import app.parley.ui.common.Intents
import app.parley.ui.startOrSay

/**
 * An address's map link. Android's contacts store has no place for coordinates next to an address (StructuredPostal
 * holds only text), and vCard's `GEO` / `ADR;GEO=` have nowhere to land in it either, so a sync or another app would
 * drop them. The link is kept instead as a website row labelled "Map (Home)" (TYPE_CUSTOM + LABEL): Google and other
 * accounts sync it, every contacts app shows it as a link that opens, vCard carries it as `URL` with `X-ABLabel`
 * (which Parley's vCard engine round-trips), and Parley reads the position back from the link itself.
 */
object AddressMapLinks {
    /** The address's label as stored in the link's label: fixed English words for the standard types. */
    fun addressLabel(a: PostalItem): String? = when (a.type) {
        StructuredPostal.TYPE_HOME -> "Home"
        StructuredPostal.TYPE_WORK -> "Work"
        StructuredPostal.TYPE_OTHER -> "Other"
        else -> a.label?.trim()?.ifEmpty { null }
    }

    private fun sites(d: ContactDetails) = d.websites.map { MapLinks.Site(it.label.takeIf { _ -> it.type == 0 }, it.value) }

    /** Address index → index of its map link in [ContactDetails.websites]. */
    fun matches(d: ContactDetails): Map<Int, Int> = MapLinks.match(d.addresses.map { addressLabel(it).orEmpty() }, sites(d))

    /** The map link of address [index], or null. */
    fun linkOf(d: ContactDetails, index: Int): String? = matches(d)[index]?.let { d.websites.getOrNull(it)?.value }

    /**
     * Saves [place] as address [index]'s map link (replacing one it had), and fills the address from the place's
     * name, or its coordinates, when the address is still empty.
     */
    fun withLink(d: ContactDetails, index: Int, place: MapLinks.Place): ContactDetails {
        val a = d.addresses.getOrNull(index) ?: return d
        val existing = matches(d)[index]
        val text = place.name ?: place.lat?.let { lat -> place.lon?.let { lon -> MapLinks.formatPair(lat, lon) } }
        val addresses = if (a.isBlank && text != null) d.addresses.toMutableList().also { it[index] = a.copy(street = text) } else d.addresses
        // A short Plus Code can't be placed offline: it only becomes the address text.
        if (place.service == MapLinks.Service.PLUS_CODE && !place.hasCoordinates) return d.copy(addresses = addresses)
        val row = DataItem(value = MapLinks.storedLink(place), type = 0, label = MapLinks.label(addressLabel(a)))
        val websites = if (existing != null) {
            d.websites.toMutableList().also { it[existing] = it[existing].copy(value = row.value, type = 0, label = row.label) }
        } else {
            d.websites + row
        }
        return d.copy(addresses = addresses, websites = websites)
    }

    /** Takes address [index]'s map link off (the website row goes; the address stays). */
    fun withoutLink(d: ContactDetails, index: Int): ContactDetails {
        val w = matches(d)[index] ?: return d
        return d.copy(websites = d.websites.filterIndexed { i, _ -> i != w })
    }

    /** Opens exactly [lat], [lon] in a map app the person picks each time (Google Maps, Organic Maps, OsmAnd…). */
    fun openExact(context: Context, lat: Double, lon: Double, label: String?) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(MapLinks.geoUri(lat, lon, label)))
        launch(context, Intent.createChooser(view, context.getString(R.string.map_link_choose_app)))
    }

    /** Opens the saved link as it is: https links in the app that owns them (or the browser), geo: in a map app. */
    fun openLink(context: Context, link: String) {
        val l = link.trim()
        if (!l.contains(':')) return Intents.web(context, l)
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(l)).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun launch(context: Context, intent: Intent) = context.startOrSay(intent, context.getString(R.string.main_no_app))
}

/**
 * Editor › an address: "Add from map link", or, once there is one, what it holds with Change and Remove.
 * Hidden on a read-only address, whose account wouldn't keep a change.
 */
@Composable
internal fun AddressMapLinkRow(link: String?, onAdd: () -> Unit, onRemove: () -> Unit) {
    if (link == null) {
        TextButton(onAdd) {
            Icon(Icons.Rounded.AddLink, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.map_link_add))
        }
        return
    }
    val place = remember(link) { MapLinks.parse(link) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = if (place?.hasCoordinates == true) Icons.Rounded.PinDrop else Icons.Rounded.Map
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(if (place?.hasCoordinates == true) R.string.map_link_saved else R.string.map_link_saved_link_only),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        TextButton(onAdd) { Text(stringResource(R.string.map_link_change)) }
        IconButton(onRemove) { Icon(Icons.Rounded.LinkOff, stringResource(R.string.map_link_remove)) }
    }
}

/**
 * "Add from map link": paste or type a link; what Parley reads from it shows as you type (on the phone, nothing is
 * opened), and Add saves it with the address.
 */
@Composable
internal fun MapLinkDialog(onDismiss: () -> Unit, onAdd: (MapLinks.Place) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val place = remember(text) { MapLinks.parse(text) }
    ParleyDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AddLink, null) },
        title = { Text(stringResource(R.string.map_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.map_link_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    text, { text = it.take(2000) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.map_link_field)) },
                    placeholder = { Text(stringResource(R.string.map_link_placeholder)) },
                    // Links read left to right in every language.
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                // The clipboard is read only when this is tapped.
                AssistChip(
                    onClick = { Clipboard.readText(context, 2000)?.let { text = it } },
                    label = { Text(stringResource(R.string.map_link_paste)) },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)) },
                )
                if (text.isNotBlank()) {
                    Text(
                        describe(place), style = MaterialTheme.typography.bodyMedium,
                        color = if (place == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = { TextButton({ place?.let(onAdd) }, enabled = place != null) { Text(stringResource(R.string.map_link_add_button)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

@Composable
private fun describe(p: MapLinks.Place?): String {
    val lat = p?.lat
    val lon = p?.lon
    val name = p?.name
    return when {
        p == null -> stringResource(R.string.map_link_nothing)
        p.needsNetwork -> stringResource(R.string.map_link_short)
        lat != null && lon != null -> stringResource(R.string.map_link_found_spot, listOfNotNull(p.name, MapLinks.formatPair(lat, lon)).joinToString(" · "))
        p.service == MapLinks.Service.PLUS_CODE -> stringResource(R.string.map_link_short_code)
        p.service == MapLinks.Service.OTHER -> stringResource(R.string.map_link_unknown)
        name != null -> stringResource(R.string.map_link_found, name)
        else -> stringResource(R.string.map_link_unknown)
    }
}

/**
 * Contact page › an address. With a saved map link that holds a position, a tap opens that exact spot (the person
 * picks the map app); a link without one (a short link) opens as it is. The long-press menu keeps the address
 * search and the link itself.
 */
@Composable
internal fun AddressDetailRow(a: PostalItem, first: Boolean, typeLabel: String, link: String?) {
    val context = LocalContext.current
    val place = remember(link) { link?.let(MapLinks::parse) }
    val lat = place?.lat
    val lon = place?.lon
    val text = a.formatted
    val name = text.lines().firstOrNull { it.isNotBlank() }?.trim()
    GroupDataRow(
        if (lat != null) Icons.Rounded.PinDrop else Icons.Rounded.LocationOn, first, text,
        if (lat != null) stringResource(R.string.map_link_pinned, typeLabel) else typeLabel,
        onClick = {
            when {
                lat != null && lon != null -> AddressMapLinks.openExact(context, lat, lon, name)
                link != null -> AddressMapLinks.openLink(context, link)
                else -> Intents.map(context, text)
            }
        },
        menu = if (link != null) ({ close ->
            if (lat != null && lon != null) {
                DropdownMenuItem({ Text(stringResource(R.string.map_link_open_exact)) }, leadingIcon = { Icon(Icons.Rounded.PinDrop, null) }, onClick = {
                    close(); AddressMapLinks.openExact(context, lat, lon, name)
                })
            }
            if (text.isNotBlank()) {
                DropdownMenuItem({ Text(stringResource(R.string.map_link_search_address)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, onClick = {
                    close(); Intents.map(context, text)
                })
            }
            DropdownMenuItem(
                { Text(stringResource(R.string.map_link_open_link)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                onClick = { close(); AddressMapLinks.openLink(context, link) },
            )
        }) else null,
    )
}
