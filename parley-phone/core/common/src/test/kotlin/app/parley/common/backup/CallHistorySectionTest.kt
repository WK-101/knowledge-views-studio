package app.parley.common.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class CallHistorySectionTest {
    private val call = CallLogRecord("+33612345678", 1_600_000_000_000, 42, 2, 1, "acc", null, "Anna")

    private fun archive(withHistory: Boolean): ByteArray {
        val bo = ByteArrayOutputStream()
        BackupArchiveWriter(bo, ArchiveMeta(1, "2.0")).use { w ->
            w.writeCallLog(listOf(call.copy(date = 1_700_000_000_000)))
            if (withHistory) w.writeCallHistory(listOf(CallHistoryLine(call = call), CallHistoryLine(keepForever = "+33612345678")))
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
}
