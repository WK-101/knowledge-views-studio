package com.cairn.reader.data.prefs

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the settings backup on a real (Robolectric) DataStore: the offline-by-default posture,
 * and a full export → import round-trip proving every persisted preference — including the
 * privacy-critical `dictionaryOnline` opt-in — survives a backup and restore.
 *
 * All assertions live in one test because the production `preferencesDataStore` delegate caches a
 * single store instance, so splitting across methods would share (and leak) state between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreferencesRepositoryTest {

    @Test fun `defaults are offline and a backup round-trip restores every toggle`() = runBlocking {
        val repo = PreferencesRepository(ApplicationProvider.getApplicationContext())

        // 1) Defaults: the two features that could reach third-party servers are OFF; the privacy
        //    protections are ON.
        val defaults = repo.preferences.first()
        assertFalse("dictionary online should default off", defaults.dictionaryOnline)
        assertFalse("link-check should default off", defaults.linkCheckEnabled)
        assertTrue("sanitize should default on", defaults.sanitizeArticles)
        assertTrue("tracking strip should default on", defaults.stripTrackingParams)

        // 2) Flip a representative spread of preferences away from their defaults, then export.
        repo.setDictionaryOnline(true)
        repo.setSanitizeArticles(false)
        repo.setLinkCheckEnabled(true)
        repo.setBackupFrequency(168)
        repo.setTtsEnabled(false)

        val exported = repo.exportSettings()
        assertTrue("export carries the dictionary opt-in", exported.getBoolean("dictionaryOnline"))

        // 3) Change them again, so the restore — not leftover state — is what we verify.
        repo.setDictionaryOnline(false)
        repo.setSanitizeArticles(true)
        repo.setLinkCheckEnabled(false)
        repo.setBackupFrequency(0)
        repo.setTtsEnabled(true)

        // 4) Import the earlier export and confirm every value came back.
        repo.importSettings(exported)
        val restored = repo.preferences.first()
        assertTrue(restored.dictionaryOnline)
        assertFalse(restored.sanitizeArticles)
        assertTrue(restored.linkCheckEnabled)
        assertEquals(168, restored.backupFrequencyHours)
        assertFalse(restored.ttsEnabled)
    }
}
