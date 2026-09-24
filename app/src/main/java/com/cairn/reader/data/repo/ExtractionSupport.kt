package com.cairn.reader.data.repo

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Small, dependency-free helpers shared between [FeedRepository] (capture/sync) and
 * [ArticleExtractionService] (on-demand readability). Kept as module-internal top-level
 * functions so the two collaborators reference one definition instead of duplicating it.
 */

/**
 * Full-text index ceiling. We index the WHOLE plain-text body (not the old ~600-word window) so a
 * phrase buried deep in a long article is findable offline. This value is only a safety ceiling for
 * pathological pages (~30k words); real articles index in full.
 */
internal const val FTS_BODY_CHARS = 200_000

/** Bare host of a URL, or the URL itself when it isn't a parseable http(s) URL. */
internal fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url
