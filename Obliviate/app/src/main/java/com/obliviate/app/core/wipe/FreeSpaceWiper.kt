package com.obliviate.app.core.wipe

import android.content.Context
import android.os.StatFs
import android.system.Os
import com.obliviate.app.core.deleteContents
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Best-effort free-space overwriter for unrooted Android.
 *
 * It fills the accessible free space of the chosen volume(s) with fill data
 * (random or zeros), forces it to physical storage with fsync, optionally
 * sample-reads it back to confirm it persisted, then deletes it. This overwrites
 * the logically-freed blocks of previously-deleted files on that filesystem,
 * defeating ordinary "undelete" / file-carving recovery.
 *
 * Honesty note: on NAND flash (all phones) the Flash Translation Layer performs
 * wear-leveling and keeps spare/over-provisioned cells, so this cannot GUARANTEE
 * that every physical remnant is destroyed. For disposing of a device, a factory
 * reset (cryptographic erase) is the reliable method — see the in-app Dispose /
 * About screens and docs/ROBUSTNESS.md.
 */
object FreeSpaceWiper {

    private const val TMP_DIR = "obliviate_wipe_tmp"
    private const val CHUNK_BYTES = 512L * 1024 * 1024      // one fill file at a time
    private const val BUFFER_BYTES = 1 * 1024 * 1024        // 1 MB write buffer
    private const val REFRESH_BUFFERS = 64                  // re-randomize buffer every ~64 MB
    private const val PROGRESS_INTERVAL_MS = 400L

    private const val VERIFY_SAMPLE_BYTES = 32L * 1024 * 1024 // cap read-back at 32 MB/pass
    private const val VERIFY_WINDOW = 256 * 1024              // 256 KB sample windows

    private val random = SecureRandom()

    fun resolveBaseDir(context: Context, target: WipeTarget): File = when (target) {
        WipeTarget.INTERNAL -> context.filesDir
        WipeTarget.SHARED -> context.getExternalFilesDir(null) ?: context.filesDir
        WipeTarget.BOTH -> context.filesDir
    }

    /** Concrete base directories to wipe, de-duplicated by underlying filesystem. */
    private fun resolveTargets(context: Context, target: WipeTarget): List<File> {
        val candidates = when (target) {
            WipeTarget.INTERNAL -> listOf(context.filesDir)
            WipeTarget.SHARED -> listOfNotNull(context.getExternalFilesDir(null) ?: context.filesDir)
            WipeTarget.BOTH -> listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
        }
        val seenDevices = HashSet<Long>()
        val result = ArrayList<File>()
        for (dir in candidates) {
            val dev = try { Os.stat(dir.absolutePath).st_dev } catch (e: Exception) { -1L }
            // -1 means we couldn't stat it; keep it rather than risk dropping a real volume.
            if (dev == -1L || seenDevices.add(dev)) result.add(dir)
        }
        return result
    }

    fun availableBytes(dir: File): Long = try {
        StatFs(dir.absolutePath).availableBytes
    } catch (e: Exception) {
        0L
    }

    /**
     * Runs the wipe across all resolved volumes. [onProgress] is invoked
     * periodically. Cancellation is cooperative; fill files are always cleaned up.
     */
    suspend fun wipe(
        context: Context,
        config: WipeConfig,
        onProgress: (WipeProgress) -> Unit,
    ): WipeResult {
        val targets = resolveTargets(context, config.target)
        val passes = config.method.passes
        val volumeCount = targets.size

        // Prepare a clean tmp dir per volume and estimate total work.
        val tmps = targets.map { base ->
            File(base, TMP_DIR).apply { mkdirs(); deleteContents(this) }
        }
        val estTotal = targets
            .sumOf { (availableBytes(it) - config.keepFreeBytes).coerceAtLeast(0L) }
            .times(passes)
            .coerceAtLeast(1L)

        onProgress(WipeProgress(WipePhase.PREPARING, 1, passes, 0L, estTotal, 0L, 1, volumeCount))

        val startTime = System.currentTimeMillis()
        var cumulative = 0L
        var windowStart = startTime
        var windowBytes = 0L
        var speed = 0L
        var verifiedTotal = 0L
        var mismatchTotal = 0

        try {
            targets.indices.forEach { vi ->
                val tmp = tmps[vi]
                val volume = vi + 1

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
                                            WipeProgress(
                                                WipePhase.FILLING, pass, passes, cumulative,
                                                estTotal, speed, volume, volumeCount
                                            )
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

                    // Optional read-back verification before we release the space.
                    if (config.verify) {
                        onProgress(
                            WipeProgress(
                                WipePhase.VERIFYING, pass, passes, cumulative,
                                estTotal, speed, volume, volumeCount
                            )
                        )
                        val (verified, mismatches) = verifyFill(tmp, zero)
                        verifiedTotal += verified
                        mismatchTotal += mismatches
                    }

                    // Free the space again before the next pass / volume (and at the end).
                    onProgress(
                        WipeProgress(
                            WipePhase.DELETING, pass, passes, cumulative,
                            estTotal, speed, volume, volumeCount
                        )
                    )
                    deleteContents(tmp)
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            onProgress(WipeProgress(WipePhase.DONE, passes, passes, cumulative, estTotal, speed, volumeCount, volumeCount))
            return WipeResult(cumulative, passes, elapsed, verifiedTotal, mismatchTotal, volumeCount)
        } finally {
            // Guarantee cleanup on success, cancel, or crash.
            tmps.forEach { tmp ->
                deleteContents(tmp)
                tmp.delete()
            }
        }
    }

    /**
     * Samples the fill files and confirms they read back as the expected pattern.
     * Returns (bytesSampled, windowMismatches). This verifies the write reached
     * the logical storage layer; it does not (and cannot) verify physical cells.
     */
    private suspend fun verifyFill(tmp: File, zero: Boolean): Pair<Long, Int> {
        val files = tmp.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: return 0L to 0
        val window = ByteArray(VERIFY_WINDOW)
        var verified = 0L
        var mismatches = 0

        for (file in files) {
            coroutineContext.ensureActive()
            if (verified >= VERIFY_SAMPLE_BYTES) break
            val len = file.length()
            if (len <= 0) continue
            val offsets = longArrayOf(
                0L,
                (len / 2 - VERIFY_WINDOW).coerceAtLeast(0L),
                (len - VERIFY_WINDOW).coerceAtLeast(0L),
            )
            val raf = RandomAccessFile(file, "r")
            try {
                for (off in offsets) {
                    if (verified >= VERIFY_SAMPLE_BYTES) break
                    raf.seek(off)
                    val read = raf.read(window)
                    if (read <= 0) continue
                    verified += read
                    if (!windowMatches(window, read, zero)) mismatches++
                }
            } finally {
                raf.close()
            }
        }
        return verified to mismatches
    }

    private fun windowMatches(buf: ByteArray, len: Int, zero: Boolean): Boolean {
        if (zero) {
            for (i in 0 until len) if (buf[i].toInt() != 0) return false
            return true
        }
        // Random fill: a window that is all one byte value would be suspicious.
        val first = buf[0]
        for (i in 1 until len) if (buf[i] != first) return true
        return false
    }
}
