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

    @Test fun typed_keys_offer_the_last_long_run_of_digits() {
        assertEquals("41234567", CaseFiles.typedReference("2#41234567#"))
        assertEquals("998877", CaseFiles.typedReference("3*12345*998877"))
        assertNull(CaseFiles.typedReference("2#1#4"))
        assertNull(CaseFiles.typedReference(""))
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
}
