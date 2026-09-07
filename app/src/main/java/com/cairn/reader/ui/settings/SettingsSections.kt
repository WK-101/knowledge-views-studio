@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cairn.reader.R
import com.cairn.reader.data.prefs.AppPreferences
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.data.prefs.SwipeAction
import com.cairn.reader.data.prefs.ThemeMode

// ─── Quick access ────────────────────────────────────────────────────────────
@Composable
internal fun ExtrasSection(
    prefs: AppPreferences,
    onOpenNotebook: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenInsights: () -> Unit,
    highlightCount: Int,
    ruleCount: Int,
) {
    SettingsGroup("Quick access") {
        SettingActionRow(
            title = stringResource(R.string.highlights_notes),
            subtitle = if (highlightCount == 0) "Long-press a sentence while reading to save it" else "$highlightCount saved",
            icon = Icons.Outlined.FormatQuote,
            onClick = onOpenNotebook,
        )
        SettingDivider()
        SettingActionRow(
            title = stringResource(R.string.offline_storage),
            subtitle = buildString {
                append(if (prefs.syncWifiOnly) "Sync on Wi-Fi only" else "Sync on any network")
                append(" · ")
                append(if (prefs.maxItemsPerFeed == 0) "keep all" else "keep ${prefs.maxItemsPerFeed}/feed")
            },
            icon = Icons.Outlined.CloudDownload,
            onClick = onOpenOffline,
        )
        SettingDivider()
        SettingActionRow(
            title = stringResource(R.string.rules_automation),
            subtitle = if (ruleCount == 0) "Auto-tag, star, file or skip new articles" else "$ruleCount active",
            icon = Icons.Outlined.Bolt,
            onClick = onOpenRules,
        )
        SettingDivider()
        SettingActionRow(
            title = stringResource(R.string.insights),
            subtitle = stringResource(R.string.private_reading_stats_top_picks_for),
            icon = Icons.Outlined.Insights,
            onClick = onOpenInsights,
        )
    }
}

// ─── Feed defaults ───────────────────────────────────────────────────────────
@Composable
internal fun FeedDefaultsSection(prefs: AppPreferences, viewModel: SettingsViewModel, folders: List<String>) {
    val scheme = MaterialTheme.colorScheme
    SettingsGroup("Feed defaults") {
        SettingCaption("Applied to each new feed you add. Change any feed from its own settings later.")
        // Default folder: pick an existing folder, "None", or type a new one — one control, no dupes.
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Default folder", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = prefs.defaultFeedFolder.isBlank(),
                    onClick = { viewModel.setDefaultFeedFolder("") },
                    label = { Text(stringResource(R.string.none)) },
                )
                folders.forEach { f ->
                    FilterChip(
                        selected = prefs.defaultFeedFolder == f,
                        onClick = { viewModel.setDefaultFeedFolder(f) },
                        label = { Text(f) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            var custom by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    singleLine = true,
                    placeholder = { Text("New folder name") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (custom.isNotBlank()) { viewModel.setDefaultFeedFolder(custom.trim()); custom = "" } }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add folder")
                }
            }
        }
        SettingDivider()
        SettingSwitchRow(
            title = "Fetch full article on sync",
            subtitle = "New feeds pull the full article, not just the feed summary.",
            checked = prefs.defaultFeedFullText,
            onCheckedChange = { viewModel.setDefaultFeedFullText(it) },
        )
        SettingDivider()
        SettingSwitchRow(
            title = "Notify for new articles",
            subtitle = "New feeds post a notification when they publish.",
            checked = prefs.defaultFeedNotify,
            onCheckedChange = { viewModel.setDefaultFeedNotify(it) },
        )
    }
}

