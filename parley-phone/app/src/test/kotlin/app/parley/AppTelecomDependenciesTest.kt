package app.parley

import android.app.Application
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.data.db.ContactMetaEntity
import app.parley.testing.AppTestbed
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

/**
 * The contract between the call path (:telecom) and Parley's data: who a caller is, whether they are saved (privately
 * too), the block written when a call is declined and its undo, and the call screen's look following the settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppTelecomDependenciesTest {
    private lateinit var t: AppTestbed
    private lateinit var deps: AppTelecomDependencies

    @Before fun setUp() {
        t = AppTestbed()
        deps = AppTelecomDependencies(t.context, t.c)
    }

    @After fun tearDown() = t.close()

    @Test fun aSavedCallerIsNamedWithTheirPinnedNote() = runBlocking {
        val id = t.contact("Ada", "Lovelace", "+44 20 7946 0001")
        val key = t.c.contacts.lookup("+442079460001")!!.lookupKey!!
        t.c.meta.setMeta(ContactMetaEntity(key, pinnedNote = "Ask about the engine"))
        val shown = deps.callerInfo("+44 20 7946 0001", null)
        assertNotNull(shown)
        assertEquals("Ada Lovelace", shown!!.name)
        assertEquals(id, shown.contactId)
        assertEquals("Ask about the engine", shown.note)
        assertNull(deps.callerInfo("+44 20 7946 0999", null))
    }

    @Test fun savedMeansAContactOrAPrivateContact() = runBlocking {
        t.contact("Bo", "", "+1 555 0100")
        t.privateContact("Secret", "+1 555 0177")
        assertTrue(deps.isSavedCaller("+15550100", null))
        assertTrue(deps.isSavedCaller("+1 555 0177", null))
        assertFalse(deps.isSavedCaller("+1 555 0199", null))
    }

    @Test fun decliningWithABlockWritesOneRuleAndUndoRemovesIt() = runBlocking {
        val id = deps.blockForDecline("+1 555 0123")
        assertNotNull(id)
        val rule = t.c.blocks.allRules().single()
        assertEquals(RuleKind.BLOCK, rule.kind)
        assertEquals(RuleType.EXACT, rule.type)
        // A second decline finds the rule there: nothing new, and nothing for Undo to remove.
        assertEquals(0L, deps.blockForDecline("+1 555 0123"))
        assertEquals(1, t.c.blocks.allRules().size)
        deps.undoBlockForDecline(id!!)
        assertTrue(t.c.blocks.allRules().isEmpty())
    }

    @Test fun theCallScreenFollowsHideScreenContent() = runBlocking {
        t.c.settings.update { it.copy(secureScreen = true) }
        t.until("the call screen made secure") { deps.appearance.value.secureScreen && deps.appearance.value.loaded }
        t.c.settings.update { it.copy(secureScreen = false) }
        t.until("the call screen no longer secure") { !deps.appearance.value.secureScreen }
    }

    @Test fun aSuggestedNameAlwaysHasTheNumber() {
        assertTrue(deps.suggestedName("+1 555 0144").filter { it.isDigit() }.endsWith("5550144"))
    }
}
