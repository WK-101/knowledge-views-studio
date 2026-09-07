package com.obliviate.app.core.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional root support. Detection is passive (checks for an `su` binary) so it
 * never triggers a root prompt on its own. The only root command wired to a
 * button is `fstrim`, which is non-destructive: it asks the storage controller
 * to TRIM already-free blocks (helping it erase them sooner). Destructive raw
 * wipes (dd/blkdiscard over the userdata block device) are intentionally NOT
 * one-tap — they are documented in docs/ROOT_MODE.md instead, because a wrong
 * device path can brick a phone and cannot be tested from CI.
 */
object RootManager {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/vendor/bin/su", "/system/sbin/su", "/data/local/xbin/su",
        "/data/local/bin/su", "/magisk/.core/bin/su", "/debug_ramdisk/su",
    )

    data class CommandResult(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /** Passive check — does not invoke `su`, so it won't pop a root prompt. */
    fun isRootAvailable(): Boolean = SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    /** Runs a command via `su -c`. This DOES trigger the device's root prompt. */
    suspend fun runAsRoot(command: String, timeoutSeconds: Long = 120): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@withContext CommandResult(-1, output + "\n[timed out]")
                }
                CommandResult(process.exitValue(), output.trim())
            } catch (e: Exception) {
                CommandResult(-1, "Failed to run as root: ${e.message}")
            }
        }

    /** Non-destructive: TRIM already-free blocks so the controller can erase them. */
    suspend fun fstrim(): CommandResult =
        runAsRoot("fstrim -v /data 2>&1; fstrim -v /storage/emulated/0 2>&1; true")
}
