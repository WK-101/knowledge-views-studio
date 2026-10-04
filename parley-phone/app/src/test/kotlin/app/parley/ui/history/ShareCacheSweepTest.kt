package app.parley.ui.history

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.parley.ui.qr.forgetScannedCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The share cache is swept by age only, so a file another app opens late is still there; a scanned card goes at once. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareCacheSweepTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun file(dir: String, name: String, ageMs: Long, now: Long): File =
        File(File(context.cacheDir, dir).apply { mkdirs() }, name).apply {
            writeText("x")
            setLastModified(now - ageMs)
        }

    @Test fun only_files_an_hour_old_go() {
        val now = System.currentTimeMillis()
        val fresh = file("share", "card.vcf", 5 * 60_000L, now)
        val old = file("share", "old.vcf", 2 * 60 * 60_000L, now)
        val transfer = file("transfer", "old.bin", 2 * 60 * 60_000L, now)
        val update = file("label_updates", "Family-2026-10-04-0900.parleyupdate", 2 * 60 * 60_000L, now)
        ExportFiles.cleanup(context, now = now)
        assertTrue("a file shared minutes ago is still being read", fresh.exists())
        assertFalse(old.exists())
        assertFalse(transfer.exists())
        assertFalse("a sent update (a farewell holds the whole label) doesn't stay in the cache", update.exists())
    }

    @Test fun a_scanned_card_is_deleted_once_read() {
        val now = System.currentTimeMillis()
        val card = file("share", "scanned-contacts.vcf", 0, now)
        val other = file("share", "my-card.vcf", 0, now)
        forgetScannedCard(context, Uri.parse("content://${context.packageName}.files/share/my-card.vcf"))
        assertTrue(other.exists())
        forgetScannedCard(context, Uri.parse("content://${context.packageName}.files/share/scanned-contacts.vcf"))
        assertFalse(card.exists())
    }
}
