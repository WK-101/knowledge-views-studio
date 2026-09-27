package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarTextTest {
    @Test fun emoji_names_become_emoji_avatars() {
        assertEquals("🐶", AvatarText.leadingEmoji("🐶 Rex"))
        assertEquals("☕", AvatarText.leadingEmoji("☕ Café"))
        assertNull(AvatarText.leadingEmoji("Anna"))
        assertNull(AvatarText.leadingEmoji("  "))
        assertNull(AvatarText.leadingEmoji("123"))
    }
}
