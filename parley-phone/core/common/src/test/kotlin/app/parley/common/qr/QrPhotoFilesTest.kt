package app.parley.common.qr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPhotoFilesTest {
    private val now = 10 * QrPhotoFiles.STALE_MS

    @Test fun only_old_photos_that_are_not_pending_go() {
        assertTrue(QrPhotoFiles.isStale("/c/qr/a.jpg", now - QrPhotoFiles.STALE_MS, now, null))
        assertTrue(QrPhotoFiles.isStale("/c/qr/a.jpg", now - 2 * QrPhotoFiles.STALE_MS, now, "/c/qr/b.jpg"))
        // A fresh photo may be the one just taken and still being read.
        assertFalse(QrPhotoFiles.isStale("/c/qr/a.jpg", now - 1000, now, null))
        // The pending capture is kept, however old.
        assertFalse(QrPhotoFiles.isStale("/c/qr/a.jpg", now - 2 * QrPhotoFiles.STALE_MS, now, "/c/qr/a.jpg"))
    }
}
