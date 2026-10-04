package app.parley.ui

import android.content.Context
import android.content.Intent

/**
 * Starts [intent], Parley's one way to hand off to another app or a system screen. False when nothing takes it or the
 * target refuses (no app, a disabled app, a settings page the phone doesn't have), after showing [missing] if given.
 */
fun Context.startOrSay(intent: Intent, missing: CharSequence? = null): Boolean = try {
    startActivity(intent)
    true
} catch (_: RuntimeException) {
    // ActivityNotFoundException and SecurityException, and whatever else a vendor's target throws back.
    if (missing != null) showMessage(this, missing)
    false
}
