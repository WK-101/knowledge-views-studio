package app.parley.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The private-contact lock looks the same in every list: one size, one icon, one place on the photo. */
class PrivateBadgeTokensTest {
    @Test fun one_small_lock_at_the_photos_bottom_end() {
        assertEquals(18.dp, PrivateBadgeTokens.size)
        assertEquals(12.dp, PrivateBadgeTokens.iconSize)
        assertTrue(PrivateBadgeTokens.iconSize < PrivateBadgeTokens.size)
        assertEquals(Icons.Rounded.Lock, PrivateBadgeTokens.icon)
        assertEquals(Alignment.BottomEnd, PrivateBadgeTokens.position)
    }
}
