package app.parley.common

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One identity path for numbers: [PhoneIdentity.e164] (libphonenumber, then the old heuristic as fallback) compared
 * with the old hand-written heuristic ([PhoneNumbers.heuristicE164]) over a corpus. Every difference is one of the
 * intentional ones listed in docs/CONTACT_MODEL.md ("Phone numbers: one identity path"); anything else fails here.
 */
class IdentityCorpusTest {
    private val util = PhoneNumberUtil.getInstance()

    /** Every example number libphonenumber ships, for every region and line type, written three ways. */
    private val examples: List<Triple<String, String, String>> by lazy {
        util.supportedRegions.sorted().flatMap { region ->
            PhoneNumberUtil.PhoneNumberType.values().mapNotNull { util.getExampleNumberForType(region, it) }.flatMap { ex ->
                val e164 = PhoneIdentity.canonicalE164(util.format(ex, PhoneNumberFormat.E164))
                listOf(PhoneNumberFormat.NATIONAL, PhoneNumberFormat.INTERNATIONAL, PhoneNumberFormat.E164).map { f ->
                    Triple(region, util.format(ex, f), e164)
                }
            }
        }
    }

    @Test fun every_example_number_reads_as_its_own_line() {
        val wrong = examples.filter { (region, written, e164) ->
            // Canada's "310-1234" service numbers are only dialable locally: no international form, as before.
            val expected = if (region == "CA" && e164 == "+13101234" && !written.startsWith("+")) null else e164
            PhoneIdentity.e164(written, region) != expected
        }
        assertTrue("${examples.size} examples; wrong: ${wrong.take(20)}", wrong.isEmpty())
        assertTrue(examples.size > 3000)
    }

    @Test fun international_forms_never_changed() {
        // Written with + (or as E.164), the old heuristic and libphonenumber agree, except that a complete number
        // shorter than the old 8-character floor (Iran's 4-digit "+98 9601" service lines) now has a form.
        val changed = examples.filter { (region, written, _) ->
            val old = PhoneNumbers.heuristicE164(written, region)
            written.startsWith("+") && old != PhoneIdentity.e164(written, region) && !(old == null && written.filter { it.isDigit() }.length < 8)
        }
        assertTrue(changed.toString(), changed.isEmpty())
    }

    @Test fun national_forms_changed_only_where_the_old_table_was_wrong_or_silent() {
        val unexpected = examples.filter { (region, written, _) ->
            val old = PhoneNumbers.heuristicE164(written, region)
            val new = PhoneIdentity.e164(written, region)
            // Silent: a region missing from the old table (Åland, Saint Martin, the Pacific islands…) now gets a key.
            old != new && old != null && region !in TRUNK_FIXED
        }
        assertTrue(unexpected.toString(), unexpected.isEmpty())
    }

