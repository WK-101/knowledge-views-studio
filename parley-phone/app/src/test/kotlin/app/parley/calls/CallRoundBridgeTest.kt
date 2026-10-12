package app.parley.calls

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.CallRounds
import app.parley.common.calls.LockScreenCaller
import app.parley.common.calls.RoundMark
import app.parley.common.calls.RoundPerson
import app.parley.common.security.DuressState
import app.parley.common.security.LockPhase
import app.parley.common.security.PrivacyView
import app.parley.data.calls.CallRoundStore
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The call round's note reaches the call screen only for someone in the round not told yet; never under a duress
 * unlock (it hides like a note), never for a private contact while private contacts are hidden, never for an emergency
 * number. The call screen itself adds "never while the phone is locked".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallRoundBridgeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: CallRoundStore

    private val open = PrivacyView(discreet = false, duress = DuressState(LockPhase.OPEN), lockScreen = LockScreenCaller.NAME_AND_NOTES, appLocked = false)
    private val discreet = open.copy(discreet = true)
    private val duress = open.copy(duress = DuressState(LockPhase.DURESS, hiding = true))

    private val ana = RoundPerson("ana", "Ana", "+447700900123")
    private val cat = RoundPerson("vault:3", "Cat", "+447700900456", private = true)

    @Before fun setUp() = runBlocking {
        FakeAndroidKeyStore.install()
        File(app.noBackupFilesDir, "docs/call_round").deleteRecursively()
        store = CallRoundStore(app)
        store.update { CallRounds.start("The funeral is on Friday", listOf(ana, cat), 1L)!! }
        Unit
    }

    private fun bridge(view: PrivacyView) = CallRoundBridge(app, { store }) { view }

    @Test fun the_note_for_someone_not_told_yet() = runBlocking {
        assertEquals("The funeral is on Friday", bridge(open).roundNoteFor("+447700900123", null))
        assertNull(bridge(open).roundNoteFor("+447700900999", null))
        store.update { CallRounds.mark(it, "ana", RoundMark.REACHED) }
        assertNull(bridge(open).roundNoteFor("+447700900123", null))
    }

    @Test fun hidden_under_duress_and_for_hidden_private_contacts() = runBlocking {
        assertNull(bridge(duress).roundNoteFor("+447700900123", null))
        assertEquals("The funeral is on Friday", bridge(open).roundNoteFor("+447700900456", null))
        assertNull(bridge(discreet).roundNoteFor("+447700900456", null))
        // Discreet mode hides private contacts only: the others still get their note.
        assertEquals("The funeral is on Friday", bridge(discreet).roundNoteFor("+447700900123", null))
    }

    @Test fun never_for_an_emergency_number() = runBlocking {
        store.update { CallRounds.start("News", listOf(RoundPerson("e", "Police", "112")), 1L)!! }
        assertNull(bridge(open).roundNoteFor("112", null))
    }
}
