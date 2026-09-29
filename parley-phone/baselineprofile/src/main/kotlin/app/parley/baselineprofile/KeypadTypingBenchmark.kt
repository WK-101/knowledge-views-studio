package app.parley.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.parley.baselineprofile.Journeys.grantPermissions
import app.parley.baselineprofile.Journeys.openKeypad
import app.parley.baselineprofile.Journeys.typeOnKeypad
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame timing while typing a number on the keypad with 3000 contacts to search. */
@RunWith(AndroidJUnit4::class)
class KeypadTypingBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun typeNoProfile() = type(CompilationMode.None())

    @Test fun typeBaselineProfile() = type(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun type(mode: CompilationMode) {
        BenchmarkData.ensure()
        rule.measureRepeated(
            packageName = PARLEY,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                grantPermissions()
                openKeypad()
            },
        ) {
            typeOnKeypad()
        }
    }
}
