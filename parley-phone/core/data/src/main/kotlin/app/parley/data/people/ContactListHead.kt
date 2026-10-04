package app.parley.data.people

import android.content.Context
import app.parley.common.ContactSummary
import app.parley.common.catching
import app.parley.common.people.ListHead
import app.parley.data.security.RecordCrypto
import app.parley.data.security.RecordSealing
import java.io.File

/**
 * The first screenful of the Contacts list, sealed with the records key on this phone only ([ListHead]), shown on a
 * cold start until the address book has loaded. Never backed up; "Delete all Parley data" removes it with the rest.
 */
class ContactListHead(context: Context, private val crypto: RecordCrypto) : RecordSealing.Resealable {
    private val file = File(context.noBackupFilesDir, "contact_list_head")

    @Volatile private var kept: String? = null

    /** The kept rows, or null (none yet, or they can't be opened). Reads the disk: call off the main thread. */
    fun load(): List<ContactSummary>? = runCatching {
        if (!file.isFile) return null
        String(crypto.openBytes(file.readBytes())).also { kept = it }
    }.getOrNull()?.let(ListHead::decode)

    /** Keeps [list]'s head when it differs from what is kept. Writes the disk: call off the main thread. */
    @Synchronized
    fun save(list: List<ContactSummary>) {
        if (!ListHead.changed(list, kept)) return
        val text = ListHead.encode(list)
        runCatching { if (write(crypto.sealBytes(text.toByteArray()))) kept = text }
    }

    private fun write(bytes: ByteArray): Boolean {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        return tmp.renameTo(file)
    }

    /**
     * Seals rows kept plain because the key couldn't be used when they were written (they would otherwise stay plain
     * until the list's head changes). False while they are still plain.
     */
    override suspend fun resealPlain(): Boolean = synchronized(this) {
        catching {
            if (!file.isFile) return@catching true
            val stored = file.readBytes()
            if (crypto.isSealed(stored)) return@catching true
            val sealed = crypto.sealBytes(stored)
            crypto.isSealed(sealed) && write(sealed)
        }.getOrDefault(false)
    }

    @Synchronized
    fun clear() {
        file.delete()
        kept = null
    }
}
