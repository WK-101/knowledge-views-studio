package app.parley.data.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuPath
import app.parley.common.calls.MenuShortcut
import app.parley.common.calls.MenuStep
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

/** Menu memory is sealed at rest, never overwritten while unreadable, and private numbers stay out of the backup. */
@RunWith(RobolectricTestRunner::class)
class MenuMemoryStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { context.getSharedPreferences("menu_memory", Context.MODE_PRIVATE) }
    private val crypto by lazy { RecordCrypto.get(context) }
    private val bank = "+442079460000"
    private val ana = "+447700900123"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        prefs.edit().clear().commit()
    }

    private fun path(number: String, tone: Char) = MenuPath(listOf(MenuStep(tone, 3_000)), at = 1, number = number)

    @Test fun stored_sealed_and_read_back() = runBlocking {
        val store = MenuMemoryStore(context) { false }
        store.update { MenuMemory.remember(it, "k", path(bank, '2')) }
        val raw = prefs.getString("state_v1", null)
        assertTrue(crypto.isSealed(raw))
        assertFalse(raw!!.contains(bank))
        val again = MenuMemoryStore(context) { false }
        assertEquals(path(bank, '2'), MenuMemory.pathFor(again.load(), "k"))
    }

    @Test fun an_unreadable_state_is_never_overwritten() = runBlocking {
        val unreadable = RecordCrypto.TEXT_PREFIX + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        prefs.edit().putString("state_v1", unreadable).commit()
        val store = MenuMemoryStore(context) { false }
        assertNull(store.update { MenuMemory.remember(it, "k", path(bank, '2')) })
        assertFalse(store.available)
        assertEquals(unreadable, prefs.getString("state_v1", null))
    }

    @Test fun nothing_plain_when_sealing_fails() = runBlocking {
        val store = MenuMemoryStore(context, { false }) { null }
        store.update { MenuMemory.remember(it, "k", path(bank, '2')) }
        assertNull(prefs.getString("state_v1", null))
        assertEquals(1, store.state.value.paths.size)
    }

    @Test fun the_backup_leaves_private_numbers_out_and_restores_by_merging() = runBlocking {
        val store = MenuMemoryStore(context) { it == ana }
        store.update {
            val s = MenuMemory.remember(MenuMemory.remember(it, "bank", path(bank, '2')), "ana", path(ana, '9'))
            MenuMemory.addShortcut(s, MenuShortcut("a", "Ana › 9", ana, listOf(MenuStep('9', 3_000)), 1), "GB")
        }
        val exported = store.backupExtras.export()
        prefs.edit().clear().commit()
        val fresh = MenuMemoryStore(context) { false }
        fresh.backupExtras.import(exported)
        val restored = fresh.state.value
        assertEquals(setOf("bank"), restored.paths.keys)
        assertTrue(restored.shortcuts.isEmpty())
    }
}
