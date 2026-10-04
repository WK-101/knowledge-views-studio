package app.parley.data.people

import android.content.Context
import app.parley.common.ContactSummary
import app.parley.common.people.ListHead
import app.parley.data.security.RecordCrypto
import java.io.File

/**
 * The first screenful of the Contacts list, sealed with the records key on this phone only ([ListHead]), shown on a
 * cold start until the address book has loaded. Never backed up; "Delete all Parley data" removes it with the rest.
 */
class ContactListHead(context: Context, private val crypto: RecordCrypto) {
    private val file = File(context.noBackupFilesDir, "contact_list_head")

    @Volatile private var kept: String? = null

    /** The kept rows, or null (none yet, or they can't be opened). Reads the disk: call off the main thread. */
    fun load(): List<ContactSummary>? = runCatching {
        if (!file.isFile) return null
        String(crypto.openBytes(file.readBytes())).also { kept = it }
    }.getOrNull()?.let(ListHead::decode)

    /** Keeps [list]'s head when it differs from what is kept. Writes the disk: call off the main thread. */
    fun save(list: List<ContactSummary>) {
        if (!ListHead.changed(list, kept)) return
        val text = ListHead.encode(list)
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(crypto.sealBytes(text.toByteArray()))
            if (tmp.renameTo(file)) kept = text
        }
    }

    fun clear() {
        file.delete()
        kept = null
    }
}
