package app.parley.data.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The network's names per number: kept sealed, updated with a small history, forgotten with the number's calls. */
@RunWith(RobolectricTestRunner::class)
class NetworkNameStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { context.getSharedPreferences("parley_network_names", Context.MODE_PRIVATE) }

    /** A stand-in for the archive key: "sealed" is the bytes behind a marker, so a test can see nothing is plain. */
    private class Keys : SealedLineStore.Keys {
        var broken = false
        override fun lineMac(number: String) = "mac-" + number.filter { it.isDigit() }.takeLast(10)
        override fun seal(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(blob: ByteArray): ByteArray {
            if (broken) error("Key unavailable")
            return blob.copyOfRange(1, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val keys = Keys()
    private val now = System.currentTimeMillis()

    @Before fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test fun records_and_updates_with_history() {
        val store = NetworkNameStore(context, keys)
        store.record("+919812300002", "Ravi Kumar", now - 3_000, "sim1", "IN", now)
        store.record("+91 98123 00002", "RAVI KUMAR", now - 2_000, "sim2", "IN", now)
        assertEquals(1, store.forNumber("+919812300002").size)
        assertEquals(now - 3_000, store.latest("+919812300002")!!.firstSeen)
        assertEquals("sim2", store.latest("+919812300002")!!.accountId)

        store.record("+919812300002", "Kumar Electricals", now - 1_000, "sim2", "IN", now)
        assertEquals(listOf("Kumar Electricals", "RAVI KUMAR"), store.forNumber("+919812300002").map { it.name })
        // Another number is apart; a new process reads the same.
        store.record("+919812300009", "Sita Devi", now, null, "IN", now)
        val again = NetworkNameStore(context, keys)
        assertEquals("Kumar Electricals", again.latest("+919812300002")!!.name)
        assertEquals("Sita Devi", again.latest("+919812300009")!!.name)
        assertNull(again.latest("+919812300005"))
        assertNull(again.latest(null))
    }

    @Test fun nothing_is_kept_in_plain_text() {
        NetworkNameStore(context, keys).record("+919812300002", "Ravi Kumar", now, "sim1", "IN", now)
        val raw = prefs.all.values.joinToString { it.toString() }
        assertTrue(raw.isNotEmpty())
        assertFalse(raw.contains("Ravi"))
    }

    @Test fun forgetting_a_number_drops_its_names_only() {
        val store = NetworkNameStore(context, keys)
        store.record("+919812300002", "Ravi Kumar", now, null, "IN", now)
        store.record("+919812300009", "Sita Devi", now, null, "IN", now)
        store.forget("+919812300002")
        assertNull(store.latest("+919812300002"))
        assertEquals("Sita Devi", store.latest("+919812300009")!!.name)
    }

    @Test fun a_reader_names_a_list_but_never_a_private_number() {
        val store = NetworkNameStore(context, keys)
        store.record("+919812300002", "Ravi Kumar", now, null, "IN", now)
        store.record("+919812300007", "Hidden Friend", now, null, "IN", now)
        val read = store.reader { it.endsWith("7") }
        assertEquals("Ravi Kumar", read("+919812300002").single().name)
        assertTrue(read("+919812300007").isEmpty())
        assertTrue(read("").isEmpty())
    }

    @Test fun names_written_while_others_cant_be_read_keep_both() {
        val store = NetworkNameStore(context, keys)
        store.record("+919812300002", "Ravi Kumar", now - 1_000, null, "IN", now)
        keys.broken = true
        val locked = NetworkNameStore(context, keys)
        assertNull(locked.latest("+919812300002"))
        keys.broken = false
        assertEquals("Ravi Kumar", NetworkNameStore(context, keys).latest("+919812300002")!!.name)
    }
}
