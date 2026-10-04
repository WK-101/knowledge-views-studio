package app.parley.common

import kotlinx.serialization.json.Json

/**
 * The JSON settings Parley's stored blobs use, by purpose. All read what a newer version wrote (unknown keys are
 * skipped). Changing one changes what is written, so a new need gets a new named entry rather than an edit.
 */
object Codecs {
    /** Settings and lists kept in preferences: only what differs from the defaults is written. */
    val stored = Json { ignoreUnknownKeys = true }

    /** [stored], and a value this version doesn't know (an enum added later) reads as the field's default. */
    val tolerant = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Every field written, defaults included: formats that other versions or tools read field by field. */
    val full = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** [full] and [tolerant] together. */
    val fullTolerant = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    /** [stored], with empty (null) fields left out too: per-call facts, kept small. */
    val compact = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Files people may open and read: [stored], indented. */
    val pretty = Json { ignoreUnknownKeys = true; prettyPrint = true }
}
