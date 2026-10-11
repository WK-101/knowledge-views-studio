package app.parley.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow

/**
 * The one selection bar of every multi-select list: it takes the top bar's place while rows are selected, with Close
 * (Back does the same), how many are selected, then the list's own [actions] (keep any ⋮ to seven items). Each list
 * passes only its actions (and, where it says more, its own [title] or [clearLabel]), so selecting looks and behaves the same in Contacts, Recents and everywhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = pluralStringResource(R.plurals.ui_selected_count, count, count),
    clearLabel: String = stringResource(R.string.ui_clear_selection),
    actions: @Composable RowScope.() -> Unit = {},
) {
    BackHandler(onBack = onClear)
    ParleyTopBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = { BackButton(onClear, clearLabel, Icons.Rounded.Close) },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}
