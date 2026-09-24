package app.parley.common

import app.parley.common.blocking.PersonalReputation
import app.parley.common.history.HistoryMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Regression tests for the round-4 bugs F7, F13, F14, F15, F19, F21, F22 and F27 (COMPETITIVE_ANALYSIS_4 §2). */
class SafetyRound4Test {

    // ------------------------------------------------------------------ F7: number matching by E.164

    /** A French and a Spanish mobile that share their last 9 digits. */
    private val fr = "+33612345678"
    private val es = "+34612345678"

    @Test fun f7_different_countries_sharing_last_9_digits_do_not_match() {
        assertEquals(PhoneNumbers.matchKey(fr), PhoneNumbers.matchKey(es)) // the old key collided
        assertFalse(PhoneNumbers.same(fr, es, "FR"))
        assertFalse(PhoneNumbers.same("06 12 34 56 78", es, "FR"))
        assertNotEquals(PhoneNumbers.lineKey(fr, "FR"), PhoneNumbers.lineKey(es, "FR"))
        assertNotEquals(PhoneNumbers.lineKey("0612345678", "FR"), PhoneNumbers.lineKey(es, "FR"))
        val mine = PhoneNumbers.LineSet(listOf(fr), "FR")
        assertFalse(es in mine)
        assertFalse("0034 612 34 56 78" in mine)
    }

    @Test fun f7_national_and_international_forms_of_one_number_match() {
        for (form in listOf("06 12 34 56 78", "0612345678", "+33 6 12 34 56 78", "0033612345678", "33612345678")) {
            assertTrue(form, PhoneNumbers.same(form, fr, "FR"))
            assertEquals(form, fr, PhoneNumbers.lineKey(form, "FR"))
            assertTrue(form, form in PhoneNumbers.LineSet(listOf("06 12 34 56 78"), "FR"))
        }
        // The SIM country decides how a national number is read.
        assertEquals("+34612345678", PhoneNumbers.lineKey("612 34 56 78", "ES"))
        assertEquals("+14155550123", PhoneNumbers.lineKey("(415) 555-0123", "US"))
    }

    @Test fun f7_fallback_key_only_without_e164() {
        assertEquals("~d112", PhoneNumbers.lineKey("112", "DE"))
        assertEquals("", PhoneNumbers.lineKey("", "DE"))
        // A national number with no known country has no E.164 form: last digits, never equal to an E.164 key.
        assertTrue(PhoneNumbers.lineKey("0612345678", null).startsWith("~"))
        // LineSet follows same(): a probe without E.164 falls back to the last digits.
        assertTrue("0612345678" in PhoneNumbers.LineSet(listOf(fr), null))
        assertFalse("0612345679" in PhoneNumbers.LineSet(listOf(fr), null))
        assertFalse("12" in PhoneNumbers.LineSet(listOf("112"), "DE"))
        assertTrue("112" in PhoneNumbers.LineSet(listOf("112"), "DE"))
        assertTrue(PhoneNumbers.LineSet(emptyList(), "FR").isEmpty)
    }

    @Test fun f7_vault_keys_are_e164_with_suffix_only_as_fallback() {
        assertEquals(listOf("e164:+33612345678"), VaultNumberKeys.stored("06 12 34 56 78", "FR"))
        assertEquals(listOf("e164:+33612345678"), VaultNumberKeys.stored("+33 6 12 34 56 78", "DE"))
        // A foreign caller with the same last 9 digits is looked up by its own E.164 first.
        assertEquals("e164:+34612345678", VaultNumberKeys.lookup(es, "FR").first())
        assertTrue(VaultNumberKeys.stored(fr, "FR").none { it in VaultNumberKeys.lookup(es, "FR", exact = true) })
        // Short codes can't be E.164: the digits are the key.
        assertEquals(listOf("112"), VaultNumberKeys.stored("112", "DE"))
        assertEquals(listOf("112"), VaultNumberKeys.lookup("112", "DE"))
        // Exact lookups (private-name provider) never use the last digits.
        assertEquals(listOf("e164:+33612345678"), VaultNumberKeys.lookup("0612345678", "FR", exact = true))
        assertEquals(emptyList<String>(), VaultNumberKeys.lookup("112", "DE", exact = true))
        assertEquals(listOf("e164:+33612345678"), VaultNumberKeys.storedAll(listOf("0612345678", "+33612345678"), "FR"))
    }

