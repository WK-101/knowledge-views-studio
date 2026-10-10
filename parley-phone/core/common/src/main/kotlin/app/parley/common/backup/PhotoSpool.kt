package app.parley.common.backup

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Contact photos of one backup being written or read, by SHA-256, so a backup never holds every photo in memory (a few
 * thousand photos are hundreds of megabytes). With a [dir] they go to a temporary file there, one at a time, each
 * sealed with AES-GCM under a key that lives only in this object: the file is removed from the folder as soon as it is
 * opened (it lives on only while open) and is unreadable without the key anyway. Without a [dir] they stay in memory,
 * as before (tests, small archives).
 */
internal class PhotoSpool(dir: File?) : Closeable {
    private class Slot(val offset: Long, val length: Int, val plainSize: Int)

    private val slots = TreeMap<String, Slot>()
    private val memory = if (dir == null) HashMap<String, ByteArray>() else null
    private val file: RandomAccessFile?
    private val key: SecretKeySpec?
    private val random = SecureRandom()

    init {
        if (dir == null) {
            file = null
            key = null
        } else {
            dir.mkdirs()
            val f = File.createTempFile("photos", ".spool", dir)
            file = RandomAccessFile(f, "rw")
            // Unlinked at once: nothing is left behind if the process dies, and no other code can open it by name.
            f.delete()
            key = SecretKeySpec(ByteArray(KEY_BYTES).also(random::nextBytes), "AES")
        }
    }

    val size: Int get() = slots.size

    /** Every photo's hash, in order. */
    fun hashes(): Set<String> = slots.keys

    operator fun contains(hash: String): Boolean = hash in slots

    /** The size of [hash]'s photo, or null when there is none. */
    fun sizeOf(hash: String): Int? = slots[hash]?.plainSize

    /** Keeps [bytes] as [hash]'s photo unless one is kept already. */
    @Synchronized
    fun put(hash: String, bytes: ByteArray) {
        if (hash in slots) return
        val f = file
        if (f == null) {
            memory!![hash] = bytes
            slots[hash] = Slot(0, bytes.size, bytes.size)
            return
        }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val sealed = cipher(Cipher.ENCRYPT_MODE, nonce, hash).doFinal(bytes)
        val at = f.length()
        f.seek(at)
        f.write(nonce)
        f.write(sealed)
        slots[hash] = Slot(at, NONCE_BYTES + sealed.size, bytes.size)
    }

    /** Remembers [hash] and its size only (a hash-only writer needs no bytes). */
    @Synchronized
    fun note(hash: String, size: Int) {
        if (hash !in slots) slots[hash] = Slot(0, 0, size)
    }

    /** [hash]'s photo, or null when there is none. */
    @Synchronized
    fun get(hash: String): ByteArray? {
        val s = slots[hash] ?: return null
        val f = file ?: return memory!![hash]
        val blob = ByteArray(s.length)
        f.seek(s.offset)
        f.readFully(blob)
        return cipher(Cipher.DECRYPT_MODE, blob.copyOfRange(0, NONCE_BYTES), hash).doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
    }

    private fun cipher(mode: Int, nonce: ByteArray, hash: String): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        // Bound to its hash: a slot can't be served as another photo.
        updateAAD(hash.toByteArray(Charsets.US_ASCII))
    }

    @Synchronized
    override fun close() {
        file?.close()
        memory?.clear()
        slots.clear()
    }

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
