package app.parley.common.storage

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The one way Parley replaces a file: the new content goes to `<name>.tmp`, is flushed and synced to the disk, then
 * renamed over the old file in one step, and the folder is synced so the rename itself survives a power cut. Without
 * the syncs a crash a few seconds after a write can leave an empty file on ext4 and f2fs, which for a wrapped key, an
 * archived contact or the PIN record means data gone for good.
 *
 * One failure behaviour everywhere: the old file stays exactly as it was, the temporary file is removed, the error is
 * reported once ([report]) and the call returns false ([writeOrThrow] throws instead, for callers that must stop).
 * File names don't change, so readers never need to know about this.
 */
object DurableFiles {
    const val TMP_SUFFIX = ".tmp"

    /** The steps that touch the disk; a test swaps them to fail or "lose power" at each point. */
    interface Disk {
        /** Writes [body] to [file] (created or truncated), flushes it and syncs its data to the disk. */
        fun writeSynced(file: File, body: (OutputStream) -> Unit)

        /** Syncs an already written [file]'s data to the disk. */
        fun sync(file: File)

        /** Renames [from] to [to] in one step, replacing [to] when [replace]. */
        fun rename(from: File, to: File, replace: Boolean)

        /** Syncs [dir]'s entries (so a rename in it is on the disk); best effort, as not every file system allows it. */
        fun syncDir(dir: File)
    }

    /** The real disk. */
    object RealDisk : Disk {
        override fun writeSynced(file: File, body: (OutputStream) -> Unit) {
            FileOutputStream(file).use { fos ->
                val buffered = BufferedOutputStream(fos, BUFFER)
                body(KeepOpen(buffered))
                buffered.flush()
                fos.fd.sync()
            }
        }

        override fun sync(file: File) {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        }

        override fun rename(from: File, to: File, replace: Boolean) {
            if (replace) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                if (to.exists()) throw IOException("${to.name} already exists")
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        }

        override fun syncDir(dir: File) {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /** A body may close what it is given (a `use {}` on a wrapping stream); the sync still has to follow. */
    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }

    @Volatile var disk: Disk = RealDisk

    /** Where failures are reported (Android's log in the app; nothing in plain JVM tests). */
    @Volatile var report: (String, Throwable) -> Unit = { _, _ -> }

    /** Replaces [target] with [bytes]. False, with the old file kept, when it couldn't be written. */
    fun write(target: File, bytes: ByteArray): Boolean = write(target) { it.write(bytes) }

    fun writeText(target: File, text: String): Boolean = write(target, text.toByteArray(Charsets.UTF_8))

    /** Replaces [target] with what [body] writes (streamed, never held whole). False, with the old file kept, on failure. */
    @Suppress("TooGenericExceptionCaught") // The body's own errors (sealing, encoding) end the same way as a disk error.
    fun write(target: File, body: (OutputStream) -> Unit): Boolean = try {
        writeOrThrow(target, body)
        true
    } catch (e: Exception) {
        report("Couldn't write ${target.name}; the previous copy is kept", e)
        false
    }

    /** As [write], but throws: for callers that must not go on (an archive written before the contact is purged). */
    fun writeOrThrow(target: File, bytes: ByteArray) = writeOrThrow(target) { it.write(bytes) }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // Clean up whatever went wrong, then rethrow it.
    fun writeOrThrow(target: File, body: (OutputStream) -> Unit) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("No folder for ${target.name}")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Can't create ${dir.name}")
        val tmp = File(dir, target.name + TMP_SUFFIX)
        try {
            disk.writeSynced(tmp, body)
            disk.rename(tmp, target, replace = true)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        syncDir(dir)
    }

    /**
     * Moves an already written file [staged] into place as [target] (synced first). On failure [staged] is removed and
     * the old [target] kept.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure keeps the old file and removes the staged one.
    fun place(staged: File, target: File): Boolean = try {
        disk.sync(staged)
        disk.rename(staged, target, replace = true)
        syncDir(target.absoluteFile.parentFile)
        true
    } catch (e: Exception) {
        staged.delete()
        report("Couldn't put ${target.name} in place; the previous copy is kept", e)
        false
    }

    /**
     * Renames [from] to [to] (a file set aside, a picture following its contact), never replacing an existing [to]
     * unless [replace]. False, with both left as they were, when it couldn't.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure leaves both files as they were.
    fun move(from: File, to: File, replace: Boolean = false): Boolean = try {
        disk.rename(from, to, replace)
        syncDir(to.absoluteFile.parentFile)
        from.absoluteFile.parentFile?.takeIf { it != to.absoluteFile.parentFile }?.let(::syncDir)
        true
    } catch (e: Exception) {
        report("Couldn't move ${from.name}", e)
        false
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Not every file system can sync a folder; the file itself was.
    private fun syncDir(dir: File?) {
        if (dir == null) return
        try {
            disk.syncDir(dir)
        } catch (_: Exception) {
            // Some file systems refuse to sync a folder; the rename is done and most of them keep it anyway.
        }
    }

    private const val BUFFER = 64 * 1024
}
