package app.parley.ui.people.archive

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.catching
import app.parley.common.people.ArchivedView
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.Destination
import app.parley.ui.EmptyState
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyTag
import app.parley.ui.TagTone
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An archived contact's own page (docs/CONTACT_MODEL.md, "Archived"): read-only, what it holds and when it was
 * archived, with Unarchive. A number opens its calls. Nothing here can be edited: Unarchive puts the contact back,
 * whole, where everything can be changed again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedContactScreen(vm: AppViewModel, id: Long, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val cards by vm.c.archive.cards.collectAsStateWithLifecycle()
    val card = cards.firstOrNull { it.id == id }
    // The sealed record, read off the main thread; null until read, or when it can't be.
    val lines by produceState<List<ArchivedView.Line>?>(null, id) {
        value = withContext(Dispatchers.IO) { catching { vm.c.archive.readRecord(id) }.getOrNull()?.let(ArchivedView::lines) }
    }
    val unarchiving = rememberUnarchiving(vm)
    // Unarchived (here or elsewhere) while open: the page closes, the contact is back in the lists.
    var wasShown by remember { mutableStateOf(false) }
    LaunchedEffect(card != null) { if (card != null) wasShown = true else if (wasShown) back() }
    ParleyScaffold(topBar = { ParleyTopBar(card?.name.orEmpty(), onBack = back) }) { p ->
        if (card == null) {
            // Unarchived meanwhile (here or elsewhere): nothing to show.
            EmptyState(Icons.Rounded.Unarchive, stringResource(R.string.archive_page_gone), modifier = Modifier.padding(p))
            return@ParleyScaffold
        }
        LazyColumn(Modifier.padding(p)) {
            item(key = "head") {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.l), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Avatar(card.name, null, 96.dp, isCompany = card.company.isNotBlank() && card.company == card.name)
                    Text(card.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    // The archived marker, the same tag as the other variants (Private, Temporary).
                    ParleyTag(stringResource(R.string.archive_title_screen), tone = TagTone.INFO, icon = Icons.Rounded.Archive)
                    Text(
                        stringResource(R.string.archive_row_when, DateUtils.formatDateTime(context, card.archivedAt, DateUtils.FORMAT_SHOW_DATE)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.archive_page_intro), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton({ unarchiving.start(card) }) {
                        Icon(Icons.Rounded.Unarchive, null, Modifier.padding(end = Spacing.s))
                        Text(stringResource(R.string.archive_unarchive))
                    }
                }
            }
            // While the record is read (or when it can't be), the card's own numbers stand in.
            val shown = lines ?: card.numbers.map { ArchivedView.Line(ArchivedView.Kind.PHONE, it) }
            items(shown, key = { "${it.kind}:${it.value}" }) { l ->
                val phone = l.kind == ArchivedView.Kind.PHONE
                ParleyListItem(
                    modifier = if (phone) Modifier.clickable { open(Routes.history(l.value)) } else Modifier,
                    leadingContent = { Icon(lineIcon(l.kind), null) },
                    headlineContent = {
                        Text(
                            when {
                                phone -> Bidi.ltr(Format.number(l.value, vm.countryIso))
                                l.extra.isNotEmpty() -> stringResource(R.string.archive_page_work, l.value, l.extra)
                                else -> l.value
                            },
                        )
                    },
                )
            }
        }
    }
    unarchiving.Host()
}

private fun lineIcon(k: ArchivedView.Kind): ImageVector = when (k) {
    ArchivedView.Kind.PHONE -> Icons.Rounded.Phone
    ArchivedView.Kind.EMAIL -> Icons.Rounded.Email
    ArchivedView.Kind.ADDRESS -> Icons.Rounded.Place
    ArchivedView.Kind.WORK -> Icons.Rounded.Business
    ArchivedView.Kind.WEBSITE -> Icons.Rounded.Language
    ArchivedView.Kind.DATE -> Icons.Rounded.Cake
    ArchivedView.Kind.NOTE -> Icons.AutoMirrored.Rounded.Notes
}
