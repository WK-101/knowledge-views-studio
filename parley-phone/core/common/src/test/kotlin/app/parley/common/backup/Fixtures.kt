package app.parley.common.backup

import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord

internal object Fixtures {
    fun name(display: String) = DataRow(Mime.NAME, mapOf("data1" to display, "data2" to display.substringBefore(' '), "data3" to display.substringAfter(' ', "")))
    fun phone(n: String, type: String = "2", primary: Boolean = false) = DataRow(Mime.PHONE, mapOf("data1" to n, "data2" to type, "data3" to null), isPrimary = primary)
    fun email(e: String) = DataRow(Mime.EMAIL, mapOf("data1" to e, "data2" to "1"))
    fun note(t: String) = DataRow(Mime.NOTE, mapOf("data1" to t))
    fun photo(bytes: ByteArray) = DataRow(Mime.PHOTO, mapOf("data14" to "77"), blob = bytes)

    fun contact(
        key: String,
        display: String,
        vararg rows: DataRow,
        account: Pair<String?, String?> = "com.google" to "me@example.com",
        sourceId: String? = null,
        starred: Boolean = false,
    ) = ContactRecord(
        key = key,
        displayName = display,
        starred = starred,
        raws = listOf(RawRecord(account.first, account.second, sourceId = sourceId, rows = listOf(name(display)) + rows)),
    )
}
