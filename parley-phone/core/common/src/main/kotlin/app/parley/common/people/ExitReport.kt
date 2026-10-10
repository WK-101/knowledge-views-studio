package app.parley.common.people

/**
 * A report of the last time Parley stopped unexpectedly, built at the next start from what Android itself kept
 * (ApplicationExitInfo, Android 11 and later), so nothing needs to be stored beforehand. It holds no personal data:
 * the kind of stop, the stack (frames only), the app and Android versions and the device model.
 */
object ExitReport {
    enum class Kind { CRASH, NATIVE_CRASH, ANR }

    /** One stop Android reported: when, and what kind. */
    data class Exit(val time: Long, val kind: Kind)

    // ApplicationExitInfo.REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR.
    private const val REASON_CRASH = 4
    private const val REASON_CRASH_NATIVE = 5
    private const val REASON_ANR = 6

    fun kindOf(reason: Int): Kind? = when (reason) {
        REASON_CRASH -> Kind.CRASH
        REASON_CRASH_NATIVE -> Kind.NATIVE_CRASH
        REASON_ANR -> Kind.ANR
        else -> null
    }

    /** The newest crash or ANR after [since], from Android's (time, reason) list; null when there is none. */
    fun newest(exits: List<Pair<Long, Int>>, since: Long): Exit? =
        exits.filter { it.first > since }.mapNotNull { (t, r) -> kindOf(r)?.let { Exit(t, it) } }.maxByOrNull { it.time }

    /**
     * The main thread's frames from an ANR trace (the "main" thread block): "at …" and "native: …" lines only, so lock
     * addresses, thread names of other code and any text a frame doesn't hold are left out. At most [maxLines].
     */
    fun anrStack(trace: Sequence<String>, maxLines: Int = 120): String =
        // Lazy: reading stops at the end of the main thread's block.
        trace.dropWhile { !it.startsWith("\"main\"") }.drop(1).takeWhile { it.isNotBlank() }
            .map { it.trimStart() }.filter { it.startsWith("at ") || it.startsWith("native: ") }
            .take(maxLines).joinToString("\n") { "  $it" }

    /**
     * The report: the stop's kind and date, versions, device model and [stack] (frames only, numbers and addresses
     * masked). A stop without a stack says so.
     */
    fun text(exit: Exit, stack: String?, appVersion: String, android: String, device: String, formattedTime: String): String = buildString {
        appendLine("Parley stopped unexpectedly")
        appendLine("Kind: ${exit.kind.name.lowercase().replace('_', ' ')}")
        appendLine("Time: $formattedTime")
        appendLine("App: $appVersion")
        appendLine("Android: $android")
        appendLine("Device: $device")
        appendLine("Exception messages left out: yes")
        appendLine()
        // A captured crash's stack may hold exception messages; an ANR's frames were picked by [anrStack] already.
        val clean = stack?.let { if (exit.kind == Kind.ANR) it else Reports.withoutMessages(it) }
        val frames = clean?.let { Reports.trimStack(Masking.mask(it)) }?.takeIf { it.isNotBlank() }
        append(frames ?: "No stack: Android keeps none for this kind of stop. Keep crash reports (Settings › About) keeps the next one.")
    }
}
