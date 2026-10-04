package app.parley.common.ux

/**
 * Settings shown as one choice where two switches depended on each other. What is stored doesn't change: each choice
 * reads and writes the same two values, so backups and older versions read them as before.
 */
enum class SalesLines {
    /** Your calls don't tag anything. */
    OFF,

    /** Numbers that look like sales lines get a quiet tag. */
    TAG,

    /** Tagged, and silenced (contacts, numbers you allow and repeat callers still ring). */
    TAG_AND_SILENCE,
    ;

    val learn: Boolean get() = this != OFF
    val silence: Boolean get() = this == TAG_AND_SILENCE

    companion object {
        /** The silence rule only ever counted while learning was on. */
        fun of(learn: Boolean, silence: Boolean): SalesLines = when {
            !learn -> OFF
            silence -> TAG_AND_SILENCE
            else -> TAG
        }
    }
}

/** "Vibrate during calls": off, on every change (ends, swaps, merges, time limits), or that and when they answer. */
enum class CallVibration {
    OFF,
    CHANGES,
    CHANGES_AND_ANSWER,
    ;

    val haptics: Boolean get() = this != OFF
    val onConnect: Boolean get() = this == CHANGES_AND_ANSWER

    companion object {
        /** The connect buzz only ever counted while call vibration was on. */
        fun of(haptics: Boolean, onConnect: Boolean): CallVibration = when {
            !haptics -> OFF
            onConnect -> CHANGES_AND_ANSWER
            else -> CHANGES
        }
    }
}
