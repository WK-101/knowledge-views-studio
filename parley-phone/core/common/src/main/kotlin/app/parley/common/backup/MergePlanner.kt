package app.parley.common.backup

import app.parley.common.Duplicates
import app.parley.common.PhoneNumbers
import app.parley.common.TextSearch
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime

enum class RestoreMode {
    /** Match backup contacts to existing ones; add what's missing; never delete or overwrite. */
    MERGE,
    /** Insert every backup contact as new, no matching. */
    ADD_ALL,
    /** Delete existing contacts (after an automatic safety backup) and insert the backup. */
    REPLACE,
}

enum class MatchedBy { SOURCE_ID, KEY, FINGERPRINT }

sealed interface MergeAction {
    val backup: ContactRecord

    data class New(override val backup: ContactRecord) : MergeAction

    data class Identical(override val backup: ContactRecord, val existing: ContactRecord, val matchedBy: MatchedBy) : MergeAction

    /** Add [missingRows] to [existing] (e.g. to its first raw contact). Nothing is removed. */
    data class Enrich(
        override val backup: ContactRecord,
        val existing: ContactRecord,
        val matchedBy: MatchedBy,
        val missingRows: List<DataRow>,
    ) : MergeAction

    /**
     * The match is probable but the records disagree on something that can't simply be added
     * (display name, structured name, photo). The UI asks the user; [missingRows] are the rows an
     * "enrich anyway" would add.
     */
    data class Conflict(
        override val backup: ContactRecord,
        val existing: ContactRecord,
        val matchedBy: MatchedBy,
        val reasons: List<String>,
        val missingRows: List<DataRow>,
    ) : MergeAction
}

data class MergeSummary(
    val new: Int,
    val identical: Int,
    val enrich: Int,
    val conflict: Int,
    val rowsToAdd: Int,
    val toDelete: Int,
)

data class MergePlan(
    val mode: RestoreMode,
    val actions: List<MergeAction>,
    /** Existing contacts a REPLACE restore removes (empty for other modes). */
    val toDelete: List<ContactRecord>,
) {
    val summary: MergeSummary
        get() = MergeSummary(
            new = actions.count { it is MergeAction.New },
            identical = actions.count { it is MergeAction.Identical },
            enrich = actions.count { it is MergeAction.Enrich },
            conflict = actions.count { it is MergeAction.Conflict },
            rowsToAdd = actions.sumOf {
                when (it) {
                    is MergeAction.Enrich -> it.missingRows.size
                    is MergeAction.New -> it.backup.raws.sumOf { r -> r.rows.size }
                    else -> 0
                }
            },
            toDelete = toDelete.size,
        )
}

/**
 * Pure restore planning. Matching order for each backup contact:
 *  1. same accountType + accountName + sourceId on any raw contact;
 *  2. same [ContactRecord.key] (lookup key);
 *  3. fingerprint: phone [PhoneNumbers.matchKey] (≥ 7 digits), lower-cased e-mail, or
 *     [Duplicates.nameKey]; the candidate sharing the most fingerprint parts wins.
 *
 * A row is "missing" when no existing row has the same [DataRow.canonicalKey]; additionally phones
 * compare by matchKey, e-mails case-insensitively and photos by blob content, so re-formatted copies
 * aren't added twice. Name and photo are single-valued: a differing one is a conflict, not an addition.
 */
object MergePlanner {
    private val SINGLE_VALUED = setOf(Mime.NAME, Mime.PHOTO)

    fun plan(existing: List<ContactRecord>, backup: List<ContactRecord>, mode: RestoreMode = RestoreMode.MERGE): MergePlan = when (mode) {
        RestoreMode.ADD_ALL -> MergePlan(mode, backup.map { MergeAction.New(it) }, emptyList())
        RestoreMode.REPLACE -> MergePlan(mode, backup.map { MergeAction.New(it) }, existing)
        RestoreMode.MERGE -> MergePlan(mode, merge(existing, backup), emptyList())
    }

