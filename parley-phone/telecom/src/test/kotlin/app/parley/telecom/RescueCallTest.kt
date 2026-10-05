package app.parley.telecom

import android.app.NotificationManager
import app.parley.common.NotificationIds
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.MenuPress
import app.parley.common.calls.RescuePlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * Rescue call: shown like a call on the call screen, but never a call. A real call always wins, and nothing the
 * screen can do with it reaches Telecom, the call log, notes, the archive, statistics or case files.
 */
internal class RescueCallTest : CallPathTest() {
    /** The app side, recording every write a call could make (on top of what [FakeDependencies] counts). */
    private class Recording(val base: FakeDependencies) : TelecomDependencies by base {
        val writes = ArrayList<String>()
        override fun saveCallNote(number: String?, connectTimeMillis: Long, text: String) { writes += "note" }
        override fun rememberAfterCall(number: String, connectTimeMillis: Long, note: String?, followUpDays: Int?) { writes += "remember" }
        override fun onCallEnded(number: String?, incoming: Boolean, connectTimeMillis: Long) { writes += "ended" }
        override fun onCallUsage(number: String?, accountId: String?, incoming: Boolean, connectTimeMillis: Long, durationSec: Long) { writes += "usage" }
        override fun onRingFinished(number: String?, startedAt: Long, ringMillis: Long, answered: Boolean) { writes += "ring" }
        override fun onCallQuality(number: String?, facts: CallQualityFacts) { writes += "quality" }
        override fun onCaseCall(number: String, accountId: String?, facts: CallQualityFacts, keys: List<MenuPress>) { writes += "case" }
        override fun onMenuKeys(number: String, accountId: String?, presses: List<MenuPress>) { writes += "menu" }
        override fun remindToCall(number: String, accountId: String?, at: Long) { writes += "remind" }
        override suspend fun redial(number: String, accountId: String?): String? {
            writes += "redial $number"
            return null
        }
        override suspend fun agendaFor(number: String, accountId: String?): CallerAgenda? {
            writes += "agenda read"
            return CallerAgenda(listOf("Ask about the invoice"))
        }
        override suspend fun setAgendaDone(number: String, accountId: String?, text: String, done: Boolean): Boolean {
            writes += "agenda tick"
            return true
        }
        override suspend fun addAgendaItem(number: String, accountId: String?, text: String): AgendaAdded {
            writes += "agenda add"
            return AgendaAdded.ADDED
        }
        override suspend fun blockForDecline(number: String): Long? {
            writes += "block"
            return null
        }
    }

    private lateinit var recording: Recording
    private var otherAppInCall = false

    @Before fun installRecording() {
        recording = Recording(deps)
        TelecomGraph.install(recording)
        RescueCall.resetForTest()
        RescueCall.systemInCall = { otherAppInCall }
    }

    @After fun forgetRescue() {
        RescueCall.resetForTest()
    }

    private val mum = RescueCaller(name = "Mum", number = "+15550001111", label = "Mobile")

    private fun rescue(): CallUi = RescueCall.state.value?.call ?: error("no rescue call")

    private fun start(): CallUi {
        assertTrue(RescueCall.start(context, mum))
        FakeTelecom.idle()
        return rescue()
    }

    @Test fun it_rings_on_the_call_screen_without_being_a_call() {
        val c = start()
        assertEquals(CallState.RINGING, c.state)
        assertTrue(c.simulated && c.incoming && c.savedCaller)
        assertEquals("Mum", c.title)
        assertTrue(RescueCall.owns(c.id))
        // Not one of Telecom's calls: the real call list, the notifier's calls and Telecom never hear of it.
        assertTrue(CallManager.state.value.isEmpty())
        assertTrue(telecom.sent.isEmpty())
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertNotNull(nm.getNotification(NotificationIds.RESCUE_INCOMING))
        assertNull("a real call's notification id is never used", nm.getNotification(NotificationIds.CALL_INCOMING))
    }

    @Test fun it_offers_nothing_that_would_call_block_or_keep() {
        val c = start()
        assertFalse(c.canBlockAndDecline)
        assertFalse(c.canVerify)
        assertFalse(c.canDeflect)
        assertFalse(c.scamCheckOffered)
        assertFalse(c.canRespondViaText)
        CallManager.answer(c.id)
        val a = rescue()
        assertFalse(a.canHoldMode)
        assertFalse(a.memoryCard)
        assertFalse(a.canCallAgain)
        assertFalse(a.postCallCard)
        assertFalse(a.canHold || a.canMerge || a.canSwap)
    }

