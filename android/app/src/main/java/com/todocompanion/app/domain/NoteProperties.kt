package com.todocompanion.app.domain

/**
 * Wave S — notes as a local database. A note may carry a small YAML-style frontmatter block of typed
 * properties at the very top, exactly like Obsidian's Properties / a Tana supertag's fields:
 *
 *     ---
 *     status: reading
 *     rating: 4
 *     author: Ursula K. Le Guin
 *     ---
 *     # The note body…
 *
 * Pure and unit-tested: [parse] reads the block into an ordered map, [strip] returns the body without it
 * (for rendering), and [withProperties] rewrites the block (an empty map removes it entirely). Values are
 * plain strings — enough to power group-by board/gallery views and property predicates, entirely on-device.
 * The block travels verbatim in the note body, so it round-trips through the .md mirror and backup for free.
 */
object NoteProperties {

    private const val FENCE = "---"

    /** True if [body] opens with a frontmatter block. */
    fun has(body: String): Boolean = parseRaw(body) != null

    /** Ordered key→value map of the frontmatter (empty if none). */
    fun parse(body: String): LinkedHashMap<String, String> = parseRaw(body)?.first ?: LinkedHashMap()

    /** The body with any leading frontmatter block removed. */
    fun strip(body: String): String = parseRaw(body)?.second ?: body

    /** Rewrite the frontmatter to exactly [props] (empty ⇒ remove the block). Order preserved. */
    fun withProperties(body: String, props: Map<String, String>): String {
        val rest = strip(body).trimStart('\n')
        if (props.isEmpty()) return rest
        val sb = StringBuilder(FENCE).append("\n")
        for ((k, v) in props) {
            val key = k.trim()
            if (key.isEmpty()) continue
            sb.append(key).append(": ").append(v.trim().replace("\n", " ")).append("\n")
        }
        sb.append(FENCE).append("\n")
        return if (rest.isEmpty()) sb.toString().trimEnd('\n') else sb.append("\n").append(rest).toString()
    }

    /** Set/replace one key (added at the end if new); a blank value is allowed. */
    fun set(body: String, key: String, value: String): String {
        val m = parse(body); m[key.trim()] = value; return withProperties(body, m)
    }

    /** Remove one key. */
    fun remove(body: String, key: String): String {
        val m = parse(body); m.remove(key.trim()); return withProperties(body, m)
    }

    // Returns the parsed map + the body after the closing fence, or null when [body] has no frontmatter.
    private fun parseRaw(body: String): Pair<LinkedHashMap<String, String>, String>? {
        // The opening fence must be the very first line (allow a leading BOM/space-free start).
        if (!body.startsWith(FENCE)) return null
        val afterOpen = body.indexOf('\n')
        if (afterOpen < 0) return null
        // opening line must be exactly "---" (trimmed)
        if (body.substring(0, afterOpen).trim() != FENCE) return null
        val rest = body.substring(afterOpen + 1)
        // find the closing fence line
        val lines = rest.split("\n")
        var close = -1
        for (i in lines.indices) { if (lines[i].trim() == FENCE) { close = i; break } }
        if (close < 0) return null
        val map = LinkedHashMap<String, String>()
        for (i in 0 until close) {
            val line = lines[i]
            if (line.isBlank()) continue
            val c = line.indexOf(':')
            if (c <= 0) continue
            val k = line.substring(0, c).trim()
            val v = line.substring(c + 1).trim()
            if (k.isNotEmpty()) map[k] = v
        }
        val remainder = lines.drop(close + 1).joinToString("\n").trimStart('\n')
        return map to remainder
    }
}
