package com.todocompanion.app.util

import android.util.Log

/**
 * TEMP-DIAG — temporary on-device diagnostics for verifying the W0–W3 plan work landed correctly on real
 * hardware (things this build environment has no device to confirm: that MIGRATION_83_84 actually runs on a
 * real v83→v84 upgrade, that the index-backed notes queries and the unified reminder model produce correct
 * data on real content).
 *
 * Capture with:  adb logcat -s KairoDiag
 *
 * REMOVAL: every temporary call site is tagged `// TEMP-DIAG`. Delete this file and grep the tree for
 * `TEMP-DIAG` (and `Diag.`) to strip them once the device run confirms the changes.
 */
object Diag {
    const val TAG = "KairoDiag"
    fun log(area: String, msg: String) { Log.i(TAG, "[$area] $msg") }
}
