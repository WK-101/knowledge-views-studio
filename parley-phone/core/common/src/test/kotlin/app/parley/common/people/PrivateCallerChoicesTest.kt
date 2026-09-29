package app.parley.common.people

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCallerChoicesTest {
    private fun group(id: Long, title: String?) =
        DataRow(Mime.GROUP, buildMap { put(Col.D1, id.toString()); title?.let { put(Col.GROUP_TITLE, it) } })

    private val record = ContactRecord(
        key = "k", displayName = "Ada", starred = true, customRingtone = "content://tone/ada", sendToVoicemail = true,
        raws = listOf(
            RawRecord("com.google", "a@example.com", rows = listOf(group(3, "Family"), group(1, null), DataRow(Mime.PHONE, mapOf(Col.D1 to "1")))),
            RawRecord(null, null, rows = listOf(group(9, " Family "), group(4, "Doctors"))),
        ),
    )

    @Test fun an_entry_moved_in_before_keeps_the_records_star_tone_voicemail_and_labels() {
        val s = PrivateCallerChoices.seed(starred = false, ringtone = null, sendToVoicemail = false, record = record)
        assertTrue(s.starred)
        assertEquals("content://tone/ada", s.ringtone)
        assertTrue(s.sendToVoicemail)
        assertEquals(listOf(PrivateLabels.Membership(3, "Family"), PrivateLabels.Membership(4, "Doctors")), s.labels)
    }

    @Test fun the_sealed_details_win_for_the_tone_and_add_to_the_star() {
        val s = PrivateCallerChoices.seed(starred = true, ringtone = "content://tone/new", sendToVoicemail = false, record = null)
        assertTrue(s.starred)
        assertEquals("content://tone/new", s.ringtone)
        assertFalse(s.sendToVoicemail)
        assertTrue(s.labels.isEmpty())
    }

    @Test fun nothing_to_seed_for_an_entry_made_in_parley() {
        val s = PrivateCallerChoices.seed(starred = false, ringtone = " ", sendToVoicemail = false, record = null)
        assertFalse(s.starred)
        assertNull(s.ringtone)
        assertTrue(PrivateCallerChoices.labelsOf(null).isEmpty())
    }

    @Test fun a_new_date_keeps_a_temporary_contacts_history_choice() {
        assertNull(TemporaryChoice.purgeOnNewDate(alreadyTemporary = true))
        assertEquals(true, TemporaryChoice.purgeOnNewDate(alreadyTemporary = false))
    }
}
