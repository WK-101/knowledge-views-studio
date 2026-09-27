package app.parley

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode

/**
 * Debug builds only: log disk and network access on the main thread and resources that are never closed, so work
 * that belongs on a background dispatcher shows up in logcat during development. Logs only; it never crashes the app
 * or changes behaviour, and release builds (not debuggable) skip it.
 */
internal object DebugStrictMode {
    fun install(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }
}
