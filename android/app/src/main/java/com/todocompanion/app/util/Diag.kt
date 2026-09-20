package com.todocompanion.app.util

import android.content.Context
import java.io.File

/**
 * TEMP-DIAG — temporary on-device diagnostics for verifying the W0–W3 plan work landed correctly on real
 * hardware (that MIGRATION_83_84 runs on a real v83→v84 upgrade, that the index-backed notes query and the
 * unified reminder model produce correct data on real content).
 *
 * Mirrors the existing crash-report mechanism (App.kt → last_crash.txt → LastCrashDialog): lines are
 * appended to a file the user can read and COPY from inside the app via [ui.AppRoot]'s DiagDialog — no adb.
 *
 * REMOVAL: every temporary call site is tagged `// TEMP-DIAG`. Delete this file, its DiagDialog in AppRoot,
 * the Diag.attach/gather block in App.kt, and grep the tree for `TEMP-DIAG` to strip the rest.
 */
object Diag {
    const val FILE_NAME = "diag.txt"

    @Volatile private var file: File? = null
    private val pending = StringBuilder()

    private fun fileFor(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FILE_NAME)

    /** Call once at process start (before the DB is opened) so migration-time logs are captured. Starts a
     *  fresh log for this launch and flushes anything buffered before attach. */
    fun attach(ctx: Context) {
        val f = fileFor(ctx)
        synchronized(this) {
            file = f
            runCatching { f.writeText("Kairo diagnostics · ${java.util.Date()}\n") }
            if (pending.isNotEmpty()) { runCatching { f.appendText(pending.toString()) }; pending.clear() }
        }
    }

    fun log(area: String, msg: String) {
        val line = "[$area] $msg\n"
        synchronized(this) {
            val f = file
            if (f != null) runCatching { f.appendText(line) } else pending.append(line)
        }
    }

    fun read(ctx: Context): String = runCatching { fileFor(ctx).let { if (it.exists()) it.readText() else "" } }.getOrDefault("")

    fun clear(ctx: Context) { runCatching { fileFor(ctx).delete() }; synchronized(this) { file = null; pending.clear() } }
}
