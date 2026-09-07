package com.obliviate.app.core.wipe

/** Which free-space pool to overwrite. */
enum class WipeTarget(val label: String, val description: String) {
    INTERNAL(
        "Internal storage",
        "Overwrites free space on the /data area where apps keep their private files."
    ),
    SHARED(
        "Shared storage",
        "Overwrites free space on the media volume you browse in a file manager (photos, downloads)."
    ),
    BOTH(
        "Both (recommended)",
        "Overwrites free space on the internal and shared volumes. Identical volumes are wiped once."
    );
}

/** Overwrite pattern / number of passes. */
enum class WipeMethod(val label: String, val passes: Int, val description: String) {
    RANDOM(
        "Random · 1 pass",
        1,
        "One pass of cryptographically-seeded random data. Fast and effective against software recovery. Recommended."
    ),
    ZERO(
        "Zero fill · 1 pass",
        1,
        "One pass of zeros. Fastest and writes the least flash wear."
    ),
    DOD(
        "DoD 5220.22-M · 3 pass",
        3,
        "Random → zeros → random. The classic 3-pass scheme. On flash storage the extra passes add wear without a real security gain."
    );
}

data class WipeConfig(
    val target: WipeTarget,
    val method: WipeMethod,
    /** Free space to intentionally leave untouched so the OS stays stable. */
    val keepFreeBytes: Long = DEFAULT_KEEP_FREE_BYTES,
    /** Sample-read the fill back to confirm it persisted to storage. */
    val verify: Boolean = true,
) {
    companion object {
        const val DEFAULT_KEEP_FREE_BYTES: Long = 300L * 1024 * 1024 // 300 MB, safe
        const val AGGRESSIVE_KEEP_FREE_BYTES: Long = 150L * 1024 * 1024 // 150 MB, max coverage
    }
}

enum class WipePhase { PREPARING, FILLING, VERIFYING, DELETING, DONE }

data class WipeProgress(
    val phase: WipePhase,
    val pass: Int,
    val totalPasses: Int,
    val bytesWritten: Long,
    val bytesTarget: Long,
    val speedBytesPerSec: Long,
    val volume: Int = 1,
    val volumeCount: Int = 1,
) {
    val fraction: Float
        get() = if (bytesTarget <= 0) 0f else (bytesWritten.toFloat() / bytesTarget).coerceIn(0f, 1f)
}

data class WipeResult(
    val bytesOverwritten: Long,
    val passes: Int,
    val elapsedMs: Long,
    val verifiedBytes: Long,
    val verifyMismatches: Int,
    val volumesWiped: Int,
)

/** State shared between the foreground service and the UI. */
sealed interface WipeUiState {
    data object Idle : WipeUiState
    data class Running(val progress: WipeProgress) : WipeUiState
    data class Done(
        val bytesOverwritten: Long,
        val passes: Int,
        val elapsedMs: Long,
        val target: WipeTarget,
        val verifiedBytes: Long,
        val verifyMismatches: Int,
        val volumesWiped: Int,
    ) : WipeUiState
    data class Cancelled(val bytesOverwritten: Long) : WipeUiState
    data class Failed(val message: String) : WipeUiState
}