    @Test fun hand_written_corpus() {
        val corpus = listOf(
            // National, international and dialling-prefix forms of one French mobile.
            Case("FR", "06 12 34 56 78", "+33612345678"),
            Case("FR", "+33 6 12 34 56 78", "+33612345678"),
            Case("FR", "0033 6 12 34 56 78", "+33612345678"),
            Case("FR", "33612345678", "+33612345678"),
            Case("FR", "612345678", "+33612345678"),
            Case("FR", "٠٦١٢٣٤٥٦٧٨", "+33612345678"),
            // Trunk prefixes: 0, 8 (Russia), 06 (Hungary), 1 (NANP), and dialling prefixes 011, 0011, 010, 810.
            Case("RU", "8 912 345 67 89", "+79123456789"),
            Case("RU", "810 33 6 12 34 56 78", "+33612345678"),
            Case("HU", "06 30 123 4567", "+36301234567"),
            Case("US", "1 415 555 0123", "+14155550123"),
            Case("US", "(415) 555-0123", "+14155550123"),
            Case("US", "011 44 20 7946 0958", "+442079460958"),
            Case("AU", "0011 33 6 12 34 56 78", "+33612345678"),
            Case("JP", "010 33 612345678", "+33612345678"),
            Case("GB", "07700 900123", "+447700900123"),
            Case("DE", "0151 23456789", "+4915123456789"),
            // Italy keeps the leading zero after +39.
            Case("IT", "06 1234 5678", "+390612345678"),
            Case("IT", "0039 06 1234 5678", "+390612345678"),
            Case("IT", "333 123 4567", "+393331234567"),
            // Argentina: a mobile written nationally ("15" after the area code) is +54 9 internationally.
            Case("AR", "011 15 2345 6789", "+5491123456789", old = "+54111523456789"),
            Case("AR", "0351 15 123 4567", "+5493511234567", old = "+54351151234567"),
            Case("AR", "+54 9 11 2345 6789", "+5491123456789"),
            Case("AR", "11 2345 6789", "+541123456789"),
            // Brazil: a carrier code (21) between the trunk 0 and the area code.
            Case("BR", "0 21 11 91234 5678", "+5511912345678", old = "+552111912345678"),
            Case("BR", "(11) 91234-5678", "+5511912345678"),
            // Mexico's legacy forms fold into one line.
            Case("MX", "044 55 1234 5678", "+525512345678"),
            Case("MX", "01 55 1234 5678", "+525512345678"),
            Case("MX", "+52 1 55 1234 5678", "+525512345678"),
            // Countries the old table didn't know, or where it kept a trunk 0 that isn't part of the number.
            Case("AX", "041 2345678", "+358412345678", old = null),
            Case("SK", "0912 123 456", "+421912123456", old = "+4210912123456"),
            Case("CI", "01 23 45 6789", "+2250123456789", old = "+225123456789"),
            // Short codes, emergency numbers and service codes have no international form.
            Case("FR", "3631", null),
            Case("FR", "112", null),
            Case("US", "911", null),
            Case("GB", "999", null),
            Case("DE", "110", null),
            Case("FR", "116000", null),
            Case("FR", "*100#", null),
            Case("FR", "*#06#", null),
            Case("US", "#31#", null),
            // A US number without its area code is only "possible locally": no made-up international form.
            Case("US", "555-0123", null),
            // Sender names are not numbers (their letters used to dial as digits); vanity numbers still are.
            Case("FR", "VODAFONE", null, old = "+3386323663"),
            Case("FR", "AMAZONPAY", null, old = "+33262966729"),
            Case("US", "BANK", null),
            Case("US", "1-800-FLOWERS", "+18003569377"),
            // No country known: only numbers that carry their own country convert.
            Case(null, "0612345678", null),
            Case(null, "+33612345678", "+33612345678"),
            Case(null, "0033612345678", "+33612345678"),
            Case(null, "1234567", null),
            Case("", "06 12 34 56 78", null),
            Case("AQ", "0612345678", null),
            Case("FR", "", null),
            Case("FR", null, null),
        )
        for (c in corpus) {
            assertEquals("new ${c.region} ${c.raw}", c.e164, PhoneIdentity.e164(c.raw, c.region))
            assertEquals("old ${c.region} ${c.raw}", c.old, PhoneNumbers.heuristicE164(c.raw, c.region))
        }
    }

