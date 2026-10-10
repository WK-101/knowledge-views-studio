package app.parley

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Start-up timings in debuggable builds only (`adb logcat -s ParleyStart`): how long after the view model was made,
 * and after the process started, a screen had what it shows. Release builds log nothing.
 */
object StartTimings {
    private const val TAG = "ParleyStart"

    @Volatile private var enabled = false

    /** Called once by the app: logs only when the build is debuggable. */
    fun install(context: Context) {
        enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    /** Logs [what] with the time since [since] (an uptime in ms) and since the process started. */
    fun log(what: String, since: Long) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        Log.d(TAG, "$what: ${now - since} ms (${now - Process.getStartUptimeMillis()} ms after process start)")
    }
}
