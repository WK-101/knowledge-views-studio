package app.parley.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.history.RetentionDefaults
import app.parley.data.prefs.PreferenceFile
import app.parley.data.prefs.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The five-year default for call history is Parley's archive only: no default may ever trim the phone's own call log,
 * which may have come from another phone. Each case below is a way a phone can look like a new install.
 */
@RunWith(RobolectricTestRunner::class)
class RetentionDefaultTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = "retention-${System.nanoTime()}"

    @After fun tearDown() {
        scope.cancel()
    }

    /** A settings file [file], as this install sees it, once start-up has pinned what it pins. */
    private fun repo(fresh: Boolean, storeScope: CoroutineScope = scope, on: String = file): SettingsRepository = runBlocking {
        val store = PreferenceFile(app, on)
        SettingsRepository(store, storeScope) { fresh }.also { s ->
            withTimeout(5_000) { while ("call_log_retention_chosen" !in s.exportMap()) delay(10) }
        }
    }

    private fun systemLogDays(s: SettingsRepository) = runBlocking {
        s.current().let { RetentionDefaults.systemLogDays(it.callLogRetentionDays, it.callLogRetentionChosen) }
    }

    @Test fun a_fresh_install_trims_the_archive_only() = runBlocking {
        val s = repo(fresh = true)
        assertEquals(RetentionDefaults.NEW_INSTALL_DAYS, s.current().callLogRetentionDays)
        assertFalse(s.current().callLogRetentionChosen)
        assertEquals(0, systemLogDays(s))
        // Saving any other setting doesn't turn the default into a choice.
        s.update { it.copy(dialpadTones = !it.dialpadTones) }
        assertEquals(0, systemLogDays(s))
    }

    @Test fun a_device_transfer_keeps_the_restored_call_log() = runBlocking {
        // Android brought the call log over; Parley itself starts empty and looks like a new install, then is updated.
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo(fresh = true, storeScope = first)
        first.cancel()
        delay(100)
        val updated = repo(fresh = false)
        assertEquals(RetentionDefaults.NEW_INSTALL_DAYS, updated.current().callLogRetentionDays)
        assertEquals(0, systemLogDays(updated))
    }

    @Test fun an_upgrade_without_a_choice_keeps_everything() = runBlocking {
        val s = repo(fresh = false)
        assertEquals(0, s.current().callLogRetentionDays)
        assertEquals(0, systemLogDays(s))
    }

    @Test fun an_upgrade_keeps_a_limit_chosen_before_and_its_call_log_trimming() = runBlocking {
        val store = PreferenceFile(app, file)
        // As an earlier version stored it: the value only, with no mark.
        store.edit { it[intPreferencesKey("call_log_retention_days")] = 90 }
        val s = SettingsRepository(store, scope) { false }
        assertEquals(90, s.current().callLogRetentionDays)
        assertTrue(s.current().callLogRetentionChosen)
        assertEquals(90, systemLogDays(s))
    }

    @Test fun restoring_an_old_backup_without_a_retention_keeps_everything() = runBlocking {
        val s = repo(fresh = true)
        // A backup from before the default: settings, but no retention, which meant forever.
        s.importMap(RetentionDefaults.restored(mapOf("dialpad_tones" to "b:false"), SettingsRepository.RETENTION_KEY))
        assertEquals(0, s.current().callLogRetentionDays)
        assertEquals(0, systemLogDays(s))
    }

    @Test fun restoring_an_old_backup_with_a_chosen_limit_keeps_that_choice() = runBlocking {
        val s = repo(fresh = true)
        s.importMap(RetentionDefaults.restored(mapOf("call_log_retention_days" to "i:365"), SettingsRepository.RETENTION_KEY))
        assertEquals(365, s.current().callLogRetentionDays)
        assertEquals(365, systemLogDays(s))
    }

    @Test fun choosing_a_limit_trims_the_call_log_and_a_backup_carries_it() = runBlocking {
        val s = repo(fresh = true)
        s.update { it.copy(callLogRetentionDays = RetentionDefaults.NEW_INSTALL_DAYS, callLogRetentionChosen = true) }
        assertEquals(RetentionDefaults.NEW_INSTALL_DAYS, systemLogDays(s))
        val backup = s.exportMap()
        assertEquals("b:true", backup["call_log_retention_chosen"])

        // The same backup on another new phone: the choice comes with it.
        val other = repo(fresh = true, on = "other-${System.nanoTime()}")
        other.importMap(RetentionDefaults.restored(backup, SettingsRepository.RETENTION_KEY))
        assertEquals(RetentionDefaults.NEW_INSTALL_DAYS, systemLogDays(other))
    }
}
