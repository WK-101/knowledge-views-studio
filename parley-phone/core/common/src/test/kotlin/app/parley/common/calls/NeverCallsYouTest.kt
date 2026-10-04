package app.parley.common.calls

import app.parley.common.CallType
import app.parley.common.PhoneIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeverCallsYouTest {
    private val bank = NeverCallsYou.SavedAs("Northshire Bank", company = "Northshire Bank")
    private val ana = NeverCallsYou.SavedAs("Ana Lopez")
    private val number = "020 7946 0018"
    private val line = PhoneIdentity.e164(number, "GB")

    private fun shows(
        past: List<CallType>,
        savedAs: List<NeverCallsYou.SavedAs> = listOf(bank),
        n: String? = number,
        l: String? = line,
        emergency: Boolean = false,
        hidden: Boolean = false,
        conference: Boolean = false,
    ) = NeverCallsYou.shows(n, l, savedAs, past, emergency, hidden, conference)

    @Test fun shows_when_you_only_ever_called_a_saved_organisation() {
        assertTrue(shows(listOf(CallType.OUTGOING)))
        assertTrue(shows(listOf(CallType.OUTGOING, CallType.OUTGOING, CallType.OUTGOING)))
    }

    @Test fun no_history_shows_nothing() {
        // A bank you saved but never called: nothing to compare with.
        assertFalse(shows(emptyList()))
    }

    @Test fun any_call_from_them_shows_nothing() {
        assertFalse(shows(listOf(CallType.INCOMING)))
        assertFalse(shows(listOf(CallType.MISSED, CallType.MISSED)))
        assertFalse(shows(listOf(CallType.OUTGOING, CallType.INCOMING)))
        assertFalse(shows(listOf(CallType.OUTGOING, CallType.MISSED)))
        assertFalse(shows(listOf(CallType.OUTGOING, CallType.REJECTED)))
        assertFalse(shows(listOf(CallType.OUTGOING, CallType.VOICEMAIL)))
        assertFalse(shows(listOf(CallType.OUTGOING, CallType.BLOCKED)))
        assertFalse(shows(listOf(CallType.OUTGOING, CallType.ANSWERED_EXTERNALLY)))
        // A call the log can't place might have been theirs.
        assertFalse(shows(listOf(CallType.OUTGOING, CallType.UNKNOWN)))
    }

    @Test fun short_numbers_and_service_codes_show_nothing() {
        // No E.164 form: matching a short code to a contact is too loose to say anything.
        assertNull(PhoneIdentity.e164("2525", "GB"))
        assertFalse(shows(listOf(CallType.OUTGOING), n = "2525", l = PhoneIdentity.e164("2525", "GB")))
        assertFalse(shows(listOf(CallType.OUTGOING), n = "*100#", l = null))
        assertFalse(shows(listOf(CallType.OUTGOING), n = null))
        assertFalse(shows(listOf(CallType.OUTGOING), n = " "))
    }

    @Test fun emergency_numbers_hidden_numbers_and_conferences_are_excluded() {
        assertFalse(shows(listOf(CallType.OUTGOING), emergency = true))
        // Even when the platform didn't flag it, the fallback emergency list does.
        assertFalse(shows(listOf(CallType.OUTGOING), n = "112", l = "+44112"))
        assertFalse(shows(listOf(CallType.OUTGOING), n = "999", l = "+44999"))
        assertFalse(shows(listOf(CallType.OUTGOING), hidden = true))
        assertFalse(shows(listOf(CallType.OUTGOING), conference = true))
    }

    @Test fun people_never_show_it() {
        assertFalse(shows(listOf(CallType.OUTGOING), savedAs = listOf(ana)))
        assertFalse(shows(listOf(CallType.OUTGOING), savedAs = emptyList()))
    }

    @Test fun a_number_saved_for_several_contacts_needs_every_one_to_be_an_organisation() {
        val clinic = NeverCallsYou.SavedAs("Riverside Clinic")
        assertTrue(shows(listOf(CallType.OUTGOING), savedAs = listOf(bank, clinic)))
        // A person sharing the line (someone who works there, a family landline) may well call.
        assertFalse(shows(listOf(CallType.OUTGOING), savedAs = listOf(bank, ana)))
        assertFalse(shows(listOf(CallType.OUTGOING), savedAs = listOf(ana, bank)))
    }

    @Test fun what_counts_as_an_organisation() {
        // Saved as the company, or with the company in its name.
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Acme", company = "Acme")))
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Acme fraud team", company = "ACME")))
        // A person at a company is a person.
        assertFalse(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Ana Lopez", company = "Acme")))
        // A "Company main" number.
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Ana Lopez", companyLine = true)))
        // A label for organisations, singular or plural, any case.
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Northshire", labels = setOf("Banks"))))
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Dr Patel", labels = setOf("Kids' school"))))
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Gas", labels = setOf("Utilities"))))
        assertFalse(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Ana", labels = setOf("Family", "Book club"))))
        // A name that says so, but never a surname that only looks like one.
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Barclays Bank")))
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("St Mary's Hospital")))
        assertFalse(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Mike Banks")))
        assertFalse(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Banker Joe")))
    }

    @Test fun only_you_called() {
        assertFalse(NeverCallsYou.onlyYouCalled(emptyList()))
        assertTrue(NeverCallsYou.onlyYouCalled(listOf(CallType.OUTGOING)))
        assertFalse(NeverCallsYou.onlyYouCalled(listOf(CallType.INCOMING)))
    }

    private data class Row(val date: Long, val type: CallType)

    private fun first(vararg rows: Row) = NeverCallsYou.firstFromThem(rows.toList(), Row::date, Row::type)

    @Test fun first_call_from_them_after_only_yours() {
        val theirs = Row(30, CallType.MISSED)
        // In any order: the history lists newest first.
        assertEquals(theirs, first(Row(40, CallType.INCOMING), theirs, Row(20, CallType.OUTGOING), Row(10, CallType.OUTGOING)))
    }

    @Test fun no_first_call_when_they_called_before_you_did_or_never_called() {
        assertNull(first())
        assertNull(first(Row(10, CallType.OUTGOING), Row(20, CallType.OUTGOING)))
        assertNull(first(Row(10, CallType.INCOMING), Row(20, CallType.OUTGOING), Row(30, CallType.INCOMING)))
        assertNull(first(Row(10, CallType.UNKNOWN), Row(20, CallType.OUTGOING), Row(30, CallType.INCOMING)))
        assertNull(first(Row(10, CallType.OUTGOING), Row(20, CallType.UNKNOWN), Row(30, CallType.INCOMING)))
    }

    @Test fun scam_check_is_offered_for_a_saved_organisation_that_never_calls() {
        assertTrue(ScamCheck.offered(live = true, savedCaller = true, lookedUp = true, hidden = false, emergency = false, conference = false, neverCallsYou = true))
        assertFalse(ScamCheck.offered(live = true, savedCaller = true, lookedUp = true, hidden = false, emergency = false, conference = false))
        assertFalse(ScamCheck.offered(live = true, savedCaller = true, lookedUp = true, hidden = false, emergency = true, conference = false, neverCallsYou = true))
        assertFalse(ScamCheck.offered(live = false, savedCaller = true, lookedUp = true, hidden = false, emergency = false, conference = false, neverCallsYou = true))
    }
}