// ─── Startup ─────────────────────────────────────────────────────────────────
@Composable
internal fun StartupSection(prefs: AppPreferences, viewModel: SettingsViewModel) {
    SettingsGroup("Startup") {
        ChipsBlock(
            label = "Open on launch",
            options = listOf(
                "" to "Default", "Inbox" to "Inbox", "Library" to "Library",
                "ReadLater" to "Read Later", "Discover" to "Discover", "Feeds" to "Feeds",
            ),
            selected = prefs.startDestination,
            onSelect = { viewModel.setStartDestination(it) },
        )
        SettingDivider()
        ChipsBlock(
            label = "Inbox opens to",
            options = listOf(
                "" to "Default", "UNREAD" to "Unread", "STARRED" to "Starred",
                "SAVED" to "Saved", "ALL" to "All",
            ),
            selected = prefs.startFilter,
            onSelect = { viewModel.setStartFilter(it) },
        )
    }
}

// ─── Appearance ──────────────────────────────────────────────────────────────
@Composable
internal fun AppearanceSection(prefs: AppPreferences, viewModel: SettingsViewModel) {
    val scheme = MaterialTheme.colorScheme
    SettingsGroup("Appearance") {
        ChipsBlock(
            label = "Theme",
            options = ThemeMode.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
            selected = prefs.themeMode,
            onSelect = viewModel::setThemeMode,
        )
        SettingDivider()
        SettingSwitchRow(
            title = stringResource(R.string.dynamic_color),
            subtitle = stringResource(R.string.use_wallpaper_colors_android_12),
            checked = prefs.dynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
        )
        SettingDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.accent), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Text(stringResource(R.string.a_colour_theme_for_the_whole), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.cairn.reader.ui.theme.AppAccent.entries.forEach { a ->
                    val selected = prefs.appAccent == a.name
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(a.swatch)
                                .border(if (selected) 3.dp else 1.dp, if (selected) scheme.onSurface else scheme.outlineVariant, CircleShape)
                                .clickable { viewModel.setAppAccent(a.name) },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(a.label, style = MaterialTheme.typography.labelSmall, color = if (selected) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.custom_color), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Text(stringResource(R.string.pick_any_seed_and_the_app), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceVariant)
                            .border(if (prefs.appSeedColor == 0) 3.dp else 1.dp, if (prefs.appSeedColor == 0) scheme.onSurface else scheme.outlineVariant, CircleShape)
                            .clickable { viewModel.setAppSeedColor(0) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.none), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                listOf(0f, 25f, 45f, 90f, 135f, 165f, 190f, 215f, 250f, 285f, 320f, 345f).forEach { hue ->
                    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.85f))
                    val selected = prefs.appSeedColor == argb
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(argb))
                            .border(if (selected) 3.dp else 1.dp, if (selected) scheme.onSurface else scheme.outlineVariant, CircleShape)
                            .clickable { viewModel.setAppSeedColor(argb) },
                    )
                }
            }
        }
        SettingDivider()
        SettingSwitchRow(
            title = stringResource(R.string.pure_black_amoled),
            subtitle = stringResource(R.string.true_black_backgrounds_in_dark_mode),
            checked = prefs.trueBlack,
            onCheckedChange = viewModel::setTrueBlack,
        )
        SettingDivider()
        ChipsBlock(
            label = "Reading font",
            options = ReaderFont.entries.map { it to it.label },
            selected = prefs.readerFont,
            onSelect = viewModel::setReaderFont,
        )
        SettingDivider()
        ChipsBlock(
            label = "Reader theme",
            options = listOf(
                ReaderTheme.DEFAULT to "Default", ReaderTheme.PAPER to "Paper", ReaderTheme.SEPIA to "Sepia",
                ReaderTheme.GRAY to "Gray", ReaderTheme.NIGHT to "Night", ReaderTheme.BLACK to "Black",
            ),
            selected = prefs.readerTheme,
            onSelect = viewModel::setReaderTheme,
        )
        SettingDivider()
        ChipsBlock(
            label = "Text size",
            options = listOf(0.8f to "Small", 0.9f to "Cozy", 1.0f to "Default", 1.2f to "Large", 1.5f to "Larger", 2.0f to "Huge"),
            selected = prefs.readerFontScale,
            onSelect = viewModel::setReaderFontScale,
            caption = stringResource(R.string.you_can_also_pinch_to_size),
        )
    }
}

