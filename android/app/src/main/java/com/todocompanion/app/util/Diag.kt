package com.todocompanion.app.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.port.Backup
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.domain.view.GroupMode
import com.todocompanion.app.domain.view.ListPipeline
import com.todocompanion.app.domain.view.SortMode
import com.todocompanion.app.domain.view.ViewRef
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.ZoneOffset
import java.util.Date

/**
 * ⚠️ R100–R102 TEMPORARY DIAGNOSTICS — this whole file and its call sites (all tagged `// DIAG`) are
 * removed in R103 once the device-gated items are validated. It adds NO permission and NO network: it
 * only writes a plain-text report to the app's own external files dir, the same folder as last_crash.txt:
 *
 *     Android/data/com.wkhan.kairo/files/kairo_diag.txt
 *
 * Open that with any file manager and send it over. It captures, per run:
 *   (a) STARTUP    — cold-start timing (process → first frame, App.onCreate → first frame)
 *   (b) SELF-CHECK — an R8/minified check of the reflective surfaces (Room+SQLCipher, kotlinx.serialization
 *                    backup round-trip, ZXing) and the two behaviour-preserving core extractions
 *   (c) INSETS     — the edge-to-edge window insets + effective targetSdk (used by R102)
 *   (d) FRAME METRICS — on-device jank distribution (a runtime substitute for a Macrobenchmark scroll pass:
 *                    p50/p90/p99 frame time + janky/severe counts), flushed when the app is backgrounded (R103)
 */
object Diag {
    @Volatile var processStartUptime = 0L
    @Volatile var appOnCreateUptime = 0L
    @Volatile private var startupLogged = false

    private fun file(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "kairo_diag.txt")
    private fun append(c: Context, s: String) { runCatching { file(c).appendText(s) } }

    /** Call at the very top of App.onCreate. */
    fun markAppOnCreate() {
        appOnCreateUptime = SystemClock.uptimeMillis()
        processStartUptime =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Process.getStartUptimeMillis() else appOnCreateUptime
    }

