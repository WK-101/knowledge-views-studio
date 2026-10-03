package app.parley.data.people

import app.parley.common.people.ParleyRelations
import app.parley.data.DataItem

/** A device contact's relations kept in Parley only ([ParleyRelations]), as the editor's and the page's rows. */
object ParleyRelationRows {
    fun decode(stored: String?): List<DataItem> = ParleyRelations.decode(stored).map { DataItem(value = it.name, type = it.type, label = it.label) }

    fun encode(rows: List<DataItem>): String? = ParleyRelations.encode(rows.map { ParleyRelations.Entry(it.value, it.type, it.label) })
}
