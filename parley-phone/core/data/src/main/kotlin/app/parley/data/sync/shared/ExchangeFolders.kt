package app.parley.data.sync.shared

import app.parley.common.catching
import app.parley.common.storage.DurableFiles
import app.parley.common.sync.shared.SharedLabelFiles
import app.parley.common.sync.shared.SharedLabelUpdates
import java.io.File

/**
 * The files of a label shared by update files only, kept in this phone's own storage: what a shared folder would hold
 * on this phone. Every file is already sealed with the label's key and signed (docs/SHARED_LABELS.md). Stamps are the
 * content's hash, as an update's files carry no modified time.
 */
class LocalLabelFolder(private val dir: File) : LabelFolder {
    override suspend fun list(): Map<String, String?> {
        if (!dir.isDirectory) return emptyMap()
        return dir.listFiles().orEmpty().filter { it.isFile && SharedLabelUpdates.isLabelFile(it.name) }
            .associate { f -> f.name to catching { SharedLabelUpdates.stamp(f.readBytes()) }.getOrNull() }
    }

    override fun read(name: String): ByteArray? = fileOf(name)?.takeIf { it.isFile && it.length() <= SharedLabelFiles.MAX_FILE_BYTES }
        ?.let { f -> runCatching { f.readBytes() }.getOrNull() }

    /** Written to a temporary file and renamed, so a crash never leaves half a file. */
    override fun write(name: String, bytes: ByteArray): String? {
        val target = fileOf(name) ?: return null
        return runCatching {
            DurableFiles.writeOrThrow(target, bytes)
            SharedLabelUpdates.stamp(bytes)
        }.getOrNull()
    }

    override fun delete(name: String): Boolean = fileOf(name)?.delete() == true

    /** Only a label's own file names, so nothing is ever written outside [dir]. */
    private fun fileOf(name: String): File? = if (SharedLabelUpdates.isLabelFile(name)) File(dir, name) else null

    /** Removes every file (the label left this phone). */
    fun clear() {
        dir.deleteRecursively()
    }
}

/**
 * A label's folder as it would look with an update's files arrived ([incoming], chosen already): reads see them,
 * writes go to the folder underneath and replace them. What is left in [incoming] after a run is what the run didn't
 * write over; the caller keeps what should stay.
 */
internal class OverlayFolder(private val base: LabelFolder, incoming: Map<String, ByteArray>) : LabelFolder {
    private val pending = LinkedHashMap(incoming)

    /** The arrived files the run didn't write over. */
    val untouched: Set<String> get() = pending.keys

    override suspend fun list(): Map<String, String?>? =
        base.list()?.let { listed -> listed + pending.mapValues { (_, bytes) -> SharedLabelUpdates.stamp(bytes) } }

    override fun read(name: String): ByteArray? = pending[name] ?: base.read(name)

    override fun write(name: String, bytes: ByteArray): String? = base.write(name, bytes)?.also { pending.remove(name) }

    override fun delete(name: String): Boolean {
        val there = pending.remove(name) != null
        return base.delete(name) || there
    }
}
