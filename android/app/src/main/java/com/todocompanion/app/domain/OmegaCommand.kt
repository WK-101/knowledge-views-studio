package com.todocompanion.app.domain

/**
 * Ω1 — the command palette. One typed line becomes one intent the app can run: capture, navigate,
 * act, or ask. Pure and side-effect-free; the palette UI executes whatever this returns. Because
 * every action already lives in one store, a single field can drive the whole app.
 */
object OmegaCommand {

    sealed interface Command {
        /** "track deep work" / "start reading" — begin a timer for an activity (creates it if new). */
        data class Track(val activity: String) : Command
        /** "go to health" / "open calendar" — navigate to a tab, smart list, goal or list by name. */
        data class Goto(val target: String) : Command
        /** A one-word app action — plan, review, momentum, stats, the annual report, a recap. */
        data class Act(val action: Action) : Command
        /** "how many hours on Health this week" — a data question answered by the local query engine. */
        data class Ask(val question: String) : Command
        /** Anything else — hand the raw text to smart capture (a new task or habit). */
        data class Capture(val text: String) : Command
    }

    enum class Action { PLAN, WEEKLY_REVIEW, MOMENTUM, STATS, ANNUAL_REPORT, RECAP_WEEK, RECAP_LAST_WEEK, RECAP_MONTH }

    private val TRACK = Regex("^(?:track|start|timer|time)\\s+(.+)$", RegexOption.IGNORE_CASE)
    // "setting dark mode" / "settings backup" / "preferences" → jump to Settings, pre-filtered (R28 #5).
    private val SETTINGS = Regex("^(?:settings?|preferences?|prefs?|config)\\b\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val GOTO = Regex("^(?:go\\s*to|goto|open|show|jump\\s+to)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val ASK_PREFIX = Regex("^(?:ask|q:|query)\\s+(.+)$", RegexOption.IGNORE_CASE)
    // Question-shaped lines the local query engine can try to answer.
    private val QUESTION = Regex("^(?:how\\s+many|how\\s+much|how\\s+long|what|which|when)\\b.*", RegexOption.IGNORE_CASE)

    // Fixed action phrases → an Act. Checked as whole-line (case-insensitive, trimmed).
    private val ACTIONS: List<Pair<Regex, Action>> = listOf(
        Regex("^(?:plan(?:\\s+my)?\\s+day|plan)$", RegexOption.IGNORE_CASE) to Action.PLAN,
        Regex("^(?:review\\s+(?:last|past)\\s+week|recap\\s+last\\s+week|last\\s+week)$", RegexOption.IGNORE_CASE) to Action.RECAP_LAST_WEEK,
        Regex("^(?:review\\s+(?:this\\s+)?week|recap\\s+(?:this\\s+)?week|week\\s+in\\s+review|recap)$", RegexOption.IGNORE_CASE) to Action.RECAP_WEEK,
        Regex("^(?:review\\s+(?:this\\s+|last\\s+)?month|recap\\s+(?:this\\s+|last\\s+)?month|month\\s+in\\s+review)$", RegexOption.IGNORE_CASE) to Action.RECAP_MONTH,
        Regex("^(?:weekly\\s+review|guided\\s+review|review)$", RegexOption.IGNORE_CASE) to Action.WEEKLY_REVIEW,
        Regex("^(?:momentum|dashboard|home)$", RegexOption.IGNORE_CASE) to Action.MOMENTUM,
        Regex("^(?:stats|statistics|my\\s+stats)$", RegexOption.IGNORE_CASE) to Action.STATS,
        Regex("^(?:year\\s+in\\s+review|annual\\s+report|my\\s+year|wrapped|life\\s+report)$", RegexOption.IGNORE_CASE) to Action.ANNUAL_REPORT,
    )

    fun parse(raw: String): Command {
        val s = raw.trim()
        if (s.isEmpty()) return Command.Capture("")

        TRACK.find(s)?.let { return Command.Track(it.groupValues[1].trim()) }

        for ((re, action) in ACTIONS) if (re.matches(s)) return Command.Act(action)

        ASK_PREFIX.find(s)?.let { return Command.Ask(it.groupValues[1].trim()) }
        SETTINGS.find(s)?.let { return Command.Goto("settings:" + it.groupValues[1].trim()) }
        GOTO.find(s)?.let { return Command.Goto(it.groupValues[1].trim()) }
        if (QUESTION.matches(s)) return Command.Ask(s)

        return Command.Capture(s)
    }
}
