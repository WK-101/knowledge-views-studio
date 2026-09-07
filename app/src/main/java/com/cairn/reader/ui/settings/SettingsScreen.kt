@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.settings

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.data.prefs.SwipeAction
import com.cairn.reader.data.prefs.ThemeMode
import com.cairn.reader.ui.components.FeedSettingsSheet
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    onOpenNotebook: () -> Unit = {},
    onOpenOffline: () -> Unit = {},
    onOpenRules: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    onOpenDataForever: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val highlightCount by viewModel.highlightCount.collectAsStateWithLifecycle()
    val ruleCount by viewModel.ruleCount.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var feedSettings by remember { mutableStateOf<SourceEntity?>(null) }
    // Sources can be a long list; keep the section folded by default so it doesn't crowd Settings.
    var sourcesExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item {
            // Anti-shutdown headline: one tap to the "Your data, forever" panel.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.primaryContainer)
                    .clickable(onClick = onOpenDataForever)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.your_data_forever), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onPrimaryContainer)
                    Text(stringResource(R.string.no_account_no_lock_in_back), style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer.copy(alpha = 0.85f))
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onPrimaryContainer)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { sourcesExpanded = !sourcesExpanded }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (sourcesExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (sourcesExpanded) "Collapse" else "Expand",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.height(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                SettingsSectionLabel("SOURCES · ${sources.size}")
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = viewModel::syncNow) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.sync_now))
                }
            }
        }
        if (sourcesExpanded) {
            items(sources, key = { it.id }) { source ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { feedSettings = source }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = source.folder?.takeIf { it.isNotBlank() }?.let { "$it · ${source.feedUrl}" } ?: source.feedUrl
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
                }
            }
        }

        item {
            ExtrasSection(
                prefs = prefs,
                onOpenNotebook = onOpenNotebook,
                onOpenOffline = onOpenOffline,
                onOpenRules = onOpenRules,
                onOpenInsights = onOpenInsights,
                highlightCount = highlightCount,
                ruleCount = ruleCount,
            )
        }
        item { FeedDefaultsSection(prefs, viewModel, folders) }
        item { StartupSection(prefs, viewModel) }
        item { AppearanceSection(prefs, viewModel) }
        item { GesturesSection(prefs, viewModel) }
        item { ListDensitySection(prefs, viewModel) }
        item { GeneralTogglesSection(prefs, viewModel) }
        item { BottomBarSection(prefs, viewModel) }
        item { FiltersSection(prefs, viewModel) }
        item {
            SettingsGroup("Storage") {
                Column(Modifier.padding(16.dp)) { StorageSection(viewModel) }
            }
        }
        item { ImportExportSection(prefs, viewModel) }
        item { PrivacyAboutSection(prefs, viewModel) }
    }

    feedSettings?.let { source ->
        FeedSettingsSheet(
            source = source,
            folders = folders,
            onFolder = { viewModel.setFolder(source.id, it) },
            onFullText = { viewModel.setFullText(source.id, it) },
            onNotify = { viewModel.setNotify(source.id, it) },
            onMuted = { viewModel.setMuted(source.id, it) },
            onRemove = { viewModel.removeSource(source.id) },
            onDismiss = { feedSettings = null },
        )
    }
}
