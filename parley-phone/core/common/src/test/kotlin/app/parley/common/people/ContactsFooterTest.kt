package app.parley.common.people

import app.parley.common.people.ContactsFooter.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactsFooterTest {
    private val none = LabelFilter()

    @Test fun counts_what_is_shown() {
        assertEquals(Line.All(120), ContactsFooter.line(120, "", none))
        assertEquals(Line.All(120, 3), ContactsFooter.line(120, "", none, private = 3))
        assertNull(ContactsFooter.line(0, "", none))
    }

    @Test fun a_search_counts_results_whatever_the_filter() {
        assertEquals(Line.Results(4), ContactsFooter.line(4, "an", none))
        assertEquals(Line.Results(4), ContactsFooter.line(4, "an", LabelFilter(labels = setOf("Family"))))
        assertEquals(Line.Results(2), ContactsFooter.line(2, "an", none, privateList = true))
    }

    @Test fun names_a_single_label_or_account() {
        assertEquals(Line.In(12, "Family"), ContactsFooter.line(12, "", LabelFilter(labels = setOf("Family"))))
        assertEquals(Line.In(30, "Google · me"), ContactsFooter.line(30, "", LabelFilter(account = "Google · me")))
        assertEquals(Line.Unlabelled(5), ContactsFooter.line(5, "", LabelFilter(unlabelled = true)))
        assertEquals(Line.Filtered(7), ContactsFooter.line(7, "", LabelFilter(labels = setOf("Family", "Work"))))
        assertEquals(Line.Filtered(7), ContactsFooter.line(7, "", LabelFilter(labels = setOf("Family"), account = "Google · me")))
        assertEquals(Line.Private(3), ContactsFooter.line(3, "", none, privateList = true))
    }
}
