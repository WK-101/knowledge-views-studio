package app.parley.common.blocking

import app.parley.common.RuleKind
import app.parley.common.RuleType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Small RFC 4180 reader: quotes, doubled quotes, CRLF or LF, and `,` `;` or tab as the separator. */
object Csv {
    fun detectSeparator(text: String): Char {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(',', ';', '\t').maxByOrNull { sep -> line.count { it == sep } }?.takeIf { sep -> line.count { it == sep } > 0 } ?: ','
    }

    fun parse(text: String, separator: Char = detectSeparator(text)): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        fun endCell() {
            row += cell.toString()
            cell.setLength(0)
        }
        fun endRow() {
            endCell()
            if (row.any { it.isNotBlank() }) rows += row
            row = ArrayList()
        }
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> if (cell.isEmpty()) quoted = true else cell.append(c)
                    separator -> endCell()
                    '\r' -> Unit
                    '\n' -> endRow()
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}

/** One rule found in an imported file. */
data class ImportedRule(val pattern: String, val type: RuleType, val kind: RuleKind = RuleKind.BLOCK, val note: String? = null)

/** Which columns hold what (B26 column mapping). Indices are 0-based; -1 = none. */
data class ColumnMapping(
    val hasHeader: Boolean,
    val number: Int,
    val note: Int = -1,
    /** Column saying "allow"/"white"/"block" per row. */
    val kind: Int = -1,
    /** YACB writes '#' for "one digit" in its patterns. */
    val hashIsOneDigit: Boolean = false,
)

enum class ImportPreset(val title: String, val help: String) {
    GENERIC("Spreadsheet (CSV)", "Pick the column with the numbers. Optional columns: a note, and whether each row is allowed or blocked."),
    YACB("Yet Another Call Blocker blacklist", "The CSV exported from YACB's blacklist screen (name and pattern columns)."),
    NO_PHONE_SPAM("NoPhoneSpam list", "One number or pattern per line; * at the end means \"starts with\"."),
}

object ListImport {
    private val NUMBER_HEADERS = listOf("pattern", "number", "phone", "telefon", "numero", "número", "tel", "nummer")
    private val NOTE_HEADERS = listOf("name", "note", "label", "comment", "description", "desc")
    private val KIND_HEADERS = listOf("kind", "list", "action", "allow", "type")

    /** Guesses the mapping from the first rows. */
    fun guessMapping(rows: List<List<String>>, preset: ImportPreset = ImportPreset.GENERIC): ColumnMapping {
        val first = rows.firstOrNull().orEmpty().map { it.trim().lowercase() }
        val header = first.isNotEmpty() && first.none { looksLikeNumber(it) } && first.any { h -> NUMBER_HEADERS.any { h.contains(it) } || NOTE_HEADERS.any { h.contains(it) } }
        val numberCol = if (header) {
            first.indexOfFirst { h -> h == "pattern" }.takeIf { it >= 0 } ?: first.indexOfFirst { h -> NUMBER_HEADERS.any { h.contains(it) } }
        } else {
            (first.indices).firstOrNull { i -> rows.take(20).count { looksLikeNumber(it.getOrNull(i).orEmpty()) } >= minOf(rows.size, 20) / 2 } ?: 0
        }
        val noteCol = if (header) first.indexOfFirst { h -> NOTE_HEADERS.any { h.contains(it) } && h != first.getOrNull(numberCol) } else -1
        val kindCol = if (header) first.indexOfFirst { h -> KIND_HEADERS.any { h == it } } else -1
        return ColumnMapping(header, numberCol.coerceAtLeast(0), noteCol, kindCol, hashIsOneDigit = preset == ImportPreset.YACB)
    }

    fun looksLikeNumber(s: String): Boolean {
        val t = s.trim()
        val digits = t.count { it.isDigit() }
        return digits >= 3 && t.all { it.isDigit() || it in "+-() .*?#" }
    }

    /** Converts one cell into a rule: trailing '*' = prefix, other '*'/'?' = pattern, otherwise exact. */
    fun toRule(cell: String, hashIsOneDigit: Boolean = false): Pair<String, RuleType>? {
        var p = cell.trim().filter { it.isDigit() || it in "+*?#" }
        if (hashIsOneDigit) p = p.replace('#', '?') else p = p.filter { it != '#' }
        if (p.count { it.isDigit() } < 2) return null
        val wild = p.indexOfFirst { it == '*' || it == '?' }
        return when {
            wild < 0 -> p to RuleType.EXACT
            p.endsWith("*") && p.dropLast(1).none { it == '*' || it == '?' } -> p.dropLast(1) to RuleType.PREFIX
            else -> p to RuleType.WILDCARD
        }
    }

    fun rows(rows: List<List<String>>, m: ColumnMapping): List<ImportedRule> {
        val body = if (m.hasHeader) rows.drop(1) else rows
        return body.mapNotNull { r ->
            val (pattern, type) = toRule(r.getOrNull(m.number).orEmpty(), m.hashIsOneDigit) ?: return@mapNotNull null
            val note = r.getOrNull(m.note)?.trim()?.takeIf { it.isNotEmpty() && m.note >= 0 }
            val kindText = if (m.kind >= 0) r.getOrNull(m.kind)?.trim()?.lowercase().orEmpty() else ""
            val kind = if (kindText.contains("allow") || kindText.contains("white") || kindText == "true") RuleKind.ALLOW else RuleKind.BLOCK
            ImportedRule(pattern, type, kind, note)
        }.distinctBy { "${it.kind}|${it.type}|${it.pattern}" }
    }

