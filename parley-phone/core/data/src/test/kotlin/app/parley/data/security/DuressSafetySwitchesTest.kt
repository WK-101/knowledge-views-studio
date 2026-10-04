package app.parley.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.LockScreenCaller
import app.parley.common.people.LookupApproval
import app.parley.common.security.PinVerdict
import app.parley.data.DataContainer
import app.parley.data.SettingsRepository
import app.parley.data.people.PrivateNameAccess
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
 * What a duress session can't make stick: the private-name Directory switch and approvals, and settings brought back by a
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
        assertFalse(names.setDirectoryEnabled(true))
        names.setApproval("com.example.watcher", LookupApproval.DENIED)
        // The screens see the session's view...
        assertTrue(names.state.value.directory)
        assertEquals(LookupApproval.DENIED, names.state.value.approvals["com.example.watcher"])
        // ...the provider and the stored file don't.
        assertFalse(names.stored.directory)
        assertNull(names.approval("com.example.watcher"))
        assertFalse(storedPrefs().getBoolean("directory", false))
        assertFalse(storedPrefs().getString("directory_approvals", "{}")!!.contains("watcher"))
        // A restore can't grant either.
        names.importApprovals("""{"directory:com.example.watcher":{"approval":"DENIED"}}""")
        assertFalse(storedPrefs().getString("directory_approvals", "{}")!!.contains("watcher"))
        // The next lock forgets the session's view.
        LockTransitions.locked(c)
        assertFalse(names.state.value.directory)
        assertTrue(names.state.value.approvals.isEmpty())
    }

    @Test fun a_providers_pending_request_is_still_logged_while_hiding() = runBlocking<Unit> {
        val names = c.people.privateNames
        duressUnlock()
        names.markPending("com.example.app")
        assertEquals(LookupApproval.PENDING, names.approval("com.example.app"))
        assertTrue(storedPrefs().getString("directory_approvals", "{}")!!.contains("com.example.app"))
    }

    @Test fun outside_duress_the_switches_are_stored() = runBlocking<Unit> {
        val names = c.people.privateNames
        assertTrue(names.setDirectoryEnabled(true))
        names.setApproval("com.example.app", LookupApproval.DENIED)
        assertTrue(names.stored.directory)
        assertEquals(LookupApproval.DENIED, names.approval("com.example.app"))
    }

    /** What the removed lookup provider kept goes the first time the store opens; the Directory's data stays. */
    @Test fun the_lookup_providers_data_is_removed_and_the_directorys_kept() = runBlocking<Unit> {
        storedPrefs().edit()
            .putBoolean("enabled", true)
            .putString("approvals", """{"com.example.lookup":"DENIED"}""")
            .putString("approval_certs", "{}")
            .putBoolean("directory", true)
            .putString("directory_approvals", """{"com.example.phone":"DENIED"}""")
            .putString("asked_at", """{"p:com.example.lookup":1,"d:com.example.phone":2}""")
            .putString("log", """[{"p":"com.example.lookup","t":1,"o":"DENIED"},{"p":"com.example.phone","t":2,"o":"DENIED","d":true}]""")
            .commit()
        val names = PrivateNameAccess(context)
        listOf("enabled", "approvals", "approval_certs").forEach { assertFalse(it, storedPrefs().contains(it)) }
        assertFalse(storedPrefs().getString("asked_at", "")!!.contains("p:"))
        assertTrue(storedPrefs().getString("asked_at", "")!!.contains("d:com.example.phone"))
        assertTrue(names.stored.directory)
        assertEquals(mapOf("com.example.phone" to LookupApproval.DENIED), names.stored.approvals)
        assertEquals(listOf("com.example.phone"), names.stored.log.map { it.packageName })
        // A backup made before still restores its Directory approvals, and skips the lookup provider's.
        names.setApproval("com.example.phone", null)
        names.importApprovals("""{"com.example.lookup":{"approval":"DENIED"},"directory:com.example.phone":{"approval":"DENIED"}}""")
        assertEquals(mapOf("com.example.phone" to LookupApproval.DENIED), names.stored.approvals)
        assertTrue(names.exportApprovals().contains("directory:com.example.phone"))
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

    @Test fun caller_on_the_lock_screen_stays_as_stored_after_a_duress_session() = runBlocking<Unit> {
        c.settings.update { it.copy(lockScreenCaller = LockScreenCaller.NAME) }
        duressUnlock()
        // Set on the page...
        c.settings.update { it.copy(lockScreenCaller = LockScreenCaller.NAME_AND_NOTES) }
        assertEquals(LockScreenCaller.NAME_AND_NOTES, c.settings.current().lockScreenCaller)
        assertEquals("s:NAME", c.settings.exportMap()["lock_screen_caller"])
        // ...or brought back by a restore whose safety settings were applied with the duress PIN.
        c.settings.importMap(mapOf("lock_screen_caller" to "s:NAME_AND_NOTES"))
        assertEquals("s:NAME", c.settings.exportMap()["lock_screen_caller"])
        // The next lock (then the real PIN) finds the stored choice.
        LockTransitions.locked(c)
        assertEquals(LockScreenCaller.NAME, c.settings.current().lockScreenCaller)
    }

    @Test fun restored_privacy_settings_wait_for_confirmation() {
        for (k in listOf("app_lock", "lock_after_minutes", "secure_screen", "hide_vault", "private_vault_history", "lock_screen_caller")) {
            assertTrue(k, k in SettingsRepository.SECURITY_KEYS)
        }
    }
}
