package app.parley.ui.contact

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parley.R
import app.parley.common.people.Profile
import app.parley.common.people.ProfileProblem
import app.parley.common.people.ProfileService
import app.parley.common.people.SocialProfiles
import app.parley.data.DataItem
import app.parley.ui.Clipboard
import app.parley.ui.FormRow
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.SegmentedGroupScope
import app.parley.ui.common.Intents

/** The services in the order the "Add a profile" list and the service menu show them: the most used first. */
val profileServices: List<ProfileService> = SocialProfiles.common + ProfileService.entries.filter { it !in SocialProfiles.common }

/**
 * A service's badge: its short mark in a small tonal circle (Parley ships no brand logos). Decorative: the service's
 * name is always written beside it.
 */
@Composable
fun ProfileGlyph(service: ProfileService, size: Dp = 24.dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            service.mark, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp, maxLines = 1,
        )
    }
}

/**
 * The contact page's "Profiles" rows: the handle as the service writes it ("@ana.lima") over the service's name. A tap
 * opens the https profile address, which the service's app takes when installed (its app links) and the browser
 * otherwise; Parley itself fetches nothing. Long-press copies the handle, or the link.
 */
@OptIn(ExperimentalFoundationApi::class)
fun SegmentedGroupScope.profileRows(profiles: List<Profile>) {
    profiles.filter { it.handle.isNotBlank() }.forEach { p ->
        item {
            val context = LocalContext.current
            var menu by remember { mutableStateOf(false) }
            val url = p.url
            Box {
                InfoRow(
                    modifier = Modifier.combinedClickable(
                        onClick = { if (url.isNotEmpty()) Intents.web(context, url) else Clipboard.copy(context, p.display, sensitive = false) },
                        onClickLabel = stringResource(R.string.detail_open_profile, p.service.label),
                        onLongClick = { menu = true },
                        onLongClickLabel = stringResource(R.string.main_more_actions),
                    ),
                    leading = { ProfileGlyph(p.service) },
                    headline = { Text(p.display) },
                    supporting = { Text(p.service.label) },
                    trailing = if (url.isNotEmpty()) {
                        {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.detail_open_profile, p.service.label),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        null
                    },
                )
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        { Text(stringResource(R.string.hist_action_copy)) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; Clipboard.copy(context, p.display, sensitive = false) },
                    )
                    if (url.isNotEmpty()) {
                        DropdownMenuItem(
                            { Text(stringResource(R.string.detail_copy_link)) }, leadingIcon = { Icon(Icons.Rounded.Link, null) },
                            onClick = { menu = false; Clipboard.copy(context, url, sensitive = false) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One profile in the editor: the service as the field's selector, the handle typed or pasted (a pasted profile link
 * gives its handle, and a link of another service switches the service). The row stays a website row underneath:
 * the profile's address, labelled with the service's name ([SocialProfiles]).
 */
@Composable
internal fun ProfileRow(
    item: DataItem,
    profile: Profile,
    focus: FocusRequester,
    icon: ImageVector?,
    groupTitle: String?,
    shape: Shape,
    locked: Boolean,
    onChange: (DataItem) -> Unit,
    onRemove: () -> Unit,
) {
    val service = profile.service
    // What is typed is kept as typed while the field is used; the row holds the address made from it.
    var text by remember { mutableStateOf(profile.handle) }
    LaunchedEffect(item.value) {
        if (SocialProfiles.valueFor(service, text) != item.value) text = SocialProfiles.normalize(service, item.value)
    }
    fun relabel(to: ProfileService, typed: String): DataItem =
        item.copy(type = SocialProfiles.TYPE_CUSTOM, label = to.label, value = SocialProfiles.valueFor(to, typed))
    val problem = SocialProfiles.problem(service, text)
    val formatHint = when (problem) {
        ProfileProblem.FORMAT -> stringResource(R.string.edit_profile_format, service.label)
        ProfileProblem.NEEDS_SERVER -> stringResource(R.string.edit_profile_needs_server)
        null -> null
    }
    FormRow(
        icon, groupTitle,
        end = if (!locked) { { RemoveButton(stringResource(R.string.edit_remove_profile), onRemove) } } else null,
    ) {
        TypedLine(
            pill = if (locked) null else {
                {
                    TypePill(service.label, profileServices.map { it.label }) { i ->
                        val to = profileServices[i]
                        if (to != service) onChange(relabel(to, text))
                    }
                }
            },
        ) { trailing ->
            EditorField(
                service.label, text, shape = shape, keyboard = KeyboardType.Uri, locked = locked, focus = focus,
                placeholder = service.placeholder,
                hint = formatHint,
                support = if (text.isBlank() && !locked) stringResource(R.string.edit_profile_hint) else null,
                trailing = trailing,
            ) { typed ->
                // A link of another service (pasted into the wrong row) moves the row to that service.
                val other = SocialProfiles.fromUrl(typed)?.takeIf { it.service != service }
                if (other != null) {
                    text = other.handle
                    onChange(relabel(other.service, other.handle))
                } else {
                    text = typed
                    onChange(item.copy(value = SocialProfiles.valueFor(service, typed)))
                }
            }
        }
    }
}

/** "Add a profile": the services, most used first, then "Other link" (an ordinary website with its own label). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilePickerSheet(onDismiss: () -> Unit, onPick: (ProfileService?) -> Unit) {
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.edit_add_profile_title)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            profileServices.forEach { s ->
                ParleyListItem(
                    headlineContent = { Text(s.label) },
                    modifier = Modifier.clickable { onPick(s) },
                    leadingContent = { ProfileGlyph(s, 32.dp) },
                )
            }
            ParleyListItem(
                headlineContent = { Text(stringResource(R.string.edit_profile_other_link)) },
                supportingContent = { Text(stringResource(R.string.edit_profile_other_link_summary)) },
                modifier = Modifier.clickable { onPick(null) },
                leadingContent = { Icon(Icons.Rounded.Link, null, Modifier.size(32.dp).padding(4.dp)) },
            )
        }
    }
}
