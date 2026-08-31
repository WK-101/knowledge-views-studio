package com.todocompanion.app.domain

/**
 * R52 — a tiny, offline vCard (.vcf) reader that pulls out just what an Occasions birthday needs: a
 * display name and a BDAY. Handles vCard 2.1 / 3.0 / 4.0, line folding (RFC 6350 §3.2), the common
 * BDAY forms (`19900101`, `1990-01-01`, `--0101`/`--01-01` year-unknown, and a leading `T`/time we
 * ignore), and quoted-printable soft breaks. No network, no contacts permission — the user hands us a
 * file they exported themselves. Everything else in the card is ignored.
 */
object VCard {

    /** One importable birthday. [year] is null when the card omits it (`--MMDD`). */
    data class Birthday(val name: String, val month: Int, val day: Int, val year: Int?)

    fun parse(text: String): List<Birthday> {
        val out = ArrayList<Birthday>()
        // Split into individual VCARD blocks so one bad card can't swallow the next.
        val unfolded = unfold(text)
        var fn: String? = null
        var n: String? = null
        var bday: String? = null
        fun flush() {
            val name = (fn?.takeIf { it.isNotBlank() } ?: nameFromN(n))?.trim()
            val parsed = bday?.let { parseBday(it) }
            if (!name.isNullOrBlank() && parsed != null) out.add(Birthday(name, parsed.second, parsed.third, parsed.first))
            fn = null; n = null; bday = null
        }
        unfolded.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val upper = line.uppercase()
            when {
                upper.startsWith("BEGIN:VCARD") -> { fn = null; n = null; bday = null }
                upper.startsWith("END:VCARD") -> flush()
                else -> {
                    val colon = line.indexOf(':')
                    if (colon <= 0) return@forEach
                    val nameAndParams = line.substring(0, colon)
                    var value = line.substring(colon + 1)
                    val prop = nameAndParams.substringBefore(';').uppercase().substringAfter('.') // drop group prefix like item1.
                    if (nameAndParams.uppercase().contains("QUOTED-PRINTABLE")) value = decodeQuotedPrintable(value)
                    when (prop) {
                        "FN" -> fn = value.replace("\\,", ",").replace("\\;", ";").trim()
                        "N" -> n = value
                        "BDAY", "ANNIVERSARY" -> if (prop == "BDAY" || bday == null) if (value.isNotBlank()) bday = value.trim()
                    }
                }
            }
        }
        // A card with no END is still worth flushing.
        if (fn != null || n != null || bday != null) flush()
        return out
    }

    /** Unfold RFC-6350 continuation lines: a line beginning with a space or tab continues the previous. */
    private fun unfold(text: String): String {
        val sb = StringBuilder()
        text.replace("\r\n", "\n").replace("\r", "\n").split("\n").forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && sb.isNotEmpty()) {
                sb.append(line.trimStart())
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        return sb.toString()
    }

    /** N = Family;Given;Additional;Prefix;Suffix → "Given Family". */
    private fun nameFromN(n: String?): String? {
        if (n.isNullOrBlank()) return null
        val parts = n.split(";")
        val family = parts.getOrNull(0)?.trim().orEmpty()
        val given = parts.getOrNull(1)?.trim().orEmpty()
        val joined = listOf(given, family).filter { it.isNotBlank() }.joinToString(" ")
        return joined.ifBlank { n.replace(";", " ").trim() }.replace("\\,", ",").replace("\\;", ";")
    }

    /** Returns Triple(year?, month, day) or null. Accepts 19900101, 1990-01-01, --0101, --01-01, 1990-01-01T..., 19901231T... */
    private fun parseBday(raw: String): Triple<Int?, Int, Int>? {
        var s = raw.trim()
        // Some exporters wrap in VALUE=date; the value itself is what remains after the colon, so just clean.
        val tIdx = s.indexOf('T'); if (tIdx > 0) s = s.substring(0, tIdx)
        s = s.trim()
        // Year-unknown forms: --MMDD or --MM-DD (vCard 4.0).
        if (s.startsWith("--")) {
            val body = s.substring(2).replace("-", "")
            if (body.length >= 4) {
                val m = body.substring(0, 2).toIntOrNull() ?: return null
                val d = body.substring(2, 4).toIntOrNull() ?: return null
                if (valid(m, d)) return Triple(null, m, d)
            }
            return null
        }
        val digits = s.replace("-", "")
        if (digits.length == 8 && digits.all { it.isDigit() }) {
            val y = digits.substring(0, 4).toIntOrNull() ?: return null
            val m = digits.substring(4, 6).toIntOrNull() ?: return null
            val d = digits.substring(6, 8).toIntOrNull() ?: return null
            if (valid(m, d) && y in 1..9999) return Triple(y.takeIf { it > 1 }, m, d)
        }
        // MMDD only (rare) → year unknown.
        if (digits.length == 4 && digits.all { it.isDigit() }) {
            val m = digits.substring(0, 2).toIntOrNull() ?: return null
            val d = digits.substring(2, 4).toIntOrNull() ?: return null
            if (valid(m, d)) return Triple(null, m, d)
        }
        return null
    }

    private fun valid(m: Int, d: Int) = m in 1..12 && d in 1..31

    private fun decodeQuotedPrintable(s: String): String = runCatching {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '=' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) { bytes.add(v.toByte()); i += 3; continue }
            }
            bytes.add(ch.code.toByte()); i++
        }
        String(bytes.toByteArray(), Charsets.UTF_8)
    }.getOrDefault(s)
}
