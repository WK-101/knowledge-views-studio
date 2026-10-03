package app.parley.data.people

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
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
        app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit()
            .putString("errors", """[{"t":1,"w":"Import","e":"IOException: Grace Hopper.vcf"}]""").commit()
        val report = Diagnostics(app).report(AppSettings(), PeopleSettings(), emptyMap(), mask = false)
        assertFalse(report, report.contains("Grace"))
    }
}