    private fun merge(existing: List<ContactRecord>, backup: List<ContactRecord>): List<MergeAction> {
        val bySource = HashMap<String, Int>()
        val byKey = HashMap<String, Int>()
        val byPrint = HashMap<String, MutableList<Int>>()
        existing.forEachIndexed { i, c ->
            sourceKeys(c).forEach { bySource.putIfAbsent(it, i) }
            if (c.key.isNotBlank()) byKey.putIfAbsent(c.key, i)
            fingerprint(c).forEach { byPrint.getOrPut(it) { ArrayList() }.add(i) }
        }
        // Rows already planned for each existing contact, so two backup copies don't add a row twice.
        val planned = HashMap<Int, MutableSet<String>>()

        return backup.map { b ->
            var how = MatchedBy.SOURCE_ID
            var idx = sourceKeys(b).firstNotNullOfOrNull { bySource[it] }
            if (idx == null) { how = MatchedBy.KEY; idx = byKey[b.key] }
            if (idx == null) {
                how = MatchedBy.FINGERPRINT
                val votes = HashMap<Int, Int>()
                fingerprint(b).forEach { fp -> byPrint[fp]?.distinct()?.forEach { votes[it] = (votes[it] ?: 0) + 1 } }
                idx = votes.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
            }
            if (idx == null) return@map MergeAction.New(b)
            val e = existing[idx]
            val have = planned.getOrPut(idx) { rowIdentities(e).toMutableSet() }
            val existingMimes = e.raws.flatMap { r -> r.rows.map { it.mimeType } }.toSet()
            val reasons = ArrayList<String>()
            if (norm(b.displayName) != norm(e.displayName) && b.displayName.isNotBlank() && e.displayName.isNotBlank()) {
                reasons += "Different name: \"${e.displayName}\" vs \"${b.displayName}\""
            }
            val missing = ArrayList<DataRow>()
            for (row in b.raws.flatMap { it.rows }) {
                val ids = identities(row)
                if (ids.any { it in have }) continue
                if (row.mimeType in SINGLE_VALUED && row.mimeType in existingMimes) {
                    if (row.mimeType == Mime.PHOTO) reasons += "Different photo"
                    else if (reasons.none { it.startsWith("Different name") }) reasons += "Different name details"
                    continue
                }
                missing += row
                have.addAll(ids)
            }
            when {
                reasons.isNotEmpty() -> MergeAction.Conflict(b, e, how, reasons.distinct(), missing)
                missing.isEmpty() -> MergeAction.Identical(b, e, how)
                else -> MergeAction.Enrich(b, e, how, missing)
            }
        }
    }

    private fun norm(s: String) = TextSearch.normalize(s).trim().replace(Regex("\\s+"), " ")

    private fun sourceKeys(c: ContactRecord): List<String> =
        c.raws.filter { !it.sourceId.isNullOrBlank() }.map { "${it.accountType}\u0000${it.accountName}\u0000${it.sourceId}" }

    /** Fingerprint parts: phone match keys, e-mails, name key. */
    fun fingerprint(c: ContactRecord): Set<String> = buildSet {
        Duplicates.nameKey(c.displayName)?.let { add("n:$it") }
        for (r in c.raws) for (row in r.rows) {
            when (row.mimeType) {
                Mime.PHONE -> PhoneNumbers.matchKey(row[Col1]).takeIf { it.length >= 7 }?.let { add("p:$it") }
                Mime.EMAIL -> row[Col1]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { add("e:$it") }
            }
        }
    }

    private const val Col1 = "data1"

    private fun rowIdentities(c: ContactRecord): Set<String> = c.raws.flatMap { r -> r.rows.flatMap { identities(it) } }.toSet()

    /** Keys under which a row counts as already present. */
    internal fun identities(row: DataRow): List<String> = buildList {
        if (row.mimeType == Mime.PHOTO) {
            add("photo:" + (row.blob?.let(RecordJson::sha256Hex) ?: row.canonicalKey))
            return@buildList
        }
        add(row.canonicalKey)
        when (row.mimeType) {
            Mime.PHONE -> PhoneNumbers.matchKey(row[Col1]).takeIf { it.length >= 7 }?.let { add("phone:$it") }
            Mime.EMAIL -> row[Col1]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { add("email:$it") }
        }
    }
}
