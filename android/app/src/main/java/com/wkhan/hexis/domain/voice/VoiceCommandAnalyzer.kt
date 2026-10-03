package com.wkhan.hexis.domain.voice

/** What the user most likely wants from a spoken command (the router's default; every action is still
 *  offered in the review sheet so a wrong guess is one tap away). */
enum class VoiceIntent { ADD_TASK, ADD_NOTE, START_TIMER, STOP_TIMER, SEARCH, QUERY, UNKNOWN }

/**
 * The structured "plan" a transcript resolves to — the router's best guess at what to do, shown to the
 * user for confirmation before anything is written. Nothing here touches the database.
 */
sealed interface VoiceProposal {
    val intent: VoiceIntent
    val transcript: String

    /** The transcript with the leading trigger removed — the text the review field prefills with. */
    val payloadText: String

    data class AddTask(
        override val transcript: String,
        val quickAddText: String,
    ) : VoiceProposal {
        override val intent get() = VoiceIntent.ADD_TASK
        override val payloadText get() = quickAddText
    }

    data class AddNote(override val transcript: String, val text: String) : VoiceProposal {
        override val intent get() = VoiceIntent.ADD_NOTE
        override val payloadText get() = text
    }

    data class StartTimer(override val transcript: String, val activity: String) : VoiceProposal {
        override val intent get() = VoiceIntent.START_TIMER
        override val payloadText get() = activity
    }

    data class StopTimer(override val transcript: String) : VoiceProposal {
        override val intent get() = VoiceIntent.STOP_TIMER
        override val payloadText get() = ""
    }

    data class Search(override val transcript: String, val query: String) : VoiceProposal {
        override val intent get() = VoiceIntent.SEARCH
        override val payloadText get() = query
    }

    /** A read-only question over the user's own data; answered by the core (Phase 4 spoken analytics). */
    data class Query(override val transcript: String, val question: String) : VoiceProposal {
        override val intent get() = VoiceIntent.QUERY
        override val payloadText get() = question
    }

    data class Unknown(override val transcript: String) : VoiceProposal {
        override val intent get() = VoiceIntent.UNKNOWN
        override val payloadText get() = transcript
    }
}

/**
 * Turns a raw transcript into a [VoiceProposal] entirely on-device, no LLM: a pure router that
 * classifies by leading trigger words and strips the trigger, leaving [VoiceProposal.payloadText] for
 * the action to consume. Quick-add parsing (dates / tags / priority) is intentionally NOT done here —
 * the review sheet runs [com.wkhan.hexis.domain.nlp.QuickAddParser] on the (editable) text so there is
 * exactly one parse, reactive to the user's edits. The addon is a dumb ear; this is the router.
 */
object VoiceCommandAnalyzer {

    private val STOP_TRIGGERS = listOf(
        "stop the timer", "stop timer", "stop tracking", "stop the clock", "stop", "pause timer", "pause the timer",
    )
    private val TIMER_TRIGGERS = listOf(
        "start a timer for", "start timer for", "start tracking", "start a timer", "start timer",
        "timer for", "start focus", "track time for", "track",
    )
    private val SEARCH_TRIGGERS = listOf(
        "search for", "search my", "search", "find me", "find all", "find", "look for", "look up",
    )
    private val QUERY_PREFIXES = listOf(
        "what's", "whats", "what is", "what", "how many", "how much",
        "when's", "when is", "when do", "when", "do i have", "show me", "show", "list",
    )
    private val NOTE_TRIGGERS = listOf(
        "take a note that", "make a note that", "take a note", "make a note", "note that", "new note", "note",
    )
    private val TASK_TRIGGERS = listOf(
        "remind me to", "remind me", "add a task to", "add a task", "add task",
        "create a task", "create task", "new task", "add", "task",
    )

    fun analyze(transcript: String): VoiceProposal {
        val text = transcript.trim()
        if (text.isEmpty()) return VoiceProposal.Unknown(transcript)
        val lower = text.lowercase()
        return when {
            matches(lower, STOP_TRIGGERS) -> VoiceProposal.StopTimer(transcript)
            matches(lower, TIMER_TRIGGERS) -> VoiceProposal.StartTimer(transcript, stripLeading(text, TIMER_TRIGGERS))
            matches(lower, SEARCH_TRIGGERS) -> VoiceProposal.Search(transcript, stripLeading(text, SEARCH_TRIGGERS))
            matches(lower, QUERY_PREFIXES) -> VoiceProposal.Query(transcript, text)
            matches(lower, NOTE_TRIGGERS) -> VoiceProposal.AddNote(transcript, stripLeading(text, NOTE_TRIGGERS))
            else -> VoiceProposal.AddTask(transcript, stripLeading(text, TASK_TRIGGERS))
        }
    }

    private fun matches(lower: String, triggers: List<String>): Boolean =
        triggers.any { lower == it || lower.startsWith("$it ") }

    /** Remove the longest matching leading trigger phrase; return the remainder (trimmed). */
    private fun stripLeading(text: String, triggers: List<String>): String {
        val lower = text.lowercase()
        val trigger = triggers.sortedByDescending { it.length }
            .firstOrNull { lower == it || lower.startsWith("$it ") }
            ?: return text.trim()
        return text.substring(trigger.length).trim()
    }
}