    /** NoPhoneSpam and plain lists: one entry per line, `#` comments. */
    fun plainLines(text: String): List<ImportedRule> = text.lineSequence()
        .map { it.substringBefore("//").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val cell = line.split(',', ';', '\t').first()
            val note = line.split(',', ';', '\t').getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            toRule(cell)?.let { (p, t) -> ImportedRule(p, t, RuleKind.BLOCK, note) }
        }
        .distinctBy { "${it.type}|${it.pattern}" }
        .toList()

    // ---------- Call Blocker (com.callblocker) ----------

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Reads Call Blocker's JSON backup. Its schema isn't documented, so every object that has a number-like
     * field is taken; arrays named like "whitelist"/"allowed" become allow rules, fields such as
     * `isPrefix`/`type: PREFIX` make prefix rules, and `enabled: false` entries are skipped.
     */
    fun callBlockerJson(text: String): List<ImportedRule> {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("This file isn't valid JSON")
        }
        val out = ArrayList<ImportedRule>()
        walk(root, null, out)
        return out.distinctBy { "${it.kind}|${it.type}|${it.pattern}" }
    }

    private val NUMBER_KEYS = listOf("number", "phonenumber", "phone", "pattern", "prefix", "value", "normalizednumber")

    private fun walk(e: JsonElement, context: String?, out: MutableList<ImportedRule>) {
        when (e) {
            is JsonArray -> e.forEach { walk(it, context, out) }
            is JsonObject -> {
                val keys = e.keys.associateBy { it.lowercase() }
                val numberKey = NUMBER_KEYS.firstNotNullOfOrNull { keys[it] }
                val raw = numberKey?.let { (e[it] as? JsonPrimitive)?.contentOrNull }
                if (raw != null && raw.count { it.isDigit() } >= 2) {
                    val enabled = listOf("enabled", "isenabled", "active", "isactive").firstNotNullOfOrNull { keys[it] }
                        ?.let { (e[it] as? JsonPrimitive)?.booleanOrNull } ?: true
                    if (enabled) {
                        val typeText = listOf("type", "matchtype", "kind", "mode").firstNotNullOfOrNull { keys[it] }
                            ?.let { (e[it] as? JsonPrimitive)?.contentOrNull?.lowercase() }.orEmpty()
                        val isPrefix = keys["isprefix"]?.let { (e[it] as? JsonPrimitive)?.booleanOrNull } == true ||
                            typeText.contains("prefix") || numberKey.equals("prefix", ignoreCase = true)
                        val listText = (context.orEmpty() + " " + typeText + " " +
                            (listOf("list", "listtype", "category").firstNotNullOfOrNull { keys[it] }?.let { (e[it] as? JsonPrimitive)?.contentOrNull }.orEmpty())).lowercase()
                        val allow = listOf("white", "allow", "trusted", "exception").any { it in listText } ||
                            keys["isallowed"]?.let { (e[it] as? JsonPrimitive)?.booleanOrNull } == true
                        val note = listOf("name", "label", "note", "description", "comment").firstNotNullOfOrNull { keys[it] }
                            ?.let { (e[it] as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }
                        val base = toRule(if (isPrefix) raw.trimEnd('*') + "*" else raw)
                        if (base != null) out += ImportedRule(base.first, base.second, if (allow) RuleKind.ALLOW else RuleKind.BLOCK, note)
                    }
                    return
                }
                e.forEach { (k, v) -> walk(v, if (v is JsonArray || v is JsonObject) k else context, out) }
            }
            else -> Unit
        }
    }

    /**
     * Decrypts a Call Blocker `.cbbk` backup: `CBBK` magic, 16-byte salt, 12-byte IV, then AES-256-GCM
     * ciphertext with its 16-byte tag; the key is PBKDF2-HMAC-SHA256 (100,000 iterations) of the password.
     * Some versions separate the fields with '|'; both layouts are accepted.
     */
    fun decryptCbbk(file: ByteArray, password: CharArray): String {
        if (file.size < 4 + 16 + 12 + 16 || file.decodeToString(0, 4) != "CBBK") throw IllegalArgumentException("Not a Call Blocker backup (.cbbk)")
        val bar = '|'.code.toByte()
        val separated = file.size > 4 + 1 + 16 + 1 + 12 + 1 + 16 && file[4] == bar && file[4 + 1 + 16] == bar && file[4 + 1 + 16 + 1 + 12] == bar
        val buf = ByteBuffer.wrap(file)
        buf.position(4)
        if (separated) buf.get()
        val salt = ByteArray(16).also { buf.get(it) }
        if (separated) buf.get()
        val iv = ByteArray(12).also { buf.get(it) }
        if (separated) buf.get()
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, 100_000, 256)).encoded
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.doFinal(ct).decodeToString()
        } catch (e: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("Wrong password, or the file is damaged")
        } finally {
            key.fill(0)
        }
    }
}
