package app.parley.data.people

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import app.parley.common.people.ExitReport
import app.parley.common.people.Reports
import app.parley.common.security.Bounded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Local crash capture. When "Keep crash reports" is on, the last uncaught exception is written to Parley's private
 * storage (before the process dies) and offered on the next start: save it with Save as (exception messages left out,
 * numbers and email addresses masked), or dismiss it. Nothing is sent by Parley, which has no internet access.
 * Off by default in release builds, on in debug builds (testers need the first crash); turning it off deletes a stored
 * report.
 *
 * Independently of the switch, [stopSinceLastRun] asks Android (11 and later) whether Parley crashed or stopped
 * responding since the last start, so even a first crash with capture off leaves a report: nothing is stored for it
 * beforehand, only the time of the last stop already offered.
 */
class CrashStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("crash_capture", Context.MODE_PRIVATE)
    private val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val _enabled = MutableStateFlow(prefs.getBoolean(K_ENABLED, debuggable))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply { if (!on) remove(K_LAST) }.apply()
        _enabled.value = on
    }

    /** Chains a handler that stores the crash, then lets the previous handler (the system's) do its work. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { if (prefs.getBoolean(K_ENABLED, debuggable)) store(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun store(thread: Thread, error: Throwable) {
        val o = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("th", thread.name)
            // Class names and frames only: an exception's message can hold a number or a name.
            .put("s", Reports.trimStack(Reports.scrubbedStack(error)))
            .put("v", version)
            .put("a", "$android, $device")
        // commit(): the process is about to die, an asynchronous write could be lost.
        prefs.edit().putString(K_LAST, o.toString()).commit()
    }

    /** The stored crash, if any. */
    fun last(): Reports.Crash? = prefs.getString(K_LAST, null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            Reports.Crash(o.getLong("t"), o.optString("th"), o.optString("s"), o.optString("v"), o.optString("a"))
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(K_LAST).apply()
    }

    private val android get() = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    private val device get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val version get() = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    /**
     * The report of a crash or ANR Android recorded since the last start ([ExitReport]), offered once: the next call
     * returns null. On the very first start nothing is offered (stops from before can't be told apart). A captured
     * crash ([last]) lends its stack to a crash; an ANR's stack is the main thread from Android's trace. Null before
     * Android 11, where [last] alone tells.
     */
    @Suppress("TooGenericExceptionCaught") // Android's exit records are best effort: no report rather than a crash at start.
    fun stopSinceLastRun(formatTime: (Long) -> String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = app.getSystemService(ActivityManager::class.java) ?: return null
            val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, MAX_EXITS)
            val seen = prefs.getLong(K_SEEN, -1L)
            val newestTime = exits.maxOfOrNull { it.timestamp } ?: 0L
            prefs.edit().putLong(K_SEEN, maxOf(seen, newestTime, if (seen < 0) System.currentTimeMillis() else 0L)).apply()
            if (seen < 0) return null
            val exit = ExitReport.newest(exits.map { it.timestamp to it.reason }, seen) ?: return null
            val stack = when (exit.kind) {
                ExitReport.Kind.ANR -> exits.firstOrNull { it.timestamp == exit.time }?.traceInputStream?.use { input ->
                    // Bounded: only the main thread's block near the top is wanted, never the whole dump in memory.
                    val lines = Bounded.LineReader(Bounded.stream(input, MAX_TRACE, "trace").bufferedReader())
                    runCatching { ExitReport.anrStack(lines.lineSequence()) }.getOrNull()
                }
                ExitReport.Kind.CRASH -> last()?.takeIf { kotlin.math.abs(it.time - exit.time) < SAME_CRASH_MS }?.stack
                ExitReport.Kind.NATIVE_CRASH -> null
            }
            ExitReport.text(exit, stack, version, android, device, formatTime(exit.time))
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_LAST = "last"
        const val K_SEEN = "exit_seen"
        const val MAX_EXITS = 8
        const val MAX_TRACE = 8L shl 20

        /** A captured crash this close to Android's record of it is the same one. */
        const val SAME_CRASH_MS = 60_000L
    }
}
