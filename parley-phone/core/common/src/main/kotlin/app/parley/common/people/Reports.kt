package app.parley.common.people

/**
 * Text Parley hands to other apps only when you ask: a crash report (no exception messages; numbers, email
 * addresses and content URIs masked), a masked dump of the contacts tables for diagnostics, and selected contacts as plain text.
 */
object Reports {
    /** One captured crash, as stored on the phone until you share or dismiss it. */
    data class Crash(val time: Long, val thread: String, val stack: String, val appVersion: String, val android: String)

    /**
     * The report text. Exception messages are always left out ([withoutMessages], also for a report stored before
     * they were); the rest of [stack] is masked with [Masking] unless [mask] is false. The thread name is kept.
     */
    fun crashText(c: Crash, mask: Boolean = true, formattedTime: String = c.time.toString()): String = buildString {
        appendLine("Parley crash report")
        appendLine("Time: $formattedTime")
        appendLine("App: ${c.appVersion}")
        appendLine("Android: ${c.android}")
        appendLine("Thread: ${c.thread}")
        appendLine("Exception messages left out: yes")
        appendLine("Numbers and email addresses masked: ${if (mask) "yes" else "no"}")
        appendLine()
        val stack = withoutMessages(c.stack)
        append(if (mask) Masking.mask(stack) else stack)
    }

    /**
     * [error] as a stack trace with only class names and frames: the message of the exception, of each cause and of
     * each suppressed exception is left out, since a message can hold whatever the code had in hand (a number, a
     * name, a query). Laid out like [Throwable.printStackTrace], frames shared with the enclosing trace folded.
     */
    fun scrubbedStack(error: Throwable): String {
        val out = StringBuilder()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())

        fun write(t: Throwable, prefix: String, caption: String, enclosing: Array<StackTraceElement>) {
            if (!seen.add(t)) {
                out.append(prefix).append(caption).append("[CIRCULAR REFERENCE: ").append(t.javaClass.name).append("]\n")
                return
            }
            val frames = t.stackTrace
            var shared = 0
            while (shared < frames.size && shared < enclosing.size && frames[frames.size - 1 - shared] == enclosing[enclosing.size - 1 - shared]) shared++
            out.append(prefix).append(caption).append(t.javaClass.name).append('\n')
            for (i in 0 until frames.size - shared) out.append(prefix).append("\tat ").append(frames[i]).append('\n')
            if (shared > 0) out.append(prefix).append("\t... ").append(shared).append(" more\n")
            t.suppressed.forEach { write(it, prefix + "\t", "Suppressed: ", frames) }
            t.cause?.let { write(it, prefix, "Caused by: ", frames) }
        }
        write(error, "", "", emptyArray())
        return out.toString().trimEnd()
    }

    /**
     * A stack trace in [Throwable.printStackTrace]'s text with the exception messages cut off each heading line
     * ("java.lang.IllegalStateException: bad number …" → "java.lang.IllegalStateException"), and message lines that
     * ran on below a heading dropped. Frames ("at …", "... 3 more") are kept as they are.
     */
    fun withoutMessages(stack: String): String {
        val out = ArrayList<String>()
        for (line in stack.lines()) {
            val body = line.trimStart()
            when {
                body.startsWith("at ") || FOLDED.matches(body) -> out += line
                else -> HEADING.matchEntire(line)?.let { m -> out += m.groupValues[1] + m.groupValues[2] }
                // Anything else is the rest of a message that spanned several lines.
            }
        }
        return out.joinToString("\n")
    }

    /** "... 12 more" (frames shared with the enclosing trace) and "… 40 more lines" ([trimStack]). */
    private val FOLDED = Regex("""(\.\.\.|…) \d+ more( lines)?""")

    /**
     * A heading line: indentation, "Caused by: " or "Suppressed: ", then a qualified class name (its last part
     * capitalised), then (dropped) its message.
     */
    private val HEADING = Regex("""(\s*(?:Caused by: |Suppressed: )?)((?:[\p{L}_$][\p{L}\p{N}_$]*\.)+\p{Lu}[\p{L}\p{N}_$]*)(?::.*)?""")

    /** Keeps crash reports small: the first [maxLines] lines of the stack (the cause chain is near the top). */
    fun trimStack(stack: String, maxLines: Int = 120): String {
        val lines = stack.lines()
        return if (lines.size <= maxLines) stack else lines.take(maxLines).joinToString("\n") + "\n… ${lines.size - maxLines} more lines"
    }

    /**
     * A value from the contacts tables with its content removed but its shape kept, for the raw dump: letters become
     * "a"/"A", digits "9" except the last two of long numbers, other characters stay. "Anna +44 7700" → "Aaaa +99 9900".
     * Long values are cut.
     */
    fun shape(value: String?, max: Int = 24): String {
        if (value == null) return "null"
        if (value.isEmpty()) return "\"\""
        val digits = value.count { it.isDigit() }
        var seen = 0
        val sb = StringBuilder()
        for (ch in value) {
            if (sb.length >= max) {
                sb.append("…(${value.length})")
                break
            }
            sb.append(
                when {
                    ch.isDigit() -> { seen++; if (digits >= 5 && seen > digits - 2) ch else '9' }
                    ch.isLetter() -> if (ch.isUpperCase()) 'A' else 'a'
                    ch.isWhitespace() -> ' '
                    else -> ch
                },
            )
        }
        return sb.toString()
    }

    /** One contact for "Copy as text". */
    data class TextContact(val name: String, val numbers: List<Pair<String, String?>>, val emails: List<String>)

    /** "Anna Smith\nMobile: +44 …\nanna@x.org", contacts separated by a blank line. */
    fun contactsAsText(list: List<TextContact>): String = list.joinToString("\n\n") { c ->
        buildList {
            add(c.name)
            c.numbers.forEach { (n, label) -> add(if (label.isNullOrBlank()) n else "$label: $n") }
            c.emails.forEach { add(it) }
        }.joinToString("\n")
    }
}

