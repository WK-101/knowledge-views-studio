package app.parley.ui.contact

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.people.ContactPageLayout
import app.parley.common.people.ContactSection

/**
 * The sections a contact's page has, collected in code order with their title, the summary shown while
 * folded, and their content; [foldableSections] draws them in the order chosen in Settings.
 */
class PageSections {
    internal class Entry(val title: String, val summary: String, val body: @Composable () -> Unit)

    internal val entries = LinkedHashMap<ContactSection, Entry>()

    fun add(section: ContactSection, title: String, summary: String, body: @Composable () -> Unit) {
        entries[section] = Entry(title, summary, body)
    }

    fun titleOf(section: ContactSection): String = entries[section]?.title.orEmpty()
}

/** The sections this page shows, in order (hidden and empty ones left out). */
fun PageSections.shown(layout: ContactPageLayout): List<ContactSection> = layout.visible.filter { it in entries }

/** One list item per shown section: a fold header and its content, which folds with a spring. */
fun LazyListScope.foldableSections(sections: PageSections, layout: ContactPageLayout, onFold: (ContactSection, Boolean) -> Unit) {
    sections.shown(layout).forEach { s ->
        val e = sections.entries.getValue(s)
        item(key = s.id, contentType = "section") {
            val folded = layout.isFolded(s)
            Column {
                FoldHeader(e.title, e.summary, folded) { onFold(s, !folded) }
                AnimatedVisibility(
                    !folded,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(),
                ) { e.body() }
            }
        }
    }
}

/** The header of a foldable section: its title, a summary while folded ("124 entries"), and a turning chevron. */
@Composable
fun FoldHeader(title: String, summary: String, folded: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (folded) 0f else 180f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "chevron")
    val state = stringResource(if (folded) R.string.contact_page_folded else R.string.contact_page_open)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                role = Role.Button, onClickLabel = stringResource(if (folded) R.string.contact_page_unfold else R.string.contact_page_fold),
                onClick = onToggle,
            )
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = state
            }
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (folded && summary.isNotBlank()) stringResource(R.string.main_separator) + summary else "",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp).graphicsLayer { rotationZ = turn },
        )
    }
}

/** One of the pinned header's quick actions. */
class QuickAction(val icon: ImageVector, val label: String, val enabled: Boolean, val onClick: () -> Unit)

/**
 * The compact bar that stays under the top bar once the big header has scrolled away: the quick actions as
 * 48 dp tonal buttons and, on long pages, chips that jump to a section.
 */
@Composable
fun PinnedContactBar(actions: List<QuickAction>, jumps: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            actions.forEach { a ->
                FilledTonalIconButton(
                    a.onClick, enabled = a.enabled,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(),
                ) { Icon(a.icon, a.label) }
            }
        }
        if (jumps.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(jumps, key = { it.first }) { (label, go) ->
                    SuggestionChip(onClick = go, label = { Text(label, maxLines = 1) })
                }
            }
        }
    }
}

/** A section's name, as the page and Settings › Contact page sections show it. */
fun sectionTitle(res: Resources, s: ContactSection): String = res.getString(
    when (s) {
        ContactSection.STAY -> R.string.contact_page_sec_stay
        ContactSection.DATES -> R.string.contact_page_sec_dates
        ContactSection.PHONES -> R.string.contact_page_sec_phones
        ContactSection.EMAILS -> R.string.contact_page_sec_emails
        ContactSection.ADDRESSES -> R.string.contact_page_sec_addresses
        ContactSection.MESSENGERS -> R.string.contact_page_sec_messengers
        ContactSection.ABOUT -> R.string.contact_page_sec_about
        ContactSection.OTHER -> R.string.contact_page_sec_other
        ContactSection.TIMELINE -> R.string.contact_page_sec_timeline
        ContactSection.INSIGHTS -> R.string.contact_page_sec_insights
        ContactSection.NOTE -> R.string.contact_page_sec_note
        ContactSection.SETTINGS -> R.string.contact_page_sec_settings
    },
)
