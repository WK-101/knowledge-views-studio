package app.parley.common.calls

import app.parley.common.CallType
import app.parley.common.PhoneIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 86_400_000L

class NeverCallsYouTest {
    private val bank = NeverCallsYou.SavedAs("Northshire Bank", company = "Northshire Bank")
    private val ana = NeverCallsYou.SavedAs("Ana Lopez")
    private val number = "020 7946 0018"
    private val line = PhoneIdentity.e164(number, "GB")

    /** Parley's copy of calls holds everything from this time on (earlier than every call below). */
    private val kept = 1_000L

    /** Calls with the line, a day apart, the first at [kept] + 1 day. */
    private fun past(types: List<CallType>) = types.mapIndexed { i, t -> NeverCallsYou.PastCall(t, kept + (i + 1) * DAY) }

    private fun shows(
        past: List<CallType>,
        savedAs: List<NeverCallsYou.SavedAs> = listOf(bank),
        n: String? = number,
        l: String? = line,
        emergency: Boolean = false,
        hidden: Boolean = false,
        conference: Boolean = false,
        keptSince: Long? = kept,
    ) = NeverCallsYou.shows(n, l, savedAs, past(past), keptSince, emergency, hidden, conference)

    @Test fun shows_when_you_only_ever_called_a_saved_organisation() {
        assertTrue(shows(listOf(CallType.OUTGOING)))
        assertTrue(shows(listOf(CallType.OUTGOING, CallType.OUTGOING, CallType.OUTGOING)))
    }

    @Test fun a_call_you_said_wasnt_them_leaves_the_notice_armed() {
        val calls = past(listOf(CallType.OUTGOING, CallType.INCOMING))
        val scam = calls[1]
        // Without the mark, the spoofed call disarms it.
        assertFalse(NeverCallsYou.shows(number, line, listOf(bank), calls, kept, emergency = false))
        // "It wasn't them", said ten minutes after that call began: it leaves the history the check reads.
        val marked = NeverCallsYou.withoutDisowned(calls, listOf(scam.date + 10 * 60_000L))
        assertEquals(listOf(calls[0]), marked)
        assertTrue(NeverCallsYou.shows(number, line, listOf(bank), marked, kept, emergency = false))
        // A mark leaves out only the call it was said about: not your own calls, not a call long before it.
        assertEquals(calls, NeverCallsYou.withoutDisowned(calls, listOf(scam.date + 2 * DAY)))
        val two = past(listOf(CallType.OUTGOING, CallType.INCOMING, CallType.MISSED))
        assertEquals(two.take(2), NeverCallsYou.withoutDisowned(two, listOf(two[2].date + 60_000L)))
    }

