package com.cairn.reader.ui.model

import com.cairn.reader.data.db.SourceEntity

/**
 * Immutable presentation snapshot of a feed source for the feed-management surfaces (the feeds
 * screen, the settings feed list, and the per-feed settings sheet). It carries only the fields the
 * UI reads, so the Compose layer never touches the mutable Room [SourceEntity] directly and a
 * persistence-schema change no longer ripples into screens. Field names deliberately mirror the
 * entity so the display code reads the same either way; edits still flow back through the
 * ViewModel's typed setters, never by persisting a mutated copy of this model.
 */
data class SourceUi(
    val id: String,
    val title: String,
    val feedUrl: String,
    val siteUrl: String?,
    val folder: String?,
    val openIn: String,
    val fullTextByDefault: Boolean,
    val notify: Boolean,
    val isPodcast: Boolean,
    val muted: Boolean,
    val syncPaused: Boolean,
    val consecutiveErrors: Int,
    val lastSyncedAt: Long?,
    val latestItemAt: Long?,
    val maxItems: Int?,
    val acquisitionMode: String,
    val depthMode: String,
    val offlineTier: String,
    val backfillState: String,
    val backfillDiscovered: Int,
    val backfillDone: Int,
)

/** The one mapping seam from the persistence entity to the UI read model. */
fun SourceEntity.toUi(): SourceUi = SourceUi(
    id = id,
    title = title,
    feedUrl = feedUrl,
    siteUrl = siteUrl,
    folder = folder,
    openIn = openIn,
    fullTextByDefault = fullTextByDefault,
    notify = notify,
    isPodcast = isPodcast,
    muted = muted,
    syncPaused = syncPaused,
    consecutiveErrors = consecutiveErrors,
    lastSyncedAt = lastSyncedAt,
    latestItemAt = latestItemAt,
    maxItems = maxItems,
    acquisitionMode = acquisitionMode,
    depthMode = depthMode,
    offlineTier = offlineTier,
    backfillState = backfillState,
    backfillDiscovered = backfillDiscovered,
    backfillDone = backfillDone,
)
