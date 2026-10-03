package com.wkhan.hexis.bridge

/**
 * Capability ids. A capability is a versioned, self-describing data contract carried over the fixed
 * spine — never a bespoke AIDL interface. This is what keeps the system modular across many addons.
 */
object Capabilities {
    const val VOICE_STT = "voice.stt"

    /** Scoped read/write access to the core's own data (tasks, notes, …) — served by the CORE as provider
     *  to a consumer addon (e.g. the web bridge). See [com.wkhan.hexis.bridge.data.DataApi]. */
    const val DATA = "data"
}

/**
 * OAuth-style least-privilege scopes the core enforces per call against the caller's scoped token.
 *
 * Many capabilities need none: a live STT addon holds only [VOICE_STT_LISTEN] (permission to be
 * invoked and to receive ephemeral vocabulary hints) and gets no read/write access to core data.
 */
object BridgeScopes {
    const val VOICE_STT_LISTEN = "voice.stt.listen"
    const val CAPTURE_WRITE = "capture.write"
    const val TASKS_READ = "tasks.read"
    const val TASKS_WRITE = "tasks.write"
    const val EXPORT_ENCRYPTED = "export.encrypted"

    // `data` capability — per-domain, read vs write, default-deny. The consumer (e.g. the web bridge) is
    // granted exactly the subset the user picks; the core's handler enforces these per request.
    const val DATA_TASKS_READ = "data.tasks.read"
    const val DATA_TASKS_WRITE = "data.tasks.write"
    const val DATA_NOTES_READ = "data.notes.read"
    const val DATA_NOTES_WRITE = "data.notes.write"
    const val DATA_CALENDAR_READ = "data.calendar.read"
    const val DATA_CALENDAR_WRITE = "data.calendar.write"
    const val DATA_TIME_READ = "data.time.read"
    const val DATA_TIME_WRITE = "data.time.write"
    const val DATA_HABITS_READ = "data.habits.read"
    const val DATA_HABITS_WRITE = "data.habits.write"

    /** All read scopes — convenience for a read-only grant. */
    val DATA_ALL_READ = setOf(DATA_TASKS_READ, DATA_NOTES_READ, DATA_CALENDAR_READ, DATA_TIME_READ, DATA_HABITS_READ)

    /** Every data scope — convenience for a full grant. */
    val DATA_ALL = DATA_ALL_READ + setOf(
        DATA_TASKS_WRITE, DATA_NOTES_WRITE, DATA_CALENDAR_WRITE, DATA_TIME_WRITE, DATA_HABITS_WRITE,
    )
}
