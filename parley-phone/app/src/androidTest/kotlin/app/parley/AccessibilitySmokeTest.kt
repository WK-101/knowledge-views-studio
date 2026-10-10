package app.parley

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's Accessibility Test Framework over Parley's first screen on a real phone or emulator (labels, touch
 * targets, contrast, duplicate descriptions), through Espresso's `AccessibilityChecks`. Off by default: it runs only
 * when asked for, with the checks' library on the test classpath:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.a11yChecks=true
 * ```
 *
 * The library (`androidx.test.espresso:espresso-accessibility`) is found by name, so the suite compiles and the other
 * smoke tests run without it; asked for without it, this test fails and says what to add. The Robolectric smoke tests
 * run Parley's own checks on every build (`A11yChecks` in the unit tests).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilitySmokeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun theFirstScreenPassesTheAccessibilityChecks() {
        assumeTrue("Accessibility checks are off (a11yChecks=true turns them on)", enabled())
        val validator = validator()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), TIMEOUT_MS))
            device.waitForIdle()
            // Throws with every error the framework finds in the window's whole hierarchy.
            scenario.onActivity { activity -> check(validator, activity.window.decorView) }
        }
    }

    private fun enabled(): Boolean = InstrumentationRegistry.getArguments().getString(ARGUMENT).toBoolean()

    /** `AccessibilityChecks.enable().setRunChecksFromRootView(true)`, by name (the library is optional). */
    private fun validator(): Any {
        val checks = try {
            Class.forName(CHECKS_CLASS)
        } catch (_: ClassNotFoundException) {
            throw AssertionError("a11yChecks=true needs androidTestImplementation(\"androidx.test.espresso:espresso-accessibility\") in app/build.gradle.kts")
        }
        val validator = checks.getMethod("enable").invoke(null) ?: throw AssertionError("AccessibilityChecks.enable() gave nothing")
        validator.javaClass.getMethod("setRunChecksFromRootView", Boolean::class.javaPrimitiveType).invoke(validator, true)
        return validator
    }

    private fun check(validator: Any, root: View) {
        try {
            // checkAndReturnResults(View) in current versions, check(View) in older ones: both throw on an error.
            val method = listOf("checkAndReturnResults", "check").firstNotNullOfOrNull { name ->
                runCatching { validator.javaClass.getMethod(name, View::class.java) }.getOrNull()
            } ?: throw AssertionError("This AccessibilityChecks version has no check(View)")
            method.invoke(validator, root)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** The instrumentation argument that turns the checks on. */
        const val ARGUMENT = "a11yChecks"

        const val CHECKS_CLASS = "androidx.test.espresso.accessibility.AccessibilityChecks"
    }
}
