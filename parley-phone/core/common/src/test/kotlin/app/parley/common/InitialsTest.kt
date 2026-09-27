package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InitialsTest {
    @Test fun initials_never_split_surrogate_pairs() {
        assertEquals("AS", Initials.of("Anna Smith"))
        assertEquals("𝒜", Initials.of("𝒜lice")) // 𝒜lice: a whole mathematical letter
        assertEquals("𝒜B", Initials.of("𝒜lice Bob"))
        assertEquals("𠀋", Initials.of("𠀋𠀌")) // CJK Extension B name
        val combining = "Élodie" // É written with a combining accent
        assertEquals("É", Initials.of(combining))
        assertEquals("", Initials.of("😀 123")) // emoji and digits: no letter
        assertEquals("ß", Initials.of("ßtraße")) // uppercasing would turn one letter into two
        for (s in listOf("𝒜", "😀x", "a😀")) {
            val g = Initials.firstGrapheme(s)
            assertFalse(Character.isHighSurrogate(g.last()))
        }
    }
}
