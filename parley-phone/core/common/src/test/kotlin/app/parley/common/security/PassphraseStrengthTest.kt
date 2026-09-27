package app.parley.common.security

import app.parley.common.security.PassphraseStrength.Hint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseStrengthTest {
    private fun score(p: String) = PassphraseStrength.estimate(p).score

    @Test fun common_choices_score_low() {
        for (p in listOf("password12", "Password123", "p@ssw0rd2024", "qwertyuiop12", "aaaaaaaaaaaa", "1234567890ab", "iloveyou1990", "letmein!!!")) {
            assertTrue("$p scored ${score(p)}", score(p) < PassphraseStrength.MIN_BACKUP_SCORE)
            assertFalse(p, PassphraseStrength.acceptableForBackup(p))
        }
    }

    @Test fun long_or_random_passphrases_pass() {
        for (p in listOf("correct horse battery staple", "violet-tugboat-mango-oxide", "Tr0ub4dor&3xq!", "gV7#pL2m!wQz")) {
            assertTrue("$p scored ${score(p)}", PassphraseStrength.acceptableForBackup(p))
        }
    }

    @Test fun hints_name_the_weakness() {
        assertEquals(Hint.TOO_SHORT, PassphraseStrength.estimate("abc").hint)
        assertEquals(Hint.COMMON, PassphraseStrength.estimate("passwordpassword").hint)
        assertEquals(Hint.REPEAT, PassphraseStrength.estimate("zzzzzzzzzzzzzz").hint)
        assertEquals(Hint.KEYBOARD, PassphraseStrength.estimate("asdfghjkl;'x").hint)
        assertEquals(Hint.NONE, PassphraseStrength.estimate("correct horse battery staple").hint)
        assertEquals(0, score(""))
    }

    @Test fun score_grows_with_length() {
        assertTrue(PassphraseStrength.estimate("kitten tulip").guessesLog10 < PassphraseStrength.estimate("kitten tulip harbour").guessesLog10)
    }

    @Test fun well_known_patterns_are_never_strong() {
        // Word + year + symbol, repeated halves, names and places with a digit: the first rules any cracker tries.
        val weak = listOf(
            "Summer2024!", "summer2024summer2024", "Liverpool1!", "jessica12345", "Butterfly2024", "Chelsea2023!",
            "Michael1985", "Sunshine2020!", "Dragonfly99", "abcdabcdabcd", "Password2024!", "Barcelona10",
            "monkey123monkey123", "Charlotte2019", "Manchester1!", "Elephant2022",
        )
        for (p in weak) assertTrue("$p scored ${score(p)}", score(p) < 4)
        for (p in listOf("Summer2024!", "summer2024summer2024", "Liverpool1!", "jessica12345", "Butterfly2024")) {
            assertFalse("$p is accepted for a backup", PassphraseStrength.acceptableForBackup(p))
        }
    }

    @Test fun repeated_halves_cost_little_more_than_one_half() {
        val once = PassphraseStrength.estimate("tulip2024").guessesLog10
        val twice = PassphraseStrength.estimate("tulip2024tulip2024").guessesLog10
        assertTrue("$once vs $twice", twice - once < 2)
        assertEquals(Hint.REPEAT, PassphraseStrength.estimate("qzvxkqzvxkqzvxk").hint)
    }
}
