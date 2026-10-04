package app.parley.common.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Optional file folders (generated ringtones): written after every section, read back verified, ignored when unknown. */
class BackupArchiveFilesTest {
    private val tune = ByteArray(4000) { (it * 13).toByte() }
    private val other = ByteArray(10) { 7 }

    private fun archive(files: Map<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        BackupArchiveWriter(bo, ArchiveMeta(1000, "test")).use { w ->
            w.writeSettings(mapOf("theme" to "DARK"))
            w.writeFiles("tunes", files)
        }
        return bo.toByteArray()
    }

    @Test fun files_come_back_by_name_and_count() {
        val reader = BackupArchiveReader.open(archive(mapOf("parley-tune-0011223344556677.wav" to tune, "parley-tune-aabbccdd.wav" to other)))
        val back = LinkedHashMap<String, ByteArray>()
        reader.files("tunes") { name, bytes -> back[name] = bytes }
        assertEquals(setOf("parley-tune-0011223344556677.wav", "parley-tune-aabbccdd.wav"), back.keys)
        assertArrayEquals(tune, back["parley-tune-0011223344556677.wav"])
        assertEquals(2L, reader.manifest.counts["files.tunes"])
        assertEquals("DARK", reader.settings()?.get("theme"))
        // Another folder, or a backup made before folders existed, has none.
        var none = 0
        reader.files("other") { _, _ -> none++ }
        assertEquals(0, none)
    }

    @Test fun the_same_files_make_the_same_archive() {
        assertArrayEquals(archive(mapOf("b.wav" to other, "a.wav" to tune)), archive(mapOf("a.wav" to tune, "b.wav" to other)))
    }

    @Test fun a_name_that_could_leave_the_folder_is_refused() {
        for (bad in listOf("../a.wav", "a/b.wav", "", "a b.wav")) {
            try {
                archive(mapOf(bad to tune))
                fail("accepted '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun a_changed_file_is_caught() {
        val good = archive(mapOf("a.wav" to tune))
        // Rewrite the archive with the file's bytes changed but the manifest kept.
        val bo = ByteArrayOutputStream()
        val zin = ZipInputStream(good.inputStream())
        ZipOutputStream(bo).use { out ->
            for (e in generateSequence { zin.nextEntry }) {
                var bytes = zin.readBytes()
                if (e.name == "x-tunes/a.wav") bytes = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
                out.putNextEntry(ZipEntry(e.name)); out.write(bytes); out.closeEntry()
            }
        }
        try {
            BackupArchiveReader.open(bo.toByteArray())
            fail("opened a tampered archive")
        } catch (e: BackupIntegrityException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("x-tunes/a.wav"))
        }
    }
}
