package app.parley

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A few seconds on a real phone or emulator: Parley starts, draws its window and survives the intents other apps send
 * it. `./gradlew :app:connectedDebugAndroidTest` with one device connected (see docs/PERFORMANCE_BENCHMARKS.md).
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun theAppStartsAndDrawsItsWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), TIMEOUT_MS))
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test fun aDialIntentOpensWithoutCalling() {
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+15550100")).setClass(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(dial).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), TIMEOUT_MS))
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test fun rotatingKeepsTheAppRunning() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
