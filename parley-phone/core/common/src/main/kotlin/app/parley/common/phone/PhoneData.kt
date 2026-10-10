package app.parley.common.phone

import com.google.i18n.phonenumbers.MetadataLoader
import com.google.i18n.phonenumbers.PackedMetadataHook
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.ShortNumberInfo
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * libphonenumber's data, packed at build time (core/common/build.gradle.kts, `packPhoneData`) into two files instead
 * of about 1,100 tiny ones, each of which cost a ZIP header in the APK:
 *
 * - [METADATA]: the number metadata and short numbers, compressed as one stream (they compress far better together).
 *   It is read through the library's own loader API ([PhoneNumberUtil.createInstance] with a [MetadataLoader]).
 * - [AREA_NAMES]: the offline area names ("Mountain View, CA"), each file compressed on its own behind an index, so
 *   one area's names are read without unpacking the rest ([AreaNames]).
 *
 * Every user of libphonenumber in Parley goes through [util] (or [shortNumbers]), never `PhoneNumberUtil.getInstance()`
 * directly, so the packed copy is in place before the library's own code asks for it.
 */
object PhoneData {
    const val METADATA = "/app/parley/common/phone/phone_metadata.bin"
    const val AREA_NAMES = "/app/parley/common/phone/area_names.bin"

    /** Reads one metadata file (by the library's own name for it) out of the packed stream; null when it isn't there. */
    val loader: MetadataLoader = MetadataLoader { name -> solidEntry(METADATA, name)?.let(::ByteArrayInputStream) }

    /** The one [PhoneNumberUtil], reading the packed metadata; also what `PhoneNumberUtil.getInstance()` returns from now on. */
    val util: PhoneNumberUtil by lazy {
        PhoneNumberUtil.createInstance(loader).also(PackedMetadataHook::install)
    }

    /** Short-number facts (emergency and service numbers), from the same pack. */
    val shortNumbers: ShortNumberInfo by lazy {
        // ShortNumberInfo asks PhoneNumberUtil.getInstance() for country codes: the packed one must be in place first.
        util.let { PackedMetadataHook.shortNumbers(loader) }
    }

    private fun resource(path: String): InputStream? = PhoneData::class.java.getResourceAsStream(path)

    /**
     * The bytes of [name] in the solid pack at [path]: entries in a row (name, length, bytes), ending with an empty
     * name. A region's metadata is read once per process, so a pass over the stream (a few hundred KB) is cheap.
     */
    internal fun solidEntry(path: String, name: String): ByteArray? {
        val stream = resource(path) ?: return null
        DataInputStream(InflaterInputStream(stream.buffered())).use { input ->
            while (true) {
                val entry = try {
                    input.readUTF()
                } catch (_: EOFException) {
                    return null
                }
                if (entry.isEmpty()) return null
                val size = input.readInt()
                if (entry == name) return ByteArray(size).also(input::readFully)
                input.skipFully(size.toLong())
            }
        }
    }

    /**
     * The bytes of [name] in the indexed pack at [path]: a count, then each entry's name and compressed size, then the
     * entries one after another, each compressed on its own. Only the one entry is unpacked.
     */
    internal fun indexedEntry(path: String, name: String): ByteArray? {
        val stream = resource(path) ?: return null
        DataInputStream(stream.buffered()).use { input ->
            val count = input.readInt()
            var offset = 0L
            var size = -1
            repeat(count) {
                val entry = input.readUTF()
                val length = input.readInt()
                if (size < 0) {
                    if (entry == name) size = length else offset += length
                }
            }
            if (size < 0) return null
            input.skipFully(offset)
            val packed = ByteArray(size).also(input::readFully)
            return InflaterInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
        }
    }

    /** Skips exactly [count] bytes (`skip` may stop short; InputStream.skipNBytes needs a newer Android). */
    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) left -= skipped else if (read() < 0) throw EOFException() else left--
        }
    }
}
