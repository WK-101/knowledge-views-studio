package app.parley.security

import java.util.WeakHashMap
import android.os.Bundle
import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.parley.common.AppSettings
import app.parley.container
import app.parley.ui.AppLocale
import kotlinx.coroutines.launch

/**
 * Base of every activity another app, the launcher or the system can start (see ExportedComponentsTest, which checks
 * that each exported one extends it or is listed with its reason). It applies the app lock and the window protection
 * the same way everywhere, instead of each entry point making its own, weaker decision:
 * - the lock is decided at start, from the settings in memory when they are loaded (so nothing flashes), and engages
 *   at stop when it is set to lock at once;
 * - "Hide screen content", and the blank recents thumbnail while the app lock is on, follow [AppLock.protectWindow]
 *   at every resume, pause and stop;
 * - [hidesOverlays] entry points (sheets over other apps, pickers that hand data back) hide other apps' overlays on
 *   Android 12+ and ignore touches through them before, against tapjacking.
 *
 * Subclasses show [LockScreen] while [AppLock.locked] and the app lock is on.
 */
abstract class LockedActivity : FragmentActivity() {
    /** Whether the whole window hides other apps' overlays (screens that act for another app). */
    protected open val hidesOverlays: Boolean = false

    // The in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLocale.override(this, newBase)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (hidesOverlays) OverlayGuard.acquire(this)
    }

    /** The settings, once loaded; null before (nothing personal shows until then). */
    protected fun loadedSettings(): AppSettings? = container.settings.takeIf { it.loaded.value }?.settings?.value

    protected fun protectWindow(leaving: Boolean = false) {
        loadedSettings()?.let { AppLock.protectWindow(this, it, leaving) }
    }

    override fun onStart() {
        super.onStart()
        // Before the first frame when the settings are in memory; otherwise as soon as they are read.
        val known = loadedSettings()?.also { AppLock.onStart(it) }
        if (known == null) lifecycleScope.launch { AppLock.onStart(container.settings.current()) }
    }

    override fun onResume() {
        super.onResume()
        protectWindow()
    }

    override fun onPause() {
        // Before Android 13 the recents thumbnail can only be blanked with FLAG_SECURE, set before it's taken.
        protectWindow(leaving = true)
        super.onPause()
    }

    override fun onStop() {
        AppLock.onStop(loadedSettings())
        protectWindow(leaving = true)
        super.onStop()
    }
}

/**
 * Hides other apps' overlays over a window (Android 12+, `HIDE_OVERLAY_WINDOWS`) and, on Android 10 and 11, drops
 * touches that pass through one. Counted per window, so nested sensitive screens release it only when the last goes.
 */
object OverlayGuard {
    private val holds = WeakHashMap<Activity, Int>()

    fun acquire(activity: Activity) {
        val n = (holds[activity] ?: 0) + 1
        holds[activity] = n
        if (n == 1) apply(activity, true)
    }

    fun release(activity: Activity) {
        val n = (holds[activity] ?: 1) - 1
        if (n <= 0) {
            holds.remove(activity)
            apply(activity, false)
        } else {
            holds[activity] = n
        }
    }

    private fun apply(activity: Activity, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) activity.window.setHideOverlayWindows(on)
        activity.window.decorView.filterTouchesWhenObscured = on
    }
}

/**
 * Marks the screen it is placed in as sensitive (unlocking, private contacts, deleting everything, restoring a backup,
 * letting apps read private names, taking back the default phone app): while it shows, other apps can't draw over
 * the window to trick a tap, and touches through an overlay are ignored.
 */
@Composable
fun SensitiveScreen() {
    val activity = LocalActivity.current ?: return
    val view: View = LocalView.current
    DisposableEffect(activity, view) {
        OverlayGuard.acquire(activity)
        // Also the window this screen is drawn in when it is a dialog of its own.
        val root = view.rootView
        val before = root.filterTouchesWhenObscured
        root.filterTouchesWhenObscured = true
        onDispose {
            root.filterTouchesWhenObscured = before
            OverlayGuard.release(activity)
        }
    }
}
