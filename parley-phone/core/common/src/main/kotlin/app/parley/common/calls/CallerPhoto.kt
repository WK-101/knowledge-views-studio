package app.parley.common.calls

/**
 * Whether the call screen shows a caller's photo and call-screen picture: Settings › Calls › "Show contact photo on
 * the call screen", unless the contact overrides it (Show or Hide in Settings for this contact). Hidden, the screen
 * shows the caller's initial on their colour instead.
 */
object CallerPhoto {
    fun shows(global: Boolean, override: Boolean?): Boolean = override ?: global

    /** The per-contact choices as stored: one "key<TAB>1|0" line each (keys are Parley's contact keys). */
    fun encode(choices: Map<String, Boolean>): String =
        choices.entries.filter { it.key.isNotBlank() && '\t' !in it.key && '\n' !in it.key }.sortedBy { it.key }
            .joinToString("\n") { "${it.key}\t${if (it.value) 1 else 0}" }

    fun decode(text: String?): Map<String, Boolean> = text.orEmpty().lineSequence().mapNotNull { line ->
        val tab = line.lastIndexOf('\t')
        if (tab <= 0) return@mapNotNull null
        when (line.substring(tab + 1).trim()) {
            "1" -> line.substring(0, tab) to true
            "0" -> line.substring(0, tab) to false
            else -> null
        }
    }.toMap()
}
