package com.wkhan.hexis.domain.voice

import com.wkhan.hexis.domain.nlp.ParsedQuickAdd
import com.wkhan.hexis.domain.nlp.QuickAddParser
import java.time.LocalDateTime

/** What the user wants from a spoken command. */
enum class VoiceIntent { ADD_TASK, ADD_NOTE, START_TIMER, QUERY, UNKNOWN }

/**
 * The structured "plan" a transcript resolves to — what the core would do, shown to the user for
 * confirmation before anything is written. Nothing here touches the database; the core commits only
 * after the user approves (see Phase 2 plan card).
 */
sealed interface VoiceProposal {
    val intent: VoiceIntent
    val transcript: String

    data class AddTask(override val transcript: String, val parsed: ParsedQuickAdd) : VoiceProposal {
        override val intent get() = VoiceIntent.ADD_TASK
        /** True when the title came out empty — the one field the user must fix before committing. */
        val needsReview: Boolean get() = parsed.title.isBlank()
    }

    data class AddNote(override val transcript: String, val text: String) : VoiceProposal {
        override val intent get() = VoiceIntent.ADD_NOTE
    }

    data class StartTimer(override val transcript: String, val activity: String) : VoiceProposal {
        override val intent get() = VoiceIntent.START_TIMER
    }

    /** A read-only question over the user's own data; answered by the core (Phase 4 spoken analytics). */
    data class Query(override val transcript: String, val question: String) : VoiceProposal {
        override val intent get() = VoiceIntent.QUERY
    }

    data class Unknown(override val transcript: String) : VoiceProposal {
        override val intent get() = VoiceIntent.UNKNOWN
    }
}

/**
 * Turns a raw transcript into a [VoiceProposal] entirely on-device, no LLM: classify the intent by
 * leading trigger words, strip the trigger, then hand the remainder to the existing [QuickAddParser]
 * for dates / times / recurrence / tags / priority. The addon is a dumb ear; this is the brain, and
 * it lives in the core next to the data and the parser.
 */
object VoiceCommandAnalyzer {

    private val QUERY_PREFIXES = listOf(
        "what's", "whats", "what is", "what", "how many", "how much",
        "when's", "when is", "when do", "when", "do i have", "show me", "show", "list",
    )
    private val TIMER_TRIGGERS = listOf(
        "start a timer for", "start timer for", "timer for",
        "start a timer", "start timer", "start tracking", "start focus", "track",
    )
    private val NOTE_TRIGGERS = listOf(
        "take a note that", "make a note that", "take a note", "make a note", "note that", "note",
    )
    private val TASK_TRIGGERS = listOf(
        "remind me to", "remind me", "add a task to", "add a task", "add task",
        "create a task", "create task", "new task", "add", "task",
    )

    fun analyze(transcript: String, now: LocalDateTime = LocalDateTime.now()): VoiceProposal {
        val text = transcript.trim()
        if (text.isEmpty()) return VoiceProposal.Unknown(transcript)
        val lower = text.lowercase()
        return when {
            matches(lower, QUERY_PREFIXES) -> VoiceProposal.Query(transcript, text)
            matches(lower, TIMER_TRIGGERS) -> VoiceProposal.StartTimer(transcript, stripLeading(text, TIMER_TRIGGERS))
            matches(lower, NOTE_TRIGGERS) -> VoiceProposal.AddNote(transcript, stripLeading(text, NOTE_TRIGGERS))
            else -> VoiceProposal.AddTask(transcript, QuickAddParser.parse(stripLeading(text, TASK_TRIGGERS), now))
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
