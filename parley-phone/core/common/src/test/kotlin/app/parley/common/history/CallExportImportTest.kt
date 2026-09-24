package app.parley.common.history

import app.parley.common.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallExportImportTest {
    private val row = ExportRow(
        date = at(2026, 3, 4, 14, 5), durationSec = 125, type = CallType.OUTGOING,
        number = "+33612345678", name = "Doe, \"Jane\"", simLabel = "Work SIM", notes = listOf("line 1", "=HYPERLINK(\"x\")"),
    )

    // ------------------------------------------------------------------ CSV

    @Test fun csv_uses_crlf_and_header() {
        val csv = CallExport.csv(listOf(row), UTC)
        assertTrue(csv.startsWith("Date,Time,Type,Name,Number,Duration (s),Duration,SIM,Notes,Timestamp (ms)\r\n"))
        assertTrue(csv.endsWith("\r\n"))
        // Only the embedded newline inside the quoted notes field is a bare LF.
        assertEquals(2, Regex("\r\n").findAll(csv).count())
    }

    @Test fun csv_escapes_every_field() {
        assertEquals("plain", CallExport.csvField("plain"))
        assertEquals("\"a,b\"", CallExport.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CallExport.csvField("say \"hi\""))
        assertEquals("\"two\nlines\"", CallExport.csvField("two\nlines"))
        assertEquals("\"cr\rhere\"", CallExport.csvField("cr\rhere"))
        val line = CallExport.csv(listOf(row), UTC).split("\r\n")[1]
        assertTrue(line.contains("\"Doe, \"\"Jane\"\"\""))
        assertTrue(line.contains("Work SIM"))
        assertTrue(line.contains("0:02:05"))
    }

    @Test fun csv_neutralises_formulas() {
        for (p in listOf("=", "+", "-", "@", "\t")) assertEquals("'${p}1+1", CallExport.neutralise("${p}1+1"))
        assertEquals("'+33612345678", CallExport.csvField("+33612345678"))
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", CallExport.csvField("=HYPERLINK(\"x\")"))
        assertEquals("safe=1", CallExport.neutralise("safe=1"))
        // Notes that start with a formula character inside a multi-line cell: the cell as a whole is neutral
        // because it starts with "line 1"; the second line isn't a separate cell.
        val csv = CallExport.csv(listOf(row.copy(notes = listOf("@SUM(A1)"))), UTC)
        assertTrue(csv.contains(",'@SUM(A1),"))
    }

    @Test fun csv_optional_bom() {
        assertTrue(CallExport.csv(emptyList(), UTC, bom = true).startsWith("﻿Date"))
        assertFalse(CallExport.csv(emptyList(), UTC, bom = false).startsWith("﻿"))
    }

    @Test fun csv_roundtrips_through_import() {
        val csv = CallExport.csv(listOf(row, row.copy(date = row.date + 1000, type = CallType.MISSED, durationSec = 0, name = null)), UTC, bom = true)
        val plan = CallCsvImport.plan(csv, emptySet(), UTC)
        assertEquals(ImportSource.PARLEY, plan.source)
        assertEquals(2, plan.toInsert.size)
        val first = plan.toInsert[0]
        assertEquals("+33612345678", first.number)
        assertEquals("Doe, \"Jane\"", first.name)
        assertEquals(125, first.durationSec)
        assertEquals(row.date, first.date)
        assertEquals(ProviderColumns.OUTGOING, first.type)
        assertEquals(ProviderColumns.MISSED, plan.toInsert[1].type)
    }

    // ------------------------------------------------------------------ JSON

    @Test fun json_export() {
        val j = CallExport.json(listOf(row), UTC)
        assertTrue(j.contains("\"type\": \"outgoing\""))
        assertTrue(j.contains("\"name\": \"Doe, \\\"Jane\\\"\""))
        assertTrue(j.contains("\"durationSec\": 125"))
    }

    // ------------------------------------------------------------------ ICS

    @Test fun ics_escaping() {
        assertEquals("a\\,b\\;c\\\\d\\ne", CallExport.icsText("a,b;c\\d\ne"))
        assertEquals("x\\ny", CallExport.icsText("x\r\ny"))
        val ics = CallExport.ics(listOf(row), now = at(2026, 3, 5))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
        assertTrue(ics.contains("SUMMARY:Call to Doe\\, \"Jane\"\r\n"))
        assertTrue(ics.contains("DTSTART:20260304T140500Z\r\n"))
        assertTrue(ics.contains("DURATION:PT125S\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertEquals(ics.split("BEGIN:VEVENT").size - 1, 1)
        // No bare LF anywhere: every line ends in CRLF.
        assertFalse(Regex("[^\r]\n").containsMatchIn(ics))
    }

    @Test fun ics_missed_call_has_no_duration() {
        val ics = CallExport.ics(listOf(row.copy(type = CallType.MISSED, durationSec = 0)), now = 0)
        assertFalse(ics.contains("DURATION"))
        assertTrue(ics.contains("SUMMARY:Missed call from"))
    }

    @Test fun ics_folds_at_75_octets_without_splitting_characters() {
        val long = "DESCRIPTION:" + "é".repeat(100) + "😀".repeat(30)
        val folded = CallExport.fold(long)
        val lines = folded.split("\r\n")
        assertTrue(lines.size > 1)
        lines.forEach { assertTrue("line of ${it.toByteArray().size} octets", it.toByteArray(Charsets.UTF_8).size <= 75) }
        lines.drop(1).forEach { assertTrue(it.startsWith(" ")) }
        // Unfolding gives back the original, so no character was split.
        assertEquals(long, folded.replace("\r\n ", ""))
        assertEquals("SHORT:x", CallExport.fold("SHORT:x"))
        val ics = CallExport.ics(listOf(row.copy(notes = listOf("n".repeat(300)))), now = 0)
        ics.split("\r\n").forEach { assertTrue(it.toByteArray().size <= 75) }
    }

    // ------------------------------------------------------------------ file names

    @Test fun file_names_are_sanitised() {
        val n = CallExport.sanitiseFileName("Anna/B: <x>|y?*\"z\\ \u0001")
        assertFalse(n.contains('/')); assertFalse(n.contains(':')); assertFalse(n.contains('\\'))
        assertFalse(n.any { it in "<>|?*\"" || it.code < 32 })
        assertEquals("calls", CallExport.sanitiseFileName("..//.."))
        assertTrue(CallExport.sanitiseFileName("a".repeat(300)).length <= 80)
        val file = CallExport.fileName("AC/DC: live", at(2026, 3, 4, 14, 5), UTC, ExportFormat.ICS)
        assertEquals("Parley calls – AC-DC- live – 2026-03-04 14-05.ics", file)
        assertFalse(file.contains(':'))
    }

    @Test fun notes_are_attached_to_their_call() {
        val c = call("06 12 34 56 78", CallType.OUTGOING, at(2026, 1, 1, 10), 120)
        val rows = CallExport.rows(
            listOf(c), names = { null }, simLabel = { null },
            notes = listOf(
                ExportNote(app.parley.common.PhoneNumbers.matchKey("+33612345678"), c.date + 5_000, "Invoice"),
                ExportNote(app.parley.common.PhoneNumbers.matchKey("+33612345678"), c.date + 86_400_000, "Other day"),
            ),
        )
        assertEquals(listOf("Invoice"), rows.single().notes)
        assertEquals(null, rows.single().name)
    }

    // ------------------------------------------------------------------ import

    private val loggerCsv =
        "name,duration,number,phone_account_id,call_type,formatted_number,sim_display_name,timestamp,cached_number_label,cached_number_type,cached_matched_number\n" +
            "\"Doe, Jane\",65,+33612345678,acc1,CallType.incoming,06 12 34 56 78,SIM 1,1772632800000,,2,\n" +
            ",0,+33699999999,acc1,CallType.missed,,SIM 1,1772636400000,null,null,null\n" +
            ",12,+33699999999,acc2,CallType.wifiOutgoing,,SIM 2,1772640000000,,,\n" +
            ",12,+33699999999,acc2,CallType.bogus,,SIM 2,1772640000000,,,\n"

    @Test fun imports_logger_format_with_new_zero() {
        val plan = CallCsvImport.plan(loggerCsv, emptySet(), UTC)
        assertEquals(ImportSource.LOGGER, plan.source)
        assertEquals(3, plan.toInsert.size)
        assertEquals(1, plan.problems.size)
        val first = plan.toInsert[0]
        assertEquals("Doe, Jane", first.name)
        assertEquals(65, first.durationSec)
        assertEquals(1772632800000, first.date)
        assertEquals(ProviderColumns.INCOMING, first.type)
        assertEquals(ProviderColumns.OUTGOING, plan.toInsert[2].type)
        plan.toInsert.forEach {
            val v = it.providerValues()
            assertEquals(0, v[ProviderColumns.NEW])
            assertEquals(1, v[ProviderColumns.IS_READ])
        }
    }

    @Test fun import_dedupes_against_history_and_itself() {
        val first = CallCsvImport.plan(loggerCsv, emptySet(), UTC)
        val existing = setOf(first.toInsert[0].dedupeKey)
        val again = CallCsvImport.plan(loggerCsv + loggerCsv.lines().drop(1).first() + "\n", existing, UTC)
        assertEquals(2, again.toInsert.size)
        assertEquals(2, again.duplicates) // one already in history, one repeated in the file
        // The same call with the number written nationally and a few ms off is still a duplicate.
        val key = NumberKeys.dedupe("06 12 34 56 78", 1772632800450) + "|" + ProviderColumns.INCOMING
        assertEquals(first.toInsert[0].dedupeKey, key)
    }

    @Test fun import_accepts_crlf_and_semicolons_and_generic_columns() {
        val csv = "Phone;Direction;Start;Seconds;Contact\r\n+4915112345678;Outgoing;2026-03-04 14:05:00;1:05;Max\r\n\"+4915112345679\";missed;04/03/2026 15:00;0;\r\n"
        val plan = CallCsvImport.plan(csv, emptySet(), UTC)
        assertEquals(ImportSource.GENERIC, plan.source)
        assertEquals(2, plan.toInsert.size)
        assertEquals(65, plan.toInsert[0].durationSec)
        assertEquals(at(2026, 3, 4, 14, 5), plan.toInsert[0].date)
        assertEquals(at(2026, 3, 4, 15, 0), plan.toInsert[1].date)
        assertEquals("Max", plan.toInsert[0].name)
        assertEquals(null, plan.toInsert[1].name)
    }

    @Test fun import_needs_mapping_when_columns_unknown() {
        val plan = CallCsvImport.plan("a,b,c\n1,2,3\n", emptySet(), UTC)
        assertEquals(0, plan.toInsert.size)
        assertFalse(plan.mapping.isUsable)
        val mapped = CallCsvImport.plan("a,b,c\n0612345678,outgoing,1772632800\n", emptySet(), UTC, ColumnMapping(number = 0, type = 1, timestamp = 2))
        assertEquals(1772632800000, mapped.toInsert.single().date)
    }

    @Test fun csv_parser_handles_quotes_and_line_breaks() {
        val rows = Csv.parse("a,\"b\"\"c\",\"d\r\ne\"\r\nx,,z\ny\r")
        assertEquals(listOf(listOf("a", "b\"c", "d\r\ne"), listOf("x", "", "z"), listOf("y")), rows)
    }

    @Test fun durations() {
        assertEquals(75L, CallCsvImport.parseDuration("75"))
        assertEquals(75L, CallCsvImport.parseDuration("1:15"))
        assertEquals(3675L, CallCsvImport.parseDuration("1:01:15"))
        assertEquals(3723L, CallCsvImport.parseDuration("1h 2m 3s"))
        assertEquals(45L, CallCsvImport.parseDuration("45s"))
        assertEquals(null, CallCsvImport.parseDuration("soon"))
    }

    // ------------------------------------------------------------------ plan meter

    @Test fun billing_rounding_is_calculated_never_written_back() {
        assertEquals(60, PlanMeter.billedSeconds(1, BillingIncrement.PER_MINUTE))
        assertEquals(60, PlanMeter.billedSeconds(60, BillingIncrement.PER_MINUTE))
        assertEquals(120, PlanMeter.billedSeconds(61, BillingIncrement.PER_MINUTE))
        assertEquals(30, PlanMeter.billedSeconds(1, BillingIncrement.HALF_MINUTE))
        assertEquals(61, PlanMeter.billedSeconds(61, BillingIncrement.PER_SECOND))
        assertEquals(0, PlanMeter.billedSeconds(0, BillingIncrement.PER_MINUTE))
        val calls = listOf(
            call("+33612345678", CallType.OUTGOING, at(2026, 3, 10), 61, sim = "s1"),
            call("+33612345678", CallType.OUTGOING, at(2026, 3, 11), 5, sim = "s1"),
        )
        val before = calls.map { it.copy() }
        val u = PlanMeter.usage(PlanConfig("s1", allowanceMinutes = 10), calls, { NumberCategory.MOBILE }, at(2026, 3, 20), UTC)
        assertEquals(180, u.billedSec)
        assertEquals(66, u.talkSec)
        assertEquals(before, calls)
        // Exports after metering still show the real durations.
        val csv = CallExport.csv(CallExport.rows(calls, { null }, { null }, emptyList()), UTC)
        assertTrue(csv.contains(",61,0:01:01,"))
        assertTrue(csv.contains(",5,0:00:05,"))
    }

    @Test fun plan_counts_only_counted_categories_sim_and_cycle() {
        val cfg = PlanConfig("s1", allowanceMinutes = 300, cycleStartDay = 15, increment = BillingIncrement.PER_MINUTE)
        val cat = { n: String -> if (n.startsWith("+800")) NumberCategory.TOLL_FREE else if (n.startsWith("+1")) NumberCategory.INTERNATIONAL else NumberCategory.MOBILE }
        val calls = listOf(
            call("+33612345678", CallType.OUTGOING, at(2026, 3, 16), 600, sim = "s1"), // counted: 10 min
            call("+33612345678", CallType.OUTGOING, at(2026, 3, 14), 600, sim = "s1"), // previous cycle
            call("+33612345678", CallType.OUTGOING, at(2026, 3, 16), 600, sim = "s2"), // other SIM
            call("+33612345678", CallType.INCOMING, at(2026, 3, 16), 600, sim = "s1"), // incoming not counted
            call("+80012345678", CallType.OUTGOING, at(2026, 3, 16), 600, sim = "s1"), // toll-free never
            call("+14155550123", CallType.OUTGOING, at(2026, 3, 16), 600, sim = "s1"), // international off
        )
        val u = PlanMeter.usage(cfg, calls, cat, at(2026, 4, 5), UTC)
        assertEquals(10, u.usedMinutes)
        assertEquals(1, u.callsCounted)
        assertEquals(java.time.LocalDate.of(2026, 3, 15), u.cycleStart)
        assertEquals(java.time.LocalDate.of(2026, 4, 15), u.cycleEnd)
        assertEquals(10, u.daysLeft)
        assertEquals("10 of 300 min used · 10 days left", u.summary())
        assertFalse(u.isNear)
        assertTrue(PlanMeter.usage(cfg.copy(allowanceMinutes = 12), calls, cat, at(2026, 4, 5), UTC).isNear)
        assertEquals(20, PlanMeter.usage(cfg.copy(countIncoming = true), calls, cat, at(2026, 4, 5), UTC).usedMinutes)
        assertEquals(30, PlanMeter.usage(cfg.copy(countIncoming = true, countInternational = true), calls, cat, at(2026, 4, 5), UTC).usedMinutes)
    }

    @Test fun cycle_start_clamps_to_month_end() {
        assertEquals(java.time.LocalDate.of(2026, 2, 28) to java.time.LocalDate.of(2026, 3, 31), PlanMeter.cycle(java.time.LocalDate.of(2026, 3, 5), 31))
        assertEquals(java.time.LocalDate.of(2026, 3, 1) to java.time.LocalDate.of(2026, 4, 1), PlanMeter.cycle(java.time.LocalDate.of(2026, 3, 1), 1))
        val list = listOf(PlanConfig("a"), PlanConfig("b", increment = BillingIncrement.HALF_MINUTE))
        assertEquals(list, PlanConfig.decodeList(PlanConfig.encodeList(list)))
    }
}
