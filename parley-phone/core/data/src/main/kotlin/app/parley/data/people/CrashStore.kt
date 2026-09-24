package app.parley.data.people

import android.content.Context
import android.os.Build
import app.parley.common.people.Reports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

/**
 * U10: opt-in local crash capture. When "Keep crash reports" is on, the last uncaught exception is written to
 * Parley's private storage (before the process dies) and offered on the next start: share it by e-mail or any app
 * (numbers and e-mail addresses masked), or dismiss it. Nothing is sent by Parley, which has no internet access.
 * Off by default; turning it off deletes a stored report.
 */
class CrashStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("crash_capture", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(K_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply { if (!on) remove(K_LAST) }.apply()
        _enabled.value = on
    }

    /** Chains a handler that stores the crash, then lets the previous handler (the system's) do its work. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { if (prefs.getBoolean(K_ENABLED, false)) store(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun store(thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        val o = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("th", thread.name)
            .put("s", Reports.trimStack(sw.toString()))
            .put("v", version)
            .put("a", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
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

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_LAST = "last"
    }
}