// ─── Reading & gestures ──────────────────────────────────────────────────────
@Composable
internal fun GesturesSection(prefs: AppPreferences, viewModel: SettingsViewModel) {
    SettingsGroup("Reading & gestures") {
        ChipsBlock(
            label = "List density",
            options = listOf(false to "Comfortable", true to "Compact"),
            selected = prefs.compactDensity,
            onSelect = viewModel::setCompactDensity,
        )
        SettingDivider()
        ChipsBlock("Swipe right · half", SwipeAction.entries.map { it to it.label }, prefs.swipeRightHalf, viewModel::setSwipeRightHalf, caption = stringResource(R.string.swipe_an_entry_a_little_for))
        SettingDivider()
        ChipsBlock("Swipe right · full", SwipeAction.entries.map { it to it.label }, prefs.swipeRightFull, viewModel::setSwipeRightFull)
        SettingDivider()
        ChipsBlock("Swipe left · half", SwipeAction.entries.map { it to it.label }, prefs.swipeLeftHalf, viewModel::setSwipeLeftHalf)
        SettingDivider()
        ChipsBlock("Swipe left · full", SwipeAction.entries.map { it to it.label }, prefs.swipeLeftFull, viewModel::setSwipeLeftFull)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.justify_reader_text), stringResource(R.string.straighten_the_right_edge_of_articles), prefs.readerJustify, viewModel::setReaderJustify)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.show_images), stringResource(R.string.off_gives_a_text_only_data), prefs.readerShowImages, viewModel::setReaderShowImages)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.immersive_scroll), stringResource(R.string.hide_the_bars_as_you_read), prefs.readerImmersive, viewModel::setReaderImmersive)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.full_screen), stringResource(R.string.use_the_entire_display_for_text), prefs.readerFullScreen, viewModel::setReaderFullScreen)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.tap_edges_to_turn_pages), stringResource(R.string.in_the_reader_tap_the_left), prefs.tapZonePaging, viewModel::setTapZonePaging)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.volume_keys_turn_pages), stringResource(R.string.while_reading_the_volume_keys_page), prefs.volumeKeyPaging, viewModel::setVolumeKeyPaging)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.open_as_web_page), stringResource(R.string.open_articles_as_the_original_web), prefs.openArticlesInWeb, viewModel::setOpenArticlesInWeb)
    }
}

// ─── List ────────────────────────────────────────────────────────────────────
@Composable
internal fun ListDensitySection(prefs: AppPreferences, viewModel: SettingsViewModel) {
    SettingsGroup("List") {
        SettingSwitchRow(stringResource(R.string.show_thumbnails), null, prefs.showThumbnail, viewModel::setShowThumbnail)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.show_excerpts), null, prefs.showExcerpt, viewModel::setShowExcerpt)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.show_reading_time), null, prefs.showReadingTime, viewModel::setShowReadingTime)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.sticky_date_headers), stringResource(R.string.group_the_inbox_under_today_yesterday), prefs.stickyDateHeaders, viewModel::setStickyDateHeaders)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.single_column_on_tablets), stringResource(R.string.keep_the_phone_layout_on_big), prefs.forceSingleColumn, viewModel::setForceSingleColumn)
    }
}

// ─── General (reading behavior, listening, automation, notifications) ─────────
@Composable
internal fun GeneralTogglesSection(prefs: AppPreferences, viewModel: SettingsViewModel) {
    SettingsGroup("General") {
        SettingSwitchRow(stringResource(R.string.mark_read_on_scroll), stringResource(R.string.in_the_inbox_articles_are_marked), prefs.markReadOnScroll, viewModel::setMarkReadOnScroll)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.listen_text_to_speech), stringResource(R.string.read_articles_aloud_off_hides_the), prefs.ttsEnabled, viewModel::setTtsEnabled)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.commute_mode_auto_offline), stringResource(R.string.after_each_background_sync_pull_the), prefs.autoOfflinePack, viewModel::setAutoOfflinePack)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.daily_brief_notification), stringResource(R.string.once_a_day_a_quiet_nudge), prefs.dailyBriefNotify, viewModel::setDailyBriefNotify)
    }
}

