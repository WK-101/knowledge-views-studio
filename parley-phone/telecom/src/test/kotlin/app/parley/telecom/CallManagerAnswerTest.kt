package app.parley.telecom

import android.content.Intent
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import app.parley.common.calls.LockScreenCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/** Answering, declining and silencing a ringing call, and "Block & decline". */
internal class CallManagerAnswerTest : CallPathTest() {
    @Test fun answeringIsAlwaysAudioOnly() {
        val c = ringing()
        CallManager.answer(idOf(c))
        assertEquals(listOf("answerCall(t1, ${VideoProfile.STATE_AUDIO_ONLY})"), telecom.sent)
        assertTrue(CallManager.wasAnsweredByUser(idOf(c)))
    }

    @Test fun aVideoCallIsAnsweredAsVoiceAndKeepsSayingSo() {
        val c = add(FakeTelecom.Spec("t1", Call.STATE_RINGING, videoState = VideoProfile.STATE_BIDIRECTIONAL))
        assertTrue("the ringing screen says it will be voice only", ui(c).videoAsVoice)
        CallManager.answer(idOf(c))
        assertEquals(listOf("answerCall(t1, ${VideoProfile.STATE_AUDIO_ONLY})"), telecom.sent)
        // Answered audio-only, Telecom reports the call without video: the screen still says why there's no picture.
        telecom.update("t1") { it.copy(state = Call.STATE_ACTIVE, videoState = VideoProfile.STATE_AUDIO_ONLY, connectTimeMillis = 1) }
        assertTrue(ui(c).videoAsVoice)
    }

    @Test fun aVoiceCallOrAnOutgoingVideoCallIsNotMarkedAsVideo() {
        assertFalse(ui(ringing()).videoAsVoice)
        val out = add(FakeTelecom.Spec("t2", Call.STATE_DIALING, incoming = false, videoState = VideoProfile.STATE_BIDIRECTIONAL))
        assertFalse(ui(out).videoAsVoice)
    }

    @Test fun decliningRejectsWithoutAMessage() {
        val c = ringing()
        CallManager.reject(idOf(c))
        assertEquals(listOf("rejectCall(t1, false, null)"), telecom.sent)
    }

    @Test fun aReplyGoesThroughTelecomWhenTheCarrierCanSendIt() {
        val c = add(FakeTelecom.Spec("t1", Call.STATE_RINGING, capabilities = Call.Details.CAPABILITY_RESPOND_VIA_TEXT))
        assertTrue(ui(c).canRespondViaText)
        CallManager.reject(idOf(c), "On my way")
        assertEquals(listOf("rejectCall(t1, true, On my way)"), telecom.sent)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun aReplyOpensTheMessagingAppWhenTheCarrierCant() {
        val c = ringing()
        CallManager.reject(idOf(c), "On my way")
        assertEquals(listOf("rejectCall(t1, false, null)"), telecom.sent)
        val sms = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, sms.action)
        assertEquals("smsto:%2B15551234567", sms.dataString)
        assertEquals("On my way", sms.getStringExtra("sms_body"))
    }

    @Test fun hangingUpDeclinesARingingCallAndEndsAConnectedOne() {
        val r = ringing()
        CallManager.hangup(idOf(r))
        assertEquals(listOf("rejectCall(t1, false, null)"), telecom.sent)
        val a = active("t2")
        CallManager.hangup(idOf(a))
        assertEquals("disconnectCall(t2)", telecom.sent.last())
    }

    @Test fun ignoringSilencesTheRingButLeavesTheCallWaiting() {
        val c = ringing()
        CallManager.ignore(idOf(c))
        assertTrue(ui(c).silenced)
        assertTrue("nothing is declined or answered", telecom.sent.isEmpty())
    }

    @Test fun theUsersSilenceKeyMarksTheRingingCall() {
        val c = ringing()
        CallManager.onSystemSilence()
        assertTrue(ui(c).systemSilenced)
    }

    @Test fun parleysOwnSilenceRequestIsNotTakenForTheUsers() {
        val c = ringing()
        // Telecom echoes Parley's own silenceRinger() back as onSilenceRinger(): that echo isn't the user.
        CallManager.silenceRinger()
        CallManager.onSystemSilence()
        assertFalse(ui(c).systemSilenced)
        // The user's own key press afterwards still counts.
        CallManager.onSystemSilence()
        assertTrue(ui(c).systemSilenced)
    }

    @Test fun blockAndDeclineWritesTheRuleThenDeclinesAsUnwanted() {
        val c = ringing()
        CallManager.blockAndDecline(idOf(c))
        assertTrue("silenced at once", ui(c).silenced)
        FakeTelecom.idle()
        assertEquals(listOf("rejectCallWithReason(t1, ${Call.REJECT_REASON_UNWANTED})"), telecom.sent)
        val b = CallManager.declineBlock.value
        assertNotNull(b)
        assertEquals(41L, b!!.ruleId)
        CallManager.undoDeclineBlock()
        FakeTelecom.idle()
        assertEquals(listOf(41L), deps.undone)
        assertTrue(CallManager.declineBlock.value!!.undone)
    }

    @Test fun whileTheRuleIsWrittenTheCallCantBeAnswered() {
        deps.blockDelayMs = 5_000
        val c = ringing()
        CallManager.blockAndDecline(idOf(c))
        assertTrue(ui(c).blockingDecline)
        assertFalse(ui(c).canBlockAndDecline)
        CallManager.answer(idOf(c))
        CallManager.holdAndAnswer(idOf(c))
        CallManager.endAndAnswer(idOf(c))
        assertTrue(telecom.sent.isEmpty())
        // A slow write doesn't keep it ringing: declined after the wait, the rule follows.
        FakeTelecom.advance(1_500)
        assertEquals(listOf("rejectCallWithReason(t1, ${Call.REJECT_REASON_UNWANTED})"), telecom.sent)
        assertTrue(CallManager.declineBlock.value!!.pending)
        FakeTelecom.advance(4_000)
        assertEquals(41L, CallManager.declineBlock.value!!.ruleId)
        assertFalse(CallManager.declineBlock.value!!.pending)
    }

    @Test fun blockAndDeclineIsNeverUsedOnAnEmergencyOrHiddenCall() {
        val e = ringing("t1", "112")
        assertFalse(ui(e).canBlockAndDecline)
        CallManager.blockAndDecline(idOf(e))
        val h = add(FakeTelecom.Spec("t2", Call.STATE_RINGING, presentation = TelecomManager.PRESENTATION_RESTRICTED))
        CallManager.blockAndDecline(idOf(h))
        FakeTelecom.advance(2_000)
        assertTrue(telecom.sent.isEmpty())
        assertNull(CallManager.declineBlock.value)
        assertFalse(ui(e).silenced)
    }

    @Test fun theLockScreenShowsOnlyWhatTheSettingAllows() {
        contact("+15551234567")
        val u = ui(ringing())
        assertEquals("Ada Lovelace", u.title)
        val locked = u.forLockScreen(LockScreenCaller.INITIALS, "Incoming call")
        assertEquals("AL", locked.title)
        assertNull(locked.photoUri)
        assertNull(locked.note)
        assertEquals("the number stays for Reply and Block", "+15551234567", locked.number)
        assertEquals("Incoming call", u.forLockScreen(LockScreenCaller.NONE, "Incoming call").title)
    }
}