    private fun header(c: Context): String {
        val info = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        val minified = (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
        return buildString {
            appendLine()
            appendLine("── Kairo diagnostics @ ${Date()} ──")
            appendLine("app ${info?.versionName ?: "?"} (vc ${info?.let { it.longVersionCodeCompat() } ?: "?"}) · ${if (minified) "release / minified (R8)" else "debug"}")
            appendLine("device ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · targetSdk ${c.applicationInfo.targetSdkVersion}")
        }
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    /** (a) Cold-start timing — call once when the first frame is drawn. Idempotent. */
    fun logFirstFrame(c: Context) {
        if (startupLogged) return
        startupLogged = true
        val now = SystemClock.uptimeMillis()
        append(c, buildString {
            append(header(c))
            appendLine("STARTUP")
            appendLine("  process → first frame : ${now - processStartUptime} ms")
            appendLine("  App.onCreate → frame  : ${now - appOnCreateUptime} ms")
            appendLine("  (measure COLD: force-stop the app in Settings, then tap the icon. Warm relaunches read much lower.)")
        })
    }

    /** (b) R8 / minified self-check — call once on a background thread after startup. */
    suspend fun runSelfCheck(c: Context, repo: AppRepository) {
        val sb = StringBuilder(header(c))
        sb.appendLine("SELF-CHECK (R8 reflective surfaces + core extractions)")

        // 1. Room + SQLCipher: open the encrypted store and read.
        val tasks = runCatching { repo.allTasks.first() }.getOrNull()
        sb.appendLine("  [${pf(tasks != null)}] Room + SQLCipher open & query — ${tasks?.size ?: "ERROR"} tasks")

        // 2. kotlinx.serialization backup encode→decode round-trip (non-destructive).
        val serOk = runCatching {
            val json = repo.exportJson()
            val back = Backup.decode(json)
            json.isNotBlank() && back.tasks.size == (tasks?.size ?: -1)
        }.getOrDefault(false)
        sb.appendLine("  [${pf(serOk)}] kotlinx.serialization backup encode→decode round-trip")

        // 3. ListPipeline extraction on synthetic input — mirrors the unit test, so it certifies the
        //    extraction still behaves correctly after R8 shrinking/renaming at runtime.
        val lpOk = runCatching {
            val t = listOf(syntheticTask("keep", "l1"), syntheticTask("gone", "l1", trashed = true))
            val cfg = ListPipeline.Cfg(ViewRef.ListView("l1"), GroupMode.NONE, SortMode.MANUAL, PriorityEngine.Config(), emptyList())
            val vc = ListPipeline.ViewCtx(emptyList(), emptyList(), emptyList(), listOf(ListEntity(id = "l1", name = "L1")), emptyList(), emptyList())
            val ids = ListPipeline.compute(t, emptyList(), cfg, emptyList(), vc, emptyList(), ZoneOffset.UTC, 0, System.currentTimeMillis())
                .flatMap { it.tasks }.map { it.id }.toSet()
            ids == setOf("keep")
        }.getOrDefault(false)
        sb.appendLine("  [${pf(lpOk)}] ListPipeline extraction (view → filter) correct under R8")

        // 4. ZXing QR encode (the offline proof-of-work surface with its own keep rules).
        val zxOk = runCatching {
            com.google.zxing.MultiFormatWriter()
                .encode("kairo-selfcheck", com.google.zxing.BarcodeFormat.QR_CODE, 64, 64).width == 64
        }.getOrDefault(false)
        sb.appendLine("  [${pf(zxOk)}] ZXing QR encode")

        val all = tasks != null && serOk && lpOk && zxOk
        sb.appendLine("  RESULT: ${if (all) "ALL PASS ✓ — R8 build is sound" else "SEE FAILURE(S) ABOVE ✗"}")
        append(c, sb.toString())
    }

    /** (c) Window insets — call from the root composable (R102). Idempotent per set of values. */
    @Volatile private var lastInsets = ""
    fun logInsets(c: Context, statusTop: Int, navBottom: Int, imeBottom: Int) {
        val key = "$statusTop/$navBottom/$imeBottom"
        if (key == lastInsets) return
        lastInsets = key
        append(c, buildString {
            append(header(c))
            appendLine("INSETS (edge-to-edge)")
            appendLine("  status-bar inset top : $statusTop px")
            appendLine("  nav-bar inset bottom : $navBottom px")
            appendLine("  ime inset bottom     : $imeBottom px")
            appendLine("  (all four app bars should sit clear of the system bars, and content should not hide under them.)")
        })
    }

    /**
     * (d) On-device frame timing — a runtime stand-in for a Macrobenchmark jank pass. Registers a per-frame
     * listener (its callback runs on a background Handler, so no main-thread I/O) that records each frame's
     * TOTAL_DURATION; flushFrameMetrics() appends the distribution + jank counts. Call startFrameWatch() once
     * after setContent, and flushFrameMetrics() from Activity.onStop (a whole foreground session per line).
     */
    private val frameThread by lazy { android.os.HandlerThread("kairo-diag-frames").apply { start() } }
    private val frameDurationsNs = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private val jankyFrames = java.util.concurrent.atomic.AtomicInteger(0)   // > 16.7 ms (a 60 Hz miss)
    private val severeFrames = java.util.concurrent.atomic.AtomicInteger(0)  // > 33.3 ms (two-frame stall)
    @Volatile private var frameWatchRegistered = false
    @Volatile private var refreshHz = 0f

    fun startFrameWatch(activity: android.app.Activity) {
        if (frameWatchRegistered) return
        frameWatchRegistered = true
        refreshHz = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.refreshRate ?: 60f
            else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.refreshRate
        }.getOrDefault(60f)
        val handler = android.os.Handler(frameThread.looper)
        runCatching {
            activity.window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
                val total = metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
                frameDurationsNs.add(total)
                val ms = total / 1_000_000.0
                when {
                    ms > 33.34 -> severeFrames.incrementAndGet()
                    ms > 16.67 -> jankyFrames.incrementAndGet()
                }
                while (frameDurationsNs.size > 20_000) frameDurationsNs.poll()
            }, handler)
        }
    }

    fun flushFrameMetrics(c: Context) {
        val arr = frameDurationsNs.toLongArray()
        if (arr.size < 20) return   // too few frames to be meaningful — skip
        arr.sort()
        fun pctMs(p: Double) = arr[((arr.size - 1) * p).toInt()] / 1_000_000.0
        val janky = jankyFrames.get(); val severe = severeFrames.get()
        append(c, buildString {
            append(header(c))
            appendLine("FRAME METRICS (on-device jank · ${f0(refreshHz.toDouble())} Hz display)")
            appendLine("  frames sampled : ${arr.size}")
            appendLine("  p50 / p90 / p99: ${f1(pctMs(0.50))} / ${f1(pctMs(0.90))} / ${f1(pctMs(0.99))} ms")
            appendLine("  max frame      : ${f1(arr.last() / 1_000_000.0)} ms")
            appendLine("  janky  >16.7ms : $janky (${f1(100.0 * janky / arr.size)}%)")
            appendLine("  severe >33.3ms : $severe (${f1(100.0 * severe / arr.size)}%)")
            appendLine("  (scroll the lists + calendar ~20s, then leave the app. Lower p99 & fewer janky = smoother.)")
        })
    }

    private fun f1(x: Double) = String.format(java.util.Locale.US, "%.1f", x)
    private fun f0(x: Double) = String.format(java.util.Locale.US, "%.0f", x)

    private fun pf(ok: Boolean) = if (ok) "PASS" else "FAIL"
    private fun syntheticTask(id: String, listId: String, trashed: Boolean = false) =
        TaskEntity(id = id, listId = listId, title = id, trashed = trashed, workspaceId = "default", createdAt = 0L, updatedAt = 0L)
}
