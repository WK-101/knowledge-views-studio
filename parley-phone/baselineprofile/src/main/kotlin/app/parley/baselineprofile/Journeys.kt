package app.parley.baselineprofile

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** The app under test (the release build and its benchmark variants keep this id). */
const val PARLEY = "app.parley.phone"

private const val WAIT_MS = 5_000L

/**
 * The user journeys the profile and the benchmarks share. Screens are reached through Parley's own intents (the
 * dialer's Recents and keypad), so the journeys don't depend on the UI language or on the tab bar's layout.
 */
object Journeys {
    /** The runtime permissions Parley's lists need, so Recents and Contacts show data instead of an explanation. */
    fun MacrobenchmarkScope.grantPermissions() {
        for (p in listOf("READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_PHONE_STATE", "CALL_PHONE", "POST_NOTIFICATIONS")) {
            device.executeShellCommand("pm grant $packageName android.permission.$p")
        }
    }

    /** Parley opened on Recents (the call-log intent), waiting until the first frame with content. */
    fun MacrobenchmarkScope.openRecents() {
        startActivityAndWait(
            Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/calls").setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        device.wait(Until.hasObject(By.scrollable(true)), WAIT_MS)
    }

    /** Parley opened on the keypad (ACTION_DIAL). */
    fun MacrobenchmarkScope.openKeypad() {
        startActivityAndWait(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Flings the main list down and back up. */
    fun MacrobenchmarkScope.flingRecents() {
        val list = device.findObject(By.scrollable(true)) ?: return
        // Keep clear of the gesture navigation area at the edges.
        list.setGestureMargin(device.displayWidth / 5)
        repeat(3) { list.fling(Direction.DOWN) }
        repeat(3) { list.fling(Direction.UP) }
        device.waitForIdle()
    }

    /** Types a number on the keypad (hardware key events, which the keypad accepts) and clears it again. */
    fun MacrobenchmarkScope.typeOnKeypad(digits: String = "5550123") {
        for (d in digits) {
            device.pressKeyCode(KeyEvent.KEYCODE_0 + (d - '0'))
            device.waitForIdle()
        }
        repeat(digits.length) { device.pressKeyCode(KeyEvent.KEYCODE_DEL) }
        device.waitForIdle()
    }
}
