package app.parley.data.people

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.data.StatePrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** "Export diagnostics" says where an error happened and of what kind, never its message (it may name someone). */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun recentErrorsKeepNoMessage() {
        val d = Diagnostics(app)
        d.record("SIM write", IllegalStateException("Couldn't save Ada Lovelace to the SIM"))
        val report = d.report(AppSettings(), PeopleSettings(), emptyMap(), mask = false)
        assertTrue(report.contains("SIM write: IllegalStateException"))
        assertFalse(report.contains("Ada"))
    }

    @Test fun anErrorStoredWithItsMessageBeforeIsShownWithout() {
        StatePrefs.open(app, "diagnostics").edit()
            .putString("errors", """[{"t":1,"w":"Import","e":"IOException: Grace Hopper.vcf"}]""").commit()
        val report = Diagnostics(app).report(AppSettings(), PeopleSettings(), emptyMap(), mask = false)
        assertFalse(report, report.contains("Grace"))
    }

    // "Keep diagnostics" off: nothing is recorded or stored.
    @Test fun whileOffNothingIsRecorded() {
        val d = Diagnostics(app) { false }
        assertFalse(d.kept())
        d.record("SIM write", IllegalStateException("boom"))
        val report = d.report(AppSettings(), PeopleSettings(), emptyMap(), mask = false)
        assertTrue(report, report.contains("None recorded"))
        // Nothing reached the store.
        assertTrue(StatePrefs.open(app, "diagnostics").getString("errors", "").isNullOrEmpty())
    }

    // Turning it on starts collection from then; turning it off and clearing forgets what was kept.
    @Test fun turningOnRecordsAndTurningOffClears() {
        var on = false
        val d = Diagnostics(app) { on }
        d.record("before", IllegalStateException("x"))
        assertFalse(d.report(AppSettings(), PeopleSettings(), emptyMap(), mask = false).contains("before"))

        on = true
        d.record("after", IllegalStateException("y"))
        assertTrue(d.report(AppSettings(), PeopleSettings(), emptyMap(), mask = false).contains("after"))

        on = false
        d.clear()
        val report = d.report(AppSettings(), PeopleSettings(), emptyMap(), mask = false)
        assertFalse(report, report.contains("after"))
        assertTrue(report, report.contains("None recorded"))
    }
}
