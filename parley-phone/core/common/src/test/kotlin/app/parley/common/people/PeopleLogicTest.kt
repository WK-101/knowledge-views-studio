package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.EventDate
import app.parley.common.PhoneEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeopleLogicTest {
    private fun c(id: Long, name: String, vararg numbers: String, key: String = "k$id", emails: List<String> = emptyList()) =
        ContactSummary(id, key, name, null, true, numbers.map { PhoneEntry(it, 2, null) }, emails)

    // ---------------------------------------------------------------- C1 second line

    @Test fun second_line_follows_the_chosen_mode() {
        val list = listOf(c(1, "Anna", "+441234567890"), c(2, "Ben"))
        val extras = mapOf(1L to PersonExtra(company = "Acme", title = "CEO", nickname = "Annie", accounts = listOf("Google · a@x")))
        assertEquals(mapOf(1L to "+441234567890"), SecondLines.compute(list, extras, SecondLineMode.NUMBER))
        assertEquals(mapOf(1L to "Acme · CEO"), SecondLines.compute(list, extras, SecondLineMode.COMPANY_TITLE))
        assertEquals(mapOf(1L to "Annie"), SecondLines.compute(list, extras, SecondLineMode.NICKNAME))
        assertEquals(mapOf(1L to "Google · a@x"), SecondLines.compute(list, extras, SecondLineMode.ACCOUNT))
        assertEquals(emptyMap<Long, String>(), SecondLines.compute(list, extras, SecondLineMode.NONE))
    }

    @Test fun colliding_names_get_the_company_even_with_no_second_line() {
        val list = listOf(c(1, "John Smith", "111111111"), c(2, "john smith", "222222222"), c(3, "Mary"))
        val extras = mapOf(1L to PersonExtra(company = "Plumbing Ltd"), 2L to PersonExtra(company = "City Council"))
        val lines = SecondLines.compute(list, extras, SecondLineMode.NONE)
        assertEquals("Plumbing Ltd", lines[1])
        assertEquals("City Council", lines[2])
        assertNull(lines[3])
    }

    @Test fun collision_falls_back_to_number_when_companies_are_equal() {
        val list = listOf(c(1, "Sam", "111111111"), c(2, "Sam", "222222222"))
        val extras = mapOf(1L to PersonExtra(company = "Acme"), 2L to PersonExtra(company = "Acme"))
        val lines = SecondLines.compute(list, extras, SecondLineMode.COMPANY_TITLE)
        assertEquals("111111111", lines[1])
        assertEquals("222222222", lines[2])
    }

    @Test fun collision_already_told_apart_keeps_the_chosen_line() {
        val list = listOf(c(1, "Sam", "111111111"), c(2, "Sam", "222222222"))
        val extras = mapOf(1L to PersonExtra(company = "A"), 2L to PersonExtra(company = "B"))
        assertEquals("111111111", SecondLines.compute(list, extras, SecondLineMode.NUMBER)[1])
    }

    @Test fun prefer_nickname() {
        val a = c(1, "Robert Jones")
        assertEquals("Bob", SecondLines.displayName(a, PersonExtra(nickname = "Bob"), true))
        assertEquals("Robert Jones", SecondLines.displayName(a, PersonExtra(nickname = "Bob"), false))
        assertEquals("Robert Jones", SecondLines.displayName(a, PersonExtra(nickname = " "), true))
    }

    // ---------------------------------------------------------------- C2 label filters

    @Test fun label_filter_or_and_unlabelled() {
        val fam = PersonExtra(labels = setOf("Family"))
        val both = PersonExtra(labels = setOf("Family", "Work"))
        val none = PersonExtra()
        val or = LabelFilter(labels = setOf("Family", "Work"))
        assertTrue(or.matches(fam)); assertTrue(or.matches(both)); assertFalse(or.matches(none))
        val and = or.copy(matchAll = true)
        assertFalse(and.matches(fam)); assertTrue(and.matches(both))
        val unl = LabelFilter(unlabelled = true)
        assertTrue(unl.matches(none)); assertTrue(unl.matches(null)); assertFalse(unl.matches(fam))
        val famOrNone = LabelFilter(labels = setOf("Family"), unlabelled = true)
        assertTrue(famOrNone.matches(none)); assertTrue(famOrNone.matches(fam))
        assertTrue(LabelFilter().matches(null))
    }

    @Test fun label_filter_by_account() {
        val f = LabelFilter(account = "Google · a@x")
        assertTrue(f.matches(PersonExtra(accounts = listOf("Google · a@x", "Phone only"))))
        assertFalse(f.matches(PersonExtra(accounts = listOf("Phone only"))))
    }

    @Test fun label_filter_forgets_deleted_labels() {
        val f = LabelFilter(labels = setOf("Old", "Work"))
        assertEquals(setOf("Work"), f.retain(setOf("Work", "Family")).labels)
    }

    @Test fun merging_labels_adds_each_member_once() {
        val members = mapOf("Friends" to setOf(1L, 2L), "Pals" to setOf(2L, 3L), "Mates" to setOf(4L))
        assertEquals(setOf(3L, 4L), LabelMerge.toAdd(members, setOf("Pals", "Mates", "Friends"), "Friends"))
    }

    // ---------------------------------------------------------------- C4 favourites

    @Test fun favourites_custom_order_by_lookup_key_with_new_ones_last() {
        val favs = listOf(c(1, "Zoe", key = "z"), c(2, "Adam", key = "a"), c(3, "Mia", key = "m"), c(4, "Bea", key = "b"))
        val sorted = FavoriteOrder.sort(favs, FavoriteSort.CUSTOM, listOf("m", "z", "gone"), collator = Comparator { x, y -> x.compareTo(y) })
        assertEquals(listOf("Mia", "Zoe", "Adam", "Bea"), sorted.map { it.displayName })
    }

    @Test fun favourites_most_called_then_name() {
        val favs = listOf(c(1, "Zoe"), c(2, "Adam"), c(3, "Mia"))
        val sorted = FavoriteOrder.sort(favs, FavoriteSort.MOST_CALLED, emptyList(), mapOf(3L to 9, 1L to 2), Comparator { x, y -> x.compareTo(y) })
        assertEquals(listOf("Mia", "Zoe", "Adam"), sorted.map { it.displayName })
        assertEquals(listOf("Adam", "Mia", "Zoe"), FavoriteOrder.sort(favs, FavoriteSort.NAME, emptyList(), collator = Comparator { x, y -> x.compareTo(y) }).map { it.displayName })
    }

    @Test fun favourites_drag_move_and_pinch() {
        assertEquals(listOf("b", "c", "a"), FavoriteOrder.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), FavoriteOrder.move(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), FavoriteOrder.move(listOf("a", "b"), 0, 5))
        assertEquals(2, FavoriteOrder.columnsAfterPinch(3, 1.5f))
        assertEquals(4, FavoriteOrder.columnsAfterPinch(3, 0.6f))
        assertEquals(3, FavoriteOrder.columnsAfterPinch(3, 1.0f))
        assertEquals(2, FavoriteOrder.columnsAfterPinch(2, 2f))
        assertEquals(6, FavoriteOrder.columnsAfterPinch(6, 0.5f))
    }

    // ---------------------------------------------------------------- C6 duplicate warning

    @Test fun duplicate_warning_by_number_email_and_full_name() {
        val lookup = DuplicateLookup(listOf(c(1, "Anna Smith", "+44 7700 900123", emails = listOf("anna@example.com")), c(2, "Bob")))
        assertEquals(DuplicateReason.NUMBER, lookup.find("", listOf("07700 900123"), emptyList())?.reason)
        assertEquals(1L, lookup.find("", emptyList(), listOf(" ANNA@example.com "))?.contact?.id)
        assertEquals(DuplicateReason.NAME, lookup.find("smith anna", emptyList(), emptyList())?.reason)
        assertNull(lookup.find("Bob", emptyList(), emptyList())) // one word is too ambiguous
        assertNull(lookup.find("Carl Jones", listOf("123"), emptyList()))
        assertEquals("Anna Smith already exists", DuplicateLookup.describe(lookup.find("Anna Smith", emptyList(), emptyList())!!))
    }

    // ---------------------------------------------------------------- C9 date of death

    @Test fun death_label_is_recognised_and_stops_birthday_reminders() {
        assertTrue(LifeEvents.isDeath(0, "Date of death"))
        assertTrue(LifeEvents.isDeath(0, " died "))
        assertTrue(LifeEvents.isDeath(0, "Todestag"))
        assertFalse(LifeEvents.isDeath(3, "Date of death"))
        assertFalse(LifeEvents.isDeath(0, "Name day"))
        assertFalse(LifeEvents.remindBirthday(3, deceased = true))
        assertTrue(LifeEvents.remindBirthday(1, deceased = true))
        assertTrue(LifeEvents.remindBirthday(3, deceased = false))
        val death = LifeEvents.deathDate(listOf(Triple(3, null, "1940-05-01"), Triple(0, "Date of death", "2020-03-10")))
        assertEquals(EventDate(2020, 3, 10), death)
    }

    @Test fun would_have_turned_and_age_at_death() {
        val birth = EventDate(1940, 5, 1)
        assertEquals(86, LifeEvents.wouldHaveTurned(birth, LocalDate.of(2026, 4, 1)))
        assertEquals(87, LifeEvents.wouldHaveTurned(birth, LocalDate.of(2026, 6, 1)))
        assertEquals(79, LifeEvents.ageAtDeath(birth, EventDate(2020, 3, 10)))
        assertEquals(80, LifeEvents.ageAtDeath(birth, EventDate(2020, 5, 1)))
        assertNull(LifeEvents.ageAtDeath(EventDate(null, 5, 1), EventDate(2020, 5, 1)))
    }

    // ---------------------------------------------------------------- C8 SIM

    @Test fun sim_fit_keeps_one_number_and_shortens_the_name() {
        val r = SimFit.fit("Alexandra Montgomery-Smith", listOf(PhoneEntry("+44 7700 900123", 1, null), PhoneEntry("07700 900999", 2, null)), otherFields = 2)
        assertEquals(SimEntry("Alexandra Mont", "07700900999"), r.entry)
        assertEquals(3, r.warnings.size)
        assertTrue(r.warnings[0].contains("1 other number is left out"))
        assertTrue(r.warnings[1].contains("shortened"))
    }

    @Test fun sim_fit_primary_wins_and_unicode_names_take_more_space() {
        val r = SimFit.fit("Zoë Ωmega", listOf(PhoneEntry("111", 1, null), PhoneEntry("222", 3, null, isPrimary = true)))
        assertEquals("222", r.entry?.number)
        // "ë" isn't in the GSM alphabet, so the whole name is stored as UCS-2: 1 + 2 bytes per character.
        assertEquals(19, SimFit.gsmLength("Zoë Ωmega"))
        assertEquals("Zoë Ωm", r.entry?.name)
        assertEquals(12, SimFit.gsmLength("Ωmega Street"))
        val cyr = SimFit.fit("Александр Пушкин", listOf(PhoneEntry("123456", 2, null)))
        assertTrue(SimFit.gsmLength(cyr.entry!!.name) <= SimFit.DEFAULT_NAME_MAX)
        assertNull(SimFit.fit("No number", emptyList()).entry)
        assertNull(SimFit.fit("Long", listOf(PhoneEntry("1".repeat(25), 2, null))).entry)
    }

    // ---------------------------------------------------------------- D2 masking

    @Test fun diagnostics_mask_numbers_emails_and_uris() {
        val m = Masking.mask("Call to +44 7700 900123 failed for anna@example.com via content://com.android.contacts/data/1234 (code 42)")
        assertFalse(m, m.contains("7700"))
        assertTrue(m, m.contains("23 failed"))
        assertTrue(m, m.contains("•••@example.com"))
        assertTrue(m, m.contains("content://com.android.contacts/•••"))
        assertTrue(m, m.contains("code 42"))
        assertEquals("••••••••23", Masking.maskNumber("0123456723"))
    }

    // ---------------------------------------------------------------- C11 provenance

    private val fmt: (Long) -> String = { "t$it" }

    @Test fun provenance_parley_when_version_unchanged() {
        val v = Provenance.verdict(
            listOf(RawState(1, "Google · a", true, 5, true)), listOf(ParleyWrite(1, 100, 5, listOf("Phone"))), 100, fmt,
        )!!
        assertEquals(ChangeSource.PARLEY, v.source)
        assertEquals("Changed by Parley on t100 · only changed fields were written (Phone)", v.text)
    }

    @Test fun provenance_sync_or_other_app_after_parley() {
        val w = listOf(ParleyWrite(1, 100, 5, emptyList()))
        assertEquals(ChangeSource.SYNC, Provenance.verdict(listOf(RawState(1, "Google · a", true, 7, false)), w, 200, fmt)!!.source)
        val other = Provenance.verdict(listOf(RawState(1, "Google · a", true, 7, true)), w, 200, fmt)!!
        assertEquals(ChangeSource.ANOTHER_APP, other.source)
        assertTrue(other.text.contains("on t200"))
    }

    @Test fun provenance_without_parley_record() {
        assertEquals(ChangeSource.SYNC, Provenance.verdict(listOf(RawState(1, "CardDAV · b", true, 3, false)), emptyList(), 50, fmt)!!.source)
        assertEquals(ChangeSource.ANOTHER_APP, Provenance.verdict(listOf(RawState(1, "Phone only", false, 3, true)), emptyList(), 50, fmt)!!.source)
        assertEquals(ChangeSource.UNKNOWN, Provenance.verdict(listOf(RawState(1, "Phone only", false, 3, false)), emptyList(), 50, fmt)!!.source)
        assertNull(Provenance.verdict(listOf(RawState(1, "Phone only", false, 3, false)), emptyList(), null, fmt))
        assertEquals("Only changed fields were written: Name, Phone", Provenance.journalNote(listOf("Name", "Phone")))
    }

    // ---------------------------------------------------------------- C7 accounts

    @Test fun account_check_flags_orphans_sync_off_and_missing_local() {
        val g = AccountKey("com.google", "a@x")
        val old = AccountKey("com.google", "old@x")
        val dav = AccountKey("bitfire.at.davdroid", "me")
        val local = AccountKey(null, null)
        val f = AccountCheck.check(setOf(g, dav), mapOf(g to 10, old to 4, local to 2), syncOff = setOf(dav), masterSyncOn = true, localAccountPresent = false, unsyncedTypes = setOf(null))
        assertEquals(
            listOf(AccountFindingKind.ORPHANED to old, AccountFindingKind.SYNC_OFF to dav, AccountFindingKind.LOCAL_MISSING to local),
            f.map { it.kind to it.account },
        )
        assertEquals(4, f.first().count)
        val quiet = AccountCheck.check(setOf(g), mapOf(g to 1), emptySet(), masterSyncOn = false, localAccountPresent = true)
        assertEquals(listOf(AccountFindingKind.MASTER_SYNC_OFF), quiet.map { it.kind })
        assertEquals(listOf(AccountFindingKind.NO_CONTACTS), AccountCheck.check(setOf(g), emptyMap(), emptySet(), true, true).map { it.kind })
    }

    // ---------------------------------------------------------------- C15 lookup policy

    @Test fun lookup_accepts_only_one_exact_number() {
        assertEquals("+447700900123", LookupPolicy.parseNumber("+44 7700 900123"))
        assertNull(LookupPolicy.parseNumber("0770%"))
        assertNull(LookupPolicy.parseNumber("12345"))
        assertNull(LookupPolicy.parseNumber("*"))
        assertNull(LookupPolicy.parseNumber("anna"))
        assertNull(LookupPolicy.parseNumber("0123456789,0987654321"))
        assertNull(LookupPolicy.parseNumber(null))
    }

    @Test fun lookup_decisions() {
        val now = 10_000_000L
        assertEquals(LookupOutcome.OFF, LookupPolicy.decide(false, LookupApproval.ALLOWED, true, emptyList(), now))
        assertEquals(LookupOutcome.REJECTED, LookupPolicy.decide(true, LookupApproval.ALLOWED, false, emptyList(), now))
        assertEquals(LookupOutcome.ASKED, LookupPolicy.decide(true, null, true, emptyList(), now))
        assertEquals(LookupOutcome.ASKED, LookupPolicy.decide(true, LookupApproval.PENDING, true, emptyList(), now))
        assertEquals(LookupOutcome.DENIED, LookupPolicy.decide(true, LookupApproval.DENIED, true, emptyList(), now))
        assertEquals(LookupOutcome.ANSWERED, LookupPolicy.decide(true, LookupApproval.ALLOWED, true, List(59) { now - 1000 }, now))
        assertEquals(LookupOutcome.RATE_LIMITED, LookupPolicy.decide(true, LookupApproval.ALLOWED, true, List(60) { now - 1000 }, now))
        assertEquals(LookupOutcome.ANSWERED, LookupPolicy.decide(true, LookupApproval.ALLOWED, true, List(60) { now - 4_000_000L }, now))
    }
}
