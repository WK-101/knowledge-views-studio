package app.parley.data.backup

import app.parley.common.backup.ArchiveOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A backup whose signature can't vouch for it starts with no part ticked; one you made starts with every data part. */
class RestoreOptionsTest {
    @Test fun a_backup_you_made_starts_with_every_data_part() {
        for (origin in listOf(ArchiveOrigin.THIS_PHONE, ArchiveOrigin.OTHER_PHONE)) {
            val o = RestoreOptions.forOrigin(origin)
            assertEquals(RestoreOptions(), o)
            assertTrue(o.contacts && o.callLog && o.blocking && o.speedDial && o.vault)
            assertFalse("settings are never ticked to begin with", o.settings)
        }
    }

    @Test fun a_suspicious_backup_starts_with_nothing_ticked() {
        val suspicious = ArchiveOrigin.entries - setOf(ArchiveOrigin.THIS_PHONE, ArchiveOrigin.OTHER_PHONE)
        assertEquals(4, suspicious.size)
        for (origin in suspicious) {
            val o = RestoreOptions.forOrigin(origin)
            assertFalse("$origin", o.anyPart)
            // The user can still tick a part knowingly.
            assertTrue(o.copy(contacts = true).anyPart)
        }
    }
}
