package app.parley.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Show names as" became a setting of its own after "Sort and show names by". A backup made before then has the one
 * old key: restoring it on a phone that already stored the new one must still show names the way they were.
 */
@RunWith(RobolectricTestRunner::class)
class NameOrderRestoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        settings = SettingsRepository(app, scope)
    }

    @After fun tearDown() {
        scope.cancel()
    }

    @Test fun an_old_backup_shows_names_the_way_it_sorted_them() = runBlocking {
        // This phone chose "First name first" itself, then restores a backup sorted (and so shown) by last name.
        settings.update { it.copy(sortByFirstName = true, showNamesLastFirst = false) }
        settings.importMap(mapOf("sort_first_name" to "b:false"))
        assertFalse(settings.current().sortByFirstName)
        assertTrue(settings.current().showNamesLastFirst)

        // Choosing it apart from "Sort by" stores it, and a backup made since carries it.
        settings.update { it.copy(showNamesLastFirst = false) }
        val backup = settings.exportMap()
        assertEquals("b:false", backup["names_last_first"])

        settings.importMap(mapOf("sort_first_name" to "b:false"))
        assertTrue(settings.current().showNamesLastFirst)
        settings.importMap(backup)
        assertFalse(settings.current().sortByFirstName)
        assertFalse(settings.current().showNamesLastFirst)
    }

    @Test fun saving_another_setting_keeps_it_following_sort_by() = runBlocking {
        settings.importMap(mapOf("sort_first_name" to "b:true"))
        settings.update { it.copy(dialpadTones = !it.dialpadTones) }
        assertFalse("names_last_first" in settings.exportMap())
        settings.importMap(mapOf("sort_first_name" to "b:false"))
        assertTrue(settings.current().showNamesLastFirst)
    }
}
