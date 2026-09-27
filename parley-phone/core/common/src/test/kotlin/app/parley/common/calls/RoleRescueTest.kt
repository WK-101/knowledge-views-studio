package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleRescueTest {
    @Test fun rescue_only_when_android_refused_without_asking() {
        assertTrue(RoleRescue.silentlyRefused(granted = false, elapsedMs = 40))
        assertFalse(RoleRescue.silentlyRefused(granted = false, elapsedMs = 1_800)) // Cancel on a real dialog
        assertFalse(RoleRescue.silentlyRefused(granted = true, elapsedMs = 40))
        assertEquals(RoleRescue.Variant.ANDROID_10_11, RoleRescue.variant(29))
        assertEquals(RoleRescue.Variant.ANDROID_12, RoleRescue.variant(32))
        assertEquals(RoleRescue.Variant.ANDROID_13_PLUS, RoleRescue.variant(36))
    }
}
