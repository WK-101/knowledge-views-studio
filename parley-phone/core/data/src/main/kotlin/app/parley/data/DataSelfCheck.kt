package app.parley.data

import app.parley.common.catching
import app.parley.common.storage.PersistentStore
import app.parley.common.storage.PersistentStores
import app.parley.common.storage.StoreKind
import app.parley.data.security.LineKeys
import app.parley.data.security.RecordCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The parts of Parley's own stored data the self-check looks at. */
enum class DataArea { RECORDS, PRIVATE_CONTACTS, PRIVATE_CALLS, SEALED_DOC, ARCHIVE, SNAPSHOTS, LINE_KEYS, BACKUP }

/** The verdict for one [DataArea]. */
enum class DataStatus {
    /** Everything there was read and made sense. */
    OK,

    /** Nothing is stored here yet (so nothing to check). */
    EMPTY,

    /** One or more records couldn't be opened. Nothing was changed; a backup is the way back. */
    UNREADABLE,

    /** Not corruption: no backup can be produced yet because Backup isn't set up. */
    NEEDS_BACKUP,
}

/**
 * One line of the self-check: what was looked at, how many records it held, how many couldn't be read, and the
 * verdict. [name] names the sealed-document folder for [DataArea.SEALED_DOC] rows; the screen maps it to plain words.
 */
data class DataCheckItem(
    val area: DataArea,
    val name: String? = null,
    val rows: Int = 0,
    val unreadable: Int = 0,
    val status: DataStatus,
)

/** The whole result, with a quick overall read for the plain-language summary. */
data class DataCheckReport(val items: List<DataCheckItem>) {
    /** How many records, across every area, couldn't be read. */
    val unreadable: Int get() = items.sumOf { it.unreadable }

    /** Nothing anywhere failed to open. */
    val allClear: Boolean get() = items.none { it.status == DataStatus.UNREADABLE }

    /** A backup can't be produced yet (Backup isn't set up), so there's nothing to restore from. */
    val backupMissing: Boolean get() = items.any { it.area == DataArea.BACKUP && it.status == DataStatus.NEEDS_BACKUP }
}

/**
 * A read-only self-check of Parley's own sealed and stored data (study SX-4): it opens each sealed store, the sealed
 * documents and the databases, counts what they hold and confirms each record still decrypts, checks the line-key
 * fingerprints resolve and that a backup could be produced. It reports only. Unlike a competitor's "self-healing", it
 * never writes, migrates, deletes or re-creates anything: an unreadable record is reported and left exactly as it is,
 * and the way back is Backup's restore. Under a duress unlock the hidden private stores are reported as a phone with
 * none would report them, so the check reveals nothing.
 */
class DataSelfCheck {
    /**
     * [c] is passed to [run] rather than held, so the checker keeps no reference to the whole container (and doesn't
     * add to the whole-container-constructor budget): it is a stateless, read-only pass over what the container owns.
     */
    suspend fun run(c: DataContainer): DataCheckReport = withContext(Dispatchers.IO) {
        val crypto = RecordCrypto.get(c.appContext)
        buildList {
            add(records(c, crypto))
            addAll(privateStores(c))
            addAll(sealedDocs(c, crypto))
            add(archive(c))
            add(snapshots(c))
            add(lineKeys(c))
            add(backup(c))
        }.let(::DataCheckReport)
    }

    private fun verdict(rows: Int, bad: Int): DataStatus = when {
        bad > 0 -> DataStatus.UNREADABLE
        rows == 0 -> DataStatus.EMPTY
        else -> DataStatus.OK
    }

    private fun item(area: DataArea, rows: Int, bad: Int, name: String? = null) =
        DataCheckItem(area, name = name, rows = rows, unreadable = bad, status = verdict(rows, bad))

    /** A store whose own read threw: reported unreadable, nothing touched. */
    private fun failed(area: DataArea, name: String? = null) = DataCheckItem(area, name = name, unreadable = 1, status = DataStatus.UNREADABLE)

    /** Pinned notes, call notes and their sealed line keys, sealed at rest in the database (small records). */
    private suspend fun records(c: DataContainer, crypto: RecordCrypto): DataCheckItem = catching {
        val meta = c.db.metaDao()
        var rows = 0
        var bad = 0
        meta.allMetaNow().forEach { m -> m.pinnedNote?.takeIf { it.isNotEmpty() }?.let { rows++; if (crypto.isUnreadable(it)) bad++ } }
        meta.allCallNotesNow().forEach { n ->
            if (n.text.isNotEmpty()) { rows++; if (crypto.isUnreadable(n.text)) bad++ }
            n.sealedKey?.takeIf { it.isNotEmpty() }?.let { rows++; if (crypto.isUnreadable(it)) bad++ }
        }
        item(DataArea.RECORDS, rows, bad)
    }.getOrElse { failed(DataArea.RECORDS) }

