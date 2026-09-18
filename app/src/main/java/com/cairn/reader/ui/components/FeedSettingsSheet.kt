@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.components

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cairn.reader.data.db.SourceEntity

/** Per-feed settings: which folder it lives in, whether to fetch full text on sync,
 *  notifications, and removing the feed. */
@Composable
fun FeedSettingsSheet(
    source: SourceEntity,
    folders: List<String>,
    onFolder: (String?) -> Unit,
    onFullText: (Boolean) -> Unit,
    onNotify: (Boolean) -> Unit,
    onMuted: (Boolean) -> Unit = {},
    onSetPaused: (Boolean) -> Unit = {},
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit = {},
    onOpenSite: (() -> Unit)? = null,
    onPodcast: (Boolean) -> Unit = {},
    onFeedUrl: (String) -> Unit = {},
    onOpenIn: (String) -> Unit = {},
    onMaxItems: (Int?) -> Unit = {},
    onVerify: (() -> Unit)? = null,
    onAcquisition: (String) -> Unit = {},
    onDepth: (String) -> Unit = {},
    onOfflineTier: (String) -> Unit = {},
    onBackfill: () -> Unit = {},
    onCancelBackfill: () -> Unit = {},
) {
    var title by remember(source.id) { mutableStateOf(source.title) }
    var folder by remember(source.id) { mutableStateOf(source.folder.orEmpty()) }
    var feedUrl by remember(source.id) { mutableStateOf(source.feedUrl) }
    var fullText by remember(source.id) { mutableStateOf(source.fullTextByDefault) }
    var notify by remember(source.id) { mutableStateOf(source.notify) }
    var muted by remember(source.id) { mutableStateOf(source.muted) }
    var paused by remember(source.id) { mutableStateOf(source.syncPaused) }
    var podcast by remember(source.id) { mutableStateOf(source.isPodcast) }
    var openIn by remember(source.id) { mutableStateOf(source.openIn) }
    var maxItems by remember(source.id) { mutableStateOf(source.maxItems) }
    var acquisition by remember(source.id) { mutableStateOf(source.acquisitionMode) }
    var depth by remember(source.id) { mutableStateOf(source.depthMode) }
    var offlineTier by remember(source.id) { mutableStateOf(source.offlineTier) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        com.cairn.reader.ui.KeepImmersiveWhileOpen()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.name), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; onRename(it) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // ---- Feed link (editable) -------------------------------------------------------
            Text(stringResource(R.string.feed_link), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text(stringResource(R.string.the_rss_atom_url_this_feed)) },
                modifier = Modifier.fillMaxWidth(),
            )
            val linkChanged = feedUrl.trim().isNotBlank() && feedUrl.trim() != source.feedUrl
            if (linkChanged) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { feedUrl = source.feedUrl }) { Text(stringResource(R.string.reset)) }
                    TextButton(onClick = { onFeedUrl(feedUrl.trim()) }) { Text(stringResource(R.string.update_link)) }
                }
            }

            // ---- Freshness verification: when it was last checked, and the newest post it holds ----
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val checked = source.lastSyncedAt?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it).toString() }
                    ?: stringResource(R.string.never)
                val latest = source.latestItemAt?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it).toString() }
                    ?: stringResource(R.string.unknown)
                Text(
                    stringResource(R.string.feed_checked_latest, checked, latest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onVerify != null) TextButton(onClick = onVerify) { Text(stringResource(R.string.verify_now)) }
            }
            Spacer(Modifier.height(16.dp))

            // ---- Acquisition: where this source's content comes from --------------------------
            Text(stringResource(R.string.get_content_via), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = acquisition == "FEED", onClick = { acquisition = "FEED"; onAcquisition("FEED") }, label = { Text(stringResource(R.string.acq_feed)) })
                FilterChip(selected = acquisition == "EXTRACT", onClick = { acquisition = "EXTRACT"; onAcquisition("EXTRACT") }, label = { Text(stringResource(R.string.acq_extract)) })
                FilterChip(selected = acquisition == "BOTH", onClick = { acquisition = "BOTH"; onAcquisition("BOTH") }, label = { Text(stringResource(R.string.acq_both)) })
            }
            Text(
                stringResource(R.string.acq_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(16.dp))

            // ---- Depth: recent window vs the whole published archive --------------------------
            Text(stringResource(R.string.content_depth), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(depth == "LIVE", { depth = "LIVE"; onDepth("LIVE") }, label = { Text(stringResource(R.string.depth_recent)) })
                FilterChip(depth == "ARCHIVE", { depth = "ARCHIVE"; onDepth("ARCHIVE") }, label = { Text(stringResource(R.string.depth_full_archive)) })
                FilterChip(depth == "BOTH", { depth = "BOTH"; onDepth("BOTH") }, label = { Text(stringResource(R.string.depth_both)) })
            }
            // ---- Offline storage tier: index-only vs full mirror (only meaningful once archiving) ----
            if (depth != "LIVE") {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.offline_tier_label), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(offlineTier == "INDEX", { offlineTier = "INDEX"; onOfflineTier("INDEX") }, label = { Text(stringResource(R.string.tier_index)) })
                    FilterChip(offlineTier == "MIRROR", { offlineTier = "MIRROR"; onOfflineTier("MIRROR") }, label = { Text(stringResource(R.string.tier_mirror)) })
                }
                Text(
                    stringResource(if (offlineTier == "MIRROR") R.string.tier_mirror_hint else R.string.tier_index_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            // Backfill action + progress. Reflects the state captured when this sheet was opened.
            when (source.backfillState) {
                "RUNNING", "PENDING" -> {
                    val discovered = source.backfillDiscovered
                    val done = source.backfillDone
                    if (source.backfillState == "RUNNING" && discovered > 0) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (done.toFloat() / discovered).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (source.backfillState == "PENDING") stringResource(R.string.backfill_queued)
                            else stringResource(R.string.backfill_running, done, discovered),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancelBackfill) { Text(stringResource(R.string.cancel)) }
                    }
                }
                else -> {
                    androidx.compose.material3.OutlinedButton(onClick = onBackfill) {
                        Text(
                            if (source.backfillState == "DONE") stringResource(R.string.rebuild_full_archive)
                            else stringResource(R.string.download_full_archive),
                        )
                    }
                    Text(
                        stringResource(R.string.backfill_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.folder), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it; onFolder(it) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.no_folder)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (folders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    folders.forEach { f ->
                        FilterChip(selected = folder.equals(f, ignoreCase = true), onClick = { folder = f; onFolder(f) }, label = { Text(f) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.full_text_on_sync), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.fetch_the_whole_article_when_new), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = fullText, onCheckedChange = { fullText = it; onFullText(it) })
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.notifications), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.notify_when_this_feed_has_new), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = notify, onCheckedChange = { notify = it; onNotify(it) })
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mute_in_inbox), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.keep_syncing_but_hide_from_the), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = muted, onCheckedChange = { muted = it; onMuted(it) })
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pause_updates), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.keep_this_feed_but_stop_syncing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = paused, onCheckedChange = { paused = it; onSetPaused(it) })
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.this_is_a_podcast), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.treat_new_items_as_audio_episodes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = podcast, onCheckedChange = { podcast = it; onPodcast(it) })
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.keep_at_most), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data class KeepOpt(val value: Int?, val label: String)
                listOf(
                    KeepOpt(null, "Default"),
                    KeepOpt(0, "All"),
                    KeepOpt(25, "25"),
                    KeepOpt(50, "50"),
                    KeepOpt(100, "100"),
                    KeepOpt(200, "200"),
                    KeepOpt(500, "500"),
                ).forEach { opt ->
                    FilterChip(selected = maxItems == opt.value, onClick = { maxItems = opt.value; onMaxItems(opt.value) }, label = { Text(opt.label) })
                }
            }
            Text(stringResource(R.string.older_un_engaged_items_beyond_this),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.open_articles_in), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = openIn == "READER", onClick = { openIn = "READER"; onOpenIn("READER") }, label = { Text(stringResource(R.string.reader)) })
                FilterChip(selected = openIn == "BROWSER", onClick = { openIn = "BROWSER"; onOpenIn("BROWSER") }, label = { Text(stringResource(R.string.in_app_browser)) })
                FilterChip(selected = openIn == "EXTERNAL", onClick = { openIn = "EXTERNAL"; onOpenIn("EXTERNAL") }, label = { Text(stringResource(R.string.external)) })
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onRemove(); onDismiss() }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.height(18.dp))
                    Text(stringResource(R.string.remove))
                }
                if (onOpenSite != null && !source.siteUrl.isNullOrBlank()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onOpenSite(); onDismiss() }) { Text(stringResource(R.string.open_site)) }
                }
            }
        }
    }
}
