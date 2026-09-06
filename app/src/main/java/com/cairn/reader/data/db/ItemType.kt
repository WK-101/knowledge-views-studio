package com.cairn.reader.data.db

/**
 * The kind of a saved item. Persisted as [name] in the `items.type` column (mapped at the
 * repository boundary); the display labels live here so every surface renders them identically
 * instead of repeating the same `when(type)` map.
 */
enum class ItemType(val label: String, val labelSingular: String) {
    ARTICLE("Articles", "Article"),
    LINK("Links", "Link"),
    VIDEO("Videos", "Video"),
    AUDIO("Podcasts", "Podcast"),
    IMAGE("Images", "Image"),
    PDF("PDFs", "PDF");

    companion object {
        /** The [ItemType] for a stored raw string, or null when it is not one of the known kinds. */
        fun fromRaw(raw: String?): ItemType? = raw?.let { r -> entries.firstOrNull { it.name == r } }

        private fun titleCase(raw: String) = raw.lowercase().replaceFirstChar(Char::uppercase)

        /** Plural display label for a raw type, title-casing anything unrecognized. */
        fun label(raw: String): String = fromRaw(raw)?.label ?: titleCase(raw)

        /** Singular display label for a raw type, title-casing anything unrecognized. */
        fun labelSingular(raw: String): String = fromRaw(raw)?.labelSingular ?: titleCase(raw)
    }
}
