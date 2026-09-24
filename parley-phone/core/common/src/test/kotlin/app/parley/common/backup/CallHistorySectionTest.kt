package app.parley.common.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CallHistorySectionTest {
    private val call = CallLogRecord("+33612345678", 1_600_000_000_000, 42, 2, 1, "acc", null, "Anna")

    private fun archive(withHistory: Boolean, history: List<CallHistoryLine> = listOf(CallHistoryLine(call = call), CallHistoryLine(keepForever = "+33612345678"))): ByteArray {
        val bo = ByteArrayOutputStream()
        BackupArchiveWriter(bo, ArchiveMeta(1, "2.0")).use { w ->
            w.writeCallLog(listOf(call.copy(date = 1_700_000_000_000)))
            if (withHistory) w.writeCallHistory(history)
            w.writeSettings(mapOf("a" to "b:true"))
        }
        return bo.toByteArray()
    }

    @Test fun roundtrip() {
        val r = BackupArchiveReader.open(archive(true))
        assertEquals(2L, r.manifest.counts[BackupArchive.Counts.ARCHIVED_CALLS])
        val lines = r.callHistory { it.toList() }!!
        assertEquals(call, lines[0].call)
        assertEquals("+33612345678", lines[1].keepForever)
        assertEquals(1, r.callLog { it.count() })
    }

    @Test fun older_backups_without_the_section_still_open() {
        val r = BackupArchiveReader.open(archive(false))
        assertNull(r.callHistory { it.toList() })
        assertEquals(1, r.callLog { it.count() })
    }

    @Test fun empty_history_writes_no_entry() {
        // Regression: an always-present callhistory.jsonl made every new backup unreadable for the previous version.
        val bytes = archive(true, history = emptyList())
        val r = BackupArchiveReader.open(bytes)
        assertFalse(r.has(BackupArchive.CALLHISTORY))
        assertNull(r.manifest.counts[BackupArchive.Counts.ARCHIVED_CALLS])
        assertFalse(entryNames(bytes).contains(BackupArchive.CALLHISTORY))
        // Same bytes as a writer that never called writeCallHistory.
        assertEquals(entryNames(archive(false)), entryNames(bytes))
    }

    @Test fun optional_sections_from_newer_versions_are_verified_but_ignored() {
        val listed = withExtra(archive(false), "x-future.json", "{}".toByteArray(), listInManifest = true)
        val r = BackupArchiveReader.open(listed)
        assertEquals(1, r.callLog { it.count() })
        assertTrue(r.has("x-future.json"))
        // Still integrity-checked: an optional entry the manifest doesn't list is rejected.
        try {
            BackupArchiveReader.open(withExtra(archive(false), "x-future.json", "{}".toByteArray(), listInManifest = false))
            fail()
        } catch (e: BackupIntegrityException) {
            assertTrue(e.message!!.contains("not listed"))
        }
        // Unknown names that aren't optional are still rejected, as are path tricks.
        for (bad in listOf("future.json", "x-../../etc", "x-a/../b")) {
            try {
                BackupArchiveReader.open(withExtra(archive(false), bad, byteArrayOf(1), listInManifest = true))
                fail(bad)
            } catch (_: BackupIntegrityException) {
            }
        }
    }

    private fun entryNames(zip: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }

    private fun withExtra(zip: ByteArray, name: String, body: ByteArray, listInManifest: Boolean): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.forEach { entries[it.name] = z.readBytes() } }
        val manifest = RecordJson.json.decodeFromString(Manifest.serializer(), entries.remove(BackupArchive.MANIFEST)!!.decodeToString())
        val m = if (listInManifest) manifest.copy(entries = manifest.entries + ManifestEntry(name, body.size.toLong(), RecordJson.sha256Hex(body))) else manifest
        entries[name] = body
        entries[BackupArchive.MANIFEST] = RecordJson.json.encodeToString(Manifest.serializer(), m).toByteArray()
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { out ->
            entries.forEach { (n, b) ->
                out.putNextEntry(ZipEntry(n))
                out.write(b)
                out.closeEntry()
            }
        }
        return bo.toByteArray()
    }
}
