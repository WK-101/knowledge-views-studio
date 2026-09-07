package com.obliviate.app.core.wipe

import android.content.Context
import android.os.StatFs
import com.obliviate.app.core.deleteContents
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Best-effort free-space overwriter for unrooted Android.
 *
 * It fills the accessible free space of the chosen volume with fill data (random
 * or zeros), forces it to physical storage with fsync, then deletes it. This
 * overwrites the logically-freed blocks of previously-deleted files on that
 * filesystem, defeating ordinary "undelete" / file-carving recovery.
 *
 * Honesty note: on NAND flash (all phones) the Flash Translation Layer performs
 * wear-leveling and keeps spare/over-provisioned cells, so this cannot GUARANTEE
 * that every physical remnant is destroyed. For disposing of a device, a factory
 * reset (cryptographic erase) is the reliable method — see the in-app About screen.
 */
object FreeSpaceWiper {

    private const val TMP_DIR = "obliviate_wipe_tmp"
    private const val CHUNK_BYTES = 512L * 1024 * 1024      // one fill file at a time
    private const val BUFFER_BYTES = 1 * 1024 * 1024        // 1 MB write buffer
    private const val REFRESH_BUFFERS = 64                  // re-randomize buffer every ~64 MB
    private const val PROGRESS_INTERVAL_MS = 400L

    private val random = SecureRandom()

    fun resolveBaseDir(context: Context, target: WipeTarget): File = when (target) {
        WipeTarget.INTERNAL -> context.filesDir
        WipeTarget.SHARED -> context.getExternalFilesDir(null) ?: context.filesDir
    }

    fun availableBytes(dir: File): Long = try {
        StatFs(dir.absolutePath).availableBytes
    } catch (e: Exception) {
        0L
    }

    /**
     * Runs the wipe. [onProgress] is invoked periodically. Cancellation is
     * cooperative via the coroutine scope; fill files are always cleaned up.
     */
    suspend fun wipe(
        context: Context,
        config: WipeConfig,
        onProgress: (WipeProgress) -> Unit,
    ): WipeResult {
        val base = resolveBaseDir(context, config.target)
        val tmp = File(base, TMP_DIR)
        if (!tmp.exists()) tmp.mkdirs()

        val passes = config.method.passes
        val startAvail = availableBytes(tmp)
        val perPassTarget = (startAvail - config.keepFreeBytes).coerceAtLeast(0L)
        val estTotal = (perPassTarget * passes).coerceAtLeast(1L)

        onProgress(WipeProgress(WipePhase.PREPARING, 1, passes, 0L, estTotal, 0L))

        val startTime = System.currentTimeMillis()
        var cumulative = 0L
        var windowStart = startTime
        var windowBytes = 0L
        var speed = 0L

        try {
            for (pass in 1..passes) {
                val zero = config.method == WipeMethod.ZERO ||
                    (config.method == WipeMethod.DOD && pass == 2)

                val buffer = ByteArray(BUFFER_BYTES)
                if (zero) buffer.fill(0) else random.nextBytes(buffer)
                var buffersSinceRefresh = 0
                var fileIndex = 0
                var stalls = 0

                fill@ while (true) {
                    coroutineContext.ensureActive()
                    val availBefore = availableBytes(tmp)
                    if (availBefore <= config.keepFreeBytes) break@fill
                    val fileTarget = minOf(CHUNK_BYTES, availBefore - config.keepFreeBytes)
                    if (fileTarget <= 0) break@fill

                    val file = File(tmp, String.format(Locale.US, "w_%02d_%05d.bin", pass, fileIndex++))
                    var fileWritten = 0L
                    try {
                        FileOutputStream(file).use { fos ->
                            while (fileWritten < fileTarget) {
                                coroutineContext.ensureActive()
                                val n = minOf(buffer.size.toLong(), fileTarget - fileWritten).toInt()
                                if (!zero) {
                                    if (buffersSinceRefresh >= REFRESH_BUFFERS) {
                                        random.nextBytes(buffer)
                                        buffersSinceRefresh = 0
                                    }
                                    buffersSinceRefresh++
                                }
                                fos.write(buffer, 0, n)
                                fileWritten += n
                                cumulative += n
                                windowBytes += n

                                val now = System.currentTimeMillis()
                                if (now - windowStart >= PROGRESS_INTERVAL_MS) {
                                    speed = windowBytes * 1000 / (now - windowStart).coerceAtLeast(1)
                                    windowStart = now
                                    windowBytes = 0
                                    onProgress(
                                        WipeProgress(WipePhase.FILLING, pass, passes, cumulative, estTotal, speed)
                                    )
                                }
                            }
                            fos.flush()
                            // Force the bytes past the page cache onto the storage device.
                            fos.fd.sync()
                        }
                    } catch (e: IOException) {
                        // Ran out of space (or a transient write error): this pass is done.
                        break@fill
                    }

                    // Robustness guard: if free space isn't actually shrinking (e.g. transparent
                    // filesystem compression on a zero-fill, or a storage quota), stop this pass
                    // rather than looping forever creating files that don't consume space.
                    val availAfter = availableBytes(tmp)
                    if (availBefore - availAfter < fileWritten / 2) {
                        if (++stalls >= 3) break@fill
                    } else {
                        stalls = 0
                    }
                }

                // Free the space again before the next pass (and at the end).
                onProgress(WipeProgress(WipePhase.DELETING, pass, passes, cumulative, estTotal, speed))
                deleteContents(tmp)
            }

            val elapsed = System.currentTimeMillis() - startTime
            onProgress(WipeProgress(WipePhase.DONE, passes, passes, cumulative, estTotal, speed))
            return WipeResult(cumulative, passes, elapsed)
        } finally {
            // Guarantee cleanup on success, cancel, or crash.
            deleteContents(tmp)
            tmp.delete()
        }
    }
}
