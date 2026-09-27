package com.wkhan.hexis.bridge

/**
 * Capability ids. A capability is a versioned, self-describing data contract carried over the fixed
 * spine — never a bespoke AIDL interface. This is what keeps the system modular across many addons.
 */
object Capabilities {
    const val VOICE_STT = "voice.stt"
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
}
