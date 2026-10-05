package app.parley.common.people

import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Script detection, the native-name row, languages as a list, citizenship, and search by Latin spelling. */
class NativeNamesTest {
    @Test fun scripts_suggest_a_language() {
        assertEquals("ru", Scripts.suggestLanguage("Иван Петров"))
        assertEquals("uk", Scripts.suggestLanguage("Олена Ковальчук-Їжак"))
        assertEquals("el", Scripts.suggestLanguage("Γιώργος Παπαδόπουλος"))
        assertEquals("ar", Scripts.suggestLanguage("محمد علي"))
        assertEquals("fa", Scripts.suggestLanguage("پریسا"))
        assertEquals("he", Scripts.suggestLanguage("דוד לוי"))
        assertEquals("zh", Scripts.suggestLanguage("王伟"))
        assertEquals("ja", Scripts.suggestLanguage("山田たろう"))
        assertEquals("ko", Scripts.suggestLanguage("김민준"))
        assertEquals("hi", Scripts.suggestLanguage("राहुल"))
        assertNull(Scripts.suggestLanguage("José Álvarez"))
        assertNull(Scripts.suggestLanguage("12345"))
    }

    @Test fun accented_latin_needs_no_spelling_other_scripts_do() {
        assertFalse(Scripts.hasNonLatin("José Ñúñez Łódź Ångström Nguyễn"))
        assertFalse(Scripts.isNonLatin("Ana"))
        assertTrue(Scripts.hasNonLatin("Ana Иванова"))
        assertTrue(Scripts.isNonLatin("Иван Petrov Петров"))
        assertTrue(Scripts.hasNonLatin("王伟"))
    }

    @Test fun the_row_label_names_the_language_and_reads_back() {
        assertEquals("Name in Russian", NativeNames.label("ru"))
        assertEquals("Name in their language", NativeNames.label(""))
        assertEquals("ru", NativeNames.languageOfLabel("Name in Russian"))
        assertEquals("zh", NativeNames.languageOfLabel("name in chinese"))
        assertEquals("", NativeNames.languageOfLabel("Name in their language"))
        assertTrue(NativeNames.isRow("0", "Name in Greek"))
        assertFalse(NativeNames.isRow("1", "Name in Greek"))
        assertFalse(NativeNames.isRow("0", "Maiden name"))
    }

    @Test fun row_values_and_back() {
        val n = NativeName("Иван Петров", "Иван", "Петров", "Russian")
        val v = NativeNames.rowValues(n)
        assertEquals("0", v[Col.D2])
        assertEquals("Name in Russian", v[Col.D3])
        assertEquals("ru", v[NativeNames.LANGUAGE_COLUMN])
        assertEquals(n.copy(language = "ru"), NativeNames.fromRow { v[it] })
        // A sync that kept only name and label still knows the language.
        assertEquals("ru", NativeNames.fromRow { mapOf(Col.D1 to "Иван", Col.D3 to "Name in Russian")[it] }.language)
    }

    @Test fun parts_compose_in_the_languages_order() {
        assertEquals("王伟", NativeName(given = "伟", family = "王", language = "zh").shown)
        assertEquals("김 민준", NativeName(given = "민준", family = "김", language = "ko").shown)
        assertEquals("Иван Петров", NativeName(given = "Иван", family = "Петров", language = "ru").shown)
        assertEquals("Ivan", NativeName(full = "Ivan", given = "x").shown)
    }

    @Test fun languages_are_a_list_typed_in_one_field() {
        assertEquals(listOf("Russian", "English"), Languages.split("Russian, English, russian,"))
        assertEquals(listOf("ru", "en"), Languages.toStoredList(listOf("Russian", "en", "RU"), Locale.ENGLISH))
        assertEquals("Russian, English", Languages.displayList(listOf("ru", "en"), Locale.ENGLISH))
        assertEquals("Russian, English", Languages.join(listOf("Russian", " ", "English")))
    }

    @Test fun citizenship_is_kept_as_iso_codes() {
        assertEquals("PT", Citizenship.toCode("pt"))
        assertEquals("PT", Citizenship.toCode("Portugal"))
        assertEquals("DE", Citizenship.toCode("Deutschland"))
        assertNull(Citizenship.toCode("Atlantis"))
        assertEquals(listOf("PT", "BR"), Citizenship.codes(listOf("Portugal", "BR", "PT")))
        assertEquals("Portugal", Citizenship.display("PT", Locale.ENGLISH))
        assertEquals("Portugal, Brazil", Citizenship.displayList(listOf("PT", "BR"), Locale.ENGLISH))
    }

