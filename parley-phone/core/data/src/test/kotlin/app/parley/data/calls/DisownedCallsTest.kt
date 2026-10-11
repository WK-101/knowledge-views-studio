package app.parley.data.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** "It wasn't them" marks and the callback windows a line rang in are kept apart, per line, and go with the line. */
@RunWith(RobolectricTestRunner::class)
class DisownedCallsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Keys : SealedLineStore.Keys {
        override fun lineMac(number: String) = "mac-$number"
        override fun seal(plain: ByteArray) = byteArrayOf(7) + plain
        override fun open(blob: ByteArray): ByteArray = blob.copyOfRange(1, blob.size)
    }

    @Test fun callback_windows_are_kept_once_per_window_beside_the_marks() {
        val now = System.currentTimeMillis()
        val store = DisownedCalls(context, Keys(), memoryFactTable(context))
        store.disown("+441632960001", now - 10)
        store.expect("+441632960001", now - 1_000, now + 1_000, now)
        // The same window again (a second call in it) is one row.
        store.expect("+441632960001", now - 1_000, now + 1_000, now)
        store.expect("+441632960002", now - 500, now + 500, now)
        assertEquals(listOf((now - 1_000)..(now + 1_000)), store.expectedFor("+441632960001"))
        assertEquals(listOf(now - 10), store.forNumber("+441632960001"))
        // Every call with the line went: both kinds go.
        store.forget("+441632960001")
        assertTrue(store.expectedFor("+441632960001").isEmpty() && store.forNumber("+441632960001").isEmpty())
        assertEquals(1, store.expectedFor("+441632960002").size)
        store.clear()
        assertTrue(store.expectedFor("+441632960002").isEmpty())
    }
}
