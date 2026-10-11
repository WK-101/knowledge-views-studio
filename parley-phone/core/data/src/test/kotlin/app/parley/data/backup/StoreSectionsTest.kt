package app.parley.data.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.spam.BuiltInPacks
import app.parley.common.spam.ListPack
import app.parley.common.spam.PackBuilder
import app.parley.common.spam.PackManifest
import app.parley.common.spam.PackOrigin
import app.parley.data.SpamListStore
import app.parley.data.history.HistoryPrefs
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

/** Backup sections of the stores without a test of their own: call-history settings and spam lists added by hand. */
@RunWith(RobolectricTestRunner::class)
class StoreSectionsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var lists: SpamListStore

    @Before fun setUp() {
        lists = SpamListStore(app)
        runBlocking { lists.state.value.packs.forEach { lists.remove(it.id) } }
    }

    @After fun tearDown() = scope.cancel()

    @Test fun call_history_settings_come_back() = runBlocking {
        val prefs = HistoryPrefs(app, scope)
        prefs.setArchiveEnabled(false)
        prefs.setCsvBom(false)
        val section = HistorySettingsBackup { prefs }
        val saved = section.export()
        prefs.setArchiveEnabled(true)
        prefs.setCsvBom(true)
        section.import(saved)
        assertFalse(prefs.current().archiveEnabled)
        assertFalse(prefs.current().csvBom)
        // A damaged section changes nothing.
        section.import(saved.mapValues { "{" })
        assertFalse(prefs.current().archiveEnabled)
    }

    @Test fun a_list_added_from_a_file_comes_back_and_a_built_in_one_stays_out() = runBlocking {
        val manifest = PackManifest(id = "user.neighbours", name = "Neighbour scams", publisher = "Me", ttlDays = 0)
        val pack = PackBuilder(manifest).apply { addNumber("+44 20 7946 0999", 1, 80) }.build(now = 0)
        assertTrue(lists.install(ListPack.parse(pack), PackOrigin.FILE) is SpamListStore.InstallResult.Installed)
        lists.installBuiltIn(BuiltInPacks.FRANCE_ARCEP)
        val section = SpamListsBackup { lists }
        val saved = section.export()
        assertEquals(1, saved.size)
        lists.remove("user.neighbours")
        assertTrue(lists.lookup("+44 20 7946 0999", "GB").hits.isEmpty())
        section.import(saved)
        assertEquals(listOf("user.neighbours"), lists.lookup("+44 20 7946 0999", "GB").hits.map { it.packId })
    }
}