    @Test fun answering_counts_the_time_and_hanging_up_leaves_nothing() {
        val c = start()
        CallManager.answer(c.id)
        val a = rescue()
        assertEquals(CallState.ACTIVE, a.state)
        assertTrue(a.connectTimeMillis > 0)
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertNull(nm.getNotification(NotificationIds.RESCUE_INCOMING))
        assertNotNull(nm.getNotification(NotificationIds.RESCUE_ONGOING))
        CallManager.hangup(a.id)
        assertNull(RescueCall.state.value?.call)
        assertEquals(CallState.DISCONNECTED, RescueCall.state.value?.ended?.state)
        assertNull(nm.getNotification(NotificationIds.RESCUE_ONGOING))
        // "Call ended" for a moment, then nothing at all.
        FakeTelecom.advance(5_000)
        assertNull(RescueCall.state.value)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun no_log_note_archive_statistic_or_case_file_is_written() {
        val c = start()
        // Every way the call screen and its notification act on a call, on the rescue call's id.
        CallManager.ignore(c.id)
        CallManager.blockAndDecline(c.id)
        CallManager.answer(c.id)
        CallManager.setMuted(true)
        CallManager.toggleSpeaker()
        CallManager.startDtmf(c.id, '1')
        CallManager.saveNote(c.id, "remember the milk")
        CallManager.startHoldMode(c.id)
        CallManager.hangUpAndCall(c.id, "+15559998888", null) {}
        FakeTelecom.advance(10_000)
        assertEquals(emptyList<String>(), recording.writes)
        assertTrue(deps.usage.isEmpty() && deps.quality.isEmpty() && deps.ringFacts.isEmpty() && deps.ended.isEmpty() && deps.menuKeys.isEmpty())
        assertTrue("nothing reached Telecom", telecom.sent.isEmpty())
        assertNull("no real call was placed and the messaging app never opened", shadowOf(context).nextStartedActivity?.takeIf { it.action != null })
    }

    @Test fun the_agenda_is_never_offered_or_kept_for_it() {
        val c = start()
        assertFalse("no agenda card, no add, no \"Did you cover these?\"", app.parley.telecom.ui.agendaApplies(c))
        CallManager.answer(c.id)
        assertFalse(app.parley.telecom.ui.agendaApplies(rescue()))
        CallManager.hangup(c.id)
        assertFalse(app.parley.telecom.ui.CallAgendas.asksAfter(RescueCall.state.value?.ended))
        assertTrue(app.parley.telecom.ui.CallAgendas.calls.keys.none { RescueCall.owns(it) })
        assertTrue(recording.writes.none { it.startsWith("agenda") })
    }

    @Test fun a_reply_declines_it_without_sending_or_opening_anything() {
        val c = start()
        CallManager.reject(c.id, "On my way")
        assertNull(RescueCall.state.value?.call)
        val started = shadowOf(context).nextStartedActivity
        assertTrue("no messaging app: ${started?.action}", started == null || started.action == null)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun a_real_call_arriving_ends_it_at_once() {
        start()
        ringing()
        assertNull("no rescue call left, and no 'Call ended' over the real one", RescueCall.state.value)
        assertEquals(1, CallManager.state.value.size)
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertNull(nm.getNotification(NotificationIds.RESCUE_INCOMING))
    }

    @Test fun an_answered_rescue_call_gives_way_too() {
        val c = start()
        CallManager.answer(c.id)
        ringing()
        assertNull(RescueCall.state.value)
        // The real call's buttons are its own again.
        CallManager.answer(CallManager.state.value.single().id)
        assertEquals(listOf("answerCall(t1, 0)"), telecom.sent)
    }

    @Test fun it_never_starts_during_a_real_call() {
        active("t1")
        assertFalse(RescueCall.start(context, mum))
        assertNull(RescueCall.state.value)
    }

    @Test fun another_phone_apps_call_wins_too() {
        otherAppInCall = true
        assertFalse(RescueCall.start(context, mum))
        otherAppInCall = false
        start()
        otherAppInCall = true
        FakeTelecom.advance(1_500)
        assertNull(RescueCall.state.value)
    }

    @Test fun unanswered_it_stops_ringing_like_a_real_call_and_keeps_no_missed_call() {
        start()
        FakeTelecom.advance(RescuePlan.RING_MS + 1_500)
        assertNull(RescueCall.state.value?.call)
        assertTrue(recording.writes.isEmpty())
    }

    @Test fun the_hang_up_tile_ends_it_when_it_is_the_only_call() {
        val c = start()
        CallManager.answer(c.id)
        assertTrue(CallManager.hangupForeground())
        assertNull(RescueCall.state.value?.call)
    }

    @Test fun mute_and_speaker_are_its_own() {
        val c = start()
        CallManager.answer(c.id)
        CallManager.setMuted(true)
        assertTrue(RescueCall.state.value!!.audio.muted)
        CallManager.toggleSpeaker()
        assertEquals(RouteType.SPEAKER, RescueCall.state.value!!.audio.current?.type)
        CallManager.toggleSpeaker()
        assertEquals(RouteType.EARPIECE, RescueCall.state.value!!.audio.current?.type)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun a_real_calls_ids_are_never_taken_for_a_rescue_call() {
        val real = ringing()
        assertFalse(RescueCall.owns(idOf(real)))
        assertTrue(CallManager.state.value.single().state == CallState.RINGING)
    }
}
