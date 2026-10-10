package app.parley.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.spam.BuiltInPacks
import app.parley.common.spam.ListPack
import app.parley.common.spam.PackOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Installed spam lists: found on a call, kept across a restart, never downgraded, and the user's choices kept. */
@RunWith(RobolectricTestRunner::class)
class SpamListStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val arcep = BuiltInPacks.FRANCE_ARCEP
    private val telemarketer = "+33 1 62 12 34 56"
    private val friend = "+33 6 12 34 56 78"
    private lateinit var lists: SpamListStore

    @Before fun setUp() {
        lists = SpamListStore(app)
        runBlocking { lists.state.value.packs.forEach { lists.remove(it.id) } }
    }

    private fun hits(store: SpamListStore = lists, number: String = telemarketer) = store.lookup(number, "FR").hits.map { it.packId }

    @Test fun an_installed_list_finds_its_numbers_and_survives_a_restart() = runBlocking {
        assertTrue(lists.installBuiltIn(arcep) is SpamListStore.InstallResult.Installed)
        assertEquals(listOf(arcep.id), hits())
        assertEquals(emptyList<String>(), hits(number = friend))
        assertFalse(lists.lookup(telemarketer, "FR").failed)
        // A new process reads the same lists from storage.
        assertEquals(listOf(arcep.id), hits(SpamListStore(app)))
    }

    @Test fun an_older_version_never_replaces_a_newer_one() = runBlocking {
        val newer = ListPack.parse(BuiltInPacks.toPack(arcep, version = 20250101))
        val older = ListPack.parse(BuiltInPacks.toPack(arcep, version = 20240101))
        assertTrue(lists.install(newer, PackOrigin.BUILTIN) is SpamListStore.InstallResult.Installed)
        val result = lists.install(older, PackOrigin.BUILTIN)
        assertEquals(SpamListStore.InstallResult.Older(20250101, 20240101), result)
        assertEquals(20250101L, lists.state.value.packs.single().version)
    }

    @Test fun a_built_in_list_is_never_replaced_by_a_file_with_its_id() = runBlocking {
        lists.installBuiltIn(arcep)
        val impostor = ListPack.parse(BuiltInPacks.toPack(arcep, version = 20990101))
        assertTrue(lists.install(impostor, PackOrigin.FILE) is SpamListStore.InstallResult.Failed)
        assertEquals(PackOrigin.BUILTIN, lists.state.value.packs.single().origin)
    }

    @Test fun a_turned_off_list_stays_off_through_an_update_and_finds_nothing() = runBlocking {
        lists.installBuiltIn(arcep)
        lists.setPack(arcep.id) { it.copy(enabled = false) }
        assertEquals(emptyList<String>(), hits())
        assertTrue(lists.installBuiltIn(arcep) is SpamListStore.InstallResult.Installed)
        assertFalse(lists.state.value.packs.single().enabled)
        assertEquals(emptyList<String>(), hits())
    }

    @Test fun not_spam_silences_one_number_only() = runBlocking {
        lists.installBuiltIn(arcep)
        lists.suppress(arcep.id, telemarketer, "FR")
        assertEquals(emptyList<String>(), hits())
        assertEquals(listOf(arcep.id), hits(number = "+33 1 62 99 99 99"))
    }

    @Test fun removing_a_list_forgets_it_and_its_files() = runBlocking {
        lists.installBuiltIn(arcep)
        lists.remove(arcep.id)
        assertTrue(lists.state.value.packs.isEmpty())
        assertEquals(emptyList<String>(), hits())
        assertTrue(SpamListStore(app).state.value.packs.isEmpty())
    }
}
