package app.parley.common.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * An attacker's pressure on the backup file (docs/audit/SECURITY_PRESSURE_7_0.md): someone who holds a copy of it, or
 * can write to the folder it is kept in, flips, cuts, swaps and crafts bytes. Every such file must be refused with an
 * [IOException] (damaged, or the wrong secret), never opened with changed content, never crash the process with anything
 * but an exception, and never cost more than the capped key derivation.
 */
class BackupPressureTest {
    private companion object {
        val PASS = "correct horse battery staple".toCharArray()
        val CHEAP: KdfParams = KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS)
    }

    private fun enc(plain: ByteArray, pass: CharArray = PASS): ByteArray =
        BackupCrypto.encryptBytes(plain, listOf(Recipient.Passphrase(pass)), CHEAP)

    /** Opens [ct] with [PASS]; the IOException it was refused with, or a failure if it opened. */
    private fun refused(ct: ByteArray, what: String): IOException {
        try {
            BackupCrypto.decryptBytes(ct, Unlock.Passphrase(PASS))
        } catch (e: IOException) {
            return e
        } catch (e: Exception) {
            throw AssertionError("$what: refused with ${e.javaClass.simpleName}, not as a damaged file", e)
        }
        fail("$what: a changed file opened")
        error("unreachable")
    }

    // ------------------------------------------------------------ the envelope

    @Test fun every_bit_flip_anywhere_in_the_file_is_refused() {
        val plain = Random(1).nextBytes(300)
        val ct = enc(plain)
        assertArrayEquals(plain, BackupCrypto.decryptBytes(ct, Unlock.Passphrase(PASS)))
        for (i in ct.indices) for (bit in intArrayOf(0, 7)) {
            val bad = ct.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            refused(bad, "bit $bit of byte $i")
        }
    }

    @Test fun every_truncation_and_any_extension_is_refused() {
        // Two full segments and a part, so cuts at segment boundaries are covered too.
        val ct = enc(Random(2).nextBytes(2 * BackupCrypto.SEGMENT_SIZE + 10))
        val header = 13 + java.nio.ByteBuffer.wrap(ct, 9, 4).int
        val cuts = (0 until header + 40) + (header..ct.size step 997) +
            listOf(header + BackupCrypto.SEGMENT_SIZE + BackupCrypto.TAG_SIZE, header + 2 * (BackupCrypto.SEGMENT_SIZE + BackupCrypto.TAG_SIZE), ct.size - 1)
        for (n in cuts.distinct().filter { it < ct.size }) refused(ct.copyOf(n), "cut at $n of ${ct.size}")
        refused(ct + ByteArray(1), "one byte appended")
        refused(ct + ct.copyOfRange(header, header + BackupCrypto.SEGMENT_SIZE + BackupCrypto.TAG_SIZE), "a segment appended again")
    }

    @Test fun a_header_or_key_wrap_moved_onto_another_backup_opens_nothing() {
        val a = enc(Random(3).nextBytes(500))
        val b = enc(Random(4).nextBytes(500))
        val ha = 13 + java.nio.ByteBuffer.wrap(a, 9, 4).int
        val hb = 13 + java.nio.ByteBuffer.wrap(b, 9, 4).int
        // A's header (and so its key wrap, which opens with the same passphrase) on B's segments.
        assertTrue(refused(a.copyOf(ha) + b.copyOfRange(hb, b.size), "A's header on B") is BackupIntegrityException)
    }

    @Test fun older_or_unknown_format_versions_are_refused() {
        val ct = enc(ByteArray(10))
        for (v in intArrayOf(0, 2, 255)) {
            val bad = ct.copyOf().also { it[8] = v.toByte() }
            assertTrue(refused(bad, "version $v") is BackupIntegrityException)
        }
        // A key bundle names its version too: one it doesn't know is refused, not read as another.
        val bundle = BackupCrypto.createKeyBundle(PASS, kdf = CHEAP).toBytes()
        try {
            KeyBundle.fromBytes(bundle.copyOf().also { it[8] = 3 })
            fail("unknown bundle version accepted")
        } catch (_: BackupIntegrityException) {
        }
    }

    @Test fun kdf_settings_a_file_chooses_are_held_to_the_caps() {
        val refusedKdfs = listOf(
            KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS - 1), KdfParams.Pbkdf2(0), KdfParams.Pbkdf2(-1),
            KdfParams.Scrypt(9, 8, 1), KdfParams.Scrypt(17, 8, 1), KdfParams.Scrypt(15, 0, 1), KdfParams.Scrypt(15, 8, 0),
            KdfParams.Scrypt(16, 16, 1), KdfParams.Scrypt(15, 8, 5),
        )
        for (k in refusedKdfs) assertTrue("$k accepted", !KdfPolicy.BACKUP.accepts(KdfParams.of(k.alg, k.param)))
        // A packed scrypt value can't overflow into an accepted one.
        assertTrue(!KdfPolicy.BACKUP.accepts(KdfParams.of(KdfParams.KDF_SCRYPT, -1)))
        assertTrue(KdfPolicy.BACKUP.accepts(BackupCrypto.DEFAULT_KDF))
    }

    @Test fun the_wrong_or_an_empty_passphrase_is_a_wrong_key_never_a_crash() {
        val ct = enc(ByteArray(10))
        for (p in listOf("", " ", "correct horse battery stapl", "Correct horse battery staple")) {
            try {
                BackupCrypto.decryptBytes(ct, Unlock.Passphrase(p.toCharArray()))
                fail("opened with '$p'")
            } catch (_: WrongKeyException) {
            }
        }
    }

    @Test fun a_key_bundle_with_a_weak_rsa_key_is_refused() {
        val good = BackupCrypto.createKeyBundle(PASS, kdf = CHEAP)
        val weak = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public.encoded
        val crafted = KeyBundle(weak, good.kdf, good.salt, good.passphraseWrap, good.recoveryWrap, good.recoverySalt).toBytes()
        try {
            KeyBundle.fromBytes(crafted)
            fail("1024-bit key accepted")
        } catch (_: BackupIntegrityException) {
        }
    }

    @Test fun nonces_never_repeat_within_a_backup_or_between_backups() {
        val prefix = ByteArray(BackupCrypto.NONCE_PREFIX_SIZE) { 7 }
        val seen = HashSet<String>()
        for (i in 0L until 20_000L) for (last in listOf(false, true)) {
            assertTrue("segment $i/$last repeats", seen.add(BackupCrypto.segmentNonce(prefix, i, last).joinToString(",")))
        }
        // Each backup has its own data key, salt and nonce prefix, even for the same content and passphrase.
        val a = BackupCrypto.readHeader(ByteArrayInputStream(enc(ByteArray(10))))
        val b = BackupCrypto.readHeader(ByteArrayInputStream(enc(ByteArray(10))))
        assertTrue(!a.noncePrefix.contentEquals(b.noncePrefix) && !a.salt.contentEquals(b.salt))
        // Sealed small records draw a fresh 96-bit nonce each time.
        val key = ByteArray(32)
        val nonces = HashSet<String>()
        repeat(20_000) { assertTrue(nonces.add(app.parley.common.crypto.Aead.seal(key, ByteArray(0)).copyOf(12).joinToString(","))) }
    }

    // ------------------------------------------------------------ the archive inside

    private fun sha(b: ByteArray) = RecordJson.hex(MessageDigest.getInstance("SHA-256").digest(b))

    /** A plain archive holding [entries] in this order, with a manifest listing [listed] (all of them by default). */
    private fun craftedZip(entries: List<Pair<String, ByteArray>>, listed: List<Pair<String, ByteArray>> = entries, format: Int = 1): ByteArray {
        val manifest = Manifest(
            formatVersion = format, createdAt = 0, appVersion = "x",
            entries = listed.map { (n, b) -> ManifestEntry(n, b.size.toLong(), sha(b)) },
        )
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            (entries + (BackupArchive.MANIFEST to RecordJson.json.encodeToString(Manifest.serializer(), manifest).toByteArray())).forEach { (n, b) ->
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        return bo.toByteArray()
    }

    /** [zip] with every occurrence of the name [from] replaced by [to] (the same length). */
    private fun renamed(zip: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val f = from.toByteArray()
        val out = zip.copyOf()
        var i = 0
        while (i <= out.size - f.size) {
            if ((f.indices).all { out[i + it] == f[it] }) to.toByteArray().copyInto(out, i)
            i++
        }
        return out
    }

    private fun archiveRefused(zip: ByteArray, what: String, limits: ArchiveLimits = ArchiveLimits()) {
        try {
            BackupArchiveReader.open(zip, limits).close()
            fail("$what: opened")
        } catch (_: BackupIntegrityException) {
        }
    }

    @Test fun hostile_entry_names_never_reach_a_file_path() {
        val names = listOf(
            "../evil", "/etc/passwd", "vault/../../shared_prefs/x.xml", "vault/..", "vault/", "vault/a/b",
            "x-tunes/../../files/archive/1", "x-tunes/..", "photos/../x.bin", "photos/" + "0".repeat(63) + ".bin",
            "x-tunes\\..\\..\\evil", "contacts.jsonl/", "C:\\evil", "x-" + "a".repeat(65), "vault/" + "a".repeat(129),
        )
        for (n in names) archiveRefused(craftedZip(listOf(n to "x".toByteArray())), "entry '$n'")
    }

    @Test fun duplicate_unlisted_missing_and_altered_entries_are_refused() {
        val s = BackupArchive.SETTINGS to "{}".toByteArray()
        val other = BackupArchive.SETTINGS to "{\"appLock\":\"false\"}".toByteArray()
        // ZipOutputStream won't write a name twice: the second is written under a twin name and renamed in the bytes.
        val twin = craftedZip(listOf(s, "settings_json" to s.second), listed = listOf(s))
        archiveRefused(renamed(twin, "settings_json", BackupArchive.SETTINGS), "a duplicate entry")
        archiveRefused(craftedZip(listOf(s), listed = emptyList()), "an entry the manifest doesn't list")
        archiveRefused(craftedZip(emptyList(), listed = listOf(s)), "a listed entry that is missing")
        archiveRefused(craftedZip(listOf(other), listed = listOf(s)), "an entry changed after the manifest")
        // The same archive with the manifest listing what it holds opens.
        BackupArchiveReader.open(craftedZip(listOf(s))).close()
    }

    @Test fun a_newer_or_nonsense_format_version_is_refused() {
        val s = BackupArchive.SETTINGS to "{}".toByteArray()
        archiveRefused(craftedZip(listOf(s), format = BackupArchive.FORMAT_VERSION + 1), "a newer format")
        archiveRefused(craftedZip(listOf(s), format = 0), "format 0")
    }

    @Test fun a_zip_bomb_is_stopped_before_it_expands() {
        // 64 MB of zeros compress to about 64 kB: far beyond what real contacts and photos expand to.
        val zeros = ByteArray(1 shl 20)
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            z.putNextEntry(ZipEntry(BackupArchive.CONTACTS))
            repeat(64) { z.write(zeros) }
            z.closeEntry()
        }
        val started = System.nanoTime()
        archiveRefused(bo.toByteArray(), "a zip bomb")
        assertTrue("took too long", System.nanoTime() - started < 20_000_000_000L)
    }

    @Test fun a_section_kept_in_memory_is_held_to_its_cap() {
        val big = BackupArchive.SETTINGS to Random(5).nextBytes(4096)
        archiveRefused(craftedZip(listOf(big)), "an oversized settings section", ArchiveLimits(maxInMemoryEntryBytes = 1024))
    }

    @Test fun random_damage_to_an_archive_ends_in_an_exception_never_a_crash() {
        val good = ByteArrayOutputStream().also { out ->
            BackupArchiveWriter(out, ArchiveMeta(1, "7.0")).use { w ->
                w.writeContacts(listOf(Fixtures.contact("k1", "Ana Lima", Fixtures.phone("+447700900123"), Fixtures.photo(Random(6).nextBytes(2000)))))
                w.writeSettings(mapOf("theme" to "dark"))
            }
        }.toByteArray()
        BackupArchiveReader.open(good).close()
        val rnd = Random(7)
        repeat(400) {
            val bad = good.copyOf()
            repeat(1 + rnd.nextInt(4)) { val i = rnd.nextInt(bad.size); bad[i] = rnd.nextInt(256).toByte() }
            try {
                BackupArchiveReader.open(bad).use { r -> r.contacts { it.count() } }
            } catch (_: Exception) {
                // Refused: what a damaged file must do. An Error (out of memory, stack) would fail the test.
            }
        }
    }

    @Test fun a_whole_backup_round_trips_through_the_envelope_and_a_flip_in_it_is_caught() {
        val zip = craftedZip(listOf(BackupArchive.SETTINGS to "{\"theme\":\"dark\"}".toByteArray()))
        val ct = enc(zip)
        val opened = BackupCrypto.decryptBytes(ct, Unlock.Passphrase(PASS))
        assertEquals("dark", BackupArchiveReader.open(opened).settings()!!["theme"])
        refused(ct.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }, "a flip in the sealed archive")
    }

    @Test fun a_crafted_header_cannot_claim_more_key_wraps_than_it_holds() {
        val good = enc(ByteArray(1))
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply {
            write(good, 0, 9); writeInt(30); write(ByteArray(30) { 0x7F })
        }
        try {
            BackupCrypto.readHeader(ByteArrayInputStream(bo.toByteArray()))
            fail("garbage header accepted")
        } catch (_: BackupIntegrityException) {
        }
    }
}