/**
 * Rules of the opt-in contacts Directory that lets approved phone apps show private names. The Contacts
 * Provider discovers the directory and forwards other apps' lookups to it with its own identity, adding the real
 * app's package as [CALLER_PACKAGE_PARAM]; only a request that really comes from the Contacts Provider may name
 * another app that way.
 */
object DirectoryPolicy {
    /** ContactsContract.Directory.CALLER_PACKAGE_PARAM_KEY. */
    const val CALLER_PACKAGE_PARAM = "callerPackage"

    /** The app a lookup is for, or null when it can't be told (then nothing is answered). */
    fun effectiveCaller(callingPackage: String?, providerPackage: String?, callerParam: String?): String? {
        val caller = callingPackage?.takeIf { it.isNotBlank() } ?: return null
        if (providerPackage != null && caller == providerPackage) return callerParam?.trim()?.takeIf { it.isNotEmpty() }
        return caller
    }

    enum class Request { DIRECTORIES, PHONE_LOOKUP, OTHER }

    /**
     * Row ids of directory results live in their own namespace (bit 62 set), so they can never be taken for a real
     * contact's id by an app that opens them; opening one reaches Parley's directory, which answers nothing.
     */
    const val ID_NAMESPACE = 1L shl 62

    fun rowId(vaultId: Long): Long = ID_NAMESPACE or (vaultId and (ID_NAMESPACE - 1))

    fun isDirectoryRowId(id: Long): Boolean = id and ID_NAMESPACE != 0L && id > 0

    /** Only these two paths are ever answered; every other query (lists, filters, lookups by key) gets nothing. */
    fun request(segments: List<String>): Request = when {
        segments == listOf("directories") -> Request.DIRECTORIES
        segments.size == 2 && (segments[0] == "phone_lookup" || segments[0] == "phone_lookup_enterprise") -> Request.PHONE_LOOKUP
        else -> Request.OTHER
    }
}
