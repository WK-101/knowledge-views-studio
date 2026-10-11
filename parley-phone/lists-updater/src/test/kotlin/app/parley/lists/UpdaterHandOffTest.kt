package app.parley.lists

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.parley.common.spam.BuiltInPacks
import app.parley.common.spam.ListPack
import app.parley.common.spam.PackBuilder
import app.parley.common.spam.PackManifest
import app.parley.common.spam.SignatureStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A community list from download to Parley: it is checked before it is kept, a broken or impostor one never reaches
 * Parley, and the provider hands Parley exactly the bytes that were checked.
 */
@RunWith(RobolectricTestRunner::class)
class UpdaterHandOffTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val web = FakeWeb()
    private val url = "https://lists.example.org/neighbours.parleylist"
    private lateinit var repo: ListsRepo

    @Before fun setUp() {
        // One repository per process in the app; a fresh one for each test here.
        ListsRepo::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        repo = ListsRepo.get(context)
        repo.setConfig { UpdaterConfig(ftcEnabled = false, arcepEnabled = false, community = listOf(CommunitySource(url))) }
        web.install()
    }

    private fun pack(id: String = "community.neighbours", version: Long = 1): ByteArray =
        PackBuilder(PackManifest(id = id, name = "Neighbour scams", version = version, ttlDays = 0))
            .apply { addNumber("+44 20 7946 0999", 1, 80) }
            .build(now = 0)

    private fun provider() = Robolectric.setupContentProvider(PacksProvider::class.java, "${context.packageName}.packs")

    @Test fun a_good_list_is_kept_and_handed_to_parley_as_downloaded() = runBlocking {
        val bytes = pack()
        web.answers[url] = FakeWeb.Answer(200, bytes, mapOf("ETag" to "\"a\""))
        assertTrue(Updater.run(context))
        val info = repo.state.value.packs.single()
        assertEquals("community.neighbours", info.id)
        assertEquals(1, info.entries)
        val p = provider()
        p.query(Uri.parse("content://x/packs"), null, null, null, null)!!.use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("community.neighbours", c.getString(c.getColumnIndexOrThrow("id")))
            assertEquals(repo.fingerprint(), c.getString(c.getColumnIndexOrThrow("publisher")))
        }
        val handed = p.openFile(Uri.parse("content://x/packs/community.neighbours"), "r").use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).readBytes()
        }
        assertArrayEquals(bytes, handed)
        assertEquals(SignatureStatus.UNSIGNED, ListPack.parse(handed).signature)
    }

    @Test fun a_broken_list_is_never_kept() = runBlocking {
        web.answers[url] = FakeWeb.Answer(200, "not a list".toByteArray())
        assertFalse(Updater.run(context))
        assertTrue(repo.state.value.packs.isEmpty())
        assertNotNull(repo.state.value.status[url]?.error)
    }

    @Test fun a_list_claiming_a_built_in_id_is_refused() = runBlocking {
        web.answers[url] = FakeWeb.Answer(200, pack(id = BuiltInPacks.FRANCE_ARCEP.id))
        assertFalse(Updater.run(context))
        assertTrue(repo.state.value.packs.isEmpty())
    }

    @Test fun an_unchanged_list_is_asked_for_with_its_tag_and_kept() = runBlocking {
        web.answers[url] = FakeWeb.Answer(200, pack(), mapOf("ETag" to "\"a\""))
        Updater.run(context)
        web.answers[url] = FakeWeb.Answer(304)
        assertTrue(Updater.run(context))
        assertEquals("\"a\"", web.asked.last().second["If-None-Match"])
        assertEquals(1, repo.state.value.packs.size)
    }

    @Test fun the_provider_is_read_only_and_names_only_its_lists() {
        val p = provider()
        assertTrue(runCatching { p.openFile(Uri.parse("content://x/packs/community.neighbours"), "rw") }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching { p.openFile(Uri.parse("content://x/packs/..%2Fstate.json"), "r") }.isFailure)
        assertTrue(runCatching { p.insert(Uri.parse("content://x/packs"), null) }.isFailure)
    }

    @Test fun turning_a_source_off_removes_its_list_from_parley() = runBlocking {
        web.answers[url] = FakeWeb.Answer(200, pack())
        Updater.run(context)
        repo.setConfig { it.copy(community = emptyList()) }
        Updater.prune(repo)
        assertTrue(repo.state.value.packs.isEmpty())
        assertEquals(0, provider().query(Uri.parse("content://x/packs"), null, null, null, null)!!.use { it.count })
    }
}
