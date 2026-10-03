package app.parley.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.LookupApproval
import app.parley.common.security.PinVerdict
import app.parley.data.DataContainer
import app.parley.data.SettingsRepository
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.cancel
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
 * What a duress session can't make stick: the private-name switches and approvals, and settings brought back by a
 * restore. The screens show the change; nothing is stored, and the next lock forgets it.
 */
@RunWith(RobolectricTestRunner::class)
class DuressSafetySwitchesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        File(context.noBackupFilesDir, "app_pin").delete()
        File(context.noBackupFilesDir, "app_lock_state").delete()
        context.getSharedPreferences("private_names", Context.MODE_PRIVATE).edit().clear().commit()
        Concealment.forgetForTest(newProcess = true)
        c = DataContainer(context)
    }

    @After fun tearDown() {
        Concealment.forgetForTest(newProcess = true)
        File(context.noBackupFilesDir, "app_lock_state").delete()
        c.scope.cancel()
    }

    private suspend fun duressUnlock() {
        c.appPin.setPin("246810", duressSession = false)
        c.appPin.setDuress("1357")
        val a = c.appPin.check("1357")
        assertEquals(PinVerdict.DURESS, a.verdict)
        LockTransitions.pinEntered(c, a)
        assertTrue(Concealment.hiding)
    }

    private fun storedPrefs() = context.getSharedPreferences("private_names", Context.MODE_PRIVATE)

    @Test fun private_name_switches_and_approvals_change_only_what_shows() = runBlocking<Unit> {
        val names = c.people.privateNames
        duressUnlock()
        names.setEnabled(true)
        assertFalse(names.setDirectoryEnabled(true))
        names.setApproval("com.example.watcher", LookupApproval.ALLOWED)
        names.setApproval("com.example.watcher", LookupApproval.DENIED, directory = true)
        // The screens see the session's view...
        assertTrue(names.state.value.enabled)
        assertTrue(names.state.value.directory)
        assertEquals(LookupApproval.ALLOWED, names.state.value.approvals["com.example.watcher"])
        // ...the providers and the stored file don't.
        assertFalse(names.stored.enabled)
        assertFalse(names.stored.directory)
        assertNull(names.approval("com.example.watcher"))
        assertFalse(storedPrefs().getBoolean("enabled", false))
        assertFalse(storedPrefs().getBoolean("directory", false))
        assertFalse(storedPrefs().getString("approvals", "{}")!!.contains("watcher"))
        // A restore can't grant either.
        names.importApprovals("""{"com.example.watcher":{"approval":"DENIED"}}""")
        assertFalse(storedPrefs().getString("approvals", "{}")!!.contains("watcher"))
        // The next lock forgets the session's view.
        LockTransitions.locked(c)
        assertFalse(names.state.value.enabled)
        assertTrue(names.state.value.approvals.isEmpty())
    }

    @Test fun a_providers_pending_request_is_still_logged_while_hiding() = runBlocking<Unit> {
        val names = c.people.privateNames
        duressUnlock()
        names.markPending("com.example.app", directory = false)
        assertEquals(LookupApproval.PENDING, names.approval("com.example.app"))
        assertTrue(storedPrefs().getString("approvals", "{}")!!.contains("com.example.app"))
    }

    @Test fun outside_duress_the_switches_are_stored() = runBlocking<Unit> {
        val names = c.people.privateNames
        names.setEnabled(true)
        assertTrue(names.setDirectoryEnabled(true))
        names.setApproval("com.example.app", LookupApproval.DENIED)
        assertTrue(storedPrefs().getBoolean("enabled", false))
        assertTrue(names.stored.directory)
        assertEquals(LookupApproval.DENIED, names.approval("com.example.app"))
    }

    @Test fun a_restore_in_a_duress_session_leaves_the_stored_safety_switches_alone() = runBlocking<Unit> {
        c.settings.update { it.copy(appLock = true, hideVault = false, privateVaultHistory = true) }
        duressUnlock()
        c.settings.importMap(mapOf("app_lock" to "b:false", "private_vault_history" to "b:false", "theme" to "s:DARK"))
        val stored = c.settings.exportMap()
        assertEquals("b:true", stored["app_lock"])
        assertEquals("b:true", stored["private_vault_history"])
        // Ordinary settings restore as usual.
        assertEquals("s:DARK", stored["theme"])
        // The session sees what was restored.
        assertFalse(c.settings.current().appLock)
        LockTransitions.locked(c)
        assertTrue(c.settings.current().appLock)
    }

    @Test fun restored_privacy_settings_wait_for_confirmation() {
        for (k in listOf("app_lock", "lock_after_minutes", "secure_screen", "hide_vault", "private_vault_history", "lock_screen_caller")) {
            assertTrue(k, k in SettingsRepository.SECURITY_KEYS)
        }
    }
}
