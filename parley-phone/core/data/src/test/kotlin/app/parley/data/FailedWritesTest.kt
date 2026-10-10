package app.parley.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.storage.DurableFiles
import app.parley.data.backup.FileBlobStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * Stores whose callers must not go on as if a write worked: when the disk refuses, they throw (as they did before
 * durable writes), so nothing in memory or in another file claims what isn't stored.
 */
@RunWith(RobolectricTestRunner::class)
class FailedWritesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private object Full : DurableFiles.Disk by DurableFiles.RealDisk {
        override fun writeSynced(file: File, body: (java.io.OutputStream) -> Unit) = throw IOException("disk full")
    }

    @After fun tearDown() {
        DurableFiles.disk = DurableFiles.RealDisk
    }

    @Test fun a_time_machine_version_that_cant_be_stored_stops_the_snapshot() {
        val store = FileBlobStore(File(app.filesDir, "blobs"))
        DurableFiles.disk = Full
        try {
            store.put("ab12", "Ada".toByteArray())
            fail("a version that wasn't stored must not be taken as stored")
        } catch (_: IOException) {
            // Expected: the snapshot that would name it stops here.
        }
        assertFalse(store.has("ab12"))
        DurableFiles.disk = DurableFiles.RealDisk
        store.put("ab12", "Ada".toByteArray())
        assertTrue(store.has("ab12"))
    }

    @Test fun spam_lists_state_that_cant_be_stored_isnt_kept_in_memory() = runBlocking {
        val lists = SpamListStore(app)
        DurableFiles.disk = Full
        try {
            lists.dismissSuggestion("builtin.uk")
            fail("a state that wasn't stored must not be taken as stored")
        } catch (_: IOException) {
            // Expected: the caller hears it.
        }
        assertFalse("builtin.uk" in lists.state.value.dismissedSuggestions)
    }
}
