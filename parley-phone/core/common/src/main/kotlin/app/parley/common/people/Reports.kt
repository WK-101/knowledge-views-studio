package app.parley.common.people

/**
 * U10 and U11: text Parley hands to other apps only when you ask: a crash report (numbers, e-mail addresses and
 * content URIs masked), a masked dump of the contacts tables for diagnostics, and selected contacts as plain text.
 */
object Reports {
    /** One captured crash, as stored on the phone until you share or dismiss it. */
    data class Crash(val time: Long, val thread: String, val stack: String, val appVersion: String, val android: String)

    /** The report text. [stack] is masked with [Masking] unless [mask] is false; the thread name is always kept. */
    fun crashText(c: Crash, mask: Boolean = true, formattedTime: String = c.time.toString()): String = buildString {
        appendLine("Parley crash report")
        appendLine("Time: $formattedTime")
        appendLine("App: ${c.appVersion}")
        appendLine("Android: ${c.android}")
        appendLine("Thread: ${c.thread}")
        appendLine("Numbers and e-mail addresses masked: ${if (mask) "yes" else "no"}")
        appendLine()
        append(if (mask) Masking.mask(c.stack) else c.stack)
    }

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
 * I7: rules of the opt-in contacts Directory that lets approved phone apps show private names. The Contacts
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