    @Test fun f7_reputation_groups_by_line() {
        val h = 3_600_000L
        val now = 100 * h
        fun call(n: String, t: Long) = CallEntry(t, n, null, CallType.REJECTED, t, 0, null, false, false)
        // One rejection each from two different countries sharing the last 9 digits: neither reaches the threshold.
        val calls = listOf(call(fr, now - 5 * h), call(es, now - 4 * h))
        assertTrue(PersonalReputation.suggestions(calls, now, countryOf = { "FR" }) { false }.isEmpty())
        // National and international forms of one number are one caller.
        val same = listOf(call("0612345678", now - 5 * h), call(fr, now - 4 * h))
        assertEquals(1, PersonalReputation.suggestions(same, now, countryOf = { "FR" }) { false }.size)
    }

    // ------------------------------------------------------------------ F15: vault winner

    @Test fun f15_newest_unexpired_vault_entry_wins() {
        val now = 1_000_000L
        val a = VaultNumberKeys.Candidate(id = 1, updatedAt = 500, createdAt = 100, expiresAt = null)
        val b = VaultNumberKeys.Candidate(id = 2, updatedAt = 900, createdAt = 50, expiresAt = null)
        val expired = VaultNumberKeys.Candidate(id = 3, updatedAt = 999, createdAt = 999, expiresAt = now - 1)
        assertEquals(2L, VaultNumberKeys.winner(listOf(a, b, expired), now)?.id)
        assertEquals(2L, VaultNumberKeys.winner(listOf(b, a), now)?.id) // order doesn't matter
        assertNull(VaultNumberKeys.winner(listOf(expired), now))
        // Ties are broken by creation time, then id.
        val c = VaultNumberKeys.Candidate(id = 4, updatedAt = 900, createdAt = 50, expiresAt = now + 1)
        assertEquals(4L, VaultNumberKeys.winner(listOf(b, c), now)?.id)
    }

    // ------------------------------------------------------------------ F13: "last messaged" record

    @Test fun f13_record_find_forget_prune() {
        var list = MessagedRecord.record(emptyList(), "06 12 34 56 78", "com.whatsapp", "WhatsApp", 10, "FR")
        list = MessagedRecord.record(list, es, "org.thoughtcrime.securesms", "Signal", 20, "FR")
        assertEquals(2, list.size)
        assertEquals("WhatsApp", MessagedRecord.find(list, fr, "FR")?.label)
        assertEquals("Signal", MessagedRecord.find(list, es, "FR")?.label)
        // Re-recording refreshes the same line instead of adding a second entry.
        list = MessagedRecord.record(list, "+33 6 12 34 56 78", null, "SMS", 30, "FR")
        assertEquals(2, list.size)
        assertEquals("SMS", MessagedRecord.find(list, "0612345678", "FR")?.label)
        list = MessagedRecord.forget(list, fr, "FR")
        assertNull(MessagedRecord.find(list, fr, "FR"))
        assertEquals("Signal", MessagedRecord.find(list, es, "FR")?.label)
        assertTrue(MessagedRecord.prune(list, 21).isEmpty())
    }

    @Test fun f13_capped_and_legacy_entries() {
        var list = emptyList<MessagedEntry>()
        repeat(MessagedRecord.MAX_ENTRIES + 5) { i -> list = MessagedRecord.record(list, "+3361234" + (1000 + i), null, "SMS", i.toLong(), "FR") }
        assertEquals(MessagedRecord.MAX_ENTRIES, list.size)
        assertEquals(5L, list.first().at)
        // An entry of the old plain record (key = last 9 digits) is still found and can be forgotten.
        val legacy = listOfNotNull(MessagedRecord.fromLegacy("612345678", "com.whatsapp", "WhatsApp", 1))
        assertEquals("WhatsApp", MessagedRecord.find(legacy, fr, "FR")?.label)
        assertTrue(MessagedRecord.forget(legacy, fr, "FR").isEmpty())
        // Recording the number again replaces the old entry.
        assertEquals(1, MessagedRecord.record(legacy, fr, null, "SMS", 2, "FR").size)
    }

