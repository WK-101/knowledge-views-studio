package app.parley.common.backup

import app.parley.common.backup.Fixtures.contact
import app.parley.common.backup.Fixtures.email
import app.parley.common.backup.Fixtures.phone
import app.parley.common.backup.Fixtures.photo
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.RawRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    private val sharedPhoto = ByteArray(3000) { (it * 7).toByte() }
    private val otherPhoto = ByteArray(500) { (it * 3 + 1).toByte() }

    private val contacts = listOf(
        contact("k1", "Ada Lovelace", phone("+44 20 7946 0000", primary = true), email("ada@example.com"), photo(sharedPhoto), sourceId = "s1"),
        contact("k2", "Alan Turing", phone("0161 496 0000"), photo(sharedPhoto)),
        contact("k3", "Grace Hopper", photo(otherPhoto), DataRow("vnd.com.whatsapp.profile", mapOf("data1" to "123@s.whatsapp.net", "data3" to "Message"))),
        ContactRecord("k4", "No Account", starred = true, customRingtone = "content://r/1", sendToVoicemail = true,
            raws = listOf(RawRecord(null, null, rows = emptyList()), RawRecord("x", "y", "set", "sid", listOf(email("a@b.c"))))),
    )
    private val calls = listOf(
        CallLogRecord("+441234", 1_700_000_000_000, 61, 1, 1, "acc", "comp", "Ada", isNew = false, isRead = true),
        CallLogRecord(null, 1_700_000_100_000, 0, 3, 2, null, null, null, isNew = true, isRead = false),
    )
    private val blocking = BlockingSnapshot(
        rules = listOf(BlockRuleRecord("+1800*", "PREFIX", "REJECT", true, "telemarketers"), BlockRuleRecord("12345", "EXACT", "SILENCE", false)),
        systemBlockedNumbers = listOf("+15550001"),
        blockedCalls = listOf(BlockedCallRecord("+18001234", "rule:1", "REJECT", 1_700_000_000_000)),
    )
    private val vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\nEND:VCARD\r\n".toByteArray()

    private fun BackupArchiveWriter.writeAll(list: List<ContactRecord> = contacts) {
        writeContacts(list)
        writeVcf(vcf)
        writeCallLog(calls)
        writeBlocking(blocking)
        writeSpeedDial(listOf(SpeedDialRecord(3, "+3"), SpeedDialRecord(2, "+2", "Mum")))
        writeNumberSims(listOf(NumberSimRecord("555000111", "sim2")))
        writeSettings(mapOf("theme" to "DARK", "answer" to "SWIPE"))
        writeVault(mapOf("vault.db" to byteArrayOf(9, 9, 9), "key.wrap" to byteArrayOf(1)))
        writeJournal(listOf("{\"op\":\"delete\",\"key\":\"k9\"}", "{\"op\":\"edit\"}"))
    }

    private fun build(createdAt: Long = 1000, list: List<ContactRecord> = contacts): Pair<ByteArray, Manifest> {
        val bo = ByteArrayOutputStream()
        val w = BackupArchiveWriter(bo, ArchiveMeta(createdAt, "1.1.0", mapOf("model" to "Pixel", "sdk" to "35")))
        w.writeAll(list)
        val m = w.finish()
        w.close()
        return bo.toByteArray() to m
    }

    private fun entryNames(zip: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        generateSequence { z.nextEntry }.map { it.name }.toList()
    }

    /** Re-zips [zip], letting [f] replace (or drop, with null) each entry, then appending [extra]. */
    private fun rezip(zip: ByteArray, extra: Map<String, ByteArray> = emptyMap(), f: (String, ByteArray) -> ByteArray?): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { out ->
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val b = f(e.name, z.readBytes()) ?: continue
                    out.putNextEntry(ZipEntry(e.name)); out.write(b); out.closeEntry()
                }
            }
            extra.forEach { (n, b) -> out.putNextEntry(ZipEntry(n)); out.write(b); out.closeEntry() }
        }
        return bo.toByteArray()
    }

    private fun assertRejected(zip: ByteArray, limits: ArchiveLimits = ArchiveLimits(), contains: String? = null) {
        try {
            BackupArchiveReader.open(zip, limits)
            fail("archive should be rejected")
        } catch (e: BackupIntegrityException) {
            if (contains != null) assertTrue(e.message, e.message!!.contains(contains))
        }
    }

    @Test fun roundTripAllSections() {
        val (zip, m) = build()
        val r = BackupArchiveReader.open(zip)
        assertEquals(m, r.manifest)
        assertEquals(contacts, r.contacts { it.toList() })
        assertEquals(calls, r.callLog { it.toList() })
        assertEquals(blocking, r.blocking())
        assertEquals(listOf(SpeedDialRecord(2, "+2", "Mum"), SpeedDialRecord(3, "+3")), r.speedDial())
        assertEquals(listOf(NumberSimRecord("555000111", "sim2")), r.numberSims())
        assertEquals(mapOf("answer" to "SWIPE", "theme" to "DARK"), r.settings())
        assertEquals(setOf("key.wrap", "vault.db"), r.vault().keys)
        assertArrayEquals(byteArrayOf(9, 9, 9), r.vault()["vault.db"])
        assertEquals(2, r.journal { it.count() })
        assertArrayEquals(vcf, r.vcf { it.readBytes() })
        assertEquals(4L, r.contactCount)
        assertEquals("Pixel", r.manifest.device["model"])
        assertEquals(2L, r.manifest.counts[BackupArchive.Counts.CALLS])
        assertEquals(2L, r.manifest.counts[BackupArchive.Counts.BLOCK_RULES])
    }

    @Test fun photosAreStoredOnceAndNotInline() {
        val (zip, m) = build()
        val photos = entryNames(zip).filter { it.startsWith("photos/") }
        assertEquals(2, photos.size)
        assertEquals(2L, m.counts[BackupArchive.Counts.PHOTOS])
        assertTrue("photos/${RecordJson.sha256Hex(sharedPhoto)}.bin" in photos)
        val jsonl = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            generateSequence { z.nextEntry }.first { it.name == BackupArchive.CONTACTS }; z.readBytes().decodeToString()
        }
        assertTrue(jsonl.contains(RecordJson.sha256Hex(sharedPhoto)))
        assertTrue(jsonl.length < sharedPhoto.size) // not inline
        assertEquals(4, jsonl.lines().count { it.isNotEmpty() })
    }

    @Test fun entryOrderIsStableAndManifestLast() {
        val names = entryNames(build().first)
        assertEquals(
            listOf("contacts.jsonl", "contacts.vcf", "calllog.jsonl", "blocking.json", "speeddial.json", "numbersim.json",
                "settings.json", "vault/key.wrap", "vault/vault.db", "journal.jsonl"),
            names.take(10),
        )
        assertEquals("manifest.json", names.last())
        val z = ZipInputStream(ByteArrayInputStream(build().first))
        generateSequence { z.nextEntry }.forEach { assertEquals(java.time.LocalDateTime.of(1980, 1, 1, 0, 0), it.timeLocal) }
    }

    @Test fun deterministicOutputAndContentHash() {
        val (a, ma) = build(createdAt = 1000)
        val (b, _) = build(createdAt = 1000)
        assertArrayEquals(a, b)
        val (_, mc) = build(createdAt = 99999)
        assertEquals(ma.contentHash(), mc.contentHash())
        val (_, md) = build(list = contacts.dropLast(1))
        assertNotEquals(ma.contentHash(), md.contentHash())
        // Hash-only computation matches the real writer without writing anything.
        assertEquals(ma.contentHash(), BackupArchive.contentHash { writeAll() })
    }

    @Test fun emptyArchiveIsValid() {
        val bo = ByteArrayOutputStream()
        BackupArchiveWriter(bo, ArchiveMeta(1, "v")).close()
        val r = BackupArchiveReader.open(bo.toByteArray())
        assertEquals(emptyList<ContactRecord>(), r.contacts { it.toList() })
        assertNull(r.blocking())
        assertNull(r.vcf { it.readBytes() })
        assertTrue(r.manifest.entries.isEmpty())
    }

    @Test fun sectionsMustBeWrittenInOrderOnce() {
        val w = BackupArchiveWriter(ByteArrayOutputStream(), ArchiveMeta(1, "v"))
        w.writeSettings(emptyMap())
        try { w.writeContacts(emptyList()); fail() } catch (_: IllegalStateException) {}
        try { w.writeSettings(emptyMap()); fail() } catch (_: IllegalStateException) {}
        try { w.writeVault(mapOf("../evil" to byteArrayOf())); fail() } catch (_: IllegalArgumentException) {}
        try { w.writeJournal(listOf("a\nb")); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun duplicateContactKeysRejectedOnWrite() {
        val w = BackupArchiveWriter(ByteArrayOutputStream(), ArchiveMeta(1, "v"))
        try { w.writeContacts(listOf(contacts[0], contacts[0])); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun modifiedEntryFailsHashCheck() {
        val (zip, _) = build()
        val bad = rezip(zip) { n, b -> if (n == BackupArchive.SETTINGS) "{\"theme\":\"LIGHT\"}".toByteArray() else b }
        assertRejected(bad, contains = "SHA-256")
        val badPhoto = rezip(zip) { n, b -> if (n.startsWith("photos/")) b.copyOf().also { it[0] = 42 } else b }
        assertRejected(badPhoto, contains = "Photo")
    }

    @Test fun unlistedMissingAndUnknownEntriesAreRejected() {
        val (zip, _) = build()
        assertRejected(rezip(zip, extra = mapOf("vault/extra" to byteArrayOf(1))) { _, b -> b }, contains = "not listed")
        assertRejected(rezip(zip) { n, b -> if (n == BackupArchive.CALLLOG) null else b }, contains = "missing")
        assertRejected(rezip(zip, extra = mapOf("../../etc/passwd" to byteArrayOf(1))) { _, b -> b }, contains = "Unexpected")
        assertRejected(rezip(zip) { n, b -> if (n == BackupArchive.MANIFEST) null else b }, contains = "manifest")
        assertRejected(rezip(zip) { n, b -> if (n == BackupArchive.MANIFEST) "{".toByteArray() else b })
        val newer = rezip(zip) { n, b -> if (n == BackupArchive.MANIFEST) b.decodeToString().replace("\"formatVersion\":1", "\"formatVersion\":2").toByteArray() else b }
        assertRejected(newer, contains = "newer")
        assertRejected("not a zip at all".toByteArray())
    }

    @Test fun manifestCountsMustMatch() {
        val (zip, _) = build()
        // Tamper with both the entry and the manifest hash consistently, but not the count.
        val jsonl = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            generateSequence { z.nextEntry }.first { it.name == BackupArchive.CONTACTS }; z.readBytes()
        }
        val shorter = jsonl.decodeToString().lines().filter { it.isNotEmpty() }.drop(1).joinToString("") { it + "\n" }.toByteArray()
        val bad = rezip(zip) { n, b ->
            when (n) {
                BackupArchive.CONTACTS -> shorter
                BackupArchive.MANIFEST -> b.decodeToString()
                    .replace(RecordJson.sha256Hex(jsonl), RecordJson.sha256Hex(shorter))
                    .replace("\"size\":${jsonl.size}", "\"size\":${shorter.size}").toByteArray()
                else -> b
            }
        }
        assertRejected(bad, contains = "count")
    }

    @Test fun zipBombLimitsAreEnforced() {
        val (zip, _) = build()
        assertRejected(zip, ArchiveLimits(maxEntries = 5), "Too many entries")
        assertRejected(zip, ArchiveLimits(maxEntryBytes = 1000), "size limit")
        assertRejected(zip, ArchiveLimits(maxTotalBytes = 2000), "total size")
        assertRejected(zip, ArchiveLimits(maxInMemoryEntryBytes = 1000), "in-memory")
        // A highly compressible 10 MB entry is caught by counting inflated bytes, not trusting headers.
        val bomb = rezip(zip) { n, b -> if (n == BackupArchive.VCF) ByteArray(10 shl 20) else b }
        assertRejected(bomb, ArchiveLimits(maxEntryBytes = 1 shl 20), "size limit")
    }

    @Test fun lazyStreamsAreReverifiedOnSecondPass() {
        val (zip, _) = build()
        var first = true
        val tampered = rezip(zip) { n, b -> if (n == BackupArchive.CALLLOG) b.copyOf().also { it[it.size - 3] = 'x'.code.toByte() } else b }
        val r = BackupArchiveReader.open({ ByteArrayInputStream(if (first) zip.also { first = false } else tampered) })
        try {
            r.callLog { it.count() }
            fail("second pass must re-verify")
        } catch (_: BackupIntegrityException) {
        } catch (_: IllegalArgumentException) {
            // kotlinx may fail to parse the tampered line first; either way nothing silently passes.
        }
    }

    @Test fun encryptedPipelineRoundTrip() {
        val pass = "pw".toCharArray()
        val bo = ByteArrayOutputStream()
        val enc = BackupCrypto.encrypt(bo, listOf(Recipient.Passphrase(pass)), BackupCrypto.MIN_ITERATIONS)
        BackupArchiveWriter(enc, ArchiveMeta(5, "1.1")).use { it.writeAll() }
        val file = bo.toByteArray()
        val key = BackupCrypto.unwrapDataKey(BackupCrypto.readHeader(ByteArrayInputStream(file)), Unlock.Passphrase(pass))
        val r = BackupArchiveReader.open({ BackupCrypto.decrypt(ByteArrayInputStream(file), key) })
        assertEquals(contacts, r.contacts { it.toList() })
        assertEquals(blocking, r.blocking())
        // Truncated ciphertext fails during verification.
        val cut = file.copyOf(file.size - 20)
        try {
            BackupArchiveReader.open({ BackupCrypto.decrypt(ByteArrayInputStream(cut), key) })
            fail()
        } catch (_: java.io.IOException) {
        }
    }

    @Test fun recordJsonIsCanonical() {
        val a = contacts[0]
        val reordered = a.copy(raws = a.raws.map { r -> r.copy(rows = r.rows.map { it.copy(values = it.values.entries.reversed().associate { e -> e.key to e.value }) }) })
        assertEquals(RecordJson.encode(a), RecordJson.encode(reordered))
        val decoded = RecordJson.decode(RecordJson.encode(a)) { if (it == RecordJson.sha256Hex(sharedPhoto)) sharedPhoto else null }
        assertEquals(a, decoded)
        assertTrue(decoded.raws[0].rows.first { it.mimeType == "vnd.android.cursor.item/phone_v2" }.values.containsKey("data3"))
        try {
            RecordJson.decode(RecordJson.encode(a)) { null }
            fail()
        } catch (_: BackupIntegrityException) {}
    }
}
