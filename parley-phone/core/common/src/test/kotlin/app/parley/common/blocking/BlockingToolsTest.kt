package app.parley.common.blocking

import app.parley.common.BlockRule
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.IncomingCallFacts
import app.parley.common.LineType
import app.parley.common.PolicyClock
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.ScreeningResult
import app.parley.common.ScreeningSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BlockingToolsTest {

    // ---------- Import ----------

    @Test fun csv_quotes_crlf_and_separators() {
        val rows = Csv.parse("name;number\r\n\"Acme; Inc\";\"+1 855 \"\"x\"\"\"\r\n\r\nBob;0800*\n")
        assertEquals(listOf(listOf("name", "number"), listOf("Acme; Inc", "+1 855 \"x\""), listOf("Bob", "0800*")), rows)
    }

    @Test fun generic_csv_mapping_is_guessed() {
        val rows = Csv.parse("Label,Phone,List\nTelemarketer,+33 1 62 00 00 00,block\nPlumber,06 12 34 56 78,allow\nRange,0800*,block\n")
        val m = ListImport.guessMapping(rows)
        assertTrue(m.hasHeader)
        assertEquals(1, m.number)
        assertEquals(0, m.note)
        assertEquals(2, m.kind)
        val r = ListImport.rows(rows, m)
        assertEquals(ImportedRule("+33162000000", RuleType.EXACT, RuleKind.BLOCK, "Telemarketer"), r[0])
        assertEquals(RuleKind.ALLOW, r[1].kind)
        assertEquals(ImportedRule("0800", RuleType.PREFIX, RuleKind.BLOCK, "Range"), r[2])
    }

    @Test fun csv_without_header() {
        val rows = Csv.parse("+18555550100\n+18555550101\n")
        val m = ListImport.guessMapping(rows)
        assertFalse(m.hasHeader)
        assertEquals(2, ListImport.rows(rows, m).size)
    }

    @Test fun yacb_hash_means_one_digit() {
        val rows = Csv.parse("name,pattern\nBank scam,+4930####\n")
        val m = ListImport.guessMapping(rows, ImportPreset.YACB)
        assertEquals(ImportedRule("+4930????", RuleType.WILDCARD, RuleKind.BLOCK, "Bank scam"), ListImport.rows(rows, m).single())
    }

    @Test fun nophonespam_lines() {
        val r = ListImport.plainLines("# my list\n+49301234*\n0900 123 456, premium\n\n")
        assertEquals(listOf(ImportedRule("+49301234", RuleType.PREFIX), ImportedRule("0900123456", RuleType.EXACT, RuleKind.BLOCK, "premium")), r)
    }

    @Test fun call_blocker_json_is_read_leniently() {
        val json = """
            {"version":3,"blockedNumbers":[{"phoneNumber":"+52 1 55 1234 5678","name":"Telcel promo","enabled":true},
              {"phoneNumber":"444","isPrefix":true},{"phoneNumber":"+525500000000","enabled":false}],
             "whitelist":[{"number":"+525511112222","label":"School"}],
             "settings":{"blockHidden":true}}
        """.trimIndent()
        val r = ListImport.callBlockerJson(json)
        assertEquals(3, r.size)
        assertEquals(ImportedRule("+5215512345678", RuleType.EXACT, RuleKind.BLOCK, "Telcel promo"), r[0])
        assertEquals(ImportedRule("444", RuleType.PREFIX, RuleKind.BLOCK, null), r[1])
        assertEquals(ImportedRule("+525511112222", RuleType.EXACT, RuleKind.ALLOW, "School"), r[2])
    }

    @Test fun cbbk_round_trip_and_wrong_password() {
        val plain = """{"blockedNumbers":[{"phoneNumber":"+18555550100"}]}"""
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("hunter2".toCharArray(), salt, 100_000, 256)).encoded
        val ct = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }.doFinal(plain.encodeToByteArray())
        val file = "CBBK".encodeToByteArray() + salt + iv + ct
        assertEquals(plain, ListImport.decryptCbbk(file, "hunter2".toCharArray()))
        val bar = byteArrayOf('|'.code.toByte())
        val separated = "CBBK".encodeToByteArray() + bar + salt + bar + iv + bar + ct
        assertEquals(plain, ListImport.decryptCbbk(separated, "hunter2".toCharArray()))
        try {
            ListImport.decryptCbbk(file, "wrong".toCharArray())
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("password"))
        }
    }

    // ---------- Dry run ----------

    private class Recorder : ScreeningEffects {
        val seen = ArrayList<ScreeningResult>()
        override fun onScreened(facts: IncomingCallFacts, result: ScreeningResult) {
            seen += result
        }
    }

    @Test fun dry_run_has_no_side_effects() {
        val effects = Recorder()
        val pipeline = ScreeningPipeline(effects)
        val rule = BlockRule(id = 1, pattern = "+33162", type = RuleType.PREFIX, hitCount = 4)
        val rules = listOf(rule)
        val settings = ScreeningSettings(blockNonContacts = false, snoozeUntil = Long.MAX_VALUE)
        val calls = listOf(
            ReplayCall("+33162000000", 1_000, CallType.MISSED, false),
            ReplayCall("+33612345678", 2_000, CallType.INCOMING, false),
            ReplayCall("+33700000000", 3_000, CallType.INCOMING, false, isContact = true),
        )
        val report = pipeline.dryRun(calls, rules, settings, ZoneOffset.UTC) { c -> IncomingCallFacts(c.number, c.hidden, c.isContact, countryIso = "FR") }
        assertTrue(effects.seen.isEmpty())
        assertEquals(4, rules.single().hitCount)
        // The running snooze doesn't hide what the rules would do.
        assertEquals(1, report.blocked)
        assertEquals("Would have blocked 1 of 2 calls from unknown numbers", report.summary())
        // A live screen does reach the effects.
        pipeline.screen(IncomingCallFacts("+33162000000", false, false, countryIso = "FR"), rules, ScreeningSettings(), PolicyClock.of(0, ZoneOffset.UTC))
        assertEquals(1, effects.seen.size)
    }

    @Test fun dry_run_shows_what_a_candidate_adds() {
        val pipeline = ScreeningPipeline(Recorder())
        val calls = listOf(ReplayCall("+33162000000", 1_000, CallType.MISSED, false), ReplayCall("+33999000000", 2_000, CallType.MISSED, false))
        val facts = { c: ReplayCall -> IncomingCallFacts(c.number, c.hidden, c.isContact, countryIso = "FR") }
        val base = pipeline.dryRun(calls, emptyList(), ScreeningSettings(), ZoneOffset.UTC, facts)
        val with = pipeline.dryRun(calls, listOf(BlockRule(pattern = "+33162", type = RuleType.PREFIX)), ScreeningSettings(), ZoneOffset.UTC, facts)
        assertEquals(listOf("+33162000000"), with.newlyBlocked(base).map { it.call.number })
    }

    // ---------- Wangiri & reputation ----------

    @Test fun wangiri() {
        assertTrue(WangiriGuard.isSuspect(CallType.MISSED, 3_000, LineType.MOBILE, "TN", "FR"))
        assertFalse(WangiriGuard.isSuspect(CallType.MISSED, 20_000, LineType.MOBILE, "TN", "FR"))
        assertFalse(WangiriGuard.isSuspect(CallType.MISSED, 3_000, LineType.MOBILE, "FR", "FR"))
        assertTrue(WangiriGuard.isSuspect(CallType.MISSED, null, LineType.PREMIUM_RATE, "FR", "FR"))
        assertFalse(WangiriGuard.isSuspect(CallType.INCOMING, 3_000, LineType.PREMIUM_RATE, "TN", "FR"))
    }

    private fun call(n: String, type: CallType, t: Long, d: Long = 0) = CallEntry(t, n, null, type, t, d, null, false, false)

    @Test fun reputation_with_regret_window() {
        val h = 3_600_000L
        val now = 100 * h
        val calls = listOf(
            call("+33700000001", CallType.REJECTED, now - 5 * h),
            call("+33700000001", CallType.REJECTED, now - 4 * h),
            call("+33700000002", CallType.REJECTED, now - 10 * 60_000), // inside the regret window
            call("+33700000002", CallType.REJECTED, now - 20 * 60_000),
            call("+33700000003", CallType.REJECTED, now - 6 * h),
            call("+33700000003", CallType.INCOMING, now - 5 * h, 2),
            call("+33700000003", CallType.OUTGOING, now - 2 * h), // you called back
            call("+33700000004", CallType.INCOMING, now - 8 * h, 1),
            call("+33700000004", CallType.INCOMING, now - 7 * h, 2),
        ).sortedByDescending { it.date }
        val s = PersonalReputation.suggestions(calls, now) { false }
        assertEquals(listOf("+33700000001", "+33700000004"), s.map { it.number })
        assertEquals("You declined 2 calls", s.first().reason())
        assertTrue(PersonalReputation.suggestions(calls, now) { it == "+33700000001" }.none { it.number == "+33700000001" })
    }
}
