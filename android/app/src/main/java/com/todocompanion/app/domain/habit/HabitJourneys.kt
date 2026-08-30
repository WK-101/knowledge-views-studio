package com.todocompanion.app.domain.habit

/**
 * R33 (F16) — guided formation JOURNEYS: curated, multi-week programs that introduce habits ONE small
 * step at a time (Fabulous/Atoms style) instead of dumping a blank tracker on a new user. Fully offline —
 * the whole catalog ships in the app as data; starting a journey creates its habits with staggered start
 * dates, so each "unlocks" on its day. No content API, no network.
 */
object HabitJourneys {

    data class Step(
        val name: String, val emoji: String, val dayOffset: Int,   // days after starting the journey until it unlocks
        val target: Int = 1, val unit: String? = null,
        val why: String = "",                                       // the science/idea, shown when it unlocks
    )
    data class Journey(val key: String, val name: String, val emoji: String, val blurb: String, val steps: List<Step>)

    val ALL: List<Journey> = listOf(
        Journey("morning", "Morning Routine", "🌅",
            "Stack a calm, capable start — one tiny anchor at a time. By week two it runs itself.",
            listOf(
                Step("Drink a glass of water", "💧", 0, 1, "glasses", "An easy anchor right after waking — the first vote for the day."),
                Step("Make the bed", "🛏️", 2, why = "A two-minute keystone win; one finished task begets others."),
                Step("2 minutes of stretching", "🧘", 4, why = "Start absurdly small — showing up beats intensity (two-minute rule)."),
                Step("Plan today's top 3", "📝", 7, why = "Implementation intentions: decide the day before you're pulled into it."),
                Step("5-minute walk outside", "🚶", 11, why = "Morning light anchors your body clock and lifts energy."),
            )),
        Journey("sleep", "Better Sleep", "😴",
            "Wind down on purpose. Small evening cues compound into deeper, earlier sleep.",
            listOf(
                Step("Set a wind-down alarm", "⏰", 0, why = "A prompt is the missing piece — let the phone remember, not you."),
                Step("Screens off 30 min before bed", "📵", 3, why = "Add friction to the loop that keeps you up; make the bad cue harder."),
                Step("Dim the lights after 9pm", "🌙", 6, why = "Environment design — lower light tells your body it's night."),
                Step("Same bedtime tonight", "🛌", 10, why = "A consistent cue is what turns sleep into an automatic routine."),
                Step("2 minutes of reading", "📖", 14, why = "A calm replacement for the scroll — same wind-down, better routine."),
            )),
        Journey("move", "Move Every Day", "🏃",
            "Build the identity of someone who moves — starting so small it's impossible to skip.",
            listOf(
                Step("1 push-up (yes, one)", "💪", 0, 1, "reps", "Two-minute rule: make it too small to fail, then let it grow."),
                Step("Stretch after coffee", "☕", 3, why = "Habit stacking — anchor movement to something you already do."),
                Step("10-minute walk", "🚶", 6, 10, "min", "Movement you'll actually repeat beats a workout you'll dread."),
                Step("Take the stairs", "🪜", 10, why = "Design the environment so the healthy choice is the default one."),
                Step("15-minute workout", "🏋️", 14, 15, "min", "By now the identity is forming — each session is a vote for it."),
            )),
        Journey("focus", "Deep Focus", "🎯",
            "Reclaim attention in small, repeatable blocks — and make distraction harder.",
            listOf(
                Step("One 10-minute focus block", "⏳", 0, 10, "min", "Start with a block you can't talk yourself out of."),
                Step("Phone in another room while working", "📴", 3, why = "Friction beats willpower — put the temptation out of reach."),
                Step("Single-task for 25 minutes", "🍅", 7, 25, "min", "One thing at a time; the timer is the commitment device."),
                Step("Plan tomorrow's deep work", "🗓️", 11, why = "Decide the what and when in advance to protect the block."),
            )),
        Journey("calm", "Calmer Mind", "🌿",
            "A gentle path to a steadier day — tiny practices that add up.",
            listOf(
                Step("3 slow breaths", "🌬️", 0, 3, "breaths", "The smallest possible start — a reset you can do anywhere."),
                Step("Name one good thing today", "🙏", 3, why = "A daily win-note reframes attention toward what's working."),
                Step("2-minute sit", "🧘", 7, 2, "min", "Consistency over length — two automatic minutes beats a rare hour."),
                Step("Evening reflection", "📔", 12, why = "Close the loop: what helped, what got in the way, what's next."),
            )),
    )

    fun byKey(key: String): Journey? = ALL.firstOrNull { it.key == key }
}
