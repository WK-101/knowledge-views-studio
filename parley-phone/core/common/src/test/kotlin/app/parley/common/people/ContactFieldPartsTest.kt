package app.parley.common.people

import app.parley.common.record.Mime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts behind custom fields, languages, RFC 9554 address parts and the phone type menu. */
class ContactFieldPartsTest {
    @Test fun custom_fields_take_googles_kind_only_in_a_google_account() {
        assertEquals(Mime.GOOGLE_CUSTOM_FIELD, CustomFields.mimeFor("com.google", "Shoe size", "38"))
        assertEquals(Mime.CUSTOM_FIELD, CustomFields.mimeFor(null, "Shoe size", "38"))
        assertEquals(Mime.CUSTOM_FIELD, CustomFields.mimeFor("at.bitfire.davdroid", "Shoe size", "38"))
        assertEquals(Mime.CUSTOM_FIELD, CustomFields.mimeIn(Mime.GOOGLE_CUSTOM_FIELD, "vnd.sec.contact.phone", "Shoe size", "38"))
        assertEquals(Mime.GOOGLE_CUSTOM_FIELD, CustomFields.mimeIn(Mime.CUSTOM_FIELD, "com.google", "Shoe size", "38"))
        assertEquals(Mime.PHONE, CustomFields.mimeIn(Mime.PHONE, "com.google", null, null))
        // Google's field needs a label and a value: half a field stays Parley's, even in a Google account.
        assertEquals(Mime.CUSTOM_FIELD, CustomFields.mimeFor("com.google", "", "38"))
        assertEquals(Mime.CUSTOM_FIELD, CustomFields.mimeFor("com.google", "Locker", " "))
        assertEquals(Mime.CUSTOM_FIELD, CustomFields.mimeIn(Mime.CUSTOM_FIELD, "com.google", null, "38"))
        assertEquals("Shoe size: 38", CustomFields.display(" Shoe size ", "38"))
        assertEquals("38", CustomFields.display("", "38"))
    }

    @Test fun address_parts_encode_in_rfc_order_and_come_back() {
        val parts = mapOf(AddressParts.Part.BUILDING to "B", AddressParts.Part.FLOOR to "3", AddressParts.Part.LANDMARK to "By the\npark")
        val stored = AddressParts.encode(parts)!!
        assertEquals("floor=3\nbuilding=B\nlandmark=By the\\npark", stored)
        assertEquals(parts, AddressParts.decode(stored))
        val components = AddressParts.toComponents(stored)
        assertEquals(11, components.size)
        assertEquals(listOf("", "", "3", "", "", "B"), components.take(6))
        assertEquals(stored, AddressParts.fromComponents(components))
        assertNull(AddressParts.encode(emptyMap()))
        assertNull(AddressParts.fromComponents(List(11) { "" }))
        assertEquals(emptyMap<AddressParts.Part, String>(), AddressParts.decode("not=a part\nrandom"))
    }

    @Test fun languages_are_stored_as_tags_and_shown_by_name() {
        assertEquals("es", Languages.toStored("Spanish", Locale.ENGLISH))
        assertEquals("es", Languages.toStored("español", Locale.ENGLISH))
        assertEquals("de", Languages.toStored("Deutsch", Locale.GERMAN))
        assertEquals("pt-BR", Languages.toStored("PT-br", Locale.ENGLISH))
        assertEquals("es", Languages.toStored(" es ", Locale.ENGLISH))
        assertEquals("Klingonish", Languages.toStored("Klingonish", Locale.ENGLISH))
        assertEquals("", Languages.toStored("  ", Locale.ENGLISH))
        assertEquals("Spanish", Languages.display("es", Locale.ENGLISH))
        assertEquals("Portuguese (Brazil)", Languages.display("pt-BR", Locale.ENGLISH))
        assertEquals("Klingonish", Languages.display("Klingonish", Locale.ENGLISH))
    }

    @Test fun every_android_phone_type_is_offered_once_with_the_common_six_first() {
        assertEquals((1..20).toSet(), PhoneTypes.all.toSet())
        assertEquals(20, PhoneTypes.all.size)
        assertEquals(listOf(2, 1, 3, 12, 4, 7), PhoneTypes.common)
        assertTrue(PhoneTypes.more.none { it in PhoneTypes.common })
    }

    @Test fun custom_fields_and_language_are_add_chips() {
        val shown = setOf(EditorForm.Kind.PHONE)
        val chips = EditorForm.addChoices(shown)
        assertTrue(EditorForm.Kind.CUSTOM_FIELD in chips)
        assertTrue(EditorForm.Kind.LANGUAGE in chips)
        // A custom field adds another row; one still empty waits to be filled first.
        assertTrue(EditorForm.Kind.CUSTOM_FIELD in EditorForm.addChoices(shown + EditorForm.Kind.CUSTOM_FIELD))
        assertTrue(EditorForm.Kind.CUSTOM_FIELD !in EditorForm.addChoices(shown + EditorForm.Kind.CUSTOM_FIELD, setOf(EditorForm.Kind.CUSTOM_FIELD)))
        assertTrue(EditorForm.Kind.LANGUAGE !in EditorForm.addChoices(shown + EditorForm.Kind.LANGUAGE))
    }

    @Test fun custom_fields_are_found_by_search() {
        val extra = BroadSearch.Extra(custom = listOf("Shoe size: 38", "Locker: A12"))
        assertEquals(BroadSearch.Field.CUSTOM, BroadSearch.match("locker", "Ana", emptyList(), emptyList(), extra))
        assertEquals(BroadSearch.Field.CUSTOM, BroadSearch.match("A12", "Ana", emptyList(), emptyList(), extra))
    }
}