// ─── Bottom bar ──────────────────────────────────────────────────────────────
@Composable
internal fun BottomBarSection(prefs: AppPreferences, viewModel: SettingsViewModel) {
    val scheme = MaterialTheme.colorScheme
    SettingsGroup("Bottom bar") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.choose_which_destinations_appear_in_the), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            val labels = linkedMapOf(
                "Inbox" to "Inbox", "Library" to "Library", "Discover" to "Discover",
                "Starred" to "Starred", "ReadLater" to "Read Later", "Highlights" to "Highlights",
                "Feeds" to "Feeds", "Search" to "Search", "Trash" to "Trash",
                "Offline" to "Offline", "Settings" to "Settings",
            )
            val enabled = prefs.bottomTabs
            val atCap = enabled.size >= 6
            val orderedEnabled = (prefs.bottomTabsOrder.filter { it in enabled } +
                labels.keys.filter { it in enabled && it !in prefs.bottomTabsOrder })
            val disabled = labels.keys.filter { it !in enabled }
            Text(stringResource(R.string.in_the_bar_use_arrows_to), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            orderedEnabled.forEachIndexed { index, name ->
                val isLastOn = enabled.size <= 1
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
                    Text(labels[name] ?: name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.moveBottomTab(name, up = true) }, enabled = index > 0) {
                        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                    }
                    IconButton(onClick = { viewModel.moveBottomTab(name, up = false) }, enabled = index < orderedEnabled.size - 1) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                    }
                    Switch(checked = true, onCheckedChange = { viewModel.setBottomTab(name, false) }, enabled = !isLastOn)
                }
            }
            if (disabled.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.available), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                disabled.forEach { name ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(labels[name] ?: name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                        Switch(checked = false, onCheckedChange = { viewModel.setBottomTab(name, true) }, enabled = !atCap)
                    }
                }
            }
        }
    }
}

// ─── Filters ─────────────────────────────────────────────────────────────────
@Composable
internal fun FiltersSection(prefs: AppPreferences, viewModel: SettingsViewModel) {
    val scheme = MaterialTheme.colorScheme
    SettingsGroup("Filters") {
        SettingSwitchRow(stringResource(R.string.hide_duplicates), stringResource(R.string.collapse_the_same_story_across_feeds), prefs.hideDuplicates, viewModel::setHideDuplicates)
        SettingDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.muted_keywords), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Text(stringResource(R.string.hide_inbox_articles_whose_title_or), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (prefs.blockedKeywords.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    prefs.blockedKeywords.sorted().forEach { term ->
                        InputChip(
                            selected = true,
                            onClick = { viewModel.removeBlockedKeyword(term) },
                            label = { Text(term) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove_2), modifier = Modifier.height(16.dp)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            var newTerm by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTerm,
                    onValueChange = { newTerm = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.add_a_keyword_to_mute)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (newTerm.isNotBlank()) { viewModel.addBlockedKeyword(newTerm); newTerm = "" } }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.mute_keyword))
                }
            }
        }
    }
}

