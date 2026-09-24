package app.parley.common.spam

import app.parley.common.PhoneNumbers
import app.parley.common.blocking.Csv
import java.time.LocalDate

/**
 * How to read one public CSV of reported numbers (B4c). Column names are matched case-insensitively,
 * first exact, then as a substring.
 */
data class CsvSpec(
    /** Header names of the number column, tried in order. Without a header, the first column. */
    val numberColumns: List<String>,
    /** Country used to read national numbers ("US" for the FTC's 10-digit numbers). */
    val countryIso: String?,
    /** Column holding a report count per row (absent: every row is one report). */
    val countColumn: String? = null,
    /** Column whose text decides the category through [categoryKeywords]. */
    val categoryColumn: String? = null,
    /** Keyword (lowercase) found in [categoryColumn] → category id. */
    val categoryKeywords: List<Pair<String, Int>> = emptyList(),
    /** Column holding a yes/no flag; rows whose value equals [flagValue] vote for [flagCategory]. */
    val flagColumn: String? = null,
    val flagValue: String = "Y",
    val flagCategory: Int = 0,
    /** Category when no vote reaches half of the reports. */
    val defaultCategory: Int = 1,
    /** Categories that win when at least half the reports vote for them, strongest first. */
    val categoryPriority: List<Int> = emptyList(),
    /** Category id (as string) → display name, copied into the manifest. */
    val categories: Map<String, String> = mapOf("1" to "Spam"),
)

/**
 * Report counts per number, merged across files (one per day). Kept in a compact text form so an updater
 * can cache each day once and rebuild a rolling window without downloading everything again.
 */
class ReportTally {
    /** E.164 key → [reports, votes for category 0..MAX_CATEGORY]. */
    private val map = HashMap<Long, IntArray>()

    val size: Int get() = map.size

    val totalReports: Long get() = map.values.sumOf { it[0].toLong() }

    fun add(key: Long, reports: Int = 1, votes: Map<Int, Int> = emptyMap()) {
        if (reports <= 0) return
        val a = map.getOrPut(key) { IntArray(MAX_CATEGORY + 2) }
        a[0] = saturatingAdd(a[0], reports)
        votes.forEach { (cat, n) -> if (cat in 0..MAX_CATEGORY && n > 0) a[cat + 1] = saturatingAdd(a[cat + 1], n) }
    }

    fun reports(key: Long): Int = map[key]?.get(0) ?: 0

    fun votes(key: Long, category: Int): Int = map[key]?.getOrNull(category + 1) ?: 0

    fun merge(other: ReportTally): ReportTally {
        other.map.forEach { (k, v) ->
            val a = map.getOrPut(k) { IntArray(MAX_CATEGORY + 2) }
            for (i in v.indices) a[i] = saturatingAdd(a[i], v[i])
        }
        return this
    }

