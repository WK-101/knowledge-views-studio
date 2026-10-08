package app.parley.data.calls

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.NetworkName
import app.parley.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * "Remember names from the network": off on a new phone and after an upgrade from a version without it (whose kept
 * names stay untouched), nothing written while off, and turning it off can delete what was kept or keep it for later.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkNameSettingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(app.filesDir, "network-names-${System.nanoTime()}.preferences_pb")
    private val now = System.currentTimeMillis()

    private object Keys : SealedLineStore.Keys {
        override fun lineMac(number: String) = "mac-" + number.filter { it.isDigit() }.takeLast(10)
        override fun seal(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(blob: ByteArray) = blob.copyOfRange(1, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    @Before fun setUp() {
        app.getSharedPreferences("parley_network_names", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        scope.cancel()
    }

    private fun repo(fresh: Boolean): SettingsRepository = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, scope) { fresh }

    /** What the app does with a name the network sent ([app.parley.AppTelecomDependencies.onNetworkName]'s rule). */
    private fun sent(store: NetworkNameStore, enabled: Boolean, number: String, name: String, private: Boolean? = false) {
        when (NetworkName.keep(enabled, private)) {
            NetworkName.Keep.RECORD -> store.record(number, name, now, "sim1", "IN", now)
            NetworkName.Keep.FORGET -> store.forget(number, listOf("IN"))
            NetworkName.Keep.SKIP -> Unit
        }
    }

    @Test fun off_on_a_new_phone() = runBlocking {
        assertFalse(repo(fresh = true).current().rememberNetworkNames)
    }

    @Test fun an_upgrade_starts_off_and_keeps_the_names_it_had() = runBlocking {
        // 6.2.1 kept a name, and stored settings without this key.
        val store = NetworkNameStore(app, Keys)
        store.record("+919812300002", "Ravi Kumar", now, "sim1", "IN", now)
        val before = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        PreferenceDataStoreFactory.create(scope = before) { file }.edit { it[booleanPreferencesKey("show_caller_photo")] = true }
        // One store per file at a time: the earlier version's is closed before this one opens.
        before.cancel()
        delay(100)

        val settings = repo(fresh = false).current()
        assertFalse(settings.rememberNetworkNames)
        // Not shown while off…
        assertFalse(NetworkName.mayShow(settings.rememberNetworkNames, saved = false, private = false))
        // …and not deleted either: still there for when the user decides.
        assertTrue(store.hasAny())
        assertEquals("Ravi Kumar", NetworkNameStore(app, Keys).latest("+919812300002")?.name)
    }

    @Test fun turning_it_on_is_kept() = runBlocking {
        val s = repo(fresh = true)
        s.update { it.copy(rememberNetworkNames = true) }
        assertTrue(s.current().rememberNetworkNames)
        assertTrue(s.exportMap()["remember_network_names"] == "b:true")
    }

    @Test fun nothing_is_stored_while_off_and_saved_numbers_too_while_on() {
        val store = NetworkNameStore(app, Keys)
        sent(store, enabled = false, number = "+919812300002", name = "Ravi Kumar")
        assertFalse(store.hasAny())
        // On: an unknown number and a saved contact's number alike.
        sent(store, enabled = true, number = "+919812300002", name = "Ravi Kumar")
        sent(store, enabled = true, number = "+919812300003", name = "Rahul S.")
        assertEquals("Rahul S.", store.latest("+919812300003")?.name)
        // A private contact's number: never written, and what was kept goes, on or off.
        sent(store, enabled = true, number = "+919812300004", name = "Asha", private = true)
        assertNull(store.latest("+919812300004"))
        sent(store, enabled = false, number = "+919812300002", name = "Ravi Kumar", private = true)
        assertNull(store.latest("+919812300002"))
    }

    @Test fun turning_it_off_deletes_or_keeps_for_later() {
        val store = NetworkNameStore(app, Keys)
        sent(store, enabled = true, number = "+919812300002", name = "Ravi Kumar")
        // Keep for later: nothing changes on the phone.
        assertTrue(store.hasAny())
        // Delete: every kept name goes.
        store.clear()
        assertFalse(store.hasAny())
        assertNull(NetworkNameStore(app, Keys).latest("+919812300002"))
    }
}
