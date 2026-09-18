@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DisplaySettings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Rocket
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.R
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.ui.components.CairnSearchField
import com.cairn.reader.ui.components.FeedSettingsSheet

/**
 * The Settings home is a compact index of categories, not one long list of controls. Tapping a
 * category drills into a focused screen with just that category's groups; system Back (or the
 * in-screen arrow) returns to the index. A search field at the top jumps straight to the category
 * that holds a control — type "duplicates", "backup", "keep", "sync"… and the matching categories
 * surface. This keeps every control one or two taps away without ever scrolling a wall of switches.
 */
enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    /** Free-text index of the controls inside this category, so search can find them by name. */
    val keywords: String,
) {
    FEEDS(
        "Feeds & articles",
        "Defaults for new feeds, sync schedule, how many to keep, duplicates & muted words",
        Icons.Outlined.RssFeed,
        "feed default folder full text fetch article sync wifi charging interval schedule refresh retention keep per feed how many articles delete older unread never duplicates hide muted keywords blocklist",
    ),
    APPEARANCE(
        "Appearance",
        "Theme, colours, and how lists look",
        Icons.Outlined.Palette,
        "theme dark light system dynamic color colour accent seed material you amoled pure black true black list density compact comfortable thumbnails excerpts reading time sticky date headers single column tablet",
    ),
    READING(
        "Reading",
        "Reader text, gestures, images and paging",
        Icons.Outlined.MenuBook,
        "reader reading font typeface theme sepia paper night text size justify images show immersive scroll full screen tap edges volume keys paging swipe left right half full gestures open web page bionic",
    ),
    GENERAL(
        "General",
        "Reading behaviour, listening, automation & notifications",
        Icons.Outlined.Tune,
        "mark read on scroll listen text to speech tts read aloud commute auto offline pack daily brief notification quiet nudge",
    ),
    STARTUP(
        "Startup & layout",
        "What opens on launch and the bottom bar",
        Icons.Outlined.Rocket,
        "startup open on launch start destination inbox library read later discover feeds filter unread starred saved all bottom bar navigation tabs destinations order",
    ),
    SOURCES(
        "Sources",
        "Your subscribed feeds",
        Icons.Outlined.DisplaySettings,
        "sources feeds subscriptions manage sync now folder edit remove per feed settings",
    ),
    STORAGE(
        "Storage",
        "Space used, offline copies and cleanup",
        Icons.Outlined.Storage,
        "storage space used device offline copies cache on open download images wifi optimize clean database orphan reclaim readable offline",
    ),
    BACKUP(
        "Backup & restore",
        "Automatic backups, import, export and transfer",
        Icons.Outlined.CloudSync,
        "backup restore automatic folder schedule frequency daily weekly full json zip archive import opml reading list pdf export markdown obsidian vault epub kindle csv webdav nextcloud device transfer diagnostics log",
    ),
    PRIVACY(
        "Privacy",
        "Tracking, sanitising, link-rot and dictionary",
        Icons.Outlined.Shield,
        "privacy strip tracking utm fbclid gclid links sanitize article content pixels beacons link rot check saved online dictionary lookups no account no ads",
    ),
    TOOLS(
        "Tools",
        "Highlights, review, rules and insights",
        Icons.Outlined.Bolt,
        "highlights notes notebook review spaced repetition recall flashcard fsrs sm2 scheduler retention interval session transcript transcription captions subtitles youtube podcast video whisper speech to text on device model rules automation auto tag star file skip insights private reading stats top picks",
    ),
    ABOUT(
        "About",
        "Version and what Cairn is",
        Icons.Outlined.Info,
        "about version cairn one reader offline",
    ),
}

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    onOpenNotebook: () -> Unit = {},
    onOpenOffline: () -> Unit = {},
    onOpenRules: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    onOpenDataForever: () -> Unit = {},
    initialCategory: String? = null,
    onCategoryConsumed: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var selected by remember { mutableStateOf<SettingsCategory?>(null) }
    var query by remember { mutableStateOf("") }

    // A deep-link (e.g. "Your data, forever" → Backup) opens a category directly, then clears the
    // request so returning to the index and re-entering Settings starts at the index again.
    LaunchedEffect(initialCategory) {
        val cat = initialCategory?.let { key -> SettingsCategory.entries.firstOrNull { it.name == key } }
        if (cat != null) {
            selected = cat
            onCategoryConsumed()
        }
    }

    // Back closes an open category first, then clears an active search, before leaving Settings.
    BackHandler(enabled = selected != null || query.isNotBlank()) {
        if (selected != null) selected = null else query = ""
    }

    val current = selected
    if (current == null) {
        SettingsIndex(
            padding = padding,
            query = query,
            onQuery = { query = it },
            onOpenCategory = { selected = it; query = "" },
            onOpenDataForever = onOpenDataForever,
        )
    } else {
        SettingsDetail(
            padding = padding,
            category = current,
            onBack = { selected = null },
            onOpenNotebook = onOpenNotebook,
            onOpenOffline = onOpenOffline,
            onOpenRules = onOpenRules,
            onOpenInsights = onOpenInsights,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun SettingsIndex(
    padding: PaddingValues,
    query: String,
    onQuery: (String) -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenDataForever: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val q = query.trim()
    val results = if (q.isEmpty()) SettingsCategory.entries.toList() else SettingsCategory.entries.filter {
        it.title.contains(q, ignoreCase = true) ||
            it.subtitle.contains(q, ignoreCase = true) ||
            it.keywords.contains(q, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                CairnSearchField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = "Search settings",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (q.isEmpty()) {
            item {
                // Anti-shutdown headline: one tap to the "Your data, forever" promise (separate from Backup).
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
        }
        if (results.isEmpty()) {
            item {
                Text(
                    "No settings match “$q”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                )
            }
        } else {
            item { Spacer(Modifier.height(4.dp)) }
            items(results, key = { it.name }) { cat ->
                CategoryRow(cat, onClick = { onOpenCategory(cat) })
            }
        }
    }
}

@Composable
private fun CategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(category.icon, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(category.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = scheme.onSurface)
            Text(category.subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsDetail(
    padding: PaddingValues,
    category: SettingsCategory,
    onBack: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenInsights: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val scheme = MaterialTheme.colorScheme
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val highlightCount by viewModel.highlightCount.collectAsStateWithLifecycle()
    val ruleCount by viewModel.ruleCount.collectAsStateWithLifecycle()
    var feedSettings by remember { mutableStateOf<SourceEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(
                    category.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        when (category) {
            SettingsCategory.FEEDS -> {
                item { FeedDefaultsSection(prefs, viewModel, folders) }
                item { SyncSettingsGroup(prefs, viewModel) }
                item { RetentionSettingsGroup(prefs, viewModel) }
                item { FiltersSection(prefs, viewModel) }
            }
            SettingsCategory.APPEARANCE -> {
                item { AppearanceSection(prefs, viewModel) }
                item { ListDensitySection(prefs, viewModel) }
            }
            SettingsCategory.READING -> {
                item { ReaderTypographySection(prefs, viewModel) }
                item { GesturesSection(prefs, viewModel) }
            }
            SettingsCategory.GENERAL -> {
                item { GeneralTogglesSection(prefs, viewModel) }
            }
            SettingsCategory.STARTUP -> {
                item { StartupSection(prefs, viewModel) }
                item { BottomBarSection(prefs, viewModel) }
            }
            SettingsCategory.SOURCES -> {
                item { SourcesGroup(sources, onSyncNow = viewModel::syncNow, onOpenFeed = { feedSettings = it }) }
            }
            SettingsCategory.STORAGE -> {
                item {
                    SettingsGroup("Space on this device") {
                        Column(Modifier.padding(16.dp)) { StorageSection(viewModel) }
                    }
                }
                item { OfflineCopiesGroup(prefs, viewModel) }
                item {
                    SettingsGroup("Offline articles") {
                        SettingActionRow(
                            title = stringResource(R.string.offline_storage),
                            subtitle = "Browse and manage every article saved for offline reading.",
                            icon = Icons.Outlined.CloudDownload,
                            onClick = onOpenOffline,
                        )
                    }
                }
            }
            SettingsCategory.BACKUP -> {
                item { ImportExportSection(prefs, viewModel) }
            }
            SettingsCategory.PRIVACY -> {
                item { PrivacySection(prefs, viewModel) }
            }
            SettingsCategory.TOOLS -> {
                item {
                    ExtrasSection(
                        onOpenNotebook = onOpenNotebook,
                        onOpenRules = onOpenRules,
                        onOpenInsights = onOpenInsights,
                        highlightCount = highlightCount,
                        ruleCount = ruleCount,
                    )
                }
                item { ReviewSettingsSection(prefs, viewModel) }
                item { TranscriptionSettingsSection() }
            }
            SettingsCategory.ABOUT -> {
                item { AboutSection() }
            }
        }
    }

    feedSettings?.let { source ->
        val archiveCtx = androidx.compose.ui.platform.LocalContext.current
        FeedSettingsSheet(
            source = source,
            folders = folders,
            onFolder = { viewModel.setFolder(source.id, it) },
            onFullText = { viewModel.setFullText(source.id, it) },
            onAcquisition = { viewModel.setAcquisition(source.id, it) },
            onDepth = { viewModel.setDepth(source.id, it) },
            onBackfill = { viewModel.requestBackfill(source.id); com.cairn.reader.work.CairnWork.startArchive(archiveCtx) },
            onCancelBackfill = { viewModel.cancelBackfill(source.id) },
            onNotify = { viewModel.setNotify(source.id, it) },
            onMuted = { viewModel.setMuted(source.id, it) },
            onSetPaused = { viewModel.setSyncPaused(source.id, it) },
            onVerify = { viewModel.verifyFeed(source.id) },
            onRemove = { viewModel.removeSource(source.id) },
            onDismiss = { feedSettings = null },
        )
    }
}

/** The subscribed-feeds list with a "Sync now" action, in the shared grouped-card style. */
@Composable
private fun SourcesGroup(
    sources: List<SourceEntity>,
    onSyncNow: () -> Unit,
    onOpenFeed: (SourceEntity) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SettingsGroup("Sources · ${sources.size}") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (sources.isEmpty()) "No feeds yet — add one from Discover or the Inbox." else "Tap a feed to change its folder, full-text, notifications or to remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onSyncNow) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sync_now))
            }
        }
        sources.forEach { source ->
            SettingDivider()
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenFeed(source) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(source.title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = source.folder?.takeIf { it.isNotBlank() }?.let { "$it · ${source.feedUrl}" } ?: source.feedUrl
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
        }
    }
}
