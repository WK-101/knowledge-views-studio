package app.parley.data.sync

import app.parley.common.backup.SyncCrypto

/** How the sync folder's files are written: encrypted (the default for new set-ups) or plain vCards (by explicit choice). */
enum class SyncMode { UNSET, ENCRYPTED, PLAIN }

/** Turns contact vCards into the folder's files and back, for one [SyncMode]. */
internal sealed class SyncCodec {
    abstract val mime: String
    abstract val extension: String

    /** Whether [name] is one of this mode's contact files (not a conflict copy or the folder header). */
    fun accepts(name: String) = name.endsWith(extension) && !name.contains(".conflict") && name != SyncCrypto.HEADER_NAME

    fun fileName(base: String) = base + extension

    fun conflictName(name: String, time: Long) = name.removeSuffix(extension) + ".conflict-" + time + extension

    /** A file's vCard and the version it was written with (always 0 for plain files, which carry none). */
    class Decoded(val vcard: ByteArray, val version: Long)

    /** Whether files carry a version, so an older copy put back can be told from a newer one. */
    abstract val versioned: Boolean

    /** The vCard in a folder file, or null when it can't be read (another key, altered, renamed). */
    abstract fun decode(name: String, bytes: ByteArray): Decoded?

    abstract fun encode(name: String, vcard: ByteArray, version: Long): ByteArray

    /** Plain vCard 4.0 files: any app or person with access to the folder can read them. */
    object Plain : SyncCodec() {
        override val mime = "text/vcard"
        override val extension = ".vcf"
        override val versioned = false
        override fun decode(name: String, bytes: ByteArray) = Decoded(bytes, 0)
        override fun encode(name: String, vcard: ByteArray, version: Long) = vcard
    }

    /** Each file sealed with the folder key, bound to its name and versioned ([SyncCrypto]). */
    class Encrypted(private val key: ByteArray) : SyncCodec() {
        override val mime = "application/octet-stream"
        override val extension = SyncCrypto.EXTENSION
        override val versioned = true
        override fun decode(name: String, bytes: ByteArray) = SyncCrypto.openVersioned(key, name, bytes)?.let { Decoded(it.vcard, it.version) }
        override fun encode(name: String, vcard: ByteArray, version: Long) = SyncCrypto.seal(key, name, vcard, version)
    }
}
