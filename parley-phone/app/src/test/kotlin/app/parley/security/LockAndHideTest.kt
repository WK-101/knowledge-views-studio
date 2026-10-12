package app.parley.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.data.DataContainer
import app.parley.data.security.Concealment
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Contacts' "Lock and hide private contacts": one tap locks their details and turns "Hide private contacts" on. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LockAndHideTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        VaultCrypto.appContext = context
        VaultCrypto.lockedByPerson = false
        Concealment.init(context)
        Concealment.forgetForTest(newProcess = true)
        c = DataContainer(context)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        VaultCrypto.lockedByPerson = false
        c.scope.cancel()
    }

    @Test fun locks_and_hides_at_once() = runBlocking {
        c.vault.unlockedByPerson()
        DiscreetSwitch.lockAndHide(context, c)
        // Locked at once (details close until the next unlock in Parley)…
        assertTrue(VaultCrypto.lockedByPerson)
        assertTrue(c.vault.lock.lockedByPerson.value)
        // …and hidden: the switch Settings shows them again with is on, and the privacy view hides them.
        withTimeout(TIMEOUT_MS) { c.settings.settings.first { DiscreetSwitch.shown(it) } }
        withTimeout(TIMEOUT_MS) { c.privacy.flow.first { it.privateHidden } }
        Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
