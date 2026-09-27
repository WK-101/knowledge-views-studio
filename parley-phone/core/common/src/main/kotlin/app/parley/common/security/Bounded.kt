package app.parley.common.security

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A file, link or code went over one of [Bounded]'s limits; it is refused rather than read into memory. */
class LimitExceededException(message: String) : IOException(message)

/**
 * One place for every read of data someone else chose: imported vCards and CSV files, QR payloads, simple-mode setup
 * files, list packs and backups. Each read has an explicit cap on bytes, entries, line length or compression ratio, so
 * a huge or crafted file fails with [LimitExceededException] instead of exhausting memory.
 */
object Bounded {
    /** The caps each kind of input uses. */
    object Caps {
        /** A simple-mode setup file (a few people and switches). */
        const val SETUP_FILE = 1L shl 20

        /** What a QR code's compressed payload may expand to. */
        const val QR_GUNZIP = 64L shl 10

        /** One vCard between BEGIN and END (photos included). */
        const val VCARD_CARD = 10 shl 20

        /** One text line of a vCard or CSV file. */
        const val TEXT_LINE = 1 shl 20

        /** Cards or rows in one imported file. */
        const val IMPORT_ENTRIES = 200_000

        /** A call-history CSV file. */
        const val CALL_CSV = 20L shl 20

        /** A `.parleylist` file as picked or received. */
        const val PACK_FILE = 32L shl 20

        /** Everything a pack's entries expand to. */
        const val PACK_TOTAL = 64L shl 20

        /** Entries in a pack ZIP (it needs four). */
        const val PACK_ENTRIES = 64

        /** A synced contact file. */
        const val SYNC_FILE = 5L shl 20

        /** Expansion allowed for DEFLATE/GZIP data (contacts compress about 5–10×; 100× is a bomb). */
        const val GZIP_RATIO = 100

        /** Small inputs may expand this much regardless of the ratio (a tiny gzip of a short text). */
        const val RATIO_SLACK = 64L shl 10
    }

    /** Reads all of [input] (without closing it), refusing more than [maxBytes]. */
    fun readBytes(input: InputStream, maxBytes: Long, what: String = "file"): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64L shl 10).toInt())
        val buf = ByteArray(16 shl 10)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw LimitExceededException("The $what is larger than ${maxBytes / 1024} KB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** A view of [input] that throws once more than [maxBytes] have been read through it. */
    fun stream(input: InputStream, maxBytes: Long, what: String = "file"): InputStream = LimitedInputStream(input, maxBytes, what)

    /** Decompresses GZIP [bytes], refusing more than [maxBytes] of output or a ratio beyond [Caps.GZIP_RATIO]. */
    fun gunzip(bytes: ByteArray, maxBytes: Long, what: String = "code"): ByteArray {
        val cap = minOf(maxBytes, bytes.size.toLong() * Caps.GZIP_RATIO + Caps.RATIO_SLACK)
        return try {
            GZIPInputStream(bytes.inputStream()).use { readBytes(it, cap, what) }
        } catch (e: LimitExceededException) {
            throw e
        } catch (e: ZipException) {
            throw IOException("The $what is damaged", e)
        }
    }

    /**
     * Guards a compressed stream: [compressed] counts what was read from the source; [check] fails once the output
     * so far is more than [ratio] times that (after [slack] bytes).
     */
    class RatioGuard(source: InputStream, private val ratio: Int = Caps.GZIP_RATIO, private val slack: Long = Caps.RATIO_SLACK) {
        private var read = 0L

        /** Wrap the raw (compressed) source with this before handing it to the decompressor. */
        val compressed: InputStream = object : FilterInputStream(source) {
            override fun read(): Int = super.read().also { if (it >= 0) read++ }

            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }

            override fun skip(n: Long): Long = super.skip(n).also { read += it }
        }

        fun check(expanded: Long) {
            if (expanded > slack && expanded > read * ratio) throw LimitExceededException("The file expands too much to be real data")
        }
    }

    /**
     * Reads the entries of a ZIP whose names [want] accepts (last path segment), with caps on the number of entries
     * seen, on each entry and on the total expanded size, plus the compression ratio. Directories are skipped.
     */
    fun unzip(
        input: InputStream,
        want: (String) -> Boolean,
        maxEntries: Int,
        maxEntryBytes: Long,
        maxTotalBytes: Long,
        what: String = "file",
    ): Map<String, ByteArray> {
        val guard = RatioGuard(input)
        val files = LinkedHashMap<String, ByteArray>()
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(guard.compressed).use { z ->
                val buf = ByteArray(64 shl 10)
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++entries > maxEntries) throw LimitExceededException("The $what has too many parts")
                    val name = e.name.substringAfterLast('/')
                    val keep = !e.isDirectory && want(name)
                    val out = if (keep) ByteArrayOutputStream() else null
                    var size = 0L
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        size += n
                        total += n
                        if (size > maxEntryBytes || total > maxTotalBytes) throw LimitExceededException("The $what is too large")
                        guard.check(total)
                        out?.write(buf, 0, n)
                    }
                    if (out != null) {
                        if (name in files) throw IOException("The $what has $name twice")
                        files[name] = out.toByteArray()
                    }
                }
            }
        } catch (e: ZipException) {
            throw IOException("The $what is damaged", e)
        }
        return files
    }

    /**
     * Line reader with a cap on each line's length (in chars). Lines end at `\n`, `\r` or `\r\n`, as with
     * [java.io.BufferedReader.readLine]. [maxLines] caps how many lines one file may have.
     */
    class LineReader(private val source: Reader, private val maxLine: Int = Caps.TEXT_LINE, private val maxLines: Long = Long.MAX_VALUE) {
        private val buf = CharArray(8192)
        private var pos = 0
        private var len = 0
        private var lines = 0L
        private var skipLf = false

        private fun fill(): Boolean {
            if (pos < len) return true
            len = source.read(buf)
            pos = 0
            return len > 0
        }

        /** The next line, or null at the end. */
        fun readLine(): String? {
            val sb = StringBuilder()
            var any = false
            while (fill()) {
                if (skipLf) {
                    skipLf = false
                    if (buf[pos] == '\n') {
                        pos++
                        continue
                    }
                }
                any = true
                val start = pos
                while (pos < len && buf[pos] != '\n' && buf[pos] != '\r') pos++
                if (sb.length + (pos - start) > maxLine) throw LimitExceededException("A line is longer than ${maxLine / 1024} KB")
                sb.appendRange(buf, start, pos)
                if (pos < len) {
                    if (buf[pos] == '\r') skipLf = true
                    pos++
                    return counted(sb.toString())
                }
            }
            return if (any) counted(sb.toString()) else null
        }

        private fun counted(line: String): String {
            if (++lines > maxLines) throw LimitExceededException("The file has too many lines")
            return line
        }

        fun lineSequence(): Sequence<String> = generateSequence { readLine() }
    }

    private class LimitedInputStream(input: InputStream, private val max: Long, private val what: String) : FilterInputStream(input) {
        private var count = 0L

        private fun add(n: Long) {
            count += n
            if (count > max) throw LimitExceededException("The $what is larger than ${max / 1024} KB")
        }

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { add(it) }

        override fun markSupported(): Boolean = false
    }
}
