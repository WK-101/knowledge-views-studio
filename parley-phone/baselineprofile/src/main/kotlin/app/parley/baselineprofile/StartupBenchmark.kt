package app.parley.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.parley.baselineprofile.Journeys.grantPermissions
import app.parley.baselineprofile.Journeys.openRecents
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold start to Recents with 3000 calls and 3000 contacts, without and with the baseline profile. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun coldStartNoProfile() = start(CompilationMode.None())

    @Test fun coldStartBaselineProfile() = start(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun start(mode: CompilationMode) {
        BenchmarkData.ensure()
        rule.measureRepeated(
            packageName = PARLEY,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = {
                grantPermissions()
                pressHome()
            },
        ) {
            openRecents()
        }
    }
}
