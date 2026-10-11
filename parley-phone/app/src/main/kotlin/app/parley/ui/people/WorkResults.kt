package app.parley.ui.people

import android.content.Context
import android.graphics.Rect
import android.provider.ContactsContract.QuickContact
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.people.WorkContact
import app.parley.container
import app.parley.data.WorkContactSearch
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.ListSectionHeader
import app.parley.ui.ParleyListItem
import app.parley.ui.Spacing
import app.parley.ui.avatarSize
import kotlinx.coroutines.delay

/** Waits this long after the last keystroke before asking the work profile (it is a cross-profile query). */
private const val WORK_SEARCH_DEBOUNCE_MS = 200L

/**
 * The work profile's contacts matching [query], for a search screen's "Work" section. Empty when there is no work
 * profile, its admin doesn't allow the search, or nothing matches. Held only by the screen showing it.
 */
@Composable
fun rememberWorkResults(query: String): List<WorkContact> {
    val search = LocalContext.current.container.workContacts
    val results by produceState(emptyList<WorkContact>(), query) {
        if (query.isBlank()) {
            value = emptyList()
            return@produceState
        }
        delay(WORK_SEARCH_DEBOUNCE_MS)
        value = search.search(query)
    }
    return results
}

/** The "Work" section under a search's results: a header, then one read-only row per work contact. */
fun LazyListScope.workResultsSection(results: List<WorkContact>, onCall: (number: String, name: String) -> Unit) {
    if (results.isEmpty()) return
    item(key = "work-header") { WorkSectionHeader() }
    items(results, key = { "w" + it.id }) { c -> WorkContactRow(c, onCall) }
}

@Composable
private fun WorkSectionHeader() {
    ListSectionHeader(stringResource(R.string.rel_group_work), inset = Spacing.xl)
}

/** A work contact: a tap opens the work profile's own card for it (or calls, if that can't open). */
@Composable
private fun WorkContactRow(c: WorkContact, onCall: (number: String, name: String) -> Unit) {
    val context = LocalContext.current
    val number = c.number
    ParleyListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.work_search_open_hint)) {
            if (!openWorkCard(context, c) && number != null) onCall(number, c.name)
        },
        leadingContent = {
            Box {
                Avatar(c.name, c.photoUri, avatarSize())
                WorkBadge(Modifier.align(Alignment.BottomEnd))
            }
        },
        headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val line = if (number != null) {
                listOfNotNull(c.numberLabel, Bidi.ltr(number)).joinToString(stringResource(R.string.main_separator))
            } else {
                stringResource(R.string.work_search_from_profile)
            }
            Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = number?.let {
            {
                IconButton({ onCall(it, c.name) }) {
                    Icon(Icons.Rounded.Call, stringResource(R.string.circle_call_who, c.name), tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
    )
}

/** The briefcase on a work contact's photo, as the lock marks a private one. */
@Composable
fun WorkBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Work, stringResource(R.string.work_search_badge),
            Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** Opens the system's quick contact card for [c], which Android shows in the work profile; false when it can't. */
private fun openWorkCard(context: Context, c: WorkContact): Boolean {
    val uri = WorkContactSearch.lookupUri(c) ?: return false
    return try {
        QuickContact.showQuickContact(context, Rect(), uri, QuickContact.MODE_LARGE, null)
        true
    } catch (_: RuntimeException) {
        // No contacts app in the work profile, or the work profile is paused.
        false
    }
}
