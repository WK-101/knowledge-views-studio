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

    /** The vCard in a folder file, or null when it can't be read (another key, altered, renamed). */
    abstract fun decode(name: String, bytes: ByteArray): ByteArray?

    abstract fun encode(name: String, vcard: ByteArray): ByteArray

    /** Plain vCard 4.0 files: any app or person with access to the folder can read them. */
    object Plain : SyncCodec() {
        override val mime = "text/vcard"
        override val extension = ".vcf"
        override fun decode(name: String, bytes: ByteArray) = bytes
        override fun encode(name: String, vcard: ByteArray) = vcard
    }

    /** Each file sealed with the folder key, bound to its name ([SyncCrypto]). */
    class Encrypted(private val key: ByteArray) : SyncCodec() {
        override val mime = "application/octet-stream"
        override val extension = SyncCrypto.EXTENSION
        override fun decode(name: String, bytes: ByteArray) = SyncCrypto.open(key, name, bytes)
        override fun encode(name: String, vcard: ByteArray) = SyncCrypto.seal(key, name, vcard)
    }
}
