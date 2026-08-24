package com.todocompanion.app.domain.priority

/**
 * Bridges TickTick's simple 4-level priority and MLO's importance+urgency.
 * importance/urgency are the stored source of truth; the simple level is a mapping.
 */
enum class PriorityLevel(val label: String) {
    NONE("None"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High");

    /** importance and urgency values this level sets. */
    val importance: Int
        get() = when (this) {
            NONE -> 2; LOW -> 3; MEDIUM -> 4; HIGH -> 5
        }
    val urgency: Int
        get() = when (this) {
            NONE -> 2; LOW -> 3; MEDIUM -> 4; HIGH -> 5
        }

    companion object {
        /** Reverse-map stored importance/urgency to the nearest simple level (for display). */
        fun from(importance: Int, urgency: Int): PriorityLevel {
            val m = maxOf(importance, urgency)
            return when {
                m >= 5 -> HIGH
                m >= 4 -> MEDIUM
                m >= 3 -> LOW
                else -> NONE
            }
        }
    }
}
