package app.parley.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every marker is a ParleyTag: one height, and a tag with an action is a big enough target inside its 48 dp. */
class ParleyTagTokensTest {
    @Test fun one_size_for_every_marker() {
        assertEquals(20.dp, ParleyTagTokens.height)
        assertEquals(32.dp, ParleyTagTokens.actionHeight)
        assertTrue(ParleyTagTokens.iconSize < ParleyTagTokens.height)
        assertTrue(ParleyTagTokens.actionIconSize < ParleyTagTokens.actionHeight)
        assertEquals(listOf(TagTone.NEUTRAL, TagTone.INFO, TagTone.WARN), TagTone.entries)
    }
}
