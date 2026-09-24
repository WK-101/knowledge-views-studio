package app.parley.common.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CsvPackConverterTest {
    private val ftc = """
        Company_Phone_Number,Created_Date,Violation_Date,Consumer_City,Consumer_State,Consumer_Area_Code,Subject,Recorded_Message_Or_Robocall
        2025550101,2026-09-22 00:28:21,2026-09-21 19:12:00,Ballwin,Illinois,309,Other,
        2025550101,2026-09-22 01:06:01,2026-09-21 22:00:00,Fremont,California,323,Other,Y
        2025550101,2026-09-22 02:00:00,2026-09-21 22:00:00,Austin,Texas,512,Dropped call or no message,Y
        8885550199,2026-09-22 03:00:00,2026-09-21 22:00:00,Austin,Texas,512,Imposters,N
        8885550199,2026-09-22 03:10:00,2026-09-21 22:00:00,Austin,Texas,512,"Reducing your debt (credit cards, mortgage, student loans)",N
        3125550142,2026-09-22 04:00:00,2026-09-21 22:00:00,Chicago,Illinois,312,Warranties & protection plans,
        not-a-number,2026-09-22 04:00:00,2026-09-21 22:00:00,Chicago,Illinois,312,Other,
        ,2026-09-22 04:00:00,2026-09-21 22:00:00,Chicago,Illinois,312,Other,
    """.trimIndent()

    private fun key(e164: String) = ListPack.key(e164)!!

    @Test fun tallies_reports_and_votes_per_number() {
        val t = CsvPackConverter.tally(ftc, FtcDncSource.SPEC)
        assertEquals(3, t.size)
        assertEquals(3, t.reports(key("+12025550101")))
        assertEquals(2, t.votes(key("+12025550101"), FtcCsv.CAT_ROBOCALL))
        assertEquals(2, t.reports(key("+18885550199")))
        assertEquals(1, t.votes(key("+18885550199"), FtcCsv.CAT_SCAM))
        assertEquals(1, t.reports(key("+13125550142")))
    }

    @Test fun converts_to_a_valid_pack_with_scores_from_report_counts_and_mapped_categories() {
        val bytes = CsvPackConverter.convert(ftc, FtcDncSource.SPEC, FtcDncSource.manifest(30, 7, 1_000L), now = 1_000L)
        val p = ListPack.parse(bytes)
        assertEquals(FtcDncSource.PACK_ID, p.manifest.id)
        assertEquals(3, p.manifest.entries)
        assertEquals(FtcCsv.categories, p.manifest.categories)
        val idx = PackIndex.of(p)
        // 3 reports, 2 of them robocalls → robocall, score 65.
        assertEquals(PackMatch(FtcCsv.CAT_ROBOCALL, 65, false), idx.find(key("+12025550101")))
        // 2 reports, 1 impostor → half the reports vote scam → scam, score 55.
        assertEquals(PackMatch(FtcCsv.CAT_SCAM, 55, false), idx.find(key("+18885550199")))
        assertEquals(PackMatch(FtcCsv.CAT_TELEMARKETING, 40, false), idx.find(key("+13125550142")))
        assertNull(idx.find(key("+13125550143")))
    }

    @Test fun matches_the_command_line_ftc_conversion() {
        // (The tool reads letters as a vanity number; the converter skips them, so compare on clean data.)
        val clean = ftc.lines().filterNot { "not-a-number" in it }.joinToString("\n")
        val viaTool = PackBuilder(PackManifest(id = "a", name = "a")).also { FtcCsv.addTo(it, clean) }
        val viaConverter = PackBuilder(PackManifest(id = "a", name = "a")).also { CsvPackConverter.tally(clean, FtcDncSource.SPEC).addTo(it, FtcDncSource.SPEC) }
        assertTrue(viaTool.numbersBytes().contentEquals(viaConverter.numbersBytes()))
    }

    @Test fun daily_tallies_survive_encoding_and_merge_into_a_window() {
        val day1 = CsvPackConverter.tally(ftc, FtcDncSource.SPEC)
        val day2 = ReportTally.decode(day1.encode())
        assertEquals(day1.encode(), day2.encode())
        val window = ReportTally().merge(day1).merge(day2)
        assertEquals(6, window.reports(key("+12025550101")))
        assertEquals(4, window.votes(key("+12025550101"), FtcCsv.CAT_ROBOCALL))
        val p = ListPack.parse(CsvPackConverter.build(window, FtcDncSource.SPEC, FtcDncSource.manifest(30, 1, 1L), now = 1L))
        assertEquals(85, PackIndex.of(p).find(key("+12025550101"))!!.score)
    }

    @Test fun generic_spec_uses_count_and_category_columns() {
        val csv = "number;reports;type\n+33612345678;7;arnaque\n0612345679;1;démarchage\n"
        val spec = CsvSpec(
            numberColumns = listOf("number"), countryIso = "FR", countColumn = "reports", categoryColumn = "type",
            categoryKeywords = listOf("arnaque" to 3, "démarchage" to 1), categoryPriority = listOf(3), categories = mapOf("1" to "Telemarketing", "3" to "Scam"),
        )
        val p = ListPack.parse(CsvPackConverter.convert(csv, spec, PackManifest(id = "fr.community", name = "FR"), now = 1L))
        val idx = PackIndex.of(p)
        assertEquals(PackMatch(3, 85, false), idx.find(key("+33612345678")))
        assertEquals(PackMatch(1, 40, false), idx.find(key("+33612345679")))
    }

    @Test fun signed_conversion_verifies() {
        val sk = Ed25519.newSecret()
        val p = ListPack.parse(CsvPackConverter.convert(ftc, FtcDncSource.SPEC, FtcDncSource.manifest(7, 1, 1L), sk, 1L))
        assertEquals(SignatureStatus.SIGNED, p.signature)
        assertNotNull(p.fingerprint)
    }

    @Test fun recognises_ftc_files_and_urls() {
        assertTrue(FtcDncSource.looksValid(ftc))
        assertFalse(FtcDncSource.looksValid("<!DOCTYPE html><html>"))
        assertEquals("https://www.ftc.gov/sites/default/files/DNC_Complaint_Numbers_2026-09-23.csv", FtcDncSource.dailyUrl(LocalDate.of(2026, 9, 23)))
    }

    @Test fun score_curve() {
        assertEquals(40, ReportScore.of(1))
        assertEquals(55, ReportScore.of(2))
        assertEquals(75, ReportScore.of(4))
        assertEquals(85, ReportScore.of(500))
    }
}
