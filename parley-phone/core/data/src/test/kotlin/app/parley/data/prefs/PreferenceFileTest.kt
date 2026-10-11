package app.parley.data.prefs

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import androidx.datastore.preferences.core.booleanPreferencesKey as dsBoolean
import androidx.datastore.preferences.core.floatPreferencesKey as dsFloat
import androidx.datastore.preferences.core.intPreferencesKey as dsInt
import androidx.datastore.preferences.core.longPreferencesKey as dsLong
import androidx.datastore.preferences.core.stringPreferencesKey as dsString
import androidx.datastore.preferences.core.stringSetPreferencesKey as dsStringSet

/**
 * The settings files that replaced DataStore: a file written by DataStore (as Parley 6.4 and older kept settings) is
 * moved over whole on first open, and edits are kept, in order, across a restart.
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceFileTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val name = "prefs-${System.nanoTime()}"
    private val legacy = File(app.filesDir, "datastore/$name.preferences_pb")

    /** Writes [legacy] with the real DataStore, then closes it, as an update to this version would find it. */
    private fun writeWithDataStore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        PreferenceDataStoreFactory.create(scope = scope) { legacy }.edit {
            it[dsBoolean("on")] = true
            it[dsInt("days")] = 1825
            it[dsLong("at")] = 1_760_000_000_000L
            it[dsString("theme")] = "Dark · ünïcödé ✓"
            it[dsString("empty")] = ""
            it[dsStringSet("warned")] = setOf("sim1|2026-10", "sim2|2026-09")
            it[dsFloat("scale")] = 1.25f
        }
        scope.cancel()
    }

    @Test fun a_datastore_file_is_moved_over_whole_and_then_removed() = runBlocking {
        writeWithDataStore()
        assertTrue(legacy.exists())
        val p = PreferenceFile(app, name).data.first()
        assertEquals(true, p[booleanPreferencesKey("on")])
        assertEquals(1825, p[intPreferencesKey("days")])
        assertEquals(1_760_000_000_000L, p[longPreferencesKey("at")])
        assertEquals("Dark · ünïcödé ✓", p[stringPreferencesKey("theme")])
        assertEquals("", p[stringPreferencesKey("empty")])
        assertEquals(setOf("sim1|2026-10", "sim2|2026-09"), p[stringSetPreferencesKey("warned")])
        assertEquals(7, p.asMap().size)
        assertFalse("moved once, then gone", legacy.exists())
        // Kept in the settings file itself.
        assertEquals(1825, app.getSharedPreferences(name, Context.MODE_PRIVATE).getInt("days", 0))
    }

    @Test fun an_old_file_never_undoes_later_changes() = runBlocking {
        PreferenceFile(app, name).edit { it[intPreferencesKey("days")] = 30 }
        writeWithDataStore()
        assertEquals(30, PreferenceFile(app, name).data.first()[intPreferencesKey("days")])
        assertFalse(legacy.exists())
    }

    @Test fun an_unreadable_old_file_is_kept_rather_than_lost() = runBlocking {
        legacy.parentFile?.mkdirs()
        legacy.writeBytes(byteArrayOf(0x0A, 0x7F, 0x01))
        assertTrue(PreferenceFile(app, name).data.first().asMap().isEmpty())
        assertTrue(legacy.exists())
    }

    @Test fun edits_are_kept_across_a_restart_and_none_is_lost() = runBlocking {
        val file = PreferenceFile(app, name)
        val key = intPreferencesKey("count")
        (1..40).map { async(Dispatchers.Default) { file.edit { it[key] = (it[key] ?: 0) + 1 } } }.awaitAll()
        file.edit { it.remove(stringPreferencesKey("absent")); it[stringPreferencesKey("theme")] = "Light" }
        val reopened = PreferenceFile(app, name).data.first()
        assertEquals(40, reopened[key])
        assertEquals("Light", reopened[stringPreferencesKey("theme")])
        file.edit { it.remove(stringPreferencesKey("theme")) }
        assertFalse(stringPreferencesKey("theme") in PreferenceFile(app, name).data.first())
    }
}
