package app.parley.data.people

import android.content.Context
import app.parley.common.catching
import app.parley.common.people.ListHead
import app.parley.data.security.RecordCrypto
import app.parley.data.security.RecordSealing
import java.io.File

/**
 * The first screenful of the Contacts list, sealed with the records key on this phone only ([ListHead]), shown on a
 * cold start until the address book and the private contacts have loaded. It holds private contacts' rows only when
 * they were listed when it was written; "Lock private contacts" and a duress unlock take them out at once
 * ([dropPrivate]), and [ListHead.shown] checks again before anything is drawn. Never backed up; "Delete all Parley
 * data" removes it with the rest.
 */
class ContactListHead(context: Context, private val crypto: RecordCrypto) : RecordSealing.Resealable {
    private val file = File(context.noBackupFilesDir, "contact_list_head")

    @Volatile private var kept: String? = null

    /** The kept rows, or null (none yet, or they can't be opened). Reads the disk: call off the main thread. */
    fun load(): ListHead.Snapshot? = runCatching {
        if (!file.isFile) return null
        String(crypto.openBytes(file.readBytes())).also { kept = it }
    }.getOrNull()?.let(ListHead::decode)

    /** Keeps [encoded] ([ListHead.encode]) when it differs from what is kept. Writes the disk: call off the main thread. */
    @Synchronized
    fun save(encoded: String) {
        if (!ListHead.changed(encoded, kept)) return
        runCatching {
            val sealed = crypto.sealBytes(encoded.toByteArray())
            // Private rows are never written in the clear (a key that can't be used now keeps the old head instead).
            if (encoded.contains("\"withPrivate\":true") && !crypto.isSealed(sealed)) return
            if (write(sealed)) kept = encoded
        }
    }

    /**
     * Takes every private contact out of the kept rows, now ("Lock private contacts", a duress unlock). When they can't
     * be read to be rewritten, the whole head goes: failing closed costs one cold start's spinner.
     */
    @Synchronized
    fun dropPrivate() {
        val text = kept ?: runCatching { if (file.isFile) String(crypto.openBytes(file.readBytes())) else null }.getOrNull()
        if (text == null) {
            if (file.isFile) clear()
            return
        }
        val without = ListHead.dropPrivate(text)
        if (without == null) {
            clear()
            return
        }
        val sealed = runCatching { crypto.sealBytes(without.toByteArray()) }.getOrNull()
        if (sealed == null || !write(sealed)) clear() else kept = without
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
