package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppNeedsTest {
    @Test fun nothing_is_said_while_parley_is_the_default() {
        DefaultAppFeature.entries.forEach { f ->
            assertFalse(f.name, DefaultAppNeeds.noteShown(f, isDefault = true, isScreener = false))
            assertFalse(f.name, DefaultAppNeeds.noteShown(f, isDefault = true, isScreener = true))
        }
    }

    @Test fun every_feature_says_so_without_any_role() {
        DefaultAppFeature.entries.forEach { f -> assertTrue(f.name, DefaultAppNeeds.noteShown(f, isDefault = false, isScreener = false)) }
    }

    @Test fun screening_role_is_enough_only_for_blocking() {
        val enough = DefaultAppFeature.entries.filterNot { DefaultAppNeeds.noteShown(it, isDefault = false, isScreener = true) }
        assertEquals(listOf(DefaultAppFeature.BLOCKING), enough)
    }
}
