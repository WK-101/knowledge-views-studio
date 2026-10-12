package app.parley.data.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.CallRound
import app.parley.common.calls.CallRounds
import app.parley.common.calls.RoundMark
import app.parley.common.calls.RoundPerson
import app.parley.data.security.RecordCrypto
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The call round is sealed at rest, comes back after a restart (resumable), and ends without a trace (clearable). */
@RunWith(RobolectricTestRunner::class)
class CallRoundStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder by lazy { File(context.noBackupFilesDir, "docs/call_round") }
    private val ana = RoundPerson("ana", "Ana", "+447700900123")

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        folder.deleteRecursively()
    }

    private fun stored(): String? = File(folder, "index.sealed").takeIf { it.isFile }?.readText()

    @Test fun sealed_resumed_and_cleared() = runBlocking {
        val store = CallRoundStore(context)
        val round = CallRounds.start("The funeral is on Friday", listOf(ana), 1L)!!
        store.update { round }
        store.update { CallRounds.mark(it, "ana", RoundMark.LATER) }
        val raw = stored()
        assertTrue(RecordCrypto.get(context).isSealed(raw))
        assertFalse(raw!!.contains("funeral"))
        assertFalse(raw.contains("+447700900123"))
        // A restart: the round is still there, as it was left.
        val again = CallRoundStore(context).load()
        assertEquals(RoundMark.LATER, again.people.single().mark)
        assertTrue(CallRoundStore(context).clear())
        assertEquals(CallRound(), CallRoundStore(context).load())
    }

    @Test fun nothing_plain_when_sealing_fails() = runBlocking {
        val store = CallRoundStore(context) { null }
        store.update { CallRounds.start("News", listOf(ana), 1L)!! }
        assertNull(stored())
        assertTrue(store.state.value.active)
    }
}
