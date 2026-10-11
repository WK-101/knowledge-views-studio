package app.parley.data

import app.parley.common.people.HandleService
import app.parley.common.people.NativeName
import app.parley.common.vcard.VCardStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier

/**
 * Every [ContactDetails] field goes through every codec: the editor draft keeps all of them, the vault record all but
 * the device bookkeeping it never needs, and the record and vCard engine all a card carries. A field added to the
 * model without a codec line fails here.
 */
@RunWith(RobolectricTestRunner::class)
class ContactDetailsCodecTest {
    // Labels go with the custom type (0), as the editor writes them: a card keeps a label only there.
    private val full = ContactDetails(
        id = 7, lookupKey = "lk", displayName = "Dr Ada Byron Lovelace Jr", photoUri = "content://p", starred = true, customRingtone = "content://r",
        sendToVoicemail = true, nameId = 1, prefix = "Dr", given = "Ada", middle = "Byron", family = "Lovelace", suffix = "Jr",
        phoneticGiven = "ay-da", phoneticFamily = "luv-lace", phoneticMiddle = "by-ron", namePartsId = 2, secondSurname = "King", generation = "II",
        nicknameId = 3, nickname = "Countess", pronounsId = 4, pronouns = "she/her", orgId = 5, company = "Engine Co", title = "Analyst",
        department = "Notes", officeLocation = "Room 4", jobDescription = "Programs", noteId = 6, note = "First program",
        phones = listOf(DataItem(10, "+44 20 7946 0000", 0, "Lab", true)), emails = listOf(DataItem(11, "ada@example.org", 1, null, false)),
        websites = listOf(DataItem(12, "example.org", 5, null, false)), relations = listOf(DataItem(13, "Charles", 0, "Friend", false)),
        parleyRelations = listOf(DataItem(null, "Mary", 1, null, false)),
        addresses = listOf(PostalItem(14, "1 St", "London", "LDN", "W1", "UK", 0, "Studio", "PO 1", "Soho", "parts")),
        events = listOf(EventItem(15, "1815-12-10", 0, "Christened", "gregorian")),
        groupIds = setOf(16), rawContacts = listOf(RawContactRef(17, AccountRef("t", "n"))), editRawId = 17, editRawVersion = 18,
        writableRawIds = listOf(17), readOnlyDataIds = setOf(19), handles = listOf(
            HandleItem(20, HandleService.MATRIX, "@ada:example.org"), HandleItem(26, HandleService.OTHER, "ada-jami", "Jami"),
        ),
        context = "From the lab", pinnedNote = "Ask about engines", messengerPrefs = "prefs", languageIds = listOf(21, 25), languagePrimaryId = 21,
        languages = listOf("en-GB", "it"), customFields = listOf(CustomFieldItem(22, "Badge", "42", "mime")),
        nativeNameId = 23, nativeName = NativeName("Ада Лавлейс", "Ада", "Лавлейс", "ru"), citizenshipIds = listOf(24), citizenships = listOf("GB", "IT"),
    )

    /** Device ids and raw-contact bookkeeping, which mean nothing outside this phone's address book. */
    private val deviceBookkeeping = setOf(
        "id", "lookupKey", "nameId", "namePartsId", "nicknameId", "pronounsId", "orgId", "noteId", "parleyRelations", "groupIds",
        "rawContacts", "editRawId", "editRawVersion", "writableRawIds", "readOnlyDataIds", "languageIds", "languagePrimaryId",
        "nativeNameId", "citizenshipIds",
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
        val lost = fields.filter { it.name !in deviceBookkeeping && !sameIgnoringIds(it.get(full), it.get(back)) }.map { it.name }
        assertEquals("Fields the vault record drops", emptyList<String>(), lost)
        assertTrue(back.displayName.startsWith("Dr Ada"))
    }

    @Test fun the_draft_is_generated_from_the_model_and_leaves_defaults_out() {
        assertEquals("""{"given":"Ada"}""", ContactDraftJson.encode(ContactDetails(given = "Ada")))
        assertEquals(ContactDetails(given = "Ada"), ContactDraftJson.decode("""{"given":"Ada","notAFieldAnyMore":1}"""))
    }

    @Test fun a_shared_or_exported_card_keeps_every_field_a_card_carries() {
        // The record the vCard engine writes, then the card itself, read back into an editor draft.
        val record = RecordDetails.toRecord(full, "k")
        val viaRecord = RecordDetails.toDetails(record)
        val viaCard = RecordDetails.toDetails(VCardStream.readAll(VCardStream.writeAll(listOf(record))).first.single())
        // Besides device bookkeeping, a card never carries what belongs to this phone or to Parley's private record:
        // the ringtone and photo address (files of this phone), "send to voicemail", the private "who is this" line,
        // the note for calls, messenger preferences and Parley-only relations. The display name is composed again.
        val notOnACard = deviceBookkeeping + setOf(
            "displayName", "photoUri", "customRingtone", "sendToVoicemail", "context", "pinnedNote", "messengerPrefs",
        )
        for ((how, back) in listOf("record" to viaRecord, "vCard" to viaCard)) {
            val lost = fields.filter { it.name !in notOnACard && !sameIgnoringIds(it.get(full), it.get(back)) }.map { it.name }
            assertEquals("Fields lost through the $how", emptyList<String>(), lost)
        }
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
