package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactFiltersTest {
    private fun doc(id: Long, build: ContactSearch.Builder.() -> Unit) = ContactSearch.Builder(id, "PT").apply(build).build()

    private val ana = doc(1) {
        name("Ana")
        number("+351 912 345 678")
        email("ana@x.org")
        address("Rua Augusta", null, null, "Lisboa", "Lisboa", null, "PT")
        work("Acme", "Engineer")
        relation("Bruno", 13, null)
        event("1990-05-14", ContactSearch.TYPE_BIRTHDAY, null)
        language("pt")
        custom("Shoe size", "38")
    }
    private val ben = doc(2) {
        name("Ben")
        address(null, null, null, "Madrid", null, null, "Spain")
        work("acme ", null)
        event("--11-02", ContactSearch.TYPE_BIRTHDAY, null)
    }
    private val nameless = doc(3) { email("someone@x.org") }

    private fun f(vararg picks: Pair<Facet, String>) = picks.fold(FieldFilter()) { acc, (facet, key) -> acc.toggle(facet, key) }

    private fun FieldFilter.passes(d: ContactSearch.Doc, photo: Boolean = false, temporary: Boolean = false) = matches(d.facets, photo, temporary)

    @Test fun facets_come_from_the_fields() {
        assertEquals(setOf("Portugal"), ana.facets.values[Facet.COUNTRY]!!.values.toSet())
        assertEquals(setOf("Lisboa"), ana.facets.values[Facet.PLACE]!!.values.toSet())
        assertEquals(setOf("Acme"), ana.facets.values[Facet.COMPANY]!!.values.toSet())
        assertEquals(setOf("Sister"), ana.facets.values[Facet.RELATION]!!.values.toSet())
        assertEquals(setOf("Portuguese"), ana.facets.values[Facet.LANGUAGE]!!.values.toSet())
        assertEquals(setOf("Shoe size"), ana.facets.values[Facet.CUSTOM_LABEL]!!.values.toSet())
        assertEquals(setOf("5"), ana.facets.values[Facet.BIRTHDAY_MONTH]!!.keys)
        assertTrue(ana.facets.has(ContactFacets.HAS_EMAIL))
        assertTrue(ana.facets.has(ContactFacets.HAS_ADDRESS))
        assertTrue(ana.facets.has(ContactFacets.HAS_BIRTHDAY))
        assertTrue(ana.facets.has(ContactFacets.HAS_NUMBER))
        assertFalse(ben.facets.has(ContactFacets.HAS_EMAIL))
    }

    @Test fun or_within_a_facet_and_across_facets() {
        val iberia = f(Facet.COUNTRY to ContactFacets.key("Portugal"), Facet.COUNTRY to ContactFacets.key("Spain"))
        assertTrue(iberia.passes(ana))
        assertTrue(iberia.passes(ben))
        assertFalse(iberia.passes(nameless))

        val iberiaWithEmail = iberia.toggle(Facet.HAS, ContactFacets.HAS_EMAIL)
        assertTrue(iberiaWithEmail.passes(ana))
        assertFalse(iberiaWithEmail.passes(ben))

        val acmeInSpain = f(Facet.COMPANY to ContactFacets.key("ACME"), Facet.COUNTRY to ContactFacets.key("spain"))
        assertTrue("company keys ignore case and spaces", acmeInSpain.passes(ben))
        assertFalse(acmeInSpain.passes(ana))
    }

    @Test fun has_and_missing_values_are_each_their_own_filter() {
        val emailAndPhoto = f(Facet.HAS to ContactFacets.HAS_EMAIL, Facet.HAS to ContactFacets.HAS_PHOTO)
        assertFalse(emailAndPhoto.passes(ana))
        assertTrue(emailAndPhoto.passes(ana, photo = true))
        val noNumber = f(Facet.MISSING to ContactFacets.NO_NUMBER)
        assertFalse(noNumber.passes(ana))
        assertTrue(noNumber.passes(ben))
        val noName = f(Facet.MISSING to ContactFacets.NO_NAME)
        assertTrue(noName.passes(nameless))
        assertFalse(noName.passes(ben))
        assertFalse("a company is a name", noName.passes(doc(4) { work("Acme", null) }))
        assertTrue(f(Facet.MISSING to ContactFacets.NO_NAME, Facet.MISSING to ContactFacets.NO_NUMBER).passes(nameless))
    }

    @Test fun birthdays_relations_languages_custom_labels_temporary() {
        assertTrue(f(Facet.HAS to ContactFacets.HAS_BIRTHDAY).passes(ben))
        assertTrue(f(Facet.BIRTHDAY_MONTH to "5", Facet.BIRTHDAY_MONTH to "11").passes(ben))
        assertFalse(f(Facet.BIRTHDAY_MONTH to "5").passes(ben))
        assertTrue(f(Facet.RELATION to ContactFacets.key("sister")).passes(ana))
        assertTrue(f(Facet.LANGUAGE to ContactFacets.key("Portuguese")).passes(ana))
        assertTrue(f(Facet.CUSTOM_LABEL to ContactFacets.key("shoe size")).passes(ana))
        assertFalse(f(Facet.CUSTOM_LABEL to ContactFacets.key("shoe size")).passes(ben))
        val temporary = f(Facet.KEPT to ContactFacets.TEMPORARY)
        assertTrue(temporary.passes(ben, temporary = true))
        assertFalse(temporary.passes(ben))
    }

    @Test fun an_unknown_contact_passes_only_an_empty_filter() {
        assertTrue(FieldFilter().matches(null, photo = false, temporary = false))
        assertFalse(f(Facet.COUNTRY to "portugal").matches(null, photo = false, temporary = false))
        assertTrue("nothing known: no number", f(Facet.MISSING to ContactFacets.NO_NUMBER).matches(null, photo = false, temporary = false))
    }

    @Test fun toggling_counts_and_emptiness() {
        val one = FieldFilter().toggle(Facet.COUNTRY, "portugal")
        assertEquals(1, one.count)
        assertFalse(one.isEmpty)
        assertTrue(one.has(Facet.COUNTRY, "portugal"))
        val none = one.toggle(Facet.COUNTRY, "portugal")
        assertTrue(none.isEmpty)
        assertEquals(FieldFilter(), none)
    }

    @Test fun choices_are_the_values_in_use_most_used_first() {
        val c = FacetChoices.from(listOf(ana.facets, ben.facets, nameless.facets, doc(5) { address(null, null, null, "Porto", null, null, "portugal") }.facets))
        assertEquals(listOf("Portugal", "Spain"), c.getValue(Facet.COUNTRY).map { it.display })
        assertEquals(2, c.getValue(Facet.COUNTRY).first().count)
        assertEquals("one company, however it is written", listOf(FacetChoice("acme", "Acme", 2)), c.getValue(Facet.COMPANY))
        assertEquals("months in calendar order", listOf("5", "11"), c.getValue(Facet.BIRTHDAY_MONTH).map { it.key })
    }

    @Test fun a_chosen_value_keeps_how_it_read() {
        val on = FieldFilter().toggle(Facet.COUNTRY, "portugal", "Portugal")
        assertEquals("Portugal", on.shownAs(Facet.COUNTRY, "portugal"))
        assertEquals(null, on.shownAs(Facet.PLACE, "portugal"))
        val off = on.toggle(Facet.COUNTRY, "portugal")
        assertTrue(off.isEmpty)
        assertEquals(null, off.shownAs(Facet.COUNTRY, "portugal"))
    }

    @Test fun countries_fold_to_one_name() {
        assertEquals("Portugal", Countries.canonical("PT"))
        assertEquals("Portugal", Countries.canonical(" portugal "))
        assertEquals("United States", Countries.canonical("USA"))
        assertEquals("United States", Countries.canonical("U.S.A."))
        assertEquals("United Kingdom", Countries.canonical("uk"))
        assertEquals("Germany", Countries.canonical("de"))
        assertEquals("Narnia", Countries.canonical("Narnia"))
        // A country's name in its own language, and in the phone's, is the same country.
        assertEquals("Germany", Countries.canonical("Deutschland"))
        assertEquals("Spain", Countries.canonical("España"))
        val japanese = Countries.build(java.util.Locale.JAPANESE)
        assertEquals("Germany", Countries.canonical("ドイツ", japanese))
        assertEquals("", Countries.canonical("  "))
    }

    @Test fun field_filters_join_the_label_filter() {
        val labels = LabelFilter(fields = f(Facet.COUNTRY to "portugal"))
        assertFalse(labels.isEmpty)
        assertEquals(ContactsFooter.Line.Filtered(3), ContactsFooter.line(3, "", labels))
        assertEquals(ContactsFooter.Line.Filtered(3), ContactsFooter.line(3, "", labels.copy(labels = setOf("Family"))))
        assertTrue(LabelFilter(fields = FieldFilter()).isEmpty)
    }
}
