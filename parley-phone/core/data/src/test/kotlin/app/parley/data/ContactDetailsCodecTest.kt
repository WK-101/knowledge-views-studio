package app.parley.data

import app.parley.common.people.HandleService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier

/**
 * Every [ContactDetails] field goes through both JSON codecs: the editor draft keeps all of them, the vault record
 * all but the device bookkeeping it never needs. A field added to the model without a codec line fails here.
 */
@RunWith(RobolectricTestRunner::class)
class ContactDetailsCodecTest {
    private val full = ContactDetails(
        id = 7, lookupKey = "lk", displayName = "Dr Ada Byron Lovelace Jr", photoUri = "content://p", starred = true, customRingtone = "content://r",
        sendToVoicemail = true, nameId = 1, prefix = "Dr", given = "Ada", middle = "Byron", family = "Lovelace", suffix = "Jr",
        phoneticGiven = "ay-da", phoneticFamily = "luv-lace", phoneticMiddle = "by-ron", namePartsId = 2, secondSurname = "King", generation = "II",
        nicknameId = 3, nickname = "Countess", pronounsId = 4, pronouns = "she/her", orgId = 5, company = "Engine Co", title = "Analyst",
        department = "Notes", officeLocation = "Room 4", jobDescription = "Programs", noteId = 6, note = "First program",
        phones = listOf(DataItem(10, "+44 20 7946 0000", 2, "Lab", true)), emails = listOf(DataItem(11, "ada@example.org", 1, null, false)),
        websites = listOf(DataItem(12, "example.org", 5, null, false)), relations = listOf(DataItem(13, "Charles", 14, "Friend", false)),
        parleyRelations = listOf(DataItem(null, "Mary", 1, null, false)),
        addresses = listOf(PostalItem(14, "1 St", "London", "LDN", "W1", "UK", 1, "Home", "PO 1", "Soho", "parts")),
        events = listOf(EventItem(15, "1815-12-10", 3, "Born", "gregorian")),
        groupIds = setOf(16), rawContacts = listOf(RawContactRef(17, AccountRef("t", "n"))), editRawId = 17, editRawVersion = 18,
        writableRawIds = listOf(17), readOnlyDataIds = setOf(19), handles = listOf(HandleItem(20, HandleService.MATRIX, "@ada:example.org", "x")),
        context = "From the lab", pinnedNote = "Ask about engines", messengerPrefs = "prefs", languageId = 21, language = "en-GB",
        customFields = listOf(CustomFieldItem(22, "Badge", "42", "mime")),
    )

    private val fields = ContactDetails::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }

    @Test fun the_sample_sets_every_field() {
        val empty = ContactDetails()
        val unset = fields.filter { it.get(full) == it.get(empty) }.map { it.name }
        assertEquals("Fields left at their default in the sample", emptyList<String>(), unset)
    }

    @Test fun the_editor_draft_keeps_every_field() {
        assertEquals(full, ContactDraftJson.decode(ContactDraftJson.encode(full)))
    }

    @Test fun the_vault_record_keeps_every_field_but_device_bookkeeping() {
        val back = ContactDetailsJson.decode(ContactDetailsJson.encode(full))
        // Device ids and raw-contact bookkeeping mean nothing for a private contact; the name is composed again.
        val notKept = setOf(
            "id", "lookupKey", "nameId", "namePartsId", "nicknameId", "pronounsId", "orgId", "noteId", "parleyRelations", "groupIds",
            "rawContacts", "editRawId", "editRawVersion", "writableRawIds", "readOnlyDataIds", "languageId",
        )
        val lost = fields.filter { it.name !in notKept && !sameIgnoringIds(it.get(full), it.get(back)) }.map { it.name }
        assertEquals("Fields the vault record drops", emptyList<String>(), lost)
        assertTrue(back.displayName.startsWith("Dr Ada"))
    }

    /** The vault's encoding is stored sealed (and hashed): it must not change by a byte. */
    @Test fun the_vault_record_is_byte_identical() {
        val d = ContactDetails(given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+15551234567", 2, null, true)), sendToVoicemail = true)
        assertEquals(
            """{"prefix":"","given":"Ada","middle":"","family":"Lovelace","suffix":"","pg":"","pf":"","nick":"","company":"","title":"","note":"",""" +
                """"starred":false,"ringtone":"","photo":"","phones":[{"v":"+15551234567","t":2,"l":"","p":true}],"emails":[],"sites":[],"rel":[],""" +
                """"addr":[],"events":[],"vm":true}""",
            ContactDetailsJson.encode(d),
        )
    }

    private fun sameIgnoringIds(a: Any?, b: Any?): Boolean = when {
        a is List<*> && b is List<*> -> a.size == b.size && a.zip(b).all { (x, y) -> stripId(x) == stripId(y) }
        else -> a == b
    }

    private fun stripId(x: Any?): Any? = when (x) {
        is DataItem -> x.copy(id = null)
        is PostalItem -> x.copy(id = null)
        is EventItem -> x.copy(id = null)
        is HandleItem -> x.copy(id = null)
        is CustomFieldItem -> x.copy(id = null, mime = null)
        else -> x
    }
}
