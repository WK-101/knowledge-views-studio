package app.parley.common.people

/**
 * Plans the provider writes for the editable rows of some data kinds (I1 handles: Im and SIP) so that an edit
 * touches only what changed and never a row of another kind: rows of kinds outside [plan]'s `kinds` are ignored
 * even when the edited list doesn't mention them, and rows the provider marks read-only are never changed.
 */
object RowEdits {
    data class Row(val id: Long?, val mime: String, val values: Map<String, String?>) {
        val isBlank: Boolean get() = values["data1"].isNullOrBlank()
    }

    sealed interface Op {
        val mime: String
        data class Insert(override val mime: String, val values: Map<String, String?>) : Op
        data class Update(val id: Long, override val mime: String, val values: Map<String, String?>) : Op
        data class Delete(val id: Long, override val mime: String) : Op
    }

    /**
     * [before]: the rows as loaded (any kinds); [after]: the edited rows of [kinds] (id = the row it came from, null
     * for new ones). Returns inserts, updates and deletes for [kinds] only.
     */
    fun plan(before: List<Row>, after: List<Row>, kinds: Set<String>, locked: Set<Long> = emptySet()): List<Op> {
        val mine = before.filter { it.mime in kinds && it.id != null }
        val byId = mine.associateBy { it.id!! }
        val kept = after.mapNotNull { it.id }.toSet()
        val ops = ArrayList<Op>()
        mine.filter { it.id !in kept && it.id !in locked }.forEach { ops += Op.Delete(it.id!!, it.mime) }
        for (row in after) {
            if (row.mime !in kinds) continue
            val id = row.id
            val prev = id?.let { byId[it] }
            when {
                id != null && prev == null -> Unit // not one of this contact's rows of these kinds: never touched
                id != null && id in locked -> Unit
                id != null && row.isBlank -> ops += Op.Delete(id, prev!!.mime)
                id != null && prev!!.mime != row.mime -> {
                    // The kind changed (e.g. an XMPP handle became a SIP address): replace the row.
                    ops += Op.Delete(id, prev.mime)
                    ops += Op.Insert(row.mime, trimmed(row.values))
                }
                id != null && same(prev!!.values, row.values) -> Unit
                id != null -> ops += Op.Update(id, row.mime, trimmed(row.values))
                !row.isBlank -> ops += Op.Insert(row.mime, trimmed(row.values))
            }
        }
        return ops
    }

    private fun trimmed(v: Map<String, String?>) = v.mapValues { it.value?.trim()?.ifEmpty { null } }

    /** Same content, ignoring surrounding spaces and empty-versus-missing columns. */
    fun same(a: Map<String, String?>, b: Map<String, String?>): Boolean {
        val keys = a.keys + b.keys
        return keys.all { k -> a[k]?.trim().orEmpty() == b[k]?.trim().orEmpty() }
    }
}
