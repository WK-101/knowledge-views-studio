package app.parley.common.record

/**
 * Keeps the provider's "default" flags consistent when rows from several raw contacts are combined (merging copies
 * into one account, restoring, moving): at most one IS_PRIMARY row per mimetype per raw contact, and at most one
 * IS_SUPER_PRIMARY row per mimetype across the whole contact. A super-primary row is always primary too, as the
 * provider and AOSP's "Set default" do.
 *
 * Earlier rows win, and a super-primary row wins over a merely primary one of its raw contact.
 */
object PrimaryFlags {
    fun normalize(raws: List<List<DataRow>>): List<List<DataRow>> {
        val superTaken = HashSet<String>()
        return raws.map { rows ->
            // Super-primary first: pick the contact-wide default per kind.
            val superIndex = HashMap<String, Int>()
            rows.forEachIndexed { i, r -> if (r.isSuperPrimary && r.mimeType !in superTaken && r.mimeType !in superIndex) superIndex[r.mimeType] = i }
            superTaken += superIndex.keys
            val primaryIndex = HashMap<String, Int>(superIndex)
            rows.forEachIndexed { i, r -> if ((r.isPrimary || r.isSuperPrimary) && r.mimeType !in primaryIndex) primaryIndex[r.mimeType] = i }
            rows.mapIndexed { i, r ->
                val primary = primaryIndex[r.mimeType] == i
                val sup = superIndex[r.mimeType] == i
                if (r.isPrimary == primary && r.isSuperPrimary == sup) r else r.copy(isPrimary = primary, isSuperPrimary = sup)
            }
        }
    }

    fun normalizeOne(rows: List<DataRow>): List<DataRow> = normalize(listOf(rows)).single()
}
