package app.parley.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.parley.baselineprofile.Journeys.grantPermissions
import app.parley.baselineprofile.Journeys.openContactPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A contact's page opened from outside (another app's "view contact"), from a cold process, with 3000 contacts and
 * 3000 calls: time to the first frame and the page's frames while it loads and scrolls.
 */
@RunWith(AndroidJUnit4::class)
class ContactPageBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun openNoProfile() = open(CompilationMode.None())

    @Test fun openBaselineProfile() = open(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun open(mode: CompilationMode) {
        BenchmarkData.ensure()
        rule.measureRepeated(
            packageName = PARLEY,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = {
                grantPermissions()
                pressHome()
            },
        ) {
            openContactPage()
        }
    }
}
