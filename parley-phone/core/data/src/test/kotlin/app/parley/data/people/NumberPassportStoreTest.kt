package app.parley.data.people

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.people.Attribution
import app.parley.common.people.NumberOrigin
import app.parley.data.security.RecordCrypto
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The number passport is sealed at rest, stamps Parley's own writes by origin, and an unreadable one warns about nothing. */
@RunWith(RobolectricTestRunner::class)
class NumberPassportStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder by lazy { File(context.noBackupFilesDir, "docs/number_passport") }
    private val mumOld = "07700 900001"
    private val mumNew = "07700 900999"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        folder.deleteRecursively()
    }

    private fun store() = NumberPassportStore(context, { "GB" }, null)
    private fun mum(vararg numbers: String) = ContactSummary(1, "mum", "Mum", null, false, numbers.map { PhoneEntry(it, 2, null) })

    @Test fun sealed_at_rest_and_another_apps_change_is_stamped() = runBlocking {
        val s = store()
        s.sweep(listOf(mum(mumOld)), { null }, now = 1_000)
        s.sweep(listOf(mum(mumNew)), { Attribution(NumberOrigin.OTHER_APP, 1_500) }, now = 2_000)
        val raw = File(folder, "index.sealed").readText()
        assertTrue(RecordCrypto.get(context).isSealed(raw))
        assertFalse(raw.contains("900999"))
        val entry = store().entry("+447700900999")!!
        assertEquals(NumberOrigin.OTHER_APP, entry.stamp?.origin)
        assertEquals(1_500L, entry.stamp?.since)
        assertEquals(mumOld, entry.stamp?.older)
    }

    @Test fun editor_hints_and_bulk_writes_keep_their_origin() = runBlocking {
        val s = store()
        s.sweep(listOf(mum(mumOld)), { null }, now = 1_000)
        s.hint(listOf(mumNew), NumberOrigin.PASTED, now = 1_100)
        s.saved("mum", listOf(mumOld), listOf(mumOld, mumNew), now = 1_200)
        assertEquals(NumberOrigin.PASTED, s.entry(mumNew)?.stamp?.origin)
        // The hint is used once: the same number typed later is yours.
        s.saved("mum", listOf(mumOld, mumNew), listOf(mumOld), now = 1_300)
        s.saved("mum", listOf(mumOld), listOf(mumOld, mumNew), now = 1_400)
        assertEquals(NumberOrigin.YOU, s.entry(mumNew)?.stamp?.origin)
        s.written(listOf("07700 900777"), NumberOrigin.RESTORED, now = 1_500)
        s.sweep(listOf(mum(mumOld, mumNew, "07700 900777")), { Attribution(NumberOrigin.OTHER_APP, null) }, now = 1_600)
        assertEquals(NumberOrigin.RESTORED, s.entry("07700 900777")?.stamp?.origin)
    }

    @Test fun an_unreadable_passport_answers_nothing() = runBlocking {
        folder.mkdirs()
        File(folder, "index.sealed").writeText(RecordCrypto.TEXT_PREFIX + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val s = store()
        assertNull(s.entry(mumNew))
        s.sweep(listOf(mum(mumNew)), { Attribution(NumberOrigin.OTHER_APP, null) }, now = 1_000)
        assertFalse(s.available)
    }
}
