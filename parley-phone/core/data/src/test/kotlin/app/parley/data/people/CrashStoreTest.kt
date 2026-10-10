package app.parley.data.people

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/** The start-up card comes from Android's own exit records: once per stop, never for stops before the first run. */
@RunWith(RobolectricTestRunner::class)
class CrashStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val am get() = app.getSystemService(ActivityManager::class.java)

    @Before fun clean() {
        app.getSharedPreferences("crash_capture", 0).edit().clear().commit()
    }

    private fun exit(time: Long, reason: Int, trace: String? = null) {
        val b = ApplicationExitInfoBuilder.newBuilder().setProcessName(app.packageName).setTimestamp(time).setReason(reason)
        trace?.let { b.setTraceInputStream(it.byteInputStream()) }
        shadowOf(am).addApplicationExitInfo(b.build())
    }

    @Test fun a_crash_since_the_last_run_is_offered_once() {
        exit(1_000L, ApplicationExitInfo.REASON_CRASH)
        val store = CrashStore(app)
        // The first run only notes where it starts: a stop from before can't be told apart.
        assertNull(store.stopSinceLastRun { it.toString() })
        val trace = "\"main\" prio=5 tid=1 Blocked\n  at app.parley.Slow.work(Slow.kt:9)\n\n"
        exit(System.currentTimeMillis() + 5_000L, ApplicationExitInfo.REASON_ANR, trace)
        val report = store.stopSinceLastRun { "then" }
        assertNotNull(report)
        assertTrue(report!!, report.contains("Kind: anr") && report.contains("Device: ") && report.contains("Time: then"))
        assertTrue(report, report.contains("at app.parley.Slow.work(Slow.kt:9)"))
        // Offered: not again.
        assertNull(store.stopSinceLastRun { it.toString() })
    }

    @Test fun a_stop_the_user_asked_for_is_no_crash() {
        val store = CrashStore(app)
        store.stopSinceLastRun { it.toString() }
        exit(System.currentTimeMillis() + 5_000L, ApplicationExitInfo.REASON_USER_REQUESTED)
        assertNull(store.stopSinceLastRun { it.toString() })
    }

    @Test fun capture_is_on_in_debuggable_builds_only() {
        app.applicationInfo.flags = app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        assertTrue(CrashStore(app).enabled.value)
        app.applicationInfo.flags = app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
        assertTrue(!CrashStore(app).enabled.value)
    }
}
