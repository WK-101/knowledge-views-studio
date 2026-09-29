package app.parley.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.parley.baselineprofile.Journeys.flingRecents
import app.parley.baselineprofile.Journeys.grantPermissions
import app.parley.baselineprofile.Journeys.openContactPage
import app.parley.baselineprofile.Journeys.openKeypad
import app.parley.baselineprofile.Journeys.openRecents
import app.parley.baselineprofile.Journeys.typeOnKeypad
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes app/src/release/generated/baselineProfiles/baseline-prof.txt (and the startup profile) for the paths people
 * wait on: start-up to Recents, scrolling it, a contact's page, typing on the keypad, and the incoming-call screen when an
 * emulator console token is given. Keep the journeys here in step with the app's critical paths (see
 * docs/PERFORMANCE_BENCHMARKS.md). Run `./gradlew :app:generateBaselineProfile` with a device connected.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        BenchmarkData.ensure()
        rule.collect(packageName = PARLEY, includeInStartupProfile = true) {
            grantPermissions()
            pressHome()
            openRecents()
            flingRecents()
            openContactPage()
            openKeypad()
            typeOnKeypad()
            if (EmulatorConsole.available) IncomingCall.ringAndDecline(this)
        }
    }
}
