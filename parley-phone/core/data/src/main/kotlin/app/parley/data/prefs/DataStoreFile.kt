package app.parley.data.prefs

import java.io.IOException

/**
 * Reads a Preferences DataStore file (`files/datastore/<name>.preferences_pb`, written by Parley 6.4 and older) without
 * the DataStore library: a protobuf `PreferenceMap` of name → value, where a value is a boolean (field 1), float (2),
 * int (3), long (4), string (5) or string set (6). Doubles and byte arrays (7, 8) were never written by Parley and are
 * skipped.
 */
internal object DataStoreFile {
    fun read(bytes: ByteArray): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        val r = Reader(bytes, 0, bytes.size)
        while (r.more()) {
            val tag = r.varint().toInt()
            if (tag == tag(1, LEN)) entry(r.sub(), out) else r.skip(tag)
        }
        return out
    }

    private fun entry(r: Reader, out: MutableMap<String, Any>) {
        var name: String? = null
        var value: Any? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag) {
                tag(1, LEN) -> name = r.string()
                tag(2, LEN) -> value = value(r.sub())
                else -> r.skip(tag)
            }
        }
        if (name != null && value != null) out[name] = value
    }

    private fun value(r: Reader): Any? {
        var v: Any? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            v = when (tag) {
                tag(1, VARINT) -> r.varint() != 0L
                tag(2, FIXED32) -> Float.fromBits(r.fixed32())
                tag(3, VARINT) -> r.varint().toInt()
                tag(4, VARINT) -> r.varint()
                tag(5, LEN) -> r.string()
                tag(6, LEN) -> stringSet(r.sub())
                else -> v.also { r.skip(tag) }
            }
        }
        return v
    }

    private fun stringSet(r: Reader): Set<String> {
        val set = LinkedHashSet<String>()
        while (r.more()) {
            val tag = r.varint().toInt()
            if (tag == tag(1, LEN)) set += r.string() else r.skip(tag)
        }
        return set
    }

    private fun tag(field: Int, wire: Int) = (field shl 3) or wire

    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val LEN = 2
    private const val FIXED32 = 5

    private class Reader(private val b: ByteArray, private var pos: Int, private val end: Int) {
        fun more() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= end) throw IOException("Truncated settings file")
                val byte = b[pos++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            throw IOException("Malformed settings file")
        }

        fun fixed32(): Int {
            if (pos + 4 > end) throw IOException("Truncated settings file")
            var v = 0
            for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }

        fun sub(): Reader {
            val len = varint().toInt()
            if (len < 0 || pos + len > end) throw IOException("Truncated settings file")
            return Reader(b, pos, pos + len).also { pos += len }
        }

        fun string(): String = sub().let { String(b, it.pos, it.end - it.pos, Charsets.UTF_8) }

        fun skip(tag: Int) {
            when (tag and 7) {
                VARINT -> varint()
                FIXED64 -> pos += 8
                LEN -> sub()
                FIXED32 -> pos += 4
                else -> throw IOException("Malformed settings file")
            }
            if (pos > end) throw IOException("Truncated settings file")
        }
    }
}