    @Test fun they_never_call_me_keeps_the_notice_on() {
        val mine = NeverCallsYou.SavedAs("Ana's school helpline", neverCalls = true)
        val person = NeverCallsYou.SavedAs("Sam", neverCalls = true)
        // Whatever the history shows, even for a contact that doesn't look like an organisation.
        assertTrue(shows(listOf(CallType.OUTGOING, CallType.INCOMING), savedAs = listOf(mine)))
        assertTrue(shows(emptyList(), savedAs = listOf(person), keptSince = null))
        // A person sharing the line who may call still keeps it off; and never for a hidden or emergency call.
        assertFalse(shows(listOf(CallType.OUTGOING), savedAs = listOf(person, ana)))
        assertFalse(shows(emptyList(), savedAs = listOf(person), hidden = true))
        assertFalse(shows(emptyList(), savedAs = listOf(person), emergency = true))
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
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Riverside", labels = setOf("Clinics"))))
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Gas", labels = setOf("Utilities"))))
        assertFalse(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Ana", labels = setOf("Family", "Book club"))))
        // A name that says so, but never a surname that only looks like one.
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Barclays Bank")))
        assertTrue(NeverCallsYou.organisation(NeverCallsYou.SavedAs("St Mary's Hospital")))
        assertFalse(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Mike Banks")))
        assertFalse(NeverCallsYou.organisation(NeverCallsYou.SavedAs("Banker Joe")))
    }

    @Test fun only_you_called() {
        assertFalse(NeverCallsYou.onlyYouCalled(emptyList(), kept))
        assertTrue(NeverCallsYou.onlyYouCalled(past(listOf(CallType.OUTGOING)), kept))
        assertFalse(NeverCallsYou.onlyYouCalled(past(listOf(CallType.INCOMING)), kept))
    }

    @Test fun colleagues_and_people_in_everyday_labels_are_people() {
        // Colleagues, other parents and teachers, friends who run a shop: labels people keep for persons.
        listOf("Office", "Work", "Business", "Company", "School", "Kids' school", "Support", "Service", "Shop", "Store", "Doctor").forEach { l ->
            assertFalse(l, NeverCallsYou.organisation(NeverCallsYou.SavedAs("Ana Lopez", labels = setOf(l))))
            assertFalse(l, NeverCallsYou.organisation(NeverCallsYou.SavedAs("Ana Lopez", company = "Acme", labels = setOf(l))))
        }
        // A colleague you've only ever called rings for the first time: no card.
        val colleague = NeverCallsYou.SavedAs("Sam Patel", company = "Northshire Bank", labels = setOf("Office", "Colleagues"))
        assertFalse(shows(listOf(CallType.OUTGOING, CallType.OUTGOING), savedAs = listOf(colleague)))
        // Institutions still are.
        listOf("Banks", "Clinics", "Hospital", "Pharmacy", "Insurance", "Utilities", "Government", "Tax").forEach { l ->
            assertTrue(l, NeverCallsYou.organisation(NeverCallsYou.SavedAs("Northshire", labels = setOf(l))))
        }
    }

    @Test fun nothing_is_said_without_a_history_that_reaches_back_past_your_first_call() {
        // Parley's copy is off (or can't be read now): Android's log alone may have lost an older call from them.
        assertFalse(shows(listOf(CallType.OUTGOING), keptSince = null))
        // The copy begins after (or with) your first call to them: a call from them before it may be gone.
        assertFalse(shows(listOf(CallType.OUTGOING), keptSince = kept + DAY))
        assertFalse(shows(listOf(CallType.OUTGOING), keptSince = kept + 2 * DAY))
        assertTrue(shows(listOf(CallType.OUTGOING), keptSince = kept + DAY - 1))
        // The same for "First call from them to you".
        assertNull(first(Row(10, CallType.OUTGOING), Row(30, CallType.MISSED), keptSince = null))
        assertNull(first(Row(10, CallType.OUTGOING), Row(30, CallType.MISSED), keptSince = 10))
        assertEquals(Row(30, CallType.MISSED), first(Row(10, CallType.OUTGOING), Row(30, CallType.MISSED), keptSince = 9))
    }

    private data class Row(val date: Long, val type: CallType)

    private fun first(vararg rows: Row, keptSince: Long? = 0) = NeverCallsYou.firstFromThem(rows.toList(), keptSince, Row::date, Row::type)

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

    private fun scamOffered(live: Boolean = true, emergency: Boolean = false, neverCallsYou: Boolean = true) =
        ScamCheck.offered(live, savedCaller = true, lookedUp = true, hidden = false, emergency = emergency, conference = false, neverCallsYou = neverCallsYou)

    @Test fun scam_check_is_offered_for_a_saved_organisation_that_never_calls() {
        assertTrue(scamOffered())
        assertFalse(scamOffered(neverCallsYou = false))
        assertFalse(scamOffered(emergency = true))
        assertFalse(scamOffered(live = false))
    }

    @Test fun the_history_says_when_parley_can_warn() {
        val onlyYours = past(listOf(CallType.OUTGOING, CallType.OUTGOING))
        assertEquals(NeverCallsYou.Watch.Armed, NeverCallsYou.watch(listOf(bank), onlyYours, keptSince = kept))
        // Parley's copy starts after your first call: not yet, and from when.
        val later = kept + 5 * DAY
        assertEquals(NeverCallsYou.Watch.From(later), NeverCallsYou.watch(listOf(bank), onlyYours, keptSince = later))
        assertEquals(NeverCallsYou.Watch.NoCopy, NeverCallsYou.watch(listOf(bank), onlyYours, keptSince = null))
        // "They never call me" needs no history.
        assertEquals(NeverCallsYou.Watch.Chosen, NeverCallsYou.watch(listOf(ana.copy(neverCalls = true)), emptyList(), null))
        // Nothing to say: a person, a line that called you, a line never called, nobody saved.
        assertNull(NeverCallsYou.watch(listOf(ana), onlyYours, kept))
        assertNull(NeverCallsYou.watch(listOf(bank), past(listOf(CallType.OUTGOING, CallType.INCOMING)), kept))
        assertNull(NeverCallsYou.watch(listOf(bank), emptyList(), kept))
        assertNull(NeverCallsYou.watch(emptyList(), onlyYours, kept))
        // It agrees with the call screen: armed exactly when the notice would show.
        assertTrue(shows(past = listOf(CallType.OUTGOING, CallType.OUTGOING)))
    }
}