    // ------------------------------------------------------------------ F14: notifications

    @Test fun f14_missed_call_name_respects_discreet_mode() {
        assertEquals("Anna", NotificationPrivacy.missedCallName("Anna", "Secret", hideVault = true, number = fr))
        assertEquals("Secret", NotificationPrivacy.missedCallName(null, "Secret", hideVault = false, number = fr))
        assertEquals(fr, NotificationPrivacy.missedCallName(null, "Secret", hideVault = true, number = fr))
        assertNull(NotificationPrivacy.missedCallName(null, null, hideVault = false, number = ""))
    }

    @Test fun f14_private_label_never_shown() {
        assertNull(NotificationPrivacy.shownLabel("Private"))
        assertNull(NotificationPrivacy.shownLabel("private"))
        assertNull(NotificationPrivacy.shownLabel(" "))
        assertEquals("Mobile", NotificationPrivacy.shownLabel("Mobile"))
    }

    // ------------------------------------------------------------------ F19: messaging country and links

    @Test fun f19_unavailable_reason_matches_link_building() {
        assertNull(MessengerLinks.unavailableReason("+923001234567"))
        assertTrue(MessengerLinks.unavailableReason(null) != null)
        assertTrue(MessengerLinks.unavailableReason("+12345") != null) // too short: the link would be null
        assertTrue(MessengerLinks.unavailableReason("+1234567890123456") != null) // too long
        for (app in MessengerApp.entries) {
            for (n in listOf("+923001234567", "+12345", "+1234567890123456")) {
                assertEquals(MessengerLinks.unavailableReason(n) == null, MessengerLinks.build(app, n) != null)
            }
        }
    }

    @Test fun f19_country_picker_search() {
        val all = NumberText.regions(Locale.ENGLISH)
        assertTrue(all.size > 200)
        assertTrue(NumberText.searchRegions(all, "fran").any { it.code == "FR" })
        assertTrue(NumberText.searchRegions(all, "+33").any { it.code == "FR" })
        assertTrue(NumberText.searchRegions(all, "pk").any { it.code == "PK" })
        assertEquals(all, NumberText.searchRegions(all, " "))
        // A national number reads differently with another country.
        assertEquals("+923001234567", NumberText.toE164("0300 1234567", "PK"))
        assertNotEquals(NumberText.toE164("0300 1234567", "PK"), NumberText.toE164("0300 1234567", "GB"))
    }

    // ------------------------------------------------------------------ F21: undo delete is idempotent

    @Test fun f21_restore_skips_rows_already_present() {
        val rows = listOf("a|1", "b|2", "b|2", "c|3")
        assertEquals(listOf("a|1", "b|2", "c|3"), HistoryMerge.missing(rows, emptyList()) { it })
        assertEquals(listOf("c|3"), HistoryMerge.missing(rows, listOf("a|1", "b|2")) { it })
        assertTrue(HistoryMerge.missing(rows, rows) { it }.isEmpty())
        // Keys ignore milliseconds, like the dedupe everywhere else.
        fun e(ms: Long) = CallEntry(0, fr, null, CallType.INCOMING, ms, 10, null, false, false)
        assertTrue(HistoryMerge.missing(listOf(e(10_500)), listOf(e(10_000)), HistoryMerge::key).isEmpty())
    }

    // ------------------------------------------------------------------ F22: keypad tones

    @Test fun f22_tones_follow_ringer_and_system_setting() {
        assertTrue(KeypadFeedback.playTone(appSetting = true, systemDialpadTones = true, ringerNormal = true))
        assertFalse(KeypadFeedback.playTone(appSetting = true, systemDialpadTones = true, ringerNormal = false))
        assertFalse(KeypadFeedback.playTone(appSetting = true, systemDialpadTones = false, ringerNormal = true))
        assertFalse(KeypadFeedback.playTone(appSetting = false, systemDialpadTones = true, ringerNormal = true))
    }

    // ------------------------------------------------------------------ F27: initials

    @Test fun f27_initials_never_split_surrogate_pairs() {
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
