package app.parley.common.spam

import app.parley.common.blocking.Csv
import java.io.File

/**
 * Offline pack building (B4 tooling). Converts public data such as the US FTC Do-Not-Call "reported calls"
 * CSV into a `.parleylist`:
 *
 * ```
 * ./gradlew :core:common:buildSpamPack --args="--ftc dnc.csv --out ftc.parleylist --id gov.ftc.dnc --name 'FTC reported calls'"
 * ./gradlew :core:common:buildSpamPack --args="--numbers list.txt --country FR --out mine.parleylist --id me.list --name 'My list' --key my.key"
 * ```
 * `--key` points to a 32-byte Ed25519 secret key file (created when missing) and signs the pack.
 */
object FtcCsv {
    const val CAT_TELEMARKETING = 1
    const val CAT_ROBOCALL = 2
    const val CAT_SCAM = 3

    val categories = mapOf("1" to "Telemarketing", "2" to "Robocall", "3" to "Scam")

    /**
     * Adds every reported number. The score grows with the number of reports: 1 report = 40, 2 = 55,
     * 5 or more = 85, capped at 95. Robocalls and impostor subjects get their own categories.
     */
    fun addTo(builder: PackBuilder, csv: String): Int {
        val rows = Csv.parse(csv)
        if (rows.isEmpty()) return 0
        val header = rows.first().map { it.trim().lowercase() }
        val hasHeader = header.any { it.contains("phone") || it.contains("number") }
        val numberCol = header.indexOfFirst { it.contains("company_phone") }.takeIf { it >= 0 }
            ?: header.indexOfFirst { it.contains("phone") || it.contains("number") }.takeIf { it >= 0 } ?: 0
        val subjectCol = header.indexOfFirst { it == "subject" }
        val roboCol = header.indexOfFirst { it.contains("robocall") }
        data class Agg(var reports: Int = 0, var robo: Int = 0, var scam: Int = 0)
        val agg = LinkedHashMap<String, Agg>()
        for (row in if (hasHeader) rows.drop(1) else rows) {
            val n = row.getOrNull(numberCol)?.trim().orEmpty()
            if (n.isEmpty()) continue
            val a = agg.getOrPut(n) { Agg() }
            a.reports++
            if (roboCol >= 0 && row.getOrNull(roboCol)?.trim()?.uppercase() == "Y") a.robo++
            val subject = if (subjectCol >= 0) row.getOrNull(subjectCol).orEmpty().lowercase() else ""
            if ("imposter" in subject || "impostor" in subject || "scam" in subject) a.scam++
        }
        var added = 0
        for ((n, a) in agg) {
            val score = when {
                a.reports >= 5 -> 85
                a.reports >= 2 -> 55 + (a.reports - 2) * 10
                else -> 40
            }.coerceAtMost(95)
            val cat = when {
                a.scam * 2 >= a.reports -> CAT_SCAM
                a.robo * 2 >= a.reports -> CAT_ROBOCALL
                else -> CAT_TELEMARKETING
            }
            if (builder.addNumber(n, cat, score, "US")) added++
        }
        return added
    }
}

/** Command-line entry point (see [FtcCsv]). */
object PackTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val k = args[i]
            if (k.startsWith("--") && i + 1 < args.size) {
                opts[k.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                i++
            }
        }
        val out = opts["out"] ?: return usage()
        val id = opts["id"] ?: return usage()
        val name = opts["name"] ?: id
        val ftc = opts["ftc"]
        val builder = PackBuilder(
            PackManifest(
                id = id, name = name, publisher = opts["publisher"].orEmpty(), source = opts["source"] ?: if (ftc != null) "https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data" else "",
                licence = opts["licence"] ?: if (ftc != null) "US public data" else "", version = opts["version"]?.toLongOrNull() ?: (System.currentTimeMillis() / 86_400_000L),
                ttlDays = opts["ttl"]?.toIntOrNull() ?: 30, regions = opts["regions"]?.split(',')?.map { it.trim() } ?: emptyList(),
                categories = if (ftc != null) FtcCsv.categories else mapOf("1" to "Spam"),
            ),
        )
        var added = 0
        if (ftc != null) added += FtcCsv.addTo(builder, File(ftc).readText())
        opts["numbers"]?.let { f ->
            File(f).readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
                if (line.endsWith("*")) {
                    if (builder.addRange(line.dropLast(1), 1, 80)) added++
                } else if (builder.addNumber(line, 1, opts["score"]?.toIntOrNull() ?: 80, opts["country"])) {
                    added++
                }
            }
        }
        val key = opts["key"]?.let { path ->
            val f = File(path)
            if (!f.exists()) f.writeBytes(Ed25519.newSecret())
            f.readBytes().also { require(it.size == 32) { "Key file must hold 32 bytes" } }
        }
        File(out).writeBytes(builder.build(key))
        println("Wrote $out: $added entries" + (key?.let { ", signed by ${Ed25519.fingerprint(Ed25519.publicKey(it))}" } ?: ", unsigned"))
    }

    private fun usage() {
        println("Usage: --out FILE --id ID [--name NAME] (--ftc CSV | --numbers TXT [--country ISO]) [--key KEYFILE] [--ttl DAYS] [--version N]")
    }
}
