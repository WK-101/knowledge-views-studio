package app.parley.data.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.DuressView
import app.parley.common.ThemeMode
import app.parley.common.calls.SafeWord
import app.parley.common.circle.InteractionType
import app.parley.common.memory.MemoryHint
import app.parley.common.memory.MemorySource
import app.parley.common.memory.NumberMemory
import app.parley.common.security.PinProblem
import app.parley.common.security.PinVerdict
import app.parley.data.memory.NumberMemoryIndex
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

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
        Concealment.forgetForTest(newProcess = true)
        c = DataContainer(context)
    }

    @After fun tearDown() {
        Concealment.forgetForTest(newProcess = true)
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
        // L1: a safe word set now shows as set, but never replaces the hidden one unseen.
        assertTrue(c.familySafety.setSafeWord("Family", SafeWord("?", "x")))
        assertEquals("x", c.familySafety.safeWord("Family")?.answer)
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

    // ---------------------------------------------------------------- the 5.0 review's findings

    private suspend fun pins() {
        c.appPin.setPin("246810", duressSession = false)
        c.appPin.setDuress("1357")
    }

    @Test fun change_pin_in_a_duress_session_says_nothing_about_the_real_pin() = runBlocking<Unit> {
        // M5.
        pins()
        assertEquals(PinVerdict.DURESS, unlock("1357"))
        // The real PIN typed as the "new PIN": the same answer as any other, and the duress PIN stays as it was.
        assertTrue(c.appPin.setPin("246810", duressSession = true))
        assertTrue(c.appPin.setPin("8642", duressSession = true))
        // "Can't be the Parley PIN" compares with the duress PIN (the one the person watching knows), never the real one.
        assertNull(c.appPin.duressProblem("246810", duressSession = true))
        assertEquals(PinProblem.SAME_AS_PIN, c.appPin.duressProblem("8642", duressSession = true))
        // After five changes every change waits, whatever is typed.
        repeat(3) { assertTrue(c.appPin.setPin("1111", duressSession = true)) }
        assertTrue(c.appPin.changeWait() > 0)
        assertFalse(c.appPin.setPin("246810", duressSession = true))
        assertFalse(c.appPin.setPin("2222", duressSession = true))
        // A duress unlock doesn't reset that; the real PIN does, and is unchanged.
        LockTransitions.locked(c)
        assertEquals(PinVerdict.DURESS, unlock("1111"))
        assertTrue(c.appPin.changeWait() > 0)
        assertEquals(PinVerdict.NORMAL, unlock("246810"))
        assertEquals(0L, c.appPin.changeWait())
    }

    @Test fun a_duress_session_looks_like_a_phone_without_a_duress_pin() = runBlocking<Unit> {
        // M7: with a Parley PIN only a PIN unlocks, duress PIN or not: the lock screen is the same either way.
        c.appPin.setPin("246810", duressSession = false)
        assertFalse(c.appPin.load().deviceUnlocks)
        c.appPin.setDuress("1357")
        assertFalse(c.appPin.load().deviceUnlocks)
        // Settings show the duress PIN off in a session; one set there shows on for the session and is never stored.
        assertEquals(PinVerdict.DURESS, unlock("1357"))
        assertEquals(AppPinStore.Summary(pinSet = true, duressSet = false), c.appPin.shown.value)
        assertTrue(c.appPin.setDuress("2468", duressSession = true))
        assertTrue(c.appPin.shown.value!!.duressSet)
        assertTrue(c.appPin.isShownDuress("2468", duressSession = true))
        LockTransitions.locked(c)
        assertEquals(PinVerdict.WRONG, c.appPin.check("2468").verdict)
        assertEquals(c.appPin.summary.value, c.appPin.shown.value)
        assertTrue(c.appPin.shown.value!!.duressSet)
    }

    @Test fun the_wrong_try_count_fails_closed_when_it_cant_be_stored() = runBlocking<Unit> {
        // M9: storage refuses the record (here: something in the way of its temporary file).
        c.appPin.setPin("246810", duressSession = false)
        val blocker = File(context.noBackupFilesDir, "app_pin.tmp").apply { mkdirs(); File(this, "x").writeText("x") }
        try {
            // The first try isn't checked at all, and waits.
            val first = c.appPin.check("246810")
            assertEquals(PinVerdict.WRONG, first.verdict)
            assertTrue(first.waitMs > 0)
            ShadowSystemClock.advanceBy(Duration.ofMillis(first.waitMs))
            // Then tries are checked, every wrong one waits, and the count is kept in memory.
            val wrong = c.appPin.check("1111")
            assertEquals(PinVerdict.WRONG, wrong.verdict)
            assertTrue(wrong.waitMs > 0)
            assertTrue(c.appPin.waitNow() > 0)
            ShadowSystemClock.advanceBy(Duration.ofMillis(c.appPin.waitNow()))
            assertEquals(PinVerdict.NORMAL, c.appPin.check("246810").verdict)
        } finally {
            blocker.deleteRecursively()
        }
    }

    @Test fun an_unreadable_pin_record_never_lets_the_screen_lock_in() = runBlocking<Unit> {
        // L5: a record that can't be opened (not hiding) keeps the PIN field; the screen lock doesn't open Parley.
        File(context.noBackupFilesDir, "app_pin").writeBytes(ByteArray(40) { 7 })
        val store = AppPinStore(context) { RecordCrypto.get(context) }
        val summary = store.load()
        assertTrue(summary.pinSet)
        assertFalse(summary.deviceUnlocks)
        assertEquals(PinVerdict.WRONG, store.check("246810").verdict)
        File(context.noBackupFilesDir, "app_pin").delete()
    }

    @Test fun hiding_that_cant_be_stored_holds_in_memory_and_is_stored_later() = runBlocking<Unit> {
        // L6.
        pins()
        val state = File(context.noBackupFilesDir, "app_lock_state")
        val blocker = File(context.noBackupFilesDir, "app_lock_state.tmp").apply { mkdirs(); File(this, "x").writeText("x") }
        var now = 0L
        Concealment.nanos = { now }
        try {
            assertEquals(PinVerdict.DURESS, unlock("1357"))
            assertTrue(Concealment.hiding)
            assertFalse(state.exists())
            blocker.deleteRecursively()
            now += 3_000_000_000L
            assertTrue(Concealment.hiding)
            assertTrue("written once it can be", state.exists())
        } finally {
            blocker.deleteRecursively()
            Concealment.nanos = System::nanoTime
        }
    }

    @Test fun notes_and_safe_words_written_in_a_duress_session_stay_and_hidden_ones_stay_hidden() = runBlocking<Unit> {
        // L1.
        pins()
        c.meta.setMeta(ContactMetaEntity("k1", pinnedNote = "Shelter: room 4"))
        c.meta.setMeta(ContactMetaEntity("k2"))
        assertEquals(PinVerdict.DURESS, unlock("1357"))
        // A note on a contact without one is stored, and shows; one typed over a hidden note shows instead of it.
        c.meta.setPinnedNote("k2", 2L, "Pick up milk")
        assertEquals("Pick up milk", c.meta.meta("k2")!!.pinnedNote)
        c.meta.setPinnedNote("k1", 1L, "Dentist Tuesday")
        assertEquals("Dentist Tuesday", c.meta.meta("k1")!!.pinnedNote)
        val moment = c.circle.interactions.log("k3", 3L, InteractionType.MEET, null, 10L, "Coffee", "d3")!!
        assertEquals("Coffee", c.circle.interactions.noteOf(moment))
        assertTrue(c.familySafety.setSafeWord("School", SafeWord("Teacher?", "Ms Bee")))
        assertEquals("Ms Bee", c.familySafety.safeWord("School")?.answer)
        // After the real PIN: what was written is kept, the hidden note is as it was.
        assertEquals(PinVerdict.NORMAL, unlock("246810"))
        assertEquals("Pick up milk", c.meta.meta("k2")!!.pinnedNote)
        assertEquals("Shelter: room 4", c.meta.meta("k1")!!.pinnedNote)
        assertEquals("Coffee", c.circle.interactions.noteOf(moment))
        assertEquals("Ms Bee", c.familySafety.safeWord("School")?.answer)
    }

    @Test fun a_duress_unlock_clears_parleys_notifications_but_not_a_call() = runBlocking<Unit> {
        // L2.
        pins()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("t", "t", NotificationManager.IMPORTANCE_LOW))
        fun post(id: Int, ongoing: Boolean) {
            val n = Notification.Builder(context, "t").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Missed call: Sam")
            nm.notify(id, n.setOngoing(ongoing).build())
        }
        post(1, ongoing = false)
        post(2, ongoing = true)
        assertEquals(PinVerdict.DURESS, unlock("1357"))
        assertEquals(listOf(2), nm.activeNotifications.map { it.id })
        nm.cancelAll()
    }

    @Test fun number_memory_drops_note_hints_while_notes_are_hidden() = runBlocking<Unit> {
        // H3: the index was built before the duress unlock and still holds the excerpt.
        pins()
        val number = "+447700900123"
        val hint = MemoryHint(MemorySource.NOTE, name = "Ana", excerpt = "Shelter, room 4", at = 5, ref = "k1")
        c.numberMemory.index.rebuild(
            listOf(
                object : NumberMemoryIndex.Source {
                    override val id = "notes"
                    override suspend fun fingerprint(): String? = "1"
                    override suspend fun read() = listOf(NumberMemory.Entry(number, hint))
                },
            ),
            "GB",
        )
        fun sources() = runBlocking { c.numberMemory.hints(number, NumberMemory.Place.KEYPAD, "GB") }.map { it.source }
        assertTrue(MemorySource.NOTE in sources())
        assertEquals(PinVerdict.DURESS, unlock("1357"))
        assertTrue(MemorySource.NOTE !in sources())
        LockTransitions.locked(c)
        assertTrue("still hidden after the lock", MemorySource.NOTE !in sources())
        assertEquals(PinVerdict.NORMAL, unlock("246810"))
        assertTrue(MemorySource.NOTE in sources())
    }

    @Test fun nothing_writes_the_stored_discreet_switch_while_hiding_and_private_names_fail_closed() = runBlocking<Unit> {
        // M6: the Quick Settings tile (or anything else) while Parley is locked after a duress unlock.
        pins()
        c.settings.update { it.copy(hideVault = false) }
        assertFalse(c.settings.hidesPrivateNames())
        assertEquals(PinVerdict.DURESS, unlock("1357"))
        LockTransitions.locked(c)
        c.settings.update { it.copy(hideVault = true) }
        c.settings.update { it.copy(hideVault = false) }
        assertEquals("b:false", c.settings.exportMap()["hide_vault"] ?: "b:false")
        // M8: the private-name providers read the settings themselves, and fail closed.
        assertTrue(c.settings.hidesPrivateNames())
        assertEquals(PinVerdict.NORMAL, unlock("246810"))
        assertFalse(c.settings.hidesPrivateNames())
        assertTrue("a read that doesn't finish in time counts as hidden", c.settings.hidesPrivateNames(timeoutMs = 0))
    }
}
