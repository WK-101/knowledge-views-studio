package app.parley.data.cases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.cases.CaseCall
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseMode
import app.parley.common.cases.CaseState
import app.parley.common.security.DuressMachine
import app.parley.common.security.PinVerdict
import app.parley.data.security.Concealment
import app.parley.data.security.RecordCrypto
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Case files are sealed at rest, each reference number again on its own, never overwritten while unreadable, and hidden under duress. */
@RunWith(RobolectricTestRunner::class)
class CaseFileStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { context.getSharedPreferences("case_files", Context.MODE_PRIVATE) }
    private val crypto by lazy { RecordCrypto.get(context) }
    private val bank = "+442079460000"
    private val clinic = "+442079460111"
    private val discreet = MutableStateFlow(false)

    private val folder by lazy { File(context.noBackupFilesDir, "docs/case_files") }

    /** Every file of the case files, by name. */
    private fun files(): Map<String, String> = folder.listFiles().orEmpty().associate { it.name to it.readText() }

    private fun store(private: Set<String> = emptySet()) = CaseFileStore(context, { it in private }, { discreet }) { "GB" }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        prefs.edit().clear().commit()
        folder.deleteRecursively()
        Concealment.init(context)
        Concealment.forgetForTest(newProcess = true)
    }

    @After fun tearDown() {
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.NORMAL, false))
    }

    private suspend fun CaseFileStore.keep(name: String, number: String, private: Boolean = false): String {
        update { CaseFiles.setMode(it, name, listOf(number), private, CaseMode.ON, 1, "GB", name) }
        return name
    }

    @Test fun stored_sealed_with_each_reference_sealed_again() = runBlocking {
        val s = store()
        val id = s.keep("Northwind Energy", bank)
        assertTrue(s.addReference(id, "Complaint", " CMP 7781 ", typed = false, now = 5))
        val raw = files()
        // The index and the one case (its id has a space, so its file is named by a digest of it).
        assertEquals(2, raw.size)
        raw.values.forEach { assertTrue(crypto.isSealed(it)) }
        assertFalse(raw.values.any { it.contains("Northwind") })
        // In memory too the reference is sealed: the case file holds no plain reference number.
        val ref = CaseFiles.byId(s.state.value, id)!!.references.single()
        assertTrue(crypto.isSealed(ref.value))
        assertFalse(CaseFiles.encode(s.state.value).contains("CMP 7781"))
        assertEquals("CMP 7781", s.openReference(ref))
        assertEquals("Complaint", ref.label)

        val again = store()
        val read = CaseFiles.byId(again.load(), id)!!
        assertEquals("CMP 7781", again.openReference(read.references.single()))
    }

    @Test fun an_unreadable_state_is_never_overwritten() = runBlocking {
        val unreadable = RecordCrypto.TEXT_PREFIX + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        prefs.edit().putString("state", unreadable).commit()
        val s = store()
        assertNull(s.update { CaseFiles.setMode(it, "Bank", listOf(bank), false, CaseMode.ON, 1, "GB", "a") })
        assertFalse(s.available)
        assertEquals(unreadable, prefs.getString("state", null))
        assertTrue(files().isEmpty())
    }

    @Test fun nothing_plain_when_sealing_fails() = runBlocking {
        val s = CaseFileStore(context, { false }, { discreet }, { "GB" }, { null }, null)
        s.update { CaseFiles.setMode(it, "Bank", listOf(bank), false, CaseMode.ON, 1, "GB", "a") }
        assertNull(prefs.getString("state", null))
        assertTrue(files().isEmpty())
        // A reference that can't be sealed isn't kept at all.
        assertFalse(s.addReference("a", "", "12345678", typed = true))
        assertTrue(CaseFiles.byId(s.state.value, "a")!!.references.isEmpty())
    }

    @Test fun duress_hides_every_case_file_and_discreet_mode_private_ones() = runBlocking {
        val s = store()
        s.keep("Bank", bank)
        s.keep("Clinic", clinic, private = true)
        assertEquals(2, s.shown.first().cases.size)
        discreet.value = true
        assertEquals(listOf("Bank"), s.shown.first().cases.map { it.name })
        discreet.value = false
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.DURESS, false))
        assertEquals(CaseState(), s.shown.first())
        // Hiding changes nothing stored, and a call is still kept meanwhile.
        s.update { CaseFiles.recordCall(it, bank, CaseCall(9, incoming = false), false, "", false, "GB", "x") }
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.NORMAL, false))
        assertEquals(listOf(9L), s.shown.first().cases.first { it.name == "Bank" }.calls.map { it.at })
    }

    @Test fun a_contact_made_private_after_its_case_hides_it_in_discreet_mode() = runBlocking {
        val privateNow = mutableSetOf<String>()
        val moved = MutableStateFlow(0)
        val s = CaseFileStore(context, { it in privateNow }, { discreet }, { moved }) { "GB" }
        s.keep("Northwind", bank)
        discreet.value = true
        assertEquals(listOf("Northwind"), s.shown.first().cases.map { it.name })
        // Made private: the case was made with private = false, and still hides.
        privateNow += bank
        moved.value++
        assertEquals(CaseState(), s.shown.first())
        assertTrue(s.isPrivateNow(s.state.value.cases.single()))
        discreet.value = false
        assertEquals(1, s.shown.first().cases.size)
    }

    @Test fun the_backup_leaves_private_contacts_out_and_carries_references_opened() = runBlocking {
        val s = store(private = setOf(clinic))
        s.keep("Bank", bank)
        s.keep("Clinic", clinic)
        assertTrue(s.addReference("Bank", "Claim", "CLM-1", typed = false))
        assertTrue(s.addReference("Clinic", "", "PX-2", typed = false))
        val values = s.backupExtras.export()
        val backup = CaseFiles.decode(values.values.single())
        assertEquals(listOf("Bank"), backup.cases.map { it.name })
        assertEquals("CLM-1", backup.cases.single().references.single().value)

        // Restored on a phone with nothing: sealed again with this phone's key.
        prefs.edit().clear().commit()
        folder.deleteRecursively()
        val fresh = store()
        fresh.backupExtras.import(values)
        val ref = CaseFiles.byId(fresh.state.value, "Bank")!!.references.single()
        assertTrue(crypto.isSealed(ref.value))
        assertEquals("CLM-1", fresh.openReference(ref))
    }

    @Test fun a_backup_made_during_a_duress_unlock_carries_no_case_files() = runBlocking {
        val s = store()
        s.keep("Bank", bank)
        assertTrue(s.addReference("Bank", "Claim", "CLM-1", typed = false))
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.DURESS, false))
        val values = s.backupExtras.export()
        assertTrue(values.isEmpty())
        // Nothing stored changed, and the real PIN brings them back into the next backup.
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.NORMAL, false))
        val after = CaseFiles.decode(s.backupExtras.export().values.single())
        assertEquals("CLM-1", after.cases.single().references.single().value)
    }

    /** 6.4 kept every case in one sealed preferences value: it moves to one file per case, once. */
    @Test fun the_single_value_moves_to_a_file_per_case_once() = runBlocking {
        val before = store().let { s ->
            s.keep("Bank", bank)
            s.keep("Clinic", clinic)
            s.state.value
        }
        folder.deleteRecursively()
        prefs.edit().putString("state", crypto.sealText(CaseFiles.encode(before))).commit()

        val s = store()
        assertEquals(before, s.load())
        assertEquals(3, files().size)
        assertNull(prefs.getString("state", null))

        // A crash after the files were written but before the preferences value went: the files win, nothing doubles.
        prefs.edit().putString("state", crypto.sealText(CaseFiles.encode(CaseState()))).commit()
        val again = store()
        assertEquals(before, again.load())
        assertNull(prefs.getString("state", null))
    }

    @Test fun a_move_cut_short_before_the_index_is_made_again() = runBlocking {
        val before = store().let { s ->
            s.keep("Bank", bank)
            s.state.value
        }
        // Only a case file reached the disk; no index lists it, so the preferences value is still what counts.
        File(folder, "index.sealed").delete()
        prefs.edit().putString("state", crypto.sealText(CaseFiles.encode(before))).commit()
        val s = store()
        assertEquals(before, s.load())
        assertTrue(File(folder, "index.sealed").isFile)
        assertNull(prefs.getString("state", null))
    }

    @Test fun a_call_rewrites_its_own_case_only_and_a_removed_case_leaves_no_file() = runBlocking {
        val s = store()
        s.keep("Bank", bank)
        s.keep("Clinic", clinic)
        val before = files()
        s.update { CaseFiles.recordCall(it, bank, CaseCall(9, incoming = false), false, "", false, "GB", "x") }
        val after = files()
        assertEquals(before["case-Clinic.sealed"], after["case-Clinic.sealed"])
        assertFalse(before["case-Bank.sealed"] == after["case-Bank.sealed"])

        s.update { st -> st.copy(cases = st.cases.filter { it.id != "Clinic" }) }
        assertEquals(setOf("index.sealed", "case-Bank.sealed"), files().keys)
        assertEquals(listOf("Bank"), store().load().cases.map { it.name })
    }
}
