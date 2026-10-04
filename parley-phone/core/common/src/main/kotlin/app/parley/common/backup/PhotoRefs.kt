package app.parley.common.backup

import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow

/**
 * Contacts without their photo bytes, for restore planning: each photo row keeps its SHA-256 in a pseudo-column
 * instead, so two address books (the phone's and the backup's) are compared by hash without holding a photo of
 * either. [filled] puts the bytes back for the few records a restore actually writes.
 */
object PhotoRefs {
    /** Pseudo-column holding a photo's SHA-256 while its bytes are left out. Never written to the provider. */
    const val HASH = "parley_photo_sha256"

    /** [record] with every photo replaced by its hash. */
    fun light(record: ContactRecord): ContactRecord =
        if (record.raws.none { r -> r.rows.any { it.blob != null } }) record
        else record.copy(raws = record.raws.map { raw -> raw.copy(rows = raw.rows.map(::light)) })

    fun light(row: DataRow): DataRow {
        val blob = row.blob ?: return row
        return row.copy(values = row.values + (HASH to RecordJson.sha256Hex(blob)), blob = null)
    }

    /** The photo's hash, whether the row holds its bytes or only the hash; null for a row without a photo. */
    fun hashOf(row: DataRow): String? = row.values[HASH] ?: row.blob?.let(RecordJson::sha256Hex)

    /**
     * [rows] with each photo's bytes back from [photo]. A hash [photo] can't resolve loses its row: the contact is
     * written without that photo rather than stopping a restore part-way (planning already reported it, see [resolvable]).
     */
    fun filled(rows: List<DataRow>, photo: (String) -> ByteArray?): List<DataRow> = rows.mapNotNull { row ->
        val hash = row.values[HASH] ?: return@mapNotNull row
        val bytes = photo(hash) ?: return@mapNotNull null
        row.copy(values = row.values - HASH, blob = bytes)
    }

    fun filled(record: ContactRecord, photo: (String) -> ByteArray?): ContactRecord =
        if (record.raws.none { r -> r.rows.any { HASH in it.values } }) record
        else record.copy(raws = record.raws.map { raw -> raw.copy(rows = filled(raw.rows, photo)) })

    /**
     * [record] without the photo rows whose hash [has] can't find, and how many were dropped. Restore planning runs
     * every backup record through this, so a photo missing from the archive is known (and reported) before anything
     * is deleted or written.
     */
    fun resolvable(record: ContactRecord, has: (String) -> Boolean): Pair<ContactRecord, Int> {
        var dropped = 0
        if (record.raws.none { r -> r.rows.any { HASH in it.values } }) return record to 0
        val raws = record.raws.map { raw ->
            raw.copy(rows = raw.rows.filter { row -> (row.values[HASH]?.let(has) ?: true).also { if (!it) dropped++ } })
        }
        return (if (dropped == 0) record else record.copy(raws = raws)) to dropped
    }
}
