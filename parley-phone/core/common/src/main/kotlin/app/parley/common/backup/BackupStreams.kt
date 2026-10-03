package app.parley.common.backup

import java.io.PushbackInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts into 64 KiB STREAM segments with constant memory. The header has already been written
 * by the time the constructor returns. Not thread-safe.
 */
class EncryptingOutputStream internal constructor(
    out: OutputStream,
    val header: EnvelopeHeader,
    private val key: SecretKey,
) : FilterOutputStream(out) {
    private val seg = header.segmentSize
    private val buf = ByteArray(seg)
    private var len = 0
    private var counter = 0L
    private var finished = false
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val prefix = header.noncePrefix

    /** The archive's data key, so the writer can verify the file it just wrote (never persisted). */
    val dataKey: SecretKey get() = key
    private val aad = header.aad

    init {
        out.write(header.aad)
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!finished) { "Stream already finished" }
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("off $off, len $len, size ${b.size}")
        var o = off
        var n = len
        while (n > 0) {
            // A full buffer is only flushed once more data arrives, so the final segment is always
            // emitted by finish() with the last flag set (an exact multiple of 64 KiB ends in a full last segment).
            if (this.len == seg) emit(last = false)
            val k = minOf(n, seg - this.len)
            System.arraycopy(b, o, buf, this.len, k)
            this.len += k; o += k; n -= k
        }
    }

    private fun emit(last: Boolean) {
        if (counter > 0xFFFF_FFFFL) throw IOException("Backup too large")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(BackupCrypto.TAG_SIZE * 8, BackupCrypto.segmentNonce(prefix, counter, last)))
        cipher.updateAAD(aad)
        out.write(cipher.doFinal(buf, 0, len))
        counter++
        len = 0
    }

    override fun flush() {
        // Segments are fixed-size; only flush what has already been sealed.
        out.flush()
    }

    /** Seals the last segment without closing the underlying stream. Idempotent. */
    fun finish() {
        if (finished) return
        emit(last = true)
        finished = true
        buf.fill(0)
        out.flush()
    }

    override fun close() {
        try {
            finish()
        } finally {
            out.close()
        }
    }
}

/**
 * Decrypts STREAM segments, releasing plaintext only after each segment's tag verifies. Reaching EOF
 * (-1) guarantees the whole payload was authentic and complete; any truncation, reordering, extension
 * or bit flip raises [BackupIntegrityException]. Callers must not act on data before reading to EOF
 * unless the action is undoable.
 */
class DecryptingInputStream internal constructor(
    input: InputStream,
    val header: EnvelopeHeader,
    private val key: SecretKey,
) : InputStream() {
    private val src = PushbackInputStream(input, 1)
    private val cipherLen = header.segmentSize + BackupCrypto.TAG_SIZE
    private val cbuf = ByteArray(cipherLen)
    private var plain = ByteArray(0)
    private var pos = 0
    private var counter = 0L
    private var done = false
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val prefix = header.noncePrefix

    private fun fill(): Boolean {
        while (pos >= plain.size) {
            if (done) return false
            var n = 0
            while (n < cipherLen) {
                val r = src.read(cbuf, n, cipherLen - n)
                if (r < 0) break
                n += r
            }
            val last = if (n < cipherLen) true else {
                val peek = src.read()
                if (peek < 0) true else { src.unread(peek); false }
            }
            intact(n >= BackupCrypto.TAG_SIZE) { "Truncated backup" }
            intact(counter <= 0xFFFF_FFFFL) { "Too many segments" }
            plain = try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BackupCrypto.TAG_SIZE * 8, BackupCrypto.segmentNonce(prefix, counter, last)))
                cipher.updateAAD(header.aad)
                cipher.doFinal(cbuf, 0, n)
            } catch (e: AEADBadTagException) {
                throw BackupIntegrityException("Backup is corrupted, truncated or was modified (segment $counter)", e)
            } catch (e: GeneralSecurityException) {
                throw BackupIntegrityException("Backup decryption failed", e)
            }
            pos = 0
            counter++
            if (last) done = true
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else plain[pos++].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("off $off, len $len, size ${b.size}")
        if (len == 0) return 0
        if (!fill()) return -1
        val k = minOf(len, plain.size - pos)
        System.arraycopy(plain, pos, b, off, k)
        pos += k
        return k
    }

    override fun available(): Int = plain.size - pos

    override fun close() = src.close()
}
