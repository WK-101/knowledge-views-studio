package app.parley.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.parley.baselineprofile.Journeys.grantPermissions
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rings the emulator and waits for Parley's call screen; declines through the emulator again. */
object IncomingCall {
    private const val CALLER = "5550100"

    fun ringAndDecline(scope: MacrobenchmarkScope) {
        EmulatorConsole.ring(CALLER)
        // The call screen or the heads-up: either shows the caller's number.
        scope.device.wait(Until.hasObject(By.textContains("0100")), 10_000)
        scope.device.waitForIdle()
        EmulatorConsole.hangUp(CALLER)
        scope.device.wait(Until.gone(By.textContains("0100")), 10_000)
    }
}

/**
 * The incoming-call path on an emulator with Parley as the default phone app: the time from Telecom adding the call to
 * the ringing notification and from screening to the verdict (Parley's own trace sections), and the frames of the call
 * screen. Skipped unless the console token is given (see docs/PERFORMANCE_BENCHMARKS.md).
 */
@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class IncomingCallBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun incomingNoProfile() = ring(CompilationMode.None())

    @Test fun incomingBaselineProfile() = ring(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun ring(mode: CompilationMode) {
        assumeTrue("Needs an emulator console token (-e consoleToken …)", EmulatorConsole.available)
        rule.measureRepeated(
            packageName = PARLEY,
            metrics = listOf(
                TraceSectionMetric("Parley.addToNotification", TraceSectionMetric.Mode.First),
                TraceSectionMetric("Parley.screenCall", TraceSectionMetric.Mode.First),
                FrameTimingMetric(),
            ),
            compilationMode = mode,
            // The process is killed first each time: an incoming call often starts it cold.
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                grantPermissions()
                pressHome()
            },
        ) {
            IncomingCall.ringAndDecline(this)
        }
    }
}
