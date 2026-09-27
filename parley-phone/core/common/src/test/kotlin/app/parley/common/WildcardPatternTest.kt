package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WildcardPatternTest {
    /** The regex translation the matcher replaced, kept here as the reference for its meaning. */
    private fun oldRegex(pattern: String): Regex? {
        val p = pattern.trim()
        if (p.isEmpty()) return null
        val sb = StringBuilder()
        for ((i, c) in p.withIndex()) {
            when {
                c == '*' -> sb.append("[0-9]*")
                c == '?' -> sb.append("[0-9]")
                c in '0'..'9' -> sb.append(c)
                c == '+' && i == 0 -> sb.append("\\+")
                c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' -> Unit
                else -> return null
            }
        }
        return Regex(sb.toString())
    }

    private fun m(pattern: String, number: String) = WildcardPattern.compile(pattern)!!.matches(number)

    @Test fun stars_questions_plus_and_separators() {
        assertTrue(m("+1 855*", "+18551234567"))
        assertTrue(m("+1 855*", "+1855"))
        assertFalse(m("+1 855*", "18551234567"))
        assertFalse(m("1855*", "+18551234567"))
        assertTrue(m("0800??????", "0800123456"))
        assertFalse(m("0800??????", "08001234567"))
        assertFalse(m("0800??????", "080012345"))
        assertTrue(m("*123", "999123"))
        assertTrue(m("*123", "123"))
        assertTrue(m("06*12*34", "0612993488834"))
        assertFalse(m("06*12*34", "0612993488835"))
        assertTrue(m("(06) 12-34.56", "06123456"))
        assertTrue(m("*", ""))
        assertFalse(m("?", ""))
    }

    @Test fun invalid_patterns() {
        assertNull(WildcardPattern.compile(""))
        assertNull(WildcardPattern.compile("   "))
        assertNull(WildcardPattern.compile("06_12"))
        assertNull(WildcardPattern.compile("06%"))
        assertNull(WildcardPattern.compile("0+6"))
        assertNull(WildcardPattern.compile("1".repeat(WildcardPattern.MAX_LENGTH + 1)))
        assertNotNull(WildcardPattern.compile("1".repeat(WildcardPattern.MAX_LENGTH)))
    }

    @Test fun two_hundred_stars_finish_at_once() {
        val started = System.nanoTime()
        val p = WildcardPattern.compile("*".repeat(199) + "9")!!
        repeat(1000) {
            assertFalse(p.matches("336123456780123450"))
            assertTrue(p.matches("1234567890123459"))
        }
        // Alternating stars and digits can't collapse: still quadratic at worst, never exponential.
        val mixed = WildcardPattern.compile((1..100).joinToString("") { "*1" }.take(200))!!
        repeat(100) { assertFalse(mixed.matches("1111111111111112")) }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 2000)
    }

    @Test fun same_answers_as_the_old_regex_on_normal_patterns() {
        val patterns = listOf(
            "+1 855*", "0800??????", "*123", "06*12*34", "+33 6 ** ** ** **", "+44 20 ????????", "?*?", "*", "+*",
            "0?0?0*", "(0800) 12-34.56", "+", "12*", "*9*9*", "+1*555*",
        )
        val numbers = listOf(
            "+18551234567", "18551234567", "0800123456", "999123", "123", "0612993488834", "+33612345678", "+442012345678",
            "0080005", "", "+", "12", "9", "99", "+1555", "+15550000", "08001234", "+33 6",
        )
        for (p in patterns) for (n in numbers) {
            assertEquals("$p vs $n", oldRegex(p)!!.matches(n), m(p, n))
        }
        // And on random short patterns and numbers (short enough for the regex to stay fast).
        val rnd = Random(7)
        val alphabet = "0123*?"
        repeat(3000) {
            val p = (if (rnd.nextInt(4) == 0) "+" else "") + (1..rnd.nextInt(1, 8)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            val n = (if (rnd.nextInt(4) == 0) "+" else "") + (1..rnd.nextInt(0, 9)).map { "0123"[rnd.nextInt(4)] }.joinToString("")
            assertEquals("$p vs $n", oldRegex(p)!!.matches(n), m(p, n))
        }
    }

    @Test fun rules_use_the_linear_matcher() {
        val rule = BlockRule(pattern = "*".repeat(199) + "9", type = RuleType.WILDCARD)
        assertFalse(CallPolicy.ruleMatches(rule, "+33612345678", "FR"))
        assertTrue(CallPolicy.ruleMatches(BlockRule(pattern = "+33 6*", type = RuleType.WILDCARD), "0612345678", "FR"))
        assertNotNull(RuleTools.check("0800_1", RuleType.WILDCARD, "FR").error)
    }
}
