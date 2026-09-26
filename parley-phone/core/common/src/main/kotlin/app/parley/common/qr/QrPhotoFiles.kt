package app.parley.common.qr

/** Q1: which temporary camera photos the scan screen may delete when it opens. */
object QrPhotoFiles {
    /** A photo this old was left by an interrupted scan; a younger one may still be written or read. */
    const val STALE_MS = 60 * 60 * 1000L

    /** Whether the photo at [path] (last written at [modified]) can go: old enough, and not the [pending] capture. */
    fun isStale(path: String, modified: Long, now: Long, pending: String?): Boolean =
        path != pending && now - modified >= STALE_MS
}
