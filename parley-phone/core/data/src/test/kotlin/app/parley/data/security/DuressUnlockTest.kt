package app.parley.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.DuressView
import app.parley.common.ThemeMode
import app.parley.common.calls.SafeWord
import app.parley.common.circle.InteractionType
import app.parley.common.security.PinVerdict
import app.parley.data.DataContainer
import app.parley.data.db.ContactMetaEntity
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
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

/**
 * I21: the lock flow with real stores. A duress PIN opens a session that hides what it should, keeps the safety
 * switches' changes in memory, survives a lock and a restart, and only the Parley PIN brings everything back, with
 * nothing lost on the way.
 */
@RunWith(RobolectricTestRunner::class)
class DuressUnlockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        File(context.noBackupFilesDir, "app_pin").delete()
        File(context.noBackupFilesDir, "app_lock_state").delete()
        Concealment.forgetForTest()
        c = DataContainer(context)
    }

    @After fun tearDown() {
        Concealment.forgetForTest()
        File(context.noBackupFilesDir, "app_lock_state").delete()
        c.scope.cancel()
    }

    private suspend fun unlock(pin: String): PinVerdict {
        val a = c.appPin.check(pin)
        LockTransitions.pinEntered(c, a)
        return a.verdict
    }

    private suspend fun storedAppLock(): String? = c.settings.exportMap()["app_lock"]

    @Test fun pins_are_kept_as_sealed_hashes_and_told_apart() = runBlocking<Unit> {
        assertTrue(c.appPin.setPin("246810", duressSession = false))
        assertFalse("the duress PIN can't be the Parley PIN", c.appPin.setDuress("246810"))
        assertTrue(c.appPin.setDuress("1357"))
        val stored = File(context.noBackupFilesDir, "app_pin").readBytes()
        assertTrue(RecordCrypto.get(context).isSealed(stored))
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("246810"))
        assertEquals(PinVerdict.NORMAL, c.appPin.check("246810").verdict)
        assertEquals(PinVerdict.DURESS, c.appPin.check("1357").verdict)
        assertEquals(PinVerdict.WRONG, c.appPin.check("0000").verdict)
        assertFalse(c.appPin.load().deviceUnlocks)
        // Turning the PIN off takes the duress PIN with it.
        assertTrue(c.appPin.removePin(duressSession = false))
        assertEquals(AppPinStore.Summary(), c.appPin.load())
    }

    @Test fun wrong_pins_wait_and_a_waiting_screen_checks_nothing() = runBlocking<Unit> {
        c.appPin.setPin("246810", duressSession = false)
        repeat(4) { assertEquals(0L, c.appPin.check("1111").waitMs) }
        assertEquals(30_000L, c.appPin.check("1111").waitMs)
        // Even the right PIN isn't tried during the wait.
        val waiting = c.appPin.check("246810")
        assertEquals(PinVerdict.WRONG, waiting.verdict)
        assertTrue(waiting.waitMs > 0)
        assertTrue(c.appPin.waitNow() > 0)
    }

    @Test fun duress_hides_until_the_real_pin_and_loses_nothing() = runBlocking<Unit> {
        c.appPin.setPin("246810", duressSession = false)
        c.appPin.setDuress("1357")
        c.settings.update { it.copy(appLock = true, hideVault = false, privateVaultHistory = true, themeMode = ThemeMode.DARK) }
        c.meta.setMeta(ContactMetaEntity("k1", pinnedNote = "Shelter: room 4"))
        val interaction = c.circle.interactions.log("k1", 1L, InteractionType.MEET, null, 10L, "[ ] Call the lawyer", "d1")!!
        assertTrue(c.familySafety.setSafeWord("Family", SafeWord("Pet's name?", "Biscuit")))

        assertEquals(PinVerdict.DURESS, unlock("1357"))
        // Discreet mode is forced, while the Privacy page shows the switches as they were.
        val s = c.settings.current()
        assertTrue(s.hideVault)
        assertEquals(DuressView(hideVault = false, privateVaultHistory = true), s.duress)
        assertTrue(c.settings.settings.first { it.duress != null }.hideVault)
        // Notes, promises and safe words read as none; private details refuse to open.
        assertNull(c.meta.meta("k1")!!.pinnedNote)
        assertNull(c.circle.interactions.interactionsFor("k1").single().note)
        assertTrue(c.familySafety.safeWords().isEmpty())
        assertTrue(c.familySafety.summary.value.safeWordLabels.isEmpty())
        assertFalse("a safe word can't be replaced unseen", c.familySafety.setSafeWord("Family", SafeWord("?", "x")))
        assertTrue(VaultCrypto.detailLocked)
        assertTrue(VaultCrypto.detailNeedsUnlock())
        // Writes that pass "no note" back keep the stored notes.
        c.meta.setMeta(ContactMetaEntity("k1", pinnedNote = null, reachOutDays = 14))
        c.circle.interactions.edit(interaction, InteractionType.MESSAGE, null)

        // Someone turns the app lock off and changes the theme, then changes the PIN.
        c.settings.update { it.copy(appLock = false, themeMode = ThemeMode.LIGHT) }
        assertFalse(c.settings.current().appLock)
        assertEquals("b:true", storedAppLock())
        assertEquals(ThemeMode.LIGHT, c.settings.current().themeMode)
        assertTrue(c.appPin.setPin("8642", duressSession = true))
        assertTrue(c.appPin.removePin(duressSession = true))
        assertTrue(c.appPin.sessionShownOff.value)

        // Locking ends the session, not the hiding; the session's switches are forgotten.
        LockTransitions.locked(c)
        assertTrue(c.settings.current().appLock)
        assertTrue(c.settings.current().hideVault)
        assertFalse(c.appPin.sessionShownOff.value)
        assertNull(c.meta.meta("k1")!!.pinnedNote)
        // The new PIN typed in the session is the duress PIN; the real one is unchanged.
        assertEquals(PinVerdict.DURESS, c.appPin.check("8642").verdict)
        assertEquals(PinVerdict.WRONG, c.appPin.check("1357").verdict)

        // A restart keeps hiding.
        Concealment.forgetForTest()
        assertTrue(Concealment.hiding)
        assertTrue(c.settings.current().hideVault)
        assertTrue(VaultCrypto.detailLocked)

        // The real PIN brings everything back, as it was.
        assertEquals(PinVerdict.NORMAL, unlock("246810"))
        assertFalse(Concealment.hiding)
        assertFalse(VaultCrypto.detailLocked)
        assertFalse(File(context.noBackupFilesDir, "app_lock_state").exists())
        val after = c.settings.current()
        assertFalse(after.hideVault)
        assertNull(after.duress)
        assertEquals("Shelter: room 4", c.meta.meta("k1")!!.pinnedNote)
        assertEquals(14, c.meta.meta("k1")!!.reachOutDays)
        val back = c.circle.interactions.interactionsFor("k1").single()
        assertEquals("[ ] Call the lawyer", back.note)
        assertEquals(InteractionType.MESSAGE, back.type)
        assertEquals("Biscuit", c.familySafety.safeWords()["Family"]?.answer)
    }

    @Test fun the_vault_option_can_leave_details_openable() = runBlocking<Unit> {
        c.appPin.setPin("246810", duressSession = false)
        c.appPin.setDuress("1357")
        c.appPin.setLockVaultOnDuress(false)
        assertEquals(PinVerdict.DURESS, unlock("1357"))
        assertTrue(Concealment.hiding)
        assertFalse(VaultCrypto.detailLocked)
        unlock("246810")
    }
}
