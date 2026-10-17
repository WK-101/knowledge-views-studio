package app.parley.common.calls

import app.parley.common.calls.HideNumber.Clir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HideNumberTest {

    // ---- the code table, by the line's country (S2) ----

    @Test fun the_default_is_the_3gpp_code() {
        assertEquals("#31#", HideNumber.code(null, hide = true))
        assertEquals("*31#", HideNumber.code(null, hide = false))
        // A country with no override falls back to the default.
        assertEquals("#31#", HideNumber.code("FR", hide = true))
        assertEquals("*31#", HideNumber.code("DE", hide = false))
    }

    @Test fun north_america_uses_star_67_and_82() {
        for (c in listOf("US", "CA", "us", "ca", "PR")) {
            assertEquals("*67", HideNumber.code(c, hide = true))
            assertEquals("*82", HideNumber.code(c, hide = false))
        }
    }

    @Test fun the_uk_australia_and_japan_have_their_own_codes() {
        assertEquals("141", HideNumber.code("GB", hide = true))
        assertEquals("1470", HideNumber.code("GB", hide = false))
        assertEquals("1831", HideNumber.code("AU", hide = true))
        assertEquals("1832", HideNumber.code("AU", hide = false))
        assertEquals("184", HideNumber.code("JP", hide = true))
        assertEquals("186", HideNumber.code("JP", hide = false))
    }

    // ---- exclusions: emergency, service/USSD, internet/SIP, already-coded ----

    @Test fun a_plain_number_may_be_prefixed() {
        assertTrue(HideNumber.mayPrefix("+33612345678", serviceCode = false))
        assertTrue(HideNumber.mayPrefix("020 7946 0958", serviceCode = false))
    }

    @Test fun service_and_ussd_codes_are_never_prefixed() {
        assertFalse(HideNumber.mayPrefix("*100#", serviceCode = true))
        assertFalse(HideNumber.mayPrefix("*#06#", serviceCode = true))
        // Anything already starting with * or # is a code or hand-typed; never stack another.
        assertFalse(HideNumber.mayPrefix("*31#0612345678", serviceCode = false))
        assertFalse(HideNumber.mayPrefix("#31#0612345678", serviceCode = false))
    }

    @Test fun internet_calls_are_never_prefixed() {
        assertFalse(HideNumber.mayPrefix("+447700900123", serviceCode = false, internet = true))
    }

    @Test fun a_blank_number_is_never_prefixed() {
        assertFalse(HideNumber.mayPrefix("", serviceCode = false))
        assertFalse(HideNumber.mayPrefix("   ", serviceCode = false))
    }

    @Test fun an_already_hidden_number_is_not_prefixed_again() {
        assertFalse(HideNumber.mayPrefix("*671235550123", serviceCode = false, country = "US"))
        assertFalse(HideNumber.mayPrefix("1410207946095", serviceCode = false, country = "GB"))
    }

    @Test fun apply_adds_the_right_code_and_leaves_excluded_calls_alone() {
        assertEquals("*67+15555550123", HideNumber.apply("+15555550123", "US", Clir.HIDE, serviceCode = false))
        assertEquals("*82+15555550123", HideNumber.apply("+15555550123", "US", Clir.SHOW, serviceCode = false))
        assertEquals("#31#+33612345678", HideNumber.apply("+33612345678", "FR", Clir.HIDE, serviceCode = false))
        // Plain never changes the number.
        assertEquals("+33612345678", HideNumber.apply("+33612345678", "FR", Clir.PLAIN, serviceCode = false))
        // A service code or an internet call is left untouched even when asked to hide.
        assertEquals("*100#", HideNumber.apply("*100#", "GB", Clir.HIDE, serviceCode = true))
        assertEquals("+447700900123", HideNumber.apply("+447700900123", "GB", Clir.HIDE, serviceCode = false, internet = true))
    }

    // ---- policy precedence ----

    @Test fun a_per_call_choice_wins_over_any_policy() {
        // Show for this call beats a contact/label/Situation that would hide.
        assertEquals(Clir.SHOW, HideNumber.resolve(perCall = false, contact = true, label = true, situation = true))
        // Hide for this call beats no policy at all.
        assertEquals(Clir.HIDE, HideNumber.resolve(perCall = true))
    }

    @Test fun any_policy_source_hides_when_there_is_no_per_call_choice() {
        assertEquals(Clir.HIDE, HideNumber.resolve(perCall = null, contact = true))
        assertEquals(Clir.HIDE, HideNumber.resolve(perCall = null, label = true))
        assertEquals(Clir.HIDE, HideNumber.resolve(perCall = null, situation = true))
    }

    @Test fun nothing_hides_by_default() {
        assertEquals(Clir.PLAIN, HideNumber.resolve(perCall = null))
        assertEquals(Clir.PLAIN, HideNumber.resolve(perCall = null, contact = false, label = false, situation = false))
    }

    // ---- stripping the prefix back off for history and redial ----

    @Test fun the_mmi_forms_are_stripped() {
        assertEquals("0612345678", HideNumber.strip("#31#0612345678"))
        assertEquals("0612345678", HideNumber.strip("*31#0612345678"))
        assertEquals("+33612345678", HideNumber.strip("#31#+33612345678"))
    }

    @Test fun the_star_forms_are_stripped() {
        assertEquals("+15555550123", HideNumber.strip("*67+15555550123"))
        assertEquals("15555550123", HideNumber.strip("*8215555550123"))
    }

    @Test fun region_digit_codes_strip_only_before_a_trunk_zero_or_plus() {
        assertEquals("02079460958", HideNumber.strip("14102079460958", "GB"))
        assertEquals("02079460958", HideNumber.strip("147002079460958", "GB"))
        assertEquals("+61299990000", HideNumber.strip("1831+61299990000", "AU"))
        assertEquals("0312345678", HideNumber.strip("1840312345678", "JP"))
        assertEquals("0312345678", HideNumber.strip("1860312345678", "JP"))
    }

    @Test fun a_digit_code_is_stripped_only_for_its_own_country() {
        // A North-American "1 410 …" or "1 860 …" must not be mistaken for the UK or Japanese code.
        assertEquals("14102079460958", HideNumber.strip("14102079460958", "US"))
        assertEquals("18605551234", HideNumber.strip("18605551234", "US"))
        // Without a country, digit codes are not stripped at all (only the symbol forms are).
        assertEquals("14102079460958", HideNumber.strip("14102079460958", null))
    }

    @Test fun a_real_number_that_only_looks_like_a_code_is_left_alone() {
        // A Glasgow number (0141...) does not begin with a bare 141, so it is never stripped.
        assertEquals("01412345678", HideNumber.strip("01412345678", "GB"))
        // 141 not followed by a trunk 0 or + is left as a number.
        assertEquals("1415550123", HideNumber.strip("1415550123", "GB"))
        // A plain international number is untouched.
        assertEquals("+441412345678", HideNumber.strip("+441412345678", "GB"))
        assertEquals("+15555550123", HideNumber.strip("+15555550123", "US"))
    }

    @Test fun strip_is_idempotent() {
        val once = HideNumber.strip("*67+15555550123", "US")
        assertEquals(once, HideNumber.strip(once, "US"))
    }

    @Test fun apply_then_strip_gives_the_number_back() {
        for (country in listOf(null, "US", "GB", "AU", "JP", "FR")) {
            for (number in listOf("+15555550123", "+447700900123", "+61412345678", "+819012345678")) {
                val hidden = HideNumber.apply(number, country, Clir.HIDE, serviceCode = false)
                assertEquals("$country $number", number, HideNumber.strip(hidden, country))
                val shown = HideNumber.apply(number, country, Clir.SHOW, serviceCode = false)
                assertEquals("$country $number", number, HideNumber.strip(shown, country))
            }
        }
    }

    @Test fun national_numbers_round_trip_through_hide_and_strip() {
        // National forms start with a trunk 0, so the digit codes strip cleanly for their own country.
        assertEquals("02079460958", HideNumber.strip(HideNumber.apply("02079460958", "GB", Clir.HIDE, serviceCode = false), "GB"))
        assertEquals("09012345678", HideNumber.strip(HideNumber.apply("09012345678", "JP", Clir.SHOW, serviceCode = false), "JP"))
    }

    @Test fun was_hidden_tells_hide_from_show() {
        assertTrue(HideNumber.wasHidden("#31#0612345678"))
        assertTrue(HideNumber.wasHidden("*67+15555550123"))
        assertTrue(HideNumber.wasHidden("14102079460958", "GB"))
        assertTrue(HideNumber.wasHidden("1840312345678", "JP"))
        // Show codes are not "hidden".
        assertFalse(HideNumber.wasHidden("*31#0612345678"))
        assertFalse(HideNumber.wasHidden("*82+15555550123"))
        assertFalse(HideNumber.wasHidden("147002079460958", "GB"))
        // A plain number was not hidden.
        assertFalse(HideNumber.wasHidden("+15555550123"))
        assertFalse(HideNumber.wasHidden("01412345678", "GB"))
        // A North-American number is not read as a Japanese hide code.
        assertFalse(HideNumber.wasHidden("18605551234", "US"))
    }

    // ---- redial: a number that may still carry the previous call's code (forOutgoing) ----

    @Test fun for_outgoing_adds_the_code_to_a_plain_number() {
        assertEquals("*67+15555550123", HideNumber.forOutgoing("+15555550123", "US", Clir.HIDE, { false }))
        assertEquals("*82+15555550123", HideNumber.forOutgoing("+15555550123", "US", Clir.SHOW, { false }))
        assertEquals("+15555550123", HideNumber.forOutgoing("+15555550123", "US", Clir.PLAIN, { false }))
    }

    @Test fun for_outgoing_replaces_a_code_the_handle_still_carries() {
        // "Call again showing my number" on a handle the platform left the hide code on: the show code must win, not
        // be dropped because the number already begins with a code (which plain apply would do).
        assertEquals("*82+15555550123", HideNumber.forOutgoing("*67+15555550123", "US", Clir.SHOW, { false }))
        assertEquals("*31#+33612345678", HideNumber.forOutgoing("#31#+33612345678", "FR", Clir.SHOW, { false }))
        assertEquals("147002079460958", HideNumber.forOutgoing("14102079460958", "GB", Clir.SHOW, { false }))
        // Re-hiding a handle that already carries the hide code stays a single hide code (never stacked).
        assertEquals("*67+15555550123", HideNumber.forOutgoing("*67+15555550123", "US", Clir.HIDE, { false }))
    }

    @Test fun for_outgoing_tests_the_service_code_on_the_stripped_number() {
        // The service-code test must run on the plain number: with the old code still in front it would look like an
        // MMI and the show code would be dropped. "*67" + a real number is not a service code once the code is off.
        var seen = ""
        val out = HideNumber.forOutgoing("*67+15555550123", "US", Clir.SHOW, { n -> seen = n; n.startsWith("*") })
        assertEquals("+15555550123", seen)
        assertEquals("*82+15555550123", out)
    }

    // ---- "call again showing my number" (S3) ----

    @Test fun call_again_showing_is_offered_only_for_a_refused_hidden_call() {
        // A hidden call turned away by the other side: offer the guess.
        for (code in listOf(EndCode.REMOTE, EndCode.REJECTED, EndCode.BUSY, EndCode.RESTRICTED)) {
            assertTrue("$code", HideNumber.offerShowAgain(wasHidden = true, connected = false, userEnded = false, code = code))
        }
    }

    @Test fun call_again_showing_is_not_offered_when_it_would_not_help() {
        // Not hidden: nothing to offer.
        assertFalse(HideNumber.offerShowAgain(wasHidden = false, connected = false, userEnded = false, code = EndCode.REJECTED))
        // Connected: the hide did not stop it.
        assertFalse(HideNumber.offerShowAgain(wasHidden = true, connected = true, userEnded = false, code = EndCode.REMOTE))
        // The user hung up: not a refusal.
        assertFalse(HideNumber.offerShowAgain(wasHidden = true, connected = false, userEnded = true, code = EndCode.REMOTE))
        // A local/cancelled/missing end is not "the other side refused".
        for (code in listOf(EndCode.LOCAL, EndCode.CANCELED, EndCode.MISSED, EndCode.ERROR, null)) {
            assertFalse("$code", HideNumber.offerShowAgain(wasHidden = true, connected = false, userEnded = false, code = code))
        }
    }
}