    /** One number per line: `key reports cat:votes cat:votes`. */
    fun encode(): String {
        val sb = StringBuilder(map.size * 20)
        map.keys.sorted().forEach { k ->
            val a = map.getValue(k)
            sb.append(k).append(' ').append(a[0])
            for (c in 0..MAX_CATEGORY) if (a[c + 1] > 0) sb.append(' ').append(c).append(':').append(a[c + 1])
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Adds every number to [builder], picking the category by vote and the score from the report count. */
    fun addTo(builder: PackBuilder, spec: CsvSpec): Int {
        var added = 0
        map.forEach { (k, a) ->
            val reports = a[0]
            val cat = spec.categoryPriority.firstOrNull { c -> c in 0..MAX_CATEGORY && a[c + 1] * 2 >= reports && a[c + 1] > 0 } ?: spec.defaultCategory
            if (builder.addNumber("+$k", cat, ReportScore.of(reports))) added++
        }
        return added
    }

    companion object {
        const val MAX_CATEGORY = 7

        private fun saturatingAdd(a: Int, b: Int): Int = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        fun decode(text: String): ReportTally {
            val t = ReportTally()
            text.lineSequence().forEach { line ->
                val p = line.trim().split(' ')
                if (p.size < 2) return@forEach
                val k = p[0].toLongOrNull() ?: return@forEach
                val r = p[1].toIntOrNull() ?: return@forEach
                val votes = p.drop(2).mapNotNull { v ->
                    val c = v.substringBefore(':').toIntOrNull()
                    val n = v.substringAfter(':').toIntOrNull()
                    if (c != null && n != null) c to n else null
                }.toMap()
                t.add(k, r, votes)
            }
            return t
        }
    }
}

/** The score a number gets from its report count: the same curve as the command-line tool ([FtcCsv]). */
object ReportScore {
    fun of(reports: Int): Int = when {
        reports >= 5 -> 85
        reports >= 2 -> 55 + (reports - 2) * 10
        else -> 40
    }.coerceAtMost(95)
}

/** Turns a CSV of reported numbers into a `.parleylist`, on the phone or in tools, with [PackBuilder]. */
object CsvPackConverter {
    /** Counts the reports in one CSV. Numbers that can't be read as a phone number are skipped. */
    fun tally(csv: String, spec: CsvSpec): ReportTally {
        val tally = ReportTally()
        val rows = Csv.parse(csv)
        if (rows.isEmpty()) return tally
        val header = rows.first().map { it.trim().lowercase() }
        val names = spec.numberColumns.map { it.lowercase() }
        fun col(name: String?): Int {
            if (name == null) return -1
            val n = name.lowercase()
            return header.indexOf(n).takeIf { it >= 0 } ?: header.indexOfFirst { it.contains(n) }
        }
        val numberCol = names.firstNotNullOfOrNull { n -> col(n).takeIf { it >= 0 } } ?: -1
        val hasHeader = numberCol >= 0 || header.any { h -> h.contains("phone") || h.contains("number") }
        val nCol = if (numberCol >= 0) numberCol else 0
        val countCol = if (hasHeader) col(spec.countColumn) else -1
        val catCol = if (hasHeader) col(spec.categoryColumn) else -1
        val flagCol = if (hasHeader) col(spec.flagColumn) else -1
        val cache = HashMap<String, Long?>()
        for (row in if (hasHeader) rows.drop(1) else rows) {
            val raw = row.getOrNull(nCol)?.trim().orEmpty()
            // Letters would be read as a vanity number ("CALL-NOW"): public data never means that.
            if (raw.isEmpty() || raw.any { it.isLetter() }) continue
            val key = cache.getOrPut(raw) { PhoneNumbers.toE164(raw, spec.countryIso)?.let { ListPack.key(it) } } ?: continue
            val reports = if (countCol >= 0) row.getOrNull(countCol)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 1 else 1
            val votes = HashMap<Int, Int>()
            if (catCol >= 0) {
                val text = row.getOrNull(catCol).orEmpty().lowercase()
                spec.categoryKeywords.firstOrNull { (kw, _) -> kw in text }?.let { (_, c) -> votes[c] = reports }
            }
            if (flagCol >= 0 && row.getOrNull(flagCol)?.trim().equals(spec.flagValue, ignoreCase = true)) {
                votes[spec.flagCategory] = (votes[spec.flagCategory] ?: 0) + reports
            }
            tally.add(key, reports, votes)
        }
        return tally
    }

    /** Builds a pack from counted reports. [base] supplies id, name, source, licence and version. */
    fun build(tally: ReportTally, spec: CsvSpec, base: PackManifest, secretKey: ByteArray? = null, now: Long = System.currentTimeMillis()): ByteArray {
        val builder = PackBuilder(base.copy(categories = base.categories.ifEmpty { spec.categories }))
        tally.addTo(builder, spec)
        return builder.build(secretKey, now)
    }

    fun convert(csv: String, spec: CsvSpec, base: PackManifest, secretKey: ByteArray? = null, now: Long = System.currentTimeMillis()): ByteArray =
        build(tally(csv, spec), spec, base, secretKey, now)
}

/**
 * The US FTC "Do Not Call reported calls" data: a public CSV per weekday, no key needed.
 * See https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data
 */
object FtcDncSource {
    const val PAGE = "https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data"
    const val PACK_ID = "gov.ftc.dnc"
    const val LICENCE = "US federal government data (public domain, 17 U.S.C. § 105); reports are not verified by the FTC"

    val SPEC = CsvSpec(
        numberColumns = listOf("company_phone_number", "phone"),
        countryIso = "US",
        categoryColumn = "subject",
        categoryKeywords = listOf("imposter" to FtcCsv.CAT_SCAM, "impostor" to FtcCsv.CAT_SCAM, "scam" to FtcCsv.CAT_SCAM),
        flagColumn = "recorded_message_or_robocall",
        flagValue = "Y",
        flagCategory = FtcCsv.CAT_ROBOCALL,
        defaultCategory = FtcCsv.CAT_TELEMARKETING,
        categoryPriority = listOf(FtcCsv.CAT_SCAM, FtcCsv.CAT_ROBOCALL),
        categories = FtcCsv.categories,
    )

    /** The daily file published for [date] (weekdays only; weekends are included in Monday's file). */
    fun dailyUrl(date: LocalDate): String = "https://www.ftc.gov/sites/default/files/DNC_Complaint_Numbers_$date.csv"

    /** True when [csv] looks like the FTC file (and not, for example, an HTML error page). */
    fun looksValid(csv: String): Boolean = csv.lineSequence().firstOrNull().orEmpty().lowercase().contains("company_phone_number")

    fun manifest(days: Int, version: Long, created: Long) = PackManifest(
        id = PACK_ID,
        name = "US FTC reported calls (last $days days)",
        publisher = "Parley Lists (built on this phone from FTC data)",
        source = PAGE,
        licence = LICENCE,
        version = version,
        created = created,
        ttlDays = 7,
        regions = listOf("US"),
        categories = FtcCsv.categories,
    )
}
