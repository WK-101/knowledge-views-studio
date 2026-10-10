package app.parley.common.cases

import app.parley.common.calls.MenuPress
import app.parley.common.crypto.Aead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class CaseFilesTest {
    private val region = "GB"
    private val bank = "+442079460000"
    private val bankNational = "020 7946 0000"
    private val clinic = "+442079460111"

    private fun call(at: Long, hold: Long = 0, menu: String = "") =
        CaseCall(at, incoming = false, durationSec = 300, holdSec = hold, connected = true, menu = menu)

    @Test fun an_organisations_first_call_starts_its_case_file() {
        val s = CaseFiles.recordCall(CaseState(), bank, call(1_000, hold = 600), organisation = true, name = "Barclays",
            private = false, region = region, newId = "a")
        val case = CaseFiles.find(s, listOf(bankNational), region)
        assertNotNull(case)
        assertEquals(CaseMode.AUTO, case!!.mode)
        assertEquals("Barclays", case.name)
        assertEquals(listOf(600L), case.calls.map { it.holdSec })
    }

    @Test fun a_person_gets_one_only_when_asked() {
        val s = CaseFiles.recordCall(CaseState(), bank, call(1_000), organisation = false, name = "Ana", private = false, region = region, newId = "a")
        assertTrue(s.cases.isEmpty())
        val kept = CaseFiles.setMode(s, "Landlord", listOf(bank), private = false, mode = CaseMode.ON, now = 5, region = region, newId = "b")
        val after = CaseFiles.recordCall(kept, bankNational, call(2_000), organisation = false, name = "Ana", private = false, region = region, newId = "c")
        val case = CaseFiles.byId(after, "b")!!
        assertEquals(CaseMode.ON, case.mode)
        assertEquals(listOf(2_000L), case.calls.map { it.at })
    }

    @Test fun a_stopped_case_keeps_nothing_more() {
        var s = CaseFiles.recordCall(CaseState(), bank, call(1_000), organisation = true, name = "Barclays", private = false, region = region, newId = "a")
        s = CaseFiles.addReference(s, "a", CaseReference("r", "Claim", "sealed", 1_500))
        s = CaseFiles.stop(s, "a")
        val stopped = CaseFiles.byId(s, "a")!!
        assertFalse(stopped.kept)
        assertTrue(stopped.calls.isEmpty() && stopped.references.isEmpty())
        // The organisation calls again: still stopped, nothing kept, no new case.
        val again = CaseFiles.recordCall(s, bank, call(3_000), organisation = true, name = "Barclays", private = false, region = region, newId = "b")
        assertEquals(s, again)
        // And no reference can be added to it.
        assertEquals(s, CaseFiles.addReference(s, "a", CaseReference("r2", "", "x", 4_000)))
    }

    @Test fun the_same_call_is_kept_once_and_calls_are_capped() {
        var s = CaseState()
        repeat(CaseFiles.MAX_CALLS + 5) { i ->
            s = CaseFiles.recordCall(s, bank, call(i * 1_000L), organisation = true, name = "Bank", private = false, region = region, newId = "a")
        }
        val last = call((CaseFiles.MAX_CALLS + 4) * 1_000L, hold = 60)
        s = CaseFiles.recordCall(s, bank, last, organisation = true, name = "Bank", private = false, region = region, newId = "a")
        val calls = s.cases.single().calls
        assertEquals(CaseFiles.MAX_CALLS, calls.size)
        assertEquals(60L, calls.first().holdSec)
        assertEquals(5_000L, calls.last().at)
    }

    @Test fun menu_keys_keep_nothing_secret() {
        val tones = "2#1#55554"
        val presses = tones.mapIndexed { i, t -> MenuPress(t, 1_000L * (i + 1)) }
        // The menu choices stay; the run of four or more digits (an account number) and everything after it go.
        assertEquals("2#1#", CaseFiles.menuOf(presses, remember = true))
        assertEquals("", CaseFiles.menuOf(presses, remember = false))
        assertEquals("", CaseFiles.menuOf(emptyList(), remember = true))
    }

    @Test fun typed_keys_offer_the_first_long_run_of_digits() {
        assertEquals("41234567", CaseFiles.typedReference("2#41234567#"))
        assertEquals("41234567", CaseFiles.typedReference("2#41234567#3#998877"))
        assertNull(CaseFiles.typedReference("2#1#4"))
        assertNull(CaseFiles.typedReference(""))
    }

    @Test fun typed_keys_never_offer_a_pin_or_a_card_number() {
        // A card number (Luhn-valid) then its PIN: neither is offered.
        assertNull(CaseFiles.typedReference("1#4111111111111111#1234#"))
        assertNull(CaseFiles.typedReference("4111111111111111"))
        // An account number then a PIN right after it: the PIN is never offered, and no short run is in such a call.
        assertNull(CaseFiles.typedReference("1#12345678#4321#"))
        assertNull(CaseFiles.typedReference("3*12345*998877"))
        // A PIN read out key by key around #: nothing up to eight digits is offered in that call.
        assertNull(CaseFiles.typedReference("2#5#7#9#41234567#"))
        // A long reference typed before any of that is still offered.
        assertEquals("1234567890", CaseFiles.typedReference("2#1234567890#4321#"))
        // A 16-digit run that fails the Luhn check isn't a card.
        assertEquals("1234567812345678", CaseFiles.typedReference("1#1234567812345678#"))
    }

    @Test fun card_numbers_are_told_by_the_luhn_check() {
        assertTrue(CaseFiles.looksLikeCard("4111111111111111"))
        assertTrue(CaseFiles.looksLikeCard("378282246310005"))
        assertFalse(CaseFiles.looksLikeCard("4111111111111112"))
        assertFalse(CaseFiles.looksLikeCard("411111111111"))
    }

    @Test fun a_case_whose_number_is_private_now_hides_with_private_contacts() {
        var s = CaseFiles.setMode(CaseState(), "Northwind", listOf(bank), private = false, mode = CaseMode.ON, now = 1, region = region, newId = "a")
        assertEquals(1, CaseFiles.visible(s, notesHidden = false, privateHidden = true) { false }.cases.size)
        assertTrue(CaseFiles.visible(s, notesHidden = false, privateHidden = true) { it == bank }.cases.isEmpty())
        assertEquals(1, CaseFiles.visible(s, notesHidden = false, privateHidden = false) { it == bank }.cases.size)
        // The next call takes on whether the number is private now, both ways.
        s = CaseFiles.recordCall(s, bank, call(5), organisation = false, name = "", private = true, region = region, newId = "x")
        assertTrue(s.cases.single().private)
        s = CaseFiles.recordCall(s, bank, call(6), organisation = false, name = "", private = false, region = region, newId = "x")
        assertFalse(s.cases.single().private)
    }

    @Test fun references_are_cleaned_and_shown_masked() {
        assertEquals("CR 48213", CaseFiles.cleanReference("  CR   48213\n"))
        assertNull(CaseFiles.cleanReference("   "))
        assertEquals(CaseFiles.MAX_REFERENCE, CaseFiles.cleanReference("9".repeat(80))!!.length)
        assertEquals("•••• 4821", CaseFiles.masked("CR-994821"))
        assertEquals("••••", CaseFiles.masked("4821"))
        assertEquals("", CaseFiles.cleanLabel(" "))
    }

    @Test fun a_contacts_numbers_join_its_case() {
        var s = CaseFiles.setMode(CaseState(), "Council", listOf(bank), private = false, mode = CaseMode.ON, now = 1, region = region, newId = "a")
        s = CaseFiles.setMode(s, "Council", listOf(bankNational, clinic), private = false, mode = CaseMode.ON, now = 2, region = region, newId = "b")
        assertEquals(1, s.cases.size)
        assertEquals(listOf(bank, clinic), s.cases.single().numbers)
    }

    @Test fun duress_hides_every_case_and_discreet_mode_the_private_ones() {
        var s = CaseFiles.setMode(CaseState(), "Bank", listOf(bank), private = false, mode = CaseMode.ON, now = 1, region = region, newId = "a")
        s = CaseFiles.setMode(s, "Clinic", listOf(clinic), private = true, mode = CaseMode.ON, now = 1, region = region, newId = "b")
        assertTrue(CaseFiles.visible(s, notesHidden = true, privateHidden = false).cases.isEmpty())
        assertEquals(listOf("a"), CaseFiles.visible(s, notesHidden = false, privateHidden = true).cases.map { it.id })
        assertEquals(2, CaseFiles.visible(s, notesHidden = false, privateHidden = false).cases.size)
    }

    /** References are sealed values: the backup opens them (inside its own encryption), the next phone seals them again. */
    @Test fun references_stay_sealed_and_travel_opened_only_in_the_backup() {
        val oldKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val newKey = ByteArray(32).also(SecureRandom()::nextBytes)
        fun seal(key: ByteArray) = { text: String -> Base64.getEncoder().encodeToString(Aead.seal(key, text.toByteArray())) }
        fun open(key: ByteArray) = { blob: String -> Aead.openOrNull(key, Base64.getDecoder().decode(blob))?.let { String(it) } }

        var s = CaseFiles.setMode(CaseState(), "Insurer", listOf(bank), private = false, mode = CaseMode.ON, now = 1, region = region, newId = "a")
        s = CaseFiles.setMode(s, "Private clinic", listOf(clinic), private = true, mode = CaseMode.ON, now = 1, region = region, newId = "p")
        s = CaseFiles.addReference(s, "a", CaseReference("r1", "Claim", seal(oldKey)("CLM-559900"), 10))
        s = CaseFiles.addReference(s, "p", CaseReference("r2", "", seal(oldKey)("PX-1"), 10))
        assertFalse(CaseFiles.encode(s).contains("CLM-559900"))

        val backup = CaseFiles.forBackup(s, { it.private }, open(oldKey))
        assertEquals(listOf("a"), backup.cases.map { it.id })
        assertEquals("CLM-559900", backup.cases.single().references.single().value)

        // Another phone: sealed with its own key, merged with what it has.
        val mine = CaseFiles.recordCall(
            CaseState(), bankNational, call(500), organisation = true, name = "Insurer Ltd", private = false, region = region, newId = "m",
        )
        val merged = CaseFiles.merge(mine, CaseFiles.sealed(CaseFiles.decode(CaseFiles.encode(backup)), seal(newKey)), region)
        val case = merged.cases.single()
        assertEquals("m", case.id)
        assertEquals("Insurer Ltd", case.name)
        val ref = case.references.single()
        assertFalse(ref.value.contains("CLM"))
        assertEquals("CLM-559900", open(newKey)(ref.value))
        // A reference the key can't open is left out of the backup rather than written sealed with a key it can't use.
        assertTrue(CaseFiles.forBackup(s, { false }) { null }.cases.all { it.references.isEmpty() })
    }

    @Test fun a_restore_adds_what_is_missing_and_keeps_this_phones_choices() {
        val mine = CaseFiles.stop(CaseFiles.setMode(CaseState(), "Bank", listOf(bank), false, CaseMode.ON, 1, region, "a"), "a")
        val restored = CaseFiles.recordCall(
            CaseFiles.setMode(CaseState(), "Clinic", listOf(clinic), false, CaseMode.ON, 1, region, "c"),
            bank, call(9), organisation = true, name = "Bank", private = false, region = region, newId = "b",
        )
        val merged = CaseFiles.merge(mine, restored, region)
        assertEquals(CaseMode.OFF, CaseFiles.find(merged, listOf(bank), region)!!.mode)
        assertNotNull(CaseFiles.find(merged, listOf(clinic), region))
        assertEquals(merged, CaseFiles.decode(CaseFiles.encode(merged)))
        assertEquals(CaseState(), CaseFiles.decode("not json"))
    }

    @Test fun a_restore_never_brings_back_what_stop_deleted() {
        var before = CaseFiles.setMode(CaseState(), "Bank", listOf(bank), false, CaseMode.ON, 1, region, "a")
        before = CaseFiles.addReference(before, "a", CaseReference("r1", "Claim", "CLM-1", 10))
        before = CaseFiles.recordCall(before, bank, call(20), organisation = true, name = "Bank", private = false, region = region, newId = "x")
        val backup = CaseFiles.forBackup(before, { false }) { it }
        val stopped = CaseFiles.stop(before, "a")
        val merged = CaseFiles.merge(stopped, backup, region).cases.single()
        assertEquals(CaseMode.OFF, merged.mode)
        assertTrue(merged.references.isEmpty())
        assertTrue(merged.calls.isEmpty())
        // A stopped case's backup copy carries nothing it once kept, and restoring it elsewhere keeps it so.
        val off = CaseFiles.forBackup(stopped, { false }) { it }.cases.single()
        assertTrue(off.references.isEmpty() && off.calls.isEmpty())
        val elsewhere = CaseFiles.merge(CaseState(), CaseState(listOf(merged.copy(references = backup.cases.single().references))), region)
        assertTrue(elsewhere.cases.single().references.isEmpty())
    }

    @Test fun stopped_cases_are_not_evicted_by_kept_ones() {
        var s = CaseFiles.setMode(CaseState(), "Old bank", listOf(bank), false, CaseMode.ON, 0, region, "stopped")
        s = CaseFiles.stop(s, "stopped")
        repeat(CaseFiles.MAX_CASES + 5) { i ->
            s = CaseFiles.setMode(s, "Org $i", listOf("+44207946%04d".format(1000 + i)), false, CaseMode.ON, 10L + i, region, "k$i")
        }
        assertEquals(CaseFiles.MAX_CASES, s.cases.count { it.kept })
        assertEquals(CaseMode.OFF, CaseFiles.byId(s, "stopped")!!.mode)
        // The organisation's next call keeps nothing and starts no new case.
        val after = CaseFiles.recordCall(s, bank, call(999), organisation = true, name = "Old bank", private = false, region = region, newId = "new")
        assertEquals(s, after)
    }

    private fun twoCases(): CaseState {
        var s = CaseFiles.recordCall(CaseState(), bank, call(1_000), organisation = true, name = "Barclays", private = false, region = region, newId = "a")
        s = CaseFiles.recordCall(s, clinic, call(5_000), organisation = true, name = "Clinic", private = false, region = region, newId = "b")
        return s
    }

    @Test fun the_list_shows_kept_cases_newest_first_and_resolved_last() {
        var s = twoCases()
        assertEquals(listOf("b", "a"), CaseFiles.listed(s).map { it.id })
        // A status set counts as activity: the bank moves up.
        s = CaseFiles.setStatus(s, "a", CaseStatus.WAITING, now = 9_000)
        assertEquals(listOf("a", "b"), CaseFiles.listed(s).map { it.id })
        assertEquals(CaseStatus.WAITING to 9_000L, CaseFiles.byId(s, "a")!!.let { it.status to it.statusAt })
        // Resolved ones go after the rest, whatever their activity.
        s = CaseFiles.setStatus(s, "a", CaseStatus.RESOLVED, now = 10_000)
        assertEquals(listOf("b", "a"), CaseFiles.listed(s).map { it.id })
        // A stopped case isn't listed, and its status can't be set.
        s = CaseFiles.stop(s, "b")
        assertEquals(listOf("a"), CaseFiles.listed(s).map { it.id })
        assertEquals(s, CaseFiles.setStatus(s, "b", CaseStatus.WAITING, now = 11_000))
    }

    @Test fun stop_for_all_stops_every_case_and_new_organisations_start_none() {
        val stopped = CaseFiles.stopAll(CaseFiles.addReference(twoCases(), "a", CaseReference("r", "Claim", "sealed", 2_000)))
        assertFalse(stopped.autoStart)
        assertTrue(stopped.cases.none { it.kept || it.calls.isNotEmpty() || it.references.isNotEmpty() })
        val council = "+442079460222"
        val after = CaseFiles.recordCall(stopped, council, call(6_000), organisation = true, name = "Council", private = false, region = region, newId = "c")
        assertNull(CaseFiles.find(after, listOf(council), region))
        assertEquals(after, CaseFiles.ensure(after, "Council", listOf(council), false, 6_000, region, "c"))
        // "Keep a case file" on a contact still works.
        val kept = CaseFiles.setMode(after, "Council", listOf(council), false, CaseMode.ON, 7_000, region, "d")
        assertTrue(CaseFiles.byId(kept, "d")!!.kept)
        // Start again: organisations start cases again, the stopped ones stay stopped.
        val again = CaseFiles.startAgain(kept)
        assertTrue(again.autoStart)
        assertFalse(CaseFiles.byId(again, "a")!!.kept)
    }

    @Test fun status_and_stop_for_all_survive_privacy_views_backups_and_restores() {
        val s = CaseFiles.setStatus(twoCases(), "a", CaseStatus.WAITING, now = 9_000).copy(autoStart = false)
        assertFalse(CaseFiles.visible(s, notesHidden = true, privateHidden = false).autoStart)
        assertFalse(CaseFiles.visible(s, notesHidden = false, privateHidden = true).autoStart)
        val restored = CaseFiles.decode(CaseFiles.encode(CaseFiles.forBackup(s, { false }) { it }))
        assertEquals(CaseStatus.WAITING, CaseFiles.byId(restored, "a")!!.status)
        assertFalse(restored.autoStart)
        // A fresh phone takes the backup's status and its "stop for all".
        val merged = CaseFiles.merge(CaseState(), restored, region)
        assertEquals(CaseStatus.WAITING, CaseFiles.find(merged, listOf(bank), region)!!.status)
        assertFalse(merged.autoStart)
        // A status set on this phone wins over the backup's.
        val mine = CaseFiles.setStatus(twoCases(), "a", CaseStatus.RESOLVED, now = 12_000)
        assertEquals(CaseStatus.RESOLVED, CaseFiles.byId(CaseFiles.merge(mine, restored, region), "a")!!.status)
        // Old stored case files have neither field: open, and organisations start cases.
        val old = CaseFiles.decode("""{"cases":[{"id":"x","name":"Bank","numbers":["$bank"]}]}""")
        assertEquals(CaseStatus.OPEN, old.cases.single().status)
        assertTrue(old.autoStart)
    }
}
