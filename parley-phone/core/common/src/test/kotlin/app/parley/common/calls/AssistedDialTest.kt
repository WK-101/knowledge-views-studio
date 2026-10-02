package app.parley.common.calls

import app.parley.common.calls.AssistedDial.Sim
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistedDialTest {
    private val util = PhoneNumberUtil.getInstance()

    private fun sim(home: String?, there: String?, id: String = "1", label: String = "SIM 1") = Sim(id, label, home, there, roaming = home != there)

    private val ukInFrance = sim("gb", "fr")

    @Test fun abroad_needs_both_countries_and_a_difference() {
        assertTrue(AssistedDial.abroad(ukInFrance))
        assertFalse(AssistedDial.abroad(sim("GB", "GB")))
        // National roaming: roaming, but at home.
        assertFalse(AssistedDial.abroad(Sim("1", "SIM 1", "GB", "GB", roaming = true)))
        assertFalse(AssistedDial.abroad(sim("GB", null)))
        assertFalse(AssistedDial.abroad(sim(null, "FR")))
        assertFalse(AssistedDial.abroad(sim("GB", "")))
    }

    @Test fun uk_mobile_typed_in_france() {
        val p = AssistedDial.convert("07400 123456", ukInFrance)
        assertNotNull(p)
        assertEquals("+447400123456", p!!.dial)
        assertEquals("+44 7400 123456", p.shown)
        assertEquals("GB", p.home)
        assertEquals("FR", p.visited)
        assertFalse(p.alsoLocal)
    }

    @Test fun nothing_at_home() {
        assertNull(AssistedDial.convert("07400 123456", sim("GB", "GB")))
        assertNull(AssistedDial.convert("07400 123456", sim("GB", null)))
    }

    /** Every country's own example mobile and fixed numbers, typed in national format while abroad. */
    @Test fun many_countries() {
        val homes = listOf("GB", "DE", "FR", "IT", "ES", "NL", "IN", "PK", "BR", "MX", "JP", "AU", "ZA", "NG", "EG", "AE", "US", "TR", "PL", "SE")
        for (home in homes) {
            val visited = if (home == "TH") "GB" else "TH"
            val examples = listOf(PhoneNumberType.MOBILE, PhoneNumberType.FIXED_LINE).mapNotNull { type ->
                util.getExampleNumberForType(home, type)
                    ?.takeIf { util.getNumberType(it) in setOf(type, PhoneNumberType.FIXED_LINE_OR_MOBILE) }
                    ?.let { type to it }
            }
            for ((type, ex) in examples) {
                val national = util.format(ex, PhoneNumberFormat.NATIONAL)
                val p = AssistedDial.convert(national, sim(home, visited))
                assertNotNull("$home $type $national", p)
                assertEquals("$home $type $national", util.format(ex, PhoneNumberFormat.E164), p!!.dial)
                assertTrue(p.shown.startsWith("+" + ex.countryCode + " "))
            }
        }
    }

    @Test fun separators_and_native_digits_are_read() {
        assertEquals("+33612345678", AssistedDial.convert("06.12.34.56.78", sim("FR", "GB"))?.dial)
        assertEquals("+33612345678", AssistedDial.convert("(06) 12-34-56-78", sim("FR", "GB"))?.dial)
        // Arabic-Indic digits from a keyboard.
        assertEquals("+447400123456", AssistedDial.convert("٠٧٤٠٠١٢٣٤٥٦", ukInFrance)?.dial)
    }

    @Test fun pause_and_wait_digits_follow() {
        val p = AssistedDial.convert("07400 123456,1234#", ukInFrance)!!
        assertEquals("+447400123456,1234#", p.dial)
        assertEquals("+44 7400 123456,1234#", p.shown)
        assertEquals("+447400123456;99", AssistedDial.convert("07400123456;99", ukInFrance)?.dial)
    }

    @Test fun numbers_with_a_country_code_are_left_alone() {
        assertNull(AssistedDial.convert("+44 7400 123456", ukInFrance))
        assertNull(AssistedDial.convert("0044 7400 123456", ukInFrance))
        assertNull(AssistedDial.convert("00 33 6 12 34 56 78", ukInFrance))
        // The US international prefix.
        assertNull(AssistedDial.convert("011 44 7400 123456", sim("US", "FR")))
    }

    @Test fun emergency_numbers_are_never_rewritten() {
        for (n in listOf("112", "999", "911", "000", "110", "119", "08")) {
            assertNull(n, AssistedDial.convert(n, ukInFrance))
            assertNull(n, AssistedDial.convert(n, sim("US", "DE")))
        }
        // The platform said so: whatever it looks like.
        assertNull(AssistedDial.convert("07400 123456", ukInFrance, emergency = true))
    }

    @Test fun short_codes_and_service_numbers_are_left_alone() {
        // Directory enquiries and helplines (six digits).
        assertNull(AssistedDial.convert("118 118", ukInFrance))
        assertNull(AssistedDial.convert("116 123", ukInFrance))
        assertNull(AssistedDial.convert("3631", sim("FR", "GB")))
        assertNull(AssistedDial.convert("100", ukInFrance))
        // Freephone, premium rate, shared cost: usually unreachable from abroad, and never personal lines.
        for (home in listOf("GB", "DE", "FR", "US", "IN", "AU")) {
            for (type in listOf(PhoneNumberType.TOLL_FREE, PhoneNumberType.PREMIUM_RATE, PhoneNumberType.SHARED_COST, PhoneNumberType.UAN)) {
                val ex = util.getExampleNumberForType(home, type) ?: continue
                val national = util.format(ex, PhoneNumberFormat.NATIONAL)
                assertNull("$home $type $national", AssistedDial.convert(national, sim(home, "TH")))
            }
        }
    }

    @Test fun codes_and_words_are_left_alone() {
        assertNull(AssistedDial.convert("*#06#", ukInFrance))
        assertNull(AssistedDial.convert("*100#", ukInFrance))
        assertNull(AssistedDial.convert("#31#07400123456", ukInFrance))
        assertNull(AssistedDial.convert("0800 FLOWERS", sim("US", "GB")))
        assertNull(AssistedDial.convert("", ukInFrance))
        assertNull(AssistedDial.convert("   ", ukInFrance))
    }

    @Test fun invalid_at_home_is_left_alone() {
        assertNull(AssistedDial.convert("0123", ukInFrance))
        assertNull(AssistedDial.convert("07400 12", ukInFrance))
        assertNull(AssistedDial.convert("09999 999999999", ukInFrance))
    }

    @Test fun countries_sharing_a_calling_code_dial_as_typed() {
        // A US number works as typed in Canada (one numbering plan), a UK number in Jersey.
        assertNull(AssistedDial.convert("(201) 555-0123", sim("US", "CA")))
        assertNull(AssistedDial.convert("07400 123456", sim("GB", "JE")))
        // But not from Mexico.
        assertEquals("+12015550123", AssistedDial.convert("(201) 555-0123", sim("US", "MX"))?.dial)
    }

    @Test fun says_when_it_could_also_be_a_local_number() {
        // A landline typed abroad: some are valid numbers in the visited country too.
        val both = listOf(
            "GB" to "IE", "DE" to "AT", "FR" to "BE", "IT" to "CH", "NL" to "BE", "ES" to "PT", "US" to "GB",
        ).mapNotNull { (home, there) ->
            val ex = util.getExampleNumberForType(home, PhoneNumberType.FIXED_LINE)
            AssistedDial.convert(util.format(ex, PhoneNumberFormat.NATIONAL), sim(home, there))
        }
        assertTrue(both.isNotEmpty())
        // A Milan landline typed in Switzerland is also a Swiss-looking number; a UK mobile in France isn't French.
        assertTrue(AssistedDial.convert("02 1234 5678", sim("IT", "CH"))!!.alsoLocal)
        assertFalse(AssistedDial.convert("07400 123456", ukInFrance)!!.alsoLocal)
        // Whatever it says, the flag matches the visited country's own check.
        for (p in both) {
            val digits = util.parse(p.dial, null).let { util.format(it, PhoneNumberFormat.NATIONAL) }
            val asLocal = runCatching { util.isValidNumberForRegion(util.parse(digits, p.visited), p.visited) }.getOrDefault(false)
            assertEquals(p.dial, asLocal, p.alsoLocal)
        }
    }

    @Test fun local_sim_hint_once_per_trip() {
        val roaming = Sim("a", "SIM 1", "GB", "FR", roaming = true)
        val local = Sim("b", "SIM 2", "FR", "FR")
        val sims = listOf(roaming, local)
        val hint = AssistedDial.localSimHint(sims, "a", null)
        assertNotNull(hint)
        assertEquals(local, hint!!.local)
        assertEquals("a|FR", hint.trip)
        // Already suggested on this trip.
        assertNull(AssistedDial.localSimHint(sims, "a", hint.trip))
        // A new trip asks again.
        val spain = listOf(roaming.copy(networkCountry = "ES"), local.copy(simCountry = "ES", networkCountry = "ES"))
        assertNotNull(AssistedDial.localSimHint(spain, "a", hint.trip))
        // Calling on the local SIM: nothing to suggest.
        assertNull(AssistedDial.localSimHint(sims, "b", null))
    }

    @Test fun no_hint_without_a_local_sim() {
        val roaming = Sim("a", "SIM 1", "GB", "FR", roaming = true)
        // Both roaming, or the second SIM has no service, or it is local somewhere else.
        assertNull(AssistedDial.localSimHint(listOf(roaming, Sim("b", "SIM 2", "DE", "FR", roaming = true)), "a", null))
        assertNull(AssistedDial.localSimHint(listOf(roaming, Sim("b", "SIM 2", "FR", null)), "a", null))
        assertNull(AssistedDial.localSimHint(listOf(roaming), "a", null))
        assertNull(AssistedDial.localSimHint(listOf(roaming, Sim("b", "SIM 2", "FR", "FR")), null, null))
        assertNull(AssistedDial.localSimHint(listOf(roaming, Sim("b", "SIM 2", "FR", "FR")), "x", null))
    }

    @Test fun trip_ends_when_the_sim_is_home() {
        val away = listOf(Sim("a", "SIM 1", "GB", "FR", roaming = true))
        assertFalse(AssistedDial.tripOver(away, "a|FR"))
        assertTrue(AssistedDial.tripOver(listOf(Sim("a", "SIM 1", "GB", "GB")), "a|FR"))
        assertTrue(AssistedDial.tripOver(listOf(Sim("a", "SIM 1", "GB", "ES", roaming = true)), "a|FR"))
        assertFalse(AssistedDial.tripOver(away, null))
        // The SIM was taken out: keep the memory.
        assertFalse(AssistedDial.tripOver(emptyList(), "a|FR"))
    }

    @Test fun config_on_by_default_and_round_trips() {
        val d = AssistedDialConfig()
        assertTrue(d.assistedDialling && d.localSimHint)
        assertEquals(d, AssistedDialConfig.decode(null))
        assertEquals(d, AssistedDialConfig.decode("{broken"))
        val off = AssistedDialConfig(assistedDialling = false)
        assertEquals(off, AssistedDialConfig.decode(AssistedDialConfig.encode(off)))
    }
}