    /**
     * Private contacts and private calls (sealed in the vault with the caller-ID key, read without the unlock-bound
     * detail key so no content is shown). Under a duress unlock they're reported empty, exactly as a phone with none.
     */
    private suspend fun privateStores(c: DataContainer): List<DataCheckItem> {
        if (catching { c.privacy.now().privateHidden }.getOrDefault(true)) {
            return listOf(
                DataCheckItem(DataArea.PRIVATE_CONTACTS, status = DataStatus.EMPTY),
                DataCheckItem(DataArea.PRIVATE_CALLS, status = DataStatus.EMPTY),
            )
        }
        val chk = catching { c.vault.check() }.getOrNull()
            ?: return listOf(failed(DataArea.PRIVATE_CONTACTS), failed(DataArea.PRIVATE_CALLS))
        return listOf(
            item(DataArea.PRIVATE_CONTACTS, chk.contacts, chk.contactsUnreadable),
            item(DataArea.PRIVATE_CALLS, chk.calls, chk.callsUnreadable),
        )
    }

    /**
     * Every sealed document (the To call list, menu memory, case files, the call round, the number passport): each is a
     * folder of `.sealed` files under `docs/`. The folders come from [PersistentStores] itself, so a store added later
     * is checked here once it is registered, and this never names a path the registry doesn't keep. Decrypt only; no
     * part is parsed or rewritten.
     */
    private fun sealedDocs(c: DataContainer, crypto: RecordCrypto): List<DataCheckItem> {
        val base = c.appContext.noBackupFilesDir
        val stores = PersistentStores.of(StoreKind.FILES)
            .filter { it.location == PersistentStore.NO_BACKUP_FILES && it.name.startsWith(DOCS) }
            .sortedBy { it.name }
        return stores.map { store ->
            val label = store.name.substringAfter(DOCS)
            catching {
                val files = File(base, store.name).listFiles().orEmpty().filter { it.name.endsWith(SEALED) }
                item(DataArea.SEALED_DOC, files.size, files.count { crypto.isUnreadable(it.readText()) }, name = label)
            }.getOrElse { failed(DataArea.SEALED_DOC, name = label) }
        }
    }

    /** Archived contacts, sealed in their own files (never private contacts: those live in the vault). */
    private suspend fun archive(c: DataContainer): DataCheckItem = catching {
        val (rows, bad) = c.archive.check()
        item(DataArea.ARCHIVE, rows, bad)
    }.getOrElse { failed(DataArea.ARCHIVE) }

    /** Daily snapshots (time machine), sealed blobs of what your contacts looked like. */
    private suspend fun snapshots(c: DataContainer): DataCheckItem = catching {
        val (rows, bad) = c.timeMachine.check()
        item(DataArea.SNAPSHOTS, rows, bad)
    }.getOrElse { failed(DataArea.SNAPSHOTS) }

    /** The Keystore-wrapped line keys that turn a number into a stable fingerprint (notes and SIMs are found by it). */
    private fun lineKeys(c: DataContainer): DataCheckItem {
        val resolves: Boolean? = catching { LineKeys.get(c.appContext).fingerprintsResolve() }.getOrDefault(false)
        val status = when (resolves) {
            null -> DataStatus.EMPTY
            true -> DataStatus.OK
            false -> DataStatus.UNREADABLE
        }
        return DataCheckItem(DataArea.LINE_KEYS, unreadable = if (status == DataStatus.UNREADABLE) 1 else 0, status = status)
    }

    /**
     * Whether a backup could be produced right now (a dry run: nothing is written). It needs the encryption keys set
     * up and a folder chosen; if either is missing there is nothing to restore from, which the check flags so the way
     * back is in place before it's ever needed.
     */
    private fun backup(c: DataContainer): DataCheckItem {
        val ready = catching { c.backup.prefs.keyBundle() != null && c.backup.prefs.state.value.folderUri != null }.getOrDefault(false)
        return DataCheckItem(DataArea.BACKUP, status = if (ready) DataStatus.OK else DataStatus.NEEDS_BACKUP)
    }

    private companion object {
        /** The suffix [app.parley.data.security.SealedDocument] gives each sealed part. */
        private const val SEALED = ".sealed"

        /** The registry-name prefix every sealed-document store shares ([PersistentStores]). */
        private const val DOCS = "docs/"
    }
}
