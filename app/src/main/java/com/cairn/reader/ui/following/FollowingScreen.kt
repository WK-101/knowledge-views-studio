@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.following

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.R
import com.cairn.reader.domain.follow.FollowSpec
import com.cairn.reader.ui.components.EmptyState
import com.cairn.reader.ui.components.ItemRow

/**
 * The Following surface (Content Engine P5): followed authors & topics as live streams over the whole
 * local archive. Pick a follow from the chip row; its matching articles fill the list below. Follows
 * are created from an article (reader overflow "Follow author", selection-pill "Follow topic").
 */
@Composable
fun FollowingScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    viewModel: FollowingViewModel = hiltViewModel(),
) {
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation)) }
            },
            title = { Text(stringResource(R.string.following)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface),
        )

        if (follows.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.following_empty_title),
                body = stringResource(R.string.following_empty_body),
                icon = Icons.Outlined.RssFeed,
            )
            return
        }

        // Follow chips — pick which stream to view.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            follows.forEach { spec ->
                FilterChip(
                    selected = spec == selected,
                    onClick = { viewModel.select(spec) },
                    label = { Text(spec.value, maxLines = 1) },
                    leadingIcon = {
                        Icon(
                            if (spec.kind == FollowSpec.Kind.AUTHOR) Icons.Outlined.Person else Icons.Outlined.Tag,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))

        selected?.let { spec ->
            // Header for the active stream: what it is + how many + unfollow.
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val kindLabel = if (spec.kind == FollowSpec.Kind.AUTHOR) stringResource(R.string.follow_author_label) else stringResource(R.string.follow_topic_label)
                Text(
                    "$kindLabel · ${items.size}",
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.unfollow(spec) }) { Text(stringResource(R.string.unfollow)) }
            }
        }

        when {
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    stringResource(R.string.following_no_matches),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                items(items, key = { it.id }) { row ->
                    ItemRow(
                        row = row,
                        onOpen = {
                            com.cairn.reader.ui.reader.ReaderQueue.set(items.map { it.id })
                            onOpenItem(row.id)
                        },
                        onToggleSave = { viewModel.toggleSave(row.id, !row.isReadLater) },
                    )
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}