// ─── Backup & data (consolidated import / export) ─────────────────────────────
@Composable
internal fun ImportExportSection(prefs: AppPreferences, viewModel: SettingsViewModel) {
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (text != null) viewModel.importOpml(text) { added -> Toast.makeText(context, if (added > 0) "Imported $added feeds" else "No new feeds found", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { Toast.makeText(context, "Restoring…", Toast.LENGTH_SHORT).show(); viewModel.importFrom(uri) { s -> Toast.makeText(context, s, Toast.LENGTH_LONG).show() } }
    }
    val bookmarksLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { Toast.makeText(context, "Importing…", Toast.LENGTH_SHORT).show(); viewModel.importBookmarks(uri) { s -> Toast.makeText(context, s, Toast.LENGTH_LONG).show() } }
    }
    val archiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) { Toast.makeText(context, "Writing archive…", Toast.LENGTH_SHORT).show(); viewModel.exportArchive(uri) { ok -> Toast.makeText(context, if (ok) "Full archive saved" else "Couldn't write the archive", Toast.LENGTH_LONG).show() } }
    }
    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            viewModel.setBackupFolder(uri.toString())
            Toast.makeText(context, "Auto-backup on — a copy was saved", Toast.LENGTH_SHORT).show()
        }
    }
    val markdownVaultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { Toast.makeText(context, "Exporting Markdown…", Toast.LENGTH_SHORT).show(); viewModel.exportMarkdownVault(uri) { s -> Toast.makeText(context, s, Toast.LENGTH_LONG).show() } }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val name = displayNameFor(context, uri) ?: "Imported PDF"
            if (bytes != null && bytes.isNotEmpty()) viewModel.importPdf(name, bytes) { ok -> Toast.makeText(context, if (ok) "PDF added to your library" else "Couldn't import that PDF", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
        }
    }
    fun shareText(mime: String, title: String, subject: String, body: String, chooser: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_TITLE, title); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { context.startActivity(Intent.createChooser(send, chooser)) }
    }

    // BACKUP & RESTORE
    SettingsGroup("Backup & restore") {
        val scheme = MaterialTheme.colorScheme
        SettingActionRow(
            title = stringResource(R.string.automatic_backup),
            subtitle = if (prefs.backupFolderUri == null) "Off — pick a folder for scheduled backups"
                else "On — ${if (prefs.backupFrequencyHours >= 168) "weekly" else "daily"} to your chosen folder",
            icon = Icons.Outlined.CloudSync,
            onClick = { backupFolderLauncher.launch(null) },
        )
        if (prefs.backupFolderUri != null) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                LabeledChips(
                    label = stringResource(R.string.backup_frequency),
                    options = listOf(0 to stringResource(R.string.off), 24 to stringResource(R.string.daily), 168 to stringResource(R.string.weekly)),
                    selected = when { prefs.backupFrequencyHours <= 0 -> 0; prefs.backupFrequencyHours in 1..47 -> 24; else -> 168 },
                    onSelect = { h -> if (h == 0) viewModel.disableBackup() else viewModel.setBackupFrequency(h) },
                )
            }
            SettingSwitchRow(stringResource(R.string.include_offline_copies), stringResource(R.string.scheduled_backups_write_a_full_zip), prefs.backupIncludeOffline, { viewModel.setBackupIncludeOffline(it) })
        }
        SettingDivider()
        SettingActionRow(stringResource(R.string.back_up_data), stringResource(R.string.a_complete_json_backup_feeds_and), Icons.Outlined.CloudUpload, {
            viewModel.exportBackup { json -> shareText("application/json", "cairn-backup.json", "Cairn backup", json, "Back up Cairn") }
        })
        SettingDivider()
        SettingActionRow(stringResource(R.string.restore), "From a .json or .zip backup", Icons.Outlined.CloudDownload, { restoreLauncher.launch(arrayOf("*/*")) })
        SettingDivider()
        SettingActionRow(stringResource(R.string.full_archive_zip), stringResource(R.string.everything_above_plus_every_offline_article), Icons.Outlined.Inventory2, {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            archiveLauncher.launch("cairn-archive-$stamp.zip")
        })
        SettingDivider()
        SettingActionRow(stringResource(R.string.transfer_to_another_device), stringResource(R.string.move_your_whole_library_to_a), Icons.Outlined.Devices, {
            Toast.makeText(context, "Preparing transfer…", Toast.LENGTH_SHORT).show()
            viewModel.transferToDevice { uri ->
                if (uri == null) { Toast.makeText(context, "Couldn't prepare the transfer", Toast.LENGTH_LONG).show(); return@transferToDevice }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "Cairn library transfer"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(send, "Send to another device")) }
            }
        })
        SettingDivider()
        Column(Modifier.padding(16.dp)) {
            WebDavBackupSection(
                savedUrl = prefs.webdavUrl.orEmpty(),
                savedUser = prefs.webdavUser.orEmpty(),
                savedPass = prefs.webdavPass.orEmpty(),
                viewModel = viewModel,
            )
        }
    }

    // IMPORT
    SettingsGroup("Import") {
        SettingActionRow(stringResource(R.string.import_opml), stringResource(R.string.bring_subscriptions_in_from_inoreader_feedly), Icons.Outlined.RssFeed, { importLauncher.launch(arrayOf("*/*")) })
        SettingDivider()
        SettingActionRow(stringResource(R.string.import_reading_list), stringResource(R.string.bring_your_saved_articles_in_from), Icons.Outlined.CloudDownload, { bookmarksLauncher.launch(arrayOf("text/html", "text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "*/*")) })
        SettingDivider()
        SettingActionRow(stringResource(R.string.import_pdf), stringResource(R.string.add_a_pdf_to_your_library), Icons.Outlined.PictureAsPdf, { pdfLauncher.launch(arrayOf("application/pdf")) })
    }

    // EXPORT
    SettingsGroup("Export") {
        SettingActionRow(stringResource(R.string.export_opml), "Your subscriptions as an OPML file", Icons.Outlined.RssFeed, {
            viewModel.exportOpml { xml -> shareText("text/xml", "cairn-subscriptions.opml", "Cairn subscriptions", xml, "Export OPML") }
        })
        SettingDivider()
        SettingActionRow(stringResource(R.string.export_to_markdown_obsidian), stringResource(R.string.write_your_whole_library_as_plain), Icons.Outlined.Description, { markdownVaultLauncher.launch(null) })
        SettingDivider()
        SettingActionRow(stringResource(R.string.send_library_to_kindle_epub), stringResource(R.string.bundle_your_whole_library_into_a), Icons.Outlined.MenuBook, {
            Toast.makeText(context, "Building EPUB…", Toast.LENGTH_SHORT).show()
            viewModel.exportLibraryEpub { file ->
                if (file == null) Toast.makeText(context, "Nothing to export — save some articles first.", Toast.LENGTH_LONG).show()
                else runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply { type = "application/epub+zip"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "Cairn Library"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    context.startActivity(Intent.createChooser(send, "Send library to Kindle"))
                }
            }
        })
        SettingDivider()
        SettingActionRow(stringResource(R.string.export_csv), stringResource(R.string.a_spreadsheet_of_every_item_title), Icons.Outlined.GridOn, {
            viewModel.exportCsv { csv -> shareText("text/csv", "cairn-items.csv", "Cairn items (CSV)", csv, "Export CSV") }
        })
    }

    // DIAGNOSTICS
    SettingsGroup("Diagnostics") {
        SettingActionRow(stringResource(R.string.share_diagnostics_log), stringResource(R.string.a_local_on_device_log_of), Icons.Outlined.Description, {
            viewModel.diagnostics { log ->
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Cairn diagnostics log"); putExtra(Intent.EXTRA_TEXT, log) }
                runCatching { context.startActivity(Intent.createChooser(send, "Share diagnostics log")) }
            }
        })
    }
}

// ─── Privacy & about ─────────────────────────────────────────────────────────
@Composable
internal fun PrivacyAboutSection(prefs: AppPreferences, viewModel: SettingsViewModel) {
    val scheme = MaterialTheme.colorScheme
    SettingsGroup("Privacy") {
        SettingSwitchRow(stringResource(R.string.strip_tracking_from_links), stringResource(R.string.remove_utm_fbclid_gclid_and_similar), prefs.stripTrackingParams, viewModel::setStripTrackingParams)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.sanitize_article_content), stringResource(R.string.strip_tracking_pixels_beacons_and_third), prefs.sanitizeArticles, viewModel::setSanitizeArticles)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.check_saved_links_for_rot), stringResource(R.string.off_by_default_when_on_cairn), prefs.linkCheckEnabled, viewModel::setLinkCheckEnabled)
        SettingDivider()
        SettingSwitchRow(stringResource(R.string.online_dictionary_lookups), stringResource(R.string.online_dictionary_lookups_desc), prefs.dictionaryOnline, viewModel::setDictionaryOnline)
        SettingCaption(stringResource(R.string.no_account_no_trackers_no_ads))
    }
    SettingsGroup("About") {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.cairn_3_43_0), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.one_reader_for_everything_you_read), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

/** A chip picker rendered inside a settings card, with an aligned label and optional caption. */
@Composable
private fun <T> ChipsBlock(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    caption: String? = null,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        LabeledChips(label = label, options = options, selected = selected, onSelect = onSelect)
        if (caption != null) {
            Spacer(Modifier.height(6.dp))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
