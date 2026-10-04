package app.parley.blocking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.BlockRule
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.blocking.BlockPlan
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
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
import org.robolectric.annotation.Config

/**
 * The one Block and Unblock: without the phone-app role a Parley rule does the job; an "Always allow" rule is lifted
 * (and comes back with Undo) unless the block asked no question; an emergency number is never blocked; Undo puts back
 * exactly what was there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BlockFlowTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val caller = "+1 202 555 0100"
    private val emergency = "112"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        VaultCrypto.appContext = context
        c = DataContainer(context)
        // Robolectric's telephony knows no emergency numbers.
        BlockFlow.emergencyCheck = { _, n -> n == emergency }
    }

    @After fun tearDown() {
        BlockFlow.emergencyCheck = { cc, n -> app.parley.data.EmergencyNumbers.isEmergency(cc.appContext, n) }
        c.scope.cancel()
    }

    private val iso get() = PhoneEnv.countryIso(context)

    private suspend fun exact(kind: RuleKind) = BlockPlan.exactRules(c.blocks.allRules(), caller, iso, kind)

    @Test fun without_the_role_a_rule_blocks_and_undo_takes_it_away() = runBlocking {
        assertFalse(BlockFlow.systemListUsable(c))
        val done = BlockFlow.block(c, listOf(caller), note = "Sales")
        assertEquals(listOf(caller), done.numbers)
        assertEquals(listOf("Sales"), exact(RuleKind.BLOCK).map { it.note })
        assertTrue(BlockFlow.now(c, caller).blocked)
        // Blocking again changes nothing.
        assertTrue(BlockFlow.block(c, listOf(caller)).numbers.isEmpty())
        done.undo()
        assertTrue(exact(RuleKind.BLOCK).isEmpty())
    }

    @Test fun an_always_allow_rule_is_lifted_and_comes_back_with_undo() = runBlocking {
        val allowId = c.blocks.saveRule(BlockRule(pattern = "+12025550100", type = RuleType.EXACT, kind = RuleKind.ALLOW, hitCount = 3))
        val done = BlockFlow.block(c, listOf(caller))
        assertEquals(1, done.liftedAllows)
        assertTrue(exact(RuleKind.ALLOW).isEmpty())
        done.undo()
        assertEquals(listOf(allowId to 3), exact(RuleKind.ALLOW).map { it.id to it.hitCount })
        assertTrue(exact(RuleKind.BLOCK).isEmpty())
    }

    @Test fun a_block_without_a_question_keeps_the_allowance() = runBlocking {
        c.blocks.saveRule(BlockRule(pattern = "+12025550100", type = RuleType.EXACT, kind = RuleKind.ALLOW))
        val done = BlockFlow.block(c, listOf(caller), keepAllows = true)
        assertTrue(done.numbers.isEmpty())
        assertEquals(1, exact(RuleKind.ALLOW).size)
        assertTrue(exact(RuleKind.BLOCK).isEmpty())
    }

    @Test fun an_emergency_number_is_never_blocked() = runBlocking {
        assertTrue(BlockFlow.plans(c, listOf(emergency)).isEmpty())
        val done = BlockFlow.block(c, listOf(emergency, caller))
        assertEquals(listOf(caller), done.numbers)
        assertEquals(listOf(emergency), done.emergency)
        assertTrue(BlockPlan.exactRules(c.blocks.allRules(), emergency, iso, RuleKind.BLOCK).isEmpty())
        assertFalse(BlockFlow.now(c, emergency).blocked)
    }

    @Test fun unblock_takes_away_the_numbers_own_rules_and_undo_restores_them() = runBlocking {
        val id = c.blocks.saveRule(BlockRule(pattern = "+12025550100", type = RuleType.EXACT, hitCount = 5))
        val prefix = c.blocks.saveRule(BlockRule(pattern = "+1202555", type = RuleType.PREFIX))
        val done = BlockFlow.unblock(c, listOf(caller))
        assertEquals(listOf(caller), done.numbers)
        assertTrue(exact(RuleKind.BLOCK).isEmpty())
        assertTrue("a wider rule isn't this number's own", c.blocks.allRules().any { it.id == prefix })
        done.undo()
        assertEquals(listOf(id to 5), exact(RuleKind.BLOCK).map { it.id to it.hitCount })
    }
}
