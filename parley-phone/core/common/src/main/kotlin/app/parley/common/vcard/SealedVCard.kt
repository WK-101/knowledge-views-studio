package app.parley.common.vcard

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.BackupIntegrityException
import app.parley.common.backup.DecryptingInputStream
import app.parley.common.backup.KdfParams
import app.parley.common.backup.Recipient
import app.parley.common.backup.Unlock
import app.parley.common.backup.WrapType
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * An encrypted vCard: a vCard 4.0 file (UTF-8) inside the backup's envelope ([BackupCrypto], "PARLEYB1") with one
 * passphrase key wrap, so it opens with the passphrase alone on any phone, or in any program that follows
 * docs/ENCRYPTED_VCARD.md (scrypt, then AES-256-GCM in 64 KiB segments). The same reviewed code as backups; nothing new
 * to trust.
 */
object SealedVCard {
    /** The file name's ending: "contacts.vcf.parley". */
    const val EXTENSION = ".vcf.parley"
    const val MIME = "application/octet-stream"

    /** The file is a Parley backup, not an encrypted vCard: it's restored from Backup instead. */
    class BackupFileException : BackupIntegrityException("This is a Parley backup, not an encrypted vCard")

    /** A stream that encrypts everything written to it into [out]; closing it finishes the file and closes [out]. */
    fun seal(out: OutputStream, passphrase: CharArray, kdf: KdfParams = BackupCrypto.DEFAULT_KDF): OutputStream =
        BackupCrypto.encrypt(out, listOf(Recipient.Passphrase(passphrase)), kdf)

    /** Whether [head], the first bytes of a file, start like Parley's envelope (an encrypted vCard or a backup). */
    fun looksSealed(head: ByteArray): Boolean {
        val magic = BackupCrypto.MAGIC.toByteArray(Charsets.US_ASCII)
        return head.size >= magic.size && head.copyOf(magic.size).contentEquals(magic)
    }

    /**
     * The vCard text inside [input]. Throws [app.parley.common.backup.WrongKeyException] for a wrong passphrase,
     * [BackupFileException] for a backup, and [BackupIntegrityException] for a damaged or cut-off file (also while
     * reading the returned stream: every segment is authenticated as it is read).
     */
    fun open(input: InputStream, passphrase: CharArray): InputStream {
        val header = BackupCrypto.readHeader(input)
        // A backup carries the key bundle's wrap; an encrypted vCard has only the passphrase's.
        if (WrapType.PASSPHRASE !in header.wrapTypes || header.wrapTypes.size != 1) throw BackupFileException()
        val plain = BufferedInputStream(DecryptingInputStream(input, header, BackupCrypto.unwrapDataKey(header, Unlock.Passphrase(passphrase))))
        plain.mark(PEEK)
        val head = ByteArray(PEEK)
        var n = 0
        while (n < PEEK) {
            val r = plain.read(head, n, PEEK - n)
            if (r < 0) break
            n += r
        }
        plain.reset()
        val text = String(head, 0, n, Charsets.UTF_8).trimStart('﻿', ' ', '\r', '\n', '\t')
        if (n > 0 && !text.startsWith("BEGIN:VCARD", ignoreCase = true)) throw BackupFileException()
        return plain
    }

    private const val PEEK = 64
}
