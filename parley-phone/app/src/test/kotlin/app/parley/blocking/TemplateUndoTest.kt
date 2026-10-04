package app.parley.blocking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.ListMode
import app.parley.data.DataContainer
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Undo of a template's Uninstall puts back what was there: the same rules as they were, the list's own choices, the settings. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TemplateUndoTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private lateinit var gallery: TemplateGallery

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        VaultCrypto.appContext = context
        c = DataContainer(context)
        gallery = TemplateGallery.get(context)
        runBlocking { gallery.state.value.installed.forEach { gallery.uninstall(c, it.id) } }
    }

    @After fun tearDown() = c.scope.cancel()

    private fun template(id: String) = gallery.builtIns.first { it.id == id }

    @Test fun undo_brings_back_the_same_rules_switched_off_with_their_hits() = runBlocking {
        val t = template("general.foreign-except-mine")
        gallery.install(c, t)
        val ruleId = gallery.installed(t.id)!!.ruleIds.single()
        // The user switched the rule off (it silenced a real caller) and it had stopped calls before.
        val rule = c.blocks.allRules().first { it.id == ruleId }.copy(enabled = false, hitCount = 4)
        c.blocks.saveRule(rule)

        val undo = gallery.uninstallWithUndo(c, t.id)
        assertNull(gallery.installed(t.id))
        assertTrue(c.blocks.allRules().none { it.id == ruleId })

        undo()
        assertEquals(listOf(rule), c.blocks.allRules().filter { it.id == ruleId })
        assertEquals(listOf(ruleId), gallery.installed(t.id)?.ruleIds)
        // Uninstalling again still removes exactly that group.
        gallery.uninstall(c, t.id)
        assertTrue(c.blocks.allRules().none { it.id == ruleId })
    }

    @Test fun undo_keeps_the_lists_own_choices_and_the_settings() = runBlocking {
        val pack = template("it.agcom-0843-0844")
        gallery.install(c, pack)
        val packId = gallery.installed(pack.id)!!.packId!!
        c.lists.setPack(packId) { it.copy(mode = ListMode.BLOCK, threshold = 70) }
        val invalid = template("general.silence-invalid")
        val before = c.settings.current().screening.blockInvalid
        gallery.install(c, invalid)
        assertTrue(c.settings.current().screening.blockInvalid)

        val undoPack = gallery.uninstallWithUndo(c, pack.id)
        val undoInvalid = gallery.uninstallWithUndo(c, invalid.id)
        assertTrue(c.lists.state.value.packs.none { it.id == packId })
        assertEquals(before, c.settings.current().screening.blockInvalid)

        undoPack()
        undoInvalid()
        val restored = c.lists.state.value.packs.firstOrNull { it.id == packId }
        assertNotNull(restored)
        assertEquals(ListMode.BLOCK, restored!!.mode)
        assertEquals(70, restored.threshold)
        assertTrue(c.settings.current().screening.blockInvalid)
        assertNotNull(gallery.installed(invalid.id))
        // Its uninstall still puts back the value from before it was first installed.
        gallery.uninstall(c, invalid.id)
        assertEquals(before, c.settings.current().screening.blockInvalid)
        assertFalse(gallery.installed(pack.id) == null)
    }
}
