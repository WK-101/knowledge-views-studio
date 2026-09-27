package app.parley.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.parley.baselineprofile.Journeys.flingRecents
import app.parley.baselineprofile.Journeys.grantPermissions
import app.parley.baselineprofile.Journeys.openRecents
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame timing while flinging Recents with 3000 calls. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RecentsScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun flingNoProfile() = fling(CompilationMode.None())

    @Test fun flingBaselineProfile() = fling(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun fling(mode: CompilationMode) {
        BenchmarkData.ensure()
        rule.measureRepeated(
            packageName = PARLEY,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                grantPermissions()
                openRecents()
            },
        ) {
            flingRecents()
        }
    }
}
