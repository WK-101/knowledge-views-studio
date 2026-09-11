package com.todocompanion.app.domain

/**
 * Wave U — cross-module note templates: the fusion move only a whole-life app can make. Each template is
 * a note scaffold that pulls the rest of the app in *live* — frontmatter [properties][NoteProperties]
 * plus [transclusion][com.todocompanion.app.util.NoteTransclusion] tokens ({{today}}/{{events}}/{{habits}}/
 * {{tasks}}) that recompute every time the note opens, and date/time tokens ([NoteTokens]) resolved once
 * at apply time. NotePlan templates a note; Amplenote templates a task; only Kairo can template a note
 * that assembles itself from your tasks, events and habits — because only Kairo has all of them.
 *
 * Pure: [apply] resolves the one-shot date/time tokens and hands back a (title, body) to save. The
 * live {{…}} tokens are intentionally left for the read view to expand.
 */
object NoteTemplates {
    data class Template(val id: String, val name: String, val emoji: String, val titleHint: String, val body: String)

    val ALL: List<Template> = listOf(
        Template(
            "daily", "Daily cockpit", "🌅", "{{date}}",
            """
            ---
            kind: daily
            mood:
            ---
            # {{date}}

            ## Today's agenda
            {{today:agenda}}

            ## On the calendar
            {{events:today}}

            ## Habits
            {{habits:due}}

            ## Notes & wins
            -
            """.trimIndent(),
        ),
        Template(
            "meeting", "Meeting", "🗣", "Meeting — {{date}}",
            """
            ---
            kind: meeting
            date: {{date}}
            attendees:
            ---
            # Meeting — {{date}}

            **Attendees:**

            ## Agenda
            -

            ## Notes
            -

            ## Decisions
            -

            ## Action items
            - [ ]
            """.trimIndent(),
        ),
        Template(
            "project", "Project kickoff", "🚀", "Project — ",
            """
            ---
            kind: project
            status: planning
            owner:
            ---
            # Project —

            ## Goal


            ## Milestones
            - [ ]

            ## Open tasks
            {{tasks:overdue}}

            ## Log
            - {{date}} — created
            """.trimIndent(),
        ),
        Template(
            "weekly", "Weekly review", "🔄", "Weekly review — {{date}}",
            """
            ---
            kind: review
            week_of: {{date}}
            ---
            # Weekly review — {{date}}

            ## What got done


            ## Still open
            {{tasks:overdue}}

            ## Habits this week
            {{habits:due}}

            ## Next week's focus
            -
            """.trimIndent(),
        ),
        Template(
            "book", "Book note", "📚", "",
            """
            ---
            kind: book
            author:
            rating:
            status: reading
            ---
            #

            ## Summary


            ## Highlights
            -

            ## Takeaways
            -
            """.trimIndent(),
        ),
        Template(
            "decision", "Decision log", "⚖️", "Decision — ",
            """
            ---
            kind: decision
            date: {{date}}
            status: open
            ---
            # Decision —

            ## Context


            ## Options
            1.

            ## Decision


            ## Because

            """.trimIndent(),
        ),
    )

    fun byId(id: String): Template? = ALL.firstOrNull { it.id == id }

    /** Resolve one-shot date/time tokens now; returns (title, body). Live {{…}} tokens are left intact. */
    fun apply(t: Template): Pair<String, String> =
        NoteTokens.expand(t.titleHint) to NoteTokens.expand(t.body)
}