    /** A stand-in for Android's ICU transliterator (core/data tests the real one). */
    private val latin = Latinizer { text ->
        mapOf("Иван Петров" to "Ivan Petrov", "Иван" to "Ivan", "王伟" to "wang wei", "Γιώργος" to "Giorgos", "محمد" to "mhmd")[text]
    }

    @Test fun names_in_other_scripts_are_found_by_their_latin_spelling() {
        val doc = ContactSearch.Builder(1, latin = latin, languages = listOf(Locale.ENGLISH)).apply {
            name("Иван Петров")
        }.build()
        assertEquals(ContactSearch.Field.NAME, ContactSearch.match("ivan", doc))
        assertEquals(ContactSearch.Field.NAME, ContactSearch.match("Иван", doc))
        assertEquals(ContactSearch.Field.NAME, ContactSearch.match("petrov", doc))
        assertNull(ContactSearch.match("maria", doc))
    }

    @Test fun the_native_name_row_is_searched_and_named_by_its_field() {
        val values = NativeNames.rowValues(NativeName("王伟", language = "zh"))
        val doc = ContactSearch.Builder(2, latin = latin, languages = listOf(Locale.ENGLISH)).apply {
            name("Wang Wei")
            row(Mime.NICKNAME) { values[it] }
        }.build()
        assertEquals(ContactSearch.Field.NATIVE_NAME, ContactSearch.match("王", doc))
        assertEquals(ContactSearch.Field.NAME, ContactSearch.match("wang", doc))
        // Its row isn't a nickname.
        assertNull(ContactSearch.match("name in chinese", doc))
    }

    @Test fun greek_arabic_and_accented_latin() {
        val doc = ContactSearch.Builder(3, latin = latin, languages = listOf(Locale.ENGLISH)).apply {
            name("Γιώργος")
            nickname("محمد")
            name("José Álvarez")
        }.build()
        assertEquals(ContactSearch.Field.NAME, ContactSearch.match("giorgos", doc))
        assertEquals(ContactSearch.Field.NAME, ContactSearch.match("γιωργος", doc))
        assertEquals(ContactSearch.Field.NICKNAME, ContactSearch.match("mhmd", doc))
        assertEquals(ContactSearch.Field.NAME, ContactSearch.match("jose alvarez", doc))
    }

    @Test fun citizenship_is_searched_and_filtered() {
        val doc = ContactSearch.Builder(4, languages = listOf(Locale.ENGLISH)).apply {
            name("Ana")
            row(Mime.CITIZENSHIP) { mapOf(Col.D1 to "PT")[it] }
            row(Mime.CITIZENSHIP) { mapOf(Col.D1 to "BR")[it] }
            row(Mime.LANGUAGE) { mapOf(Col.D1 to "pt")[it] }
            row(Mime.LANGUAGE) { mapOf(Col.D1 to "en")[it] }
        }.build()
        assertEquals(ContactSearch.Field.CITIZENSHIP, ContactSearch.match("brazil", doc))
        val pt = FieldFilter().toggle(Facet.CITIZENSHIP, ContactFacets.key("Portugal"))
        assertTrue(pt.matches(doc.facets, photo = false, temporary = false))
        assertFalse(FieldFilter().toggle(Facet.CITIZENSHIP, ContactFacets.key("Spain")).matches(doc.facets, false, false))
        // Citizenship is its own filter: it doesn't count as where they live.
        assertFalse(FieldFilter().toggle(Facet.COUNTRY, ContactFacets.key("Portugal")).matches(doc.facets, false, false))
        // "Speaks English" finds a second language too.
        assertTrue(FieldFilter().toggle(Facet.LANGUAGE, ContactFacets.key("English")).matches(doc.facets, false, false))
    }

    @Test fun editor_offers_the_new_kinds_only_as_add_chips() {
        val choices = EditorForm.addChoices(emptySet())
        assertTrue(EditorForm.Kind.NATIVE_NAME in choices)
        assertTrue(EditorForm.Kind.CITIZENSHIP in choices)
        assertFalse(EditorForm.Kind.CITIZENSHIP in EditorForm.meCardKinds)
    }
}
