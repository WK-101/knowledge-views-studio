package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionPickTest {
    @Test fun sim_then_network_then_system_locales() {
        assertEquals("FR", RegionPick.pick("fr", "de", listOf("GB"), "US"))
        assertEquals("DE", RegionPick.pick("", "de", listOf("GB"), "US"))
        // An app language without a country ("ar") never decides; the system locale does.
        assertEquals("GB", RegionPick.pick(null, null, listOf("", "GB"), ""))
        assertEquals("", RegionPick.pick(null, null, emptyList(), ""))
        assertEquals("US", RegionPick.pick(null, "123", listOf(null), "us"))
    }
}
