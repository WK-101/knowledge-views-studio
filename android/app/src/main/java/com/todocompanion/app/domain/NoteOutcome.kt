package com.todocompanion.app.domain

/**
 * Wave 3 · Outcome Ledger — "what actually moved because of this note." A note materializes its
 * `[[links]]` into typed edges across tasks / habits / events ([NoteLinkEntity]); this rolls up the
 * *live state* of those targets — done-ness, streaks, whether an event has passed — plus the time
 * tracked on the note, into one honest accountability line. Pure: the ViewModel resolves each link to a
 * small [Target] projection, this aggregates. No standalone notes app can compute it, because none owns
 * the downstream task/habit/time state.
 */
object NoteOutcome {
    /** One resolved outgoing link's live state. Unused fields are null for a type that doesn't have them. */
    data class Target(
        val type: String,          // task | habit | event | note
        val id: String,
        val title: String,
        val done: Boolean? = null, // tasks: completed?
        val past: Boolean? = null, // events: already started?
        val streak: Int? = null,   // habits: current streak
    )

    data class Rollup(
        val tasksTotal: Int = 0, val tasksDone: Int = 0,
        val eventsTotal: Int = 0, val eventsPast: Int = 0,
        val habitsTotal: Int = 0, val habitBestStreak: Int = 0,
        val noteLinks: Int = 0,
        val trackedMinutes: Int = 0,
    ) {
        val hasAny: Boolean get() =
            tasksTotal > 0 || eventsTotal > 0 || habitsTotal > 0 || noteLinks > 0 || trackedMinutes > 0
        val tasksOpen: Int get() = tasksTotal - tasksDone
    }

    fun rollup(targets: List<Target>, trackedMinutes: Int): Rollup {
        var r = Rollup(trackedMinutes = trackedMinutes)
        for (t in targets) when (t.type) {
            "task" -> r = r.copy(tasksTotal = r.tasksTotal + 1, tasksDone = r.tasksDone + if (t.done == true) 1 else 0)
            "event" -> r = r.copy(eventsTotal = r.eventsTotal + 1, eventsPast = r.eventsPast + if (t.past == true) 1 else 0)
            "habit" -> r = r.copy(habitsTotal = r.habitsTotal + 1, habitBestStreak = maxOf(r.habitBestStreak, t.streak ?: 0))
            "note" -> r = r.copy(noteLinks = r.noteLinks + 1)
        }
        return r
    }

    /** A compact one-line summary, e.g. "6 tasks · 4 done · 3.2 h · streak 12". */
    fun summaryLine(r: Rollup): String {
        val parts = ArrayList<String>()
        if (r.tasksTotal > 0) parts.add("${r.tasksTotal} ${if (r.tasksTotal == 1) "task" else "tasks"} · ${r.tasksDone} done")
        if (r.eventsTotal > 0) parts.add("${r.eventsTotal} ${if (r.eventsTotal == 1) "event" else "events"}")
        if (r.habitsTotal > 0 && r.habitBestStreak > 0) parts.add("streak ${r.habitBestStreak}")
        else if (r.habitsTotal > 0) parts.add("${r.habitsTotal} ${if (r.habitsTotal == 1) "habit" else "habits"}")
        if (r.trackedMinutes > 0) parts.add("%.1f h".format(r.trackedMinutes / 60.0))
        return parts.joinToString(" · ")
    }
}
