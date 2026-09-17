package com.todocompanion.app.data.security

import android.os.Build
import java.io.File

/**
 * SEC (Batch 6) — a best-effort, offline read of whether the OS itself undercuts the app's at-rest
 * guarantees. Kairo's threat model is explicit that a rooted or compromised OS is out of scope (code
 * running as root can ask the KeyStore to unwrap keys the moment the app does), and an emulator usually
 * has a software-only keystore. Rather than pretend otherwise, we detect the common cases with simple,
 * permission-free heuristics and surface a one-line honest advisory in Settings → Security.
 *
 * These are HEURISTICS, not a security boundary — determined root hides from all of them. They exist to
 * inform an honest user, never to gate functionality. Nothing here talks to a network or reads another
 * app's data.
 */
object SecurityAdvisory {

    /** Common paths a `su` binary lives at on a rooted device. */
    private val SU_PATHS = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/app/Superuser.apk", "/data/adb/magisk", "/data/adb/ksu",
        "/system/bin/.ext/.su", "/system/xbin/mu",
    )

    /** True if the device looks rooted (test-keys build, or a `su`/Magisk artifact on disk). Heuristic. */
    fun looksRooted(): Boolean {
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true
        return SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    /** True if the app appears to be running on an emulator. Heuristic (fingerprint / hardware / model). */
    fun looksEmulator(): Boolean {
        val fp = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val product = Build.PRODUCT ?: ""
        val hardware = Build.HARDWARE ?: ""
        val brand = Build.BRAND ?: ""
        return fp.startsWith("generic") || fp.startsWith("unknown") || fp.contains("emulator", true) ||
            model.contains("google_sdk") || model.contains("Emulator") || model.contains("Android SDK built for") ||
            product.contains("sdk") || product.contains("emulator") ||
            hardware in setOf("goldfish", "ranchu", "vbox86") ||
            (brand.startsWith("generic") && Build.DEVICE.orEmpty().startsWith("generic"))
    }

    /** A short advisory to show in Settings → Security, or null when the environment looks normal. */
    fun advisory(): String? = when {
        looksRooted() -> "This device looks rooted. On a rooted OS, software running as root can ask the " +
            "KeyStore to unwrap keys while the app is unlocked, so at-rest encryption can't fully protect " +
            "your data. Keep a JSON backup somewhere safe."
        looksEmulator() -> "This looks like an emulator. Emulators usually have a software-only keystore, so " +
            "the hardware-backed protections don't apply here."
        else -> null
    }
}