    @Test fun same_line_decisions_match_the_old_ones_except_the_documented_cases() {
        val pairs = listOf(
            Pair("FR", "06 12 34 56 78" to "+33 6 12 34 56 78") to true,
            Pair("FR", "0033612345678" to "06.12.34.56.78") to true,
            Pair("FR", "+33612345678" to "+34612345678") to false,
            Pair("FR", "3631" to "3631") to true,
            Pair("FR", "3631" to "03631") to false,
            Pair("FR", "112" to "+33112") to false,
            Pair("IT", "06 1234 5678" to "+39 06 1234 5678") to true,
            Pair("IT", "06 1234 5678" to "+39 6 1234 5678") to false,
            Pair("US", "(415) 555-0123" to "+1 415 555 0123") to true,
            Pair("US", "555-0123" to "+1 415 555 0123") to false,
            Pair("GB", "07700 900123" to "+447700900123") to true,
            Pair("MX", "044 55 1234 5678" to "+52 1 55 1234 5678") to true,
            Pair(null, "0612345678" to "+33612345678") to true,
            Pair(null, "0612345678" to "+34612345678") to true,
            Pair("AR", "+54 9 11 2345 6789" to "11 2345 6789") to false,
            Pair("FR", "*100#" to "*100#") to true,
        )
        for ((p, expected) in pairs) {
            val (region, numbers) = p
            val (a, b) = numbers
            assertEquals("new $region $a ~ $b", expected, PhoneIdentity.same(a, b, region))
            assertEquals("old $region $a ~ $b", expected, oldSame(a, b, region))
        }
        // The intentional change: an Argentine mobile saved nationally now matches the call that shows +54 9.
        assertTrue(PhoneIdentity.same("011 15 2345 6789", "+54 9 11 2345 6789", "AR"))
        assertFalse(oldSame("011 15 2345 6789", "+54 9 11 2345 6789", "AR"))
        // And a sender name is never a line (its letters used to make up a French number that matched itself).
        assertFalse(PhoneIdentity.same("VODAFONE", "VODAFONE", "FR"))
        assertTrue(oldSame("VODAFONE", "VODAFONE", "FR"))
    }

    @Test fun rows_stored_under_the_old_key_stay_readable() {
        val raw = "011 15 2345 6789"
        val oldKey = PhoneNumbers.heuristicE164(raw, "AR")!!
        assertEquals("+5491123456789", PhoneIdentity.key(raw, "AR"))
        assertEquals(listOf("+5491123456789", oldKey, "523456789"), PhoneIdentity.lookupKeys(raw, "AR"))
        assertTrue(raw in PhoneIdentity.KeySet(listOf(oldKey), "AR"))
        assertTrue(PhoneIdentity.matchesStored(oldKey, raw, "AR"))
        // Numbers whose key didn't change don't grow an extra lookup.
        assertEquals(listOf("+33612345678", "612345678"), PhoneIdentity.lookupKeys("06 12 34 56 78", "FR"))
        // Private contacts sealed under the old form are still found by caller ID, exact or not.
        assertTrue(VaultNumberKeys.E164_PREFIX + oldKey in VaultNumberKeys.lookup(raw, "AR", exact = true))
        assertTrue(VaultNumberKeys.E164_PREFIX + oldKey in VaultNumberKeys.lookup(raw, "AR"))
    }

    @Test fun repeated_questions_give_the_same_answers() {
        // The cache must never mix regions or numbers.
        repeat(3) {
            assertEquals("+33612345678", PhoneIdentity.e164("0612345678", "FR"))
            assertEquals("+34612345678", PhoneIdentity.e164("612345678", "ES"))
            assertNull(PhoneIdentity.e164("0612345678", null))
            assertNull(PhoneIdentity.e164("3631", "FR"))
        }
    }

    private data class Case(val region: String?, val raw: String?, val e164: String?, val old: String? = e164)

    /** [PhoneNumbers.same] as it was, on the heuristic alone. */
    private fun oldSame(a: String?, b: String?, region: String?): Boolean {
        val ea = PhoneNumbers.heuristicE164(a, region)
        val eb = PhoneNumbers.heuristicE164(b, region)
        if (ea != null && eb != null) return ea == eb
        val da = PhoneNumbers.digits(a)
        val db = PhoneNumbers.digits(b)
        if (da.isEmpty() || db.isEmpty()) return false
        if (da.length < 7 || db.length < 7) return da == db
        return PhoneNumbers.matchKey(da) == PhoneNumbers.matchKey(db)
    }

    private companion object {
        /** Regions where the old table kept or dropped a trunk 0 wrongly, or missed a mobile or carrier prefix. */
        val TRUNK_FIXED = setOf("AR", "BJ", "BR", "BW", "BY", "CG", "CI", "FJ", "GA", "MC", "NC", "NE", "SK", "SZ", "TO", "UY")
    }
}
