package app.parley

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.NotifyLevel
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.vcard.ColumnTarget
import app.parley.common.vcard.CsvField
import app.parley.data.AccountRef
import app.parley.ui.blocking.RuleDraftSaver
import app.parley.ui.common.AccountRefSaver
import app.parley.ui.common.BooleanListSaver
import app.parley.ui.common.StringSetSaver
import app.parley.ui.people.ColumnMappingSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long forms' state survives saved state: every value goes into a bundle and comes back the same. */
class SavedFormStateTest {
    /** Only what a Bundle holds without a Parcelable: text, numbers, booleans, their arrays and lists, null. */
    private fun bundleable(v: Any?): Boolean = when (v) {
        null, is String, is Int, is Long, is Boolean, is BooleanArray -> true
        is List<*> -> v.all(::bundleable)
        else -> false
    }

    private fun <T> roundTrip(saver: Saver<T, Any>, value: T): T {
        val scope = SaverScope { bundleable(it) }
        val saved = with(saver) { scope.save(value) }
        assertTrue("$saved fits in a bundle", bundleable(saved))
        @Suppress("UNCHECKED_CAST") // The saver restores the type it saved.
        // Nothing saved means the state starts from its initial value again (null here).
        return (if (saved == null) null else saver.restore(saved)) as T
    }

    @Test fun aRuleDraftComesBackWhole() {
        val rule = BlockRule(
            id = 12, pattern = "+44 20", type = RuleType.PREFIX, action = BlockAction.SILENCE, enabled = false, note = "Calls at night",
            kind = RuleKind.ALLOW, simId = "sim-2", schedule = Schedule(0b0011111, 22 * 60, 7 * 60), notify = NotifyLevel.QUIET,
            ringtone = "content://tone/3", expiresAt = 1_700_000_000_000L, hitCount = 4, lastHitAt = 1_690_000_000_000L, label = "Work",
        )
        assertEquals(rule, roundTrip(RuleDraftSaver, rule))
        val bare = BlockRule(pattern = "", type = RuleType.EXACT)
        assertEquals(bare, roundTrip(RuleDraftSaver, bare))
    }

    @Test fun columnChoicesAccountsSelectionsAndTicks() {
        val mapping = listOf(ColumnTarget(CsvField.PHONE, 2), ColumnTarget.IGNORED, ColumnTarget(CsvField.EMAIL))
        assertEquals(mapping, roundTrip(ColumnMappingSaver, mapping))
        assertEquals(AccountRef("com.google", "me@example.org"), roundTrip(AccountRefSaver, AccountRef("com.google", "me@example.org")))
        assertEquals(AccountRef(null, null), roundTrip(AccountRefSaver, AccountRef(null, null)))
        assertNull(roundTrip(AccountRefSaver, null))
        assertEquals(setOf("Family", "Work"), roundTrip(StringSetSaver, setOf("Family", "Work")))
        assertEquals(listOf(true, false, true), roundTrip(BooleanListSaver, listOf(true, false, true)))
    }
}
