package app.parley.data.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.memory.MemoryHint
import app.parley.common.memory.MemorySource
import app.parley.common.memory.NumberMemory
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The number-memory index: found by any way of writing the number, never holding a plain number or hint, rebuilt only
 * where something changed, and never losing what it had to a store or a key that can't be used for a moment.
 */
@RunWith(RobolectricTestRunner::class)
class NumberMemoryIndexTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir by lazy { File(context.noBackupFilesDir, "number_memory_test").apply { deleteRecursively() } }
    private val gb = "GB"

    /** HMAC with a test key, and a reversible "seal" that marks its output (enough to see nothing is stored plain). */
    private class FakeKeys(var secret: ByteArray = ByteArray(32) { 7 }) : NumberMemoryIndex.Keys {
        var sealFails = false
        var lost = false
        var startedOver = 0
        override fun startOver() {
            startedOver++
            lost = false
            secret = ByteArray(32) { 3 }
        }
        override fun key(input: String): String =
            if (lost) throw app.parley.data.history.HistoryCrypto.KeyLostException(null)
            else Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(secret, "HmacSHA256")); doFinal(input.toByteArray()) }
                .joinToString("") { "%02x".format(it) }
        override fun seal(plain: ByteArray): ByteArray {
            check(!sealFails) { "no key" }
            return MARK + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        override fun open(sealed: ByteArray): ByteArray {
            check(sealed.take(MARK.size) == MARK.toList())
            return sealed.drop(MARK.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }

        companion object {
            val MARK = byteArrayOf(1, 2, 3)
        }
    }

    private class FakeSource(override val id: String, var fp: String?, var entries: List<NumberMemory.Entry>?) : NumberMemoryIndex.Source {
        var reads = 0
        var fails = false
        override suspend fun fingerprint() = fp
        override suspend fun read(): List<NumberMemory.Entry>? {
            reads++
            check(!fails) { "store unavailable" }
            return entries
        }
    }

    private val mike = MemoryHint(MemorySource.DELETED_CONTACT, name = "Plumber Mike", at = 100, ref = "7")
    private val ana = MemoryHint(MemorySource.NOTE, name = "Ana", excerpt = "Dr Lee's office", at = 5, ref = "ana")

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
    }

    @Test fun a_hint_is_found_by_any_way_of_writing_the_number_and_nothing_is_stored_plain() = runBlocking {
        val index = NumberMemoryIndex(dir, FakeKeys())
        val deleted = FakeSource("deleted", "1", listOf(NumberMemory.Entry("07700 900123", mike)))
        val notes = FakeSource("notes", null, listOf(NumberMemory.Entry("+442079460000", ana)))
        val stats = index.rebuild(listOf(deleted, notes), gb)
        assertEquals(listOf("deleted", "notes"), stats.read)

        assertEquals(listOf(mike), index.lookup("+44 7700 900123", gb))
        assertEquals(listOf(mike), index.lookup("07700900123", gb))
        assertEquals(listOf(ana), index.lookup("020 7946 0000", gb))
        assertTrue(index.lookup("+447700900999", gb).isEmpty())

        // Only keyed hashes and sealed hints on disk: no number, no name, no note.
        val bytes = File(dir, "index.bin").readBytes().toString(Charsets.ISO_8859_1)
        listOf("7700", "900123", "2079460000", "Plumber", "Mike", "Dr Lee").forEach { assertFalse("$it on disk", it in bytes) }
    }

    @Test fun only_stores_that_changed_are_read_again() = runBlocking {
        val index = NumberMemoryIndex(dir, FakeKeys())
        val deleted = FakeSource("deleted", "1", listOf(NumberMemory.Entry("+447700900123", mike)))
        val notes = FakeSource("notes", null, listOf(NumberMemory.Entry("+442079460000", ana)))
        index.rebuild(listOf(deleted, notes), gb)

        val again = index.rebuild(listOf(deleted, notes), gb)
        assertEquals(1, deleted.reads)
        // A store without a cheap fingerprint is read every time.
        assertEquals(2, notes.reads)
        assertEquals(listOf("notes"), again.read)
        assertEquals(listOf(mike), index.lookup("+447700900123", gb))

        // Mike was restored: the journal changed, and his line is gone.
        deleted.fp = "2"
        deleted.entries = emptyList()
        index.rebuild(listOf(deleted, notes), gb)
        assertEquals(2, deleted.reads)
        assertTrue(index.lookup("+447700900123", gb).isEmpty())
        assertEquals(listOf(ana), index.lookup("+442079460000", gb))
    }

    @Test fun a_store_that_cant_be_read_now_keeps_what_it_had() = runBlocking {
        val index = NumberMemoryIndex(dir, FakeKeys())
        val archive = FakeSource("archive", "a", listOf(NumberMemory.Entry("+447700900123", mike)))
        index.rebuild(listOf(archive), gb)
        archive.fp = "b"
        archive.fails = true
        val stats = index.rebuild(listOf(archive), gb)
        assertEquals(listOf("archive"), stats.kept)
        assertEquals(listOf(mike), index.lookup("+447700900123", gb))
        // Null (the archive key is unavailable) is the same: kept.
        archive.fails = false
        archive.entries = null
        index.rebuild(listOf(archive), gb)
        assertEquals(listOf(mike), index.lookup("+447700900123", gb))
    }

    @Test fun nothing_is_written_when_hints_cant_be_sealed() = runBlocking {
        val keys = FakeKeys()
        val index = NumberMemoryIndex(dir, keys)
        val src = FakeSource("deleted", "1", listOf(NumberMemory.Entry("+447700900123", mike)))
        index.rebuild(listOf(src), gb)
        val before = File(dir, "index.bin").readBytes()

        keys.sealFails = true
        src.fp = "2"
        src.entries = listOf(NumberMemory.Entry("+447700900123", ana))
        assertTrue(runCatching { index.rebuild(listOf(src), gb) }.isFailure)
        assertArrayEquals(before, File(dir, "index.bin").readBytes())
        assertEquals(listOf(mike), index.lookup("+447700900123", gb))
    }

    @Test fun a_lost_hashing_key_is_replaced_and_the_index_rebuilt() = runBlocking {
        val keys = FakeKeys()
        val src = FakeSource("deleted", "1", listOf(NumberMemory.Entry("+447700900123", mike)))
        NumberMemoryIndex(dir, keys).rebuild(listOf(src), gb)
        // The Keystore wrapping key went (invalidated) while memory.keys stayed: every hash throws until replaced.
        keys.lost = true
        val index = NumberMemoryIndex(dir, keys)
        assertFalse(index.matchesKey())
        val stats = index.rebuild(listOf(src), gb)
        assertEquals(1, keys.startedOver)
        assertEquals(listOf("deleted"), stats.read)
        assertTrue(index.matchesKey())
        assertEquals(listOf(mike), index.lookup("+447700900123", gb))
    }

    @Test fun rows_made_with_another_key_are_never_trusted() = runBlocking {
        val keys = FakeKeys()
        val src = FakeSource("deleted", "1", listOf(NumberMemory.Entry("+447700900123", mike)))
        NumberMemoryIndex(dir, keys).rebuild(listOf(src), gb)

        // The Keystore was reset: a new index sees nothing, and the next rebuild reads every store again.
        keys.secret = ByteArray(32) { 9 }
        val fresh = NumberMemoryIndex(dir, keys)
        assertTrue(fresh.lookup("+447700900123", gb).isEmpty())
        val stats = fresh.rebuild(listOf(src), gb)
        assertEquals(listOf("deleted"), stats.read)
        assertEquals(listOf(mike), fresh.lookup("+447700900123", gb))
    }

    @Test fun a_wiped_index_answers_nothing_and_a_damaged_one_fails_open() = runBlocking {
        val index = NumberMemoryIndex(dir, FakeKeys())
        index.rebuild(listOf(FakeSource("deleted", "1", listOf(NumberMemory.Entry("+447700900123", mike)))), gb)
        assertEquals(listOf(mike), index.lookup("+447700900123", gb))
        // "Delete all Parley data" removes the folder under a running index.
        dir.deleteRecursively()
        assertTrue(index.lookup("+447700900123", gb).isEmpty())

        dir.mkdirs()
        File(dir, "index.bin").writeBytes(byteArrayOf(0x50, 0x4E, 0x4D, 0x31, 0, 9))
        assertTrue(index.lookup("+447700900123", gb).isEmpty())
    }

    @Test fun the_keystore_keys_hash_and_seal_for_real() = runBlocking {
        val keys = KeystoreMemoryKeys(context)
        assertEquals(keys.key("e164:+447700900123"), keys.key("e164:+447700900123"))
        assertFalse(keys.key("e164:+447700900123") == keys.key("e164:+447700900124"))
        val sealed = keys.seal("hint".toByteArray())
        assertFalse("hint" in String(sealed, Charsets.ISO_8859_1))
        assertEquals("hint", String(keys.open(sealed)))
        // A plain value is never accepted as a hint.
        assertTrue(runCatching { keys.open("hint".toByteArray()) }.isFailure)

        val index = NumberMemoryIndex(dir, keys)
        index.rebuild(listOf(FakeSource("deleted", "1", listOf(NumberMemory.Entry("+447700900123", mike)))), gb)
        assertEquals(listOf(mike), index.lookup("07700 900123", gb))
    }

    /** A source that keeps a running list of numbers as its state, as the call archive keeps its tally. */
    private class Appending(override val id: String) : NumberMemoryIndex.Source {
        var fp = "1"
        val received = ArrayList<String?>()
        val fresh = ArrayList<String>()
        override suspend fun fingerprint() = fp
        override suspend fun read(): List<NumberMemory.Entry>? = update(null)?.entries
        override suspend fun update(previous: ByteArray?): NumberMemoryIndex.Update {
            received += previous?.let { String(it) }
            val all = previous?.let { String(it).split(",").filter { n -> n.isNotEmpty() } }.orEmpty() + fresh
            fresh.clear()
            return NumberMemoryIndex.Update(
                all.map { NumberMemory.Entry(it, MemoryHint(MemorySource.CALLS, count = 1, at = 1)) },
                all.joinToString(",").toByteArray(),
            )
        }
    }

    @Test fun a_source_with_a_state_reads_only_what_is_new() = runBlocking {
        val keys = FakeKeys()
        val src = Appending("archive")
        src.fresh += "+447700900001"
        val index = NumberMemoryIndex(dir, keys)
        index.rebuild(listOf(src), gb)
        src.fresh += "+447700900002"
        src.fp = "2"
        NumberMemoryIndex(dir, keys).rebuild(listOf(src), gb)
        assertEquals(listOf(null, "+447700900001"), src.received)
        assertEquals(1, NumberMemoryIndex(dir, keys).lookup("+447700900001", gb).size)
        assertEquals(1, NumberMemoryIndex(dir, keys).lookup("+447700900002", gb).size)
        // The state is stored sealed, never plain.
        assertFalse("447700900001" in String(File(dir, "index.bin").readBytes(), Charsets.ISO_8859_1))

        // Another key: the state can't be trusted either, so the source starts from nothing.
        keys.secret = ByteArray(32) { 9 }
        src.fp = "3"
        val other = NumberMemoryIndex(dir, keys)
        assertFalse(other.matchesKey())
        other.rebuild(listOf(src), gb)
        assertEquals(null, src.received.last())
        assertTrue(other.matchesKey())
    }

    @Test fun the_index_made_with_the_old_keystore_key_is_rebuilt_once_and_the_old_key_goes() = runBlocking {
        // What versions before 5.4 had: an HMAC key inside the Keystore.
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder("parley_number_memory_v1", android.security.keystore.KeyProperties.PURPOSE_SIGN).build())
        gen.generateKey()
        val oldKeys = FakeKeys()
        NumberMemoryIndex(dir, oldKeys).rebuild(listOf(FakeSource("deleted", "1", listOf(NumberMemory.Entry("+447700900123", mike)))), gb)

        val keys = KeystoreMemoryKeys(context)
        val index = NumberMemoryIndex(dir, keys)
        assertFalse(index.matchesKey())
        assertTrue(index.lookup("+447700900123", gb).isEmpty())
        index.rebuild(listOf(FakeSource("deleted", "1", listOf(NumberMemory.Entry("+447700900123", mike)))), gb)
        assertTrue(index.matchesKey())
        assertEquals(listOf(mike), index.lookup("+447700900123", gb))
        keys.retireOldKey()
        assertFalse("parley_number_memory_v1" in FakeAndroidKeyStore.keys)
        assertTrue(File(context.noBackupFilesDir, KeystoreMemoryKeys.KEY_FILE).isFile)
    }
}
