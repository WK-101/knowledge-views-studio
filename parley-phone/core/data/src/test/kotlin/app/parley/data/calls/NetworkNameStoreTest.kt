package app.parley.data.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.history.NumberKeys
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

    @Test fun a_name_past_its_days_is_gone_when_read() {
        val store = NetworkNameStore(context, keys)
        val day = 86_400_000L
        // Sent 420 days ago and written 300 days ago (it was 120 days old then); nothing written since, yet it has gone.
        store.record("+919812300009", "Sita Devi", now - 10 * day, null, "IN", now - 10 * day)
        store.record("+919812300002", "Ravi Kumar", now - 420 * day, null, "IN", now - 300 * day)
        assertEquals(2, prefs.getString("rows", "")!!.lines().count { it.isNotBlank() })
        assertNull(store.latest("+919812300002"))
        assertTrue(store.reader()("+919812300002").isEmpty())
        assertEquals("Sita Devi", store.latest("+919812300009")!!.name)
        // And it is off the disk, not only out of sight.
        val again = NetworkNameStore(context, keys)
        assertNull(again.latest("+919812300002"))
        assertEquals(1, prefs.getString("rows", "")!!.lines().count { it.isNotBlank() })
    }

    /** Keys that read a number the way the archive does (national numbers with the phone's country, India). */
    private class PhoneKeys : SealedLineStore.Keys {
        override fun lineMac(number: String) = "mac-" + NumberKeys.of(number, "IN")
        override fun seal(plain: ByteArray) = byteArrayOf(7) + plain
        override fun open(blob: ByteArray) = blob.copyOfRange(1, blob.size)
    }

    @Test fun a_national_number_is_kept_as_its_sims_country_reads_it() {
        val store = NetworkNameStore(context, PhoneKeys())
        // A call on a French SIM, the number written nationally.
        store.record("06 12 34 56 78", "Claire Martin", now, "sim-fr", "FR", now)
        // Found as the same SIM reads it, and in full (a contact, a notification, Recall).
        assertEquals("Claire Martin", store.latest("0612345678", "FR")!!.name)
        assertEquals("Claire Martin", store.latest("+33 6 12 34 56 78")!!.name)
        assertEquals("Claire Martin", store.reader()("+33612345678").single().name)
        // Read with the phone's country (India) it is another line.
        assertNull(store.latest("0612345678"))
        // Forgetting with the SIMs' countries finds it.
        store.forget("0612345678", listOf("FR"))
        assertNull(store.latest("+33612345678"))
    }

    @Test fun set_aside_with_a_delete_and_put_back_by_its_undo() {
        val store = NetworkNameStore(context, keys)
        store.record("+919812300002", "Ravi Kumar", now - 2_000, "sim1", "IN", now)
        store.record("+919812300002", "Kumar Electricals", now - 1_000, "sim1", "IN", now)
        store.setAside("+919812300002", emptyList(), batch = now, now = now)
        assertNull(store.latest("+919812300002"))
        // Nothing of it in plain text while set aside.
        assertFalse(prefs.all.values.joinToString { it.toString() }.contains("Kumar"))
        // A name sent meanwhile stays, and is newest.
        store.record("+919812300002", "K Electricals", now - 500, "sim2", "IN", now)
        assertTrue(store.putBack(now, now))
        assertEquals(listOf("K Electricals", "Kumar Electricals", "Ravi Kumar"), store.forNumber("+919812300002").map { it.name })
        // Put back once: the copy is gone.
        assertFalse(store.putBack(now, now))
        assertTrue(prefs.all.keys.none { it.startsWith("aside_") })
    }

    @Test fun copies_set_aside_long_ago_are_dropped() {
        val store = NetworkNameStore(context, keys)
        store.record("+919812300002", "Ravi Kumar", now, null, "IN", now)
        val old = now - 40 * 86_400_000L
        store.setAside("+919812300002", emptyList(), batch = old, now = old)
        store.record("+919812300009", "Sita Devi", now, null, "IN", now)
        store.setAside("+919812300009", emptyList(), batch = now, now = now)
        assertEquals(listOf("aside_$now"), prefs.all.keys.filter { it.startsWith("aside_") })
    }
}
