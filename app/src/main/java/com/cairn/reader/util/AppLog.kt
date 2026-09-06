package com.cairn.reader.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny, privacy-first diagnostics log. Every warning/error goes to Logcat AND is appended to a
 * small rotating file in the app's private storage, so a field problem leaves a trace the user can
 * read or export from Settings — without any crash-reporting SDK and without anything leaving the
 * device. Failures are recorded here instead of being swallowed silently by `runCatching`.
 */
object AppLog {
    private const val TAG = "Cairn"
    private const val MAX_BYTES = 256 * 1024L      // cap; rotated to a single .1 backup
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var dir: File? = null

    /** Wire the on-disk sink once, from Application.onCreate. Logcat works even before this. */
    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { runCatching { mkdirs() } }
    }

    fun d(msg: String) { Log.d(TAG, msg) }

    fun w(msg: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)
        write("W", msg, t)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        write("E", msg, t)
    }

    /** The current log file, or null if [init] hasn't run or the file doesn't exist yet. */
    fun currentFile(): File? = dir?.let { File(it, "app.log") }?.takeIf { it.exists() }

    /** Whole log (current + backup), newest section last. For the in-app diagnostics view. */
    fun dump(): String {
        val d = dir ?: return ""
        val back = File(d, "app.log.1").takeIf { it.exists() }?.readText().orEmpty()
        val cur = File(d, "app.log").takeIf { it.exists() }?.readText().orEmpty()
        return (back + cur).trim()
    }

    fun clear() {
        val d = dir ?: return
        runCatching { File(d, "app.log").delete(); File(d, "app.log.1").delete() }
    }

    private fun write(level: String, msg: String, t: Throwable?) {
        val d = dir ?: return
        runCatching {
            val f = File(d, "app.log")
            if (f.length() > MAX_BYTES) {
                val backup = File(d, "app.log.1")
                backup.delete(); f.renameTo(backup)
            }
            val line = buildString {
                append(ts.format(Date())).append(' ').append(level).append(' ').append(msg).append('\n')
                if (t != null) append(Log.getStackTraceString(t)).append('\n')
            }
            f.appendText(line)
        }
    }
}

/** Log-and-continue: like `getOrNull()`, but the failure is recorded instead of vanishing. */
inline fun <T> Result<T>.orLog(context: String): T? =
    onFailure { AppLog.w(context, it) }.getOrNull()

/** Log-and-default: like `getOrDefault`, but the failure is recorded. */
inline fun <T> Result<T>.orLog(context: String, default: T): T =
    onFailure { AppLog.w(context, it) }.getOrDefault(default)
