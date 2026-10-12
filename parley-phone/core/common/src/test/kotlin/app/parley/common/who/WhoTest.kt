package app.parley.common.who

import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.CallPolicy
import app.parley.common.Codecs
import app.parley.common.Decision
import app.parley.common.IncomingCallFacts
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.calls.AutoAnswer
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calls.DriveProfile
import app.parley.common.calls.DriveProfileConfig
import app.parley.common.situations.Situation
import app.parley.common.situations.SituationKind
import app.parley.common.situations.SituationRing
import app.parley.common.situations.Situations
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoTest {
    private val ana = WhoFacts(known = true, favourite = false, inCircle = true, labels = setOf("Family"))
    private val stranger = WhoFacts(known = false)

    @Test fun the_same_choices_in_the_same_order_everywhere() {
        val all = listOf(WhoChoice.EVERYONE, WhoChoice.CONTACTS, WhoChoice.FAVOURITES, WhoChoice.CIRCLE, WhoChoice.LABEL)
        assertEquals(all, WhoRules.choicesFor(WhoPlace.SITUATION))
        // Off hours and answering have no Everyone (off hours off; an unknown caller is never answered), the rest in order.
        assertEquals(listOf(WhoChoice.CONTACTS, WhoChoice.FAVOURITES, WhoChoice.CIRCLE, WhoChoice.LABEL), WhoRules.choicesFor(WhoPlace.OFF_HOURS))
        assertEquals(WhoRules.choicesFor(WhoPlace.OFF_HOURS), WhoRules.choicesFor(WhoPlace.ANSWER))
        // A call round calls a handful of people: never Everyone or the whole of Contacts.
        assertEquals(listOf(WhoChoice.FAVOURITES, WhoChoice.CIRCLE, WhoChoice.LABEL), WhoRules.choicesFor(WhoPlace.ROUND))
    }

    @Test fun who_is_in() {
        assertTrue(WhoRules.includes(Who.EVERYONE, stranger))
        assertFalse(WhoRules.includes(Who.CONTACTS, stranger))
        assertFalse(WhoRules.includes(Who.CIRCLE, stranger.copy(inCircle = true)))
        assertTrue(WhoRules.includes(Who.CONTACTS, ana))
        assertFalse(WhoRules.includes(Who.FAVOURITES, ana))
        assertTrue(WhoRules.includes(Who.CIRCLE, ana))
        assertTrue(WhoRules.includes(Who.label(" Family "), ana))
        assertFalse(WhoRules.includes(Who.label("Work"), ana))
        // What can't be read fails open, as screening does.
        assertTrue(WhoRules.includes(Who.label("Work"), ana.copy(labelsUnknown = true)))
        assertTrue(WhoRules.includes(Who.CIRCLE, ana.copy(inCircle = false, circleUnknown = true)))
    }

    @Test fun off_hours_store_the_circle_as_favourites_plus_a_flag() {
        val o = OffHours(enabled = true)
        val circle = WhoRules.intoOffHours(o, Who.CIRCLE)
        assertEquals(OffHoursAllow.FAVOURITES, circle.allow)
        assertTrue(circle.circle)
        assertEquals(Who.CIRCLE, WhoRules.ofOffHours(circle))
        assertTrue(WhoRules.needsCircle(circle))
        // Choosing another drops the flag.
        val fav = WhoRules.intoOffHours(circle, Who.FAVOURITES)
        assertFalse(fav.circle)
        assertEquals(Who.FAVOURITES, WhoRules.ofOffHours(fav))
        assertEquals(Who.label("Family"), WhoRules.ofOffHours(WhoRules.intoOffHours(o, Who.label("Family"))))
        assertEquals(Who.CONTACTS, WhoRules.ofOffHours(WhoRules.intoOffHours(circle, Who.CONTACTS)))
    }

    /** What an older Parley's off hours looked like: no `circle` field. */
    @Serializable
    private data class OlderOffHours(val enabled: Boolean = false, val allow: OffHoursAllow = OffHoursAllow.CONTACTS)

    @Test fun an_older_version_reads_the_circle_as_favourites() {
        val json = Codecs.full.encodeToString(OffHours.serializer(), WhoRules.intoOffHours(OffHours(enabled = true), Who.CIRCLE))
        val old = Codecs.stored.decodeFromString(OlderOffHours.serializer(), json)
        assertEquals(OffHoursAllow.FAVOURITES, old.allow)
        // And a Situation letting the Circle ring is still a Situation letting Favourites ring there.
        val s = Situation("night", SituationKind.NIGHT).withWho(Who.CIRCLE)
        assertEquals(SituationRing.FAVOURITES, s.ring)
        assertTrue(Codecs.full.encodeToString(Situation.serializer(), s).contains("\"ring\":\"FAVOURITES\""))
    }

    @Test fun a_who_this_version_does_not_know_reads_as_favourites() {
        val future = """{"autoAnswerHeadset":true,"autoAnswerWho":{"choice":"NEIGHBOURS"}}"""
        val cfg = CallExtrasConfig.decode(future)
        assertTrue(cfg.autoAnswerHeadset)
        assertEquals(Who.FAVOURITES, cfg.autoAnswerWho)
        val drive = DriveProfileConfig.decode("""{"answerFavourites":true,"answerWho":{"choice":"NEIGHBOURS"}}""")
        assertTrue(drive.answerFavourites)
        assertEquals(Who.FAVOURITES, drive.answerWho)
    }

    @Test fun off_hours_let_the_circle_ring() {
        val s = ScreeningSettings(offHours = WhoRules.intoOffHours(OffHours(enabled = true, schedule = Schedule(Schedule.ALL_DAYS, 0, 0)), Who.CIRCLE))
        fun facts(inCircle: Boolean, starred: Boolean = false, failed: Boolean = false) = IncomingCallFacts(
            number = "+33612345678", hidden = false, isContact = true, countryIso = "FR",
            contactStarred = starred, contactInCircle = inCircle, circleLookupFailed = failed,
        )
        assertEquals(Decision.Allow, CallPolicy.evaluate(facts(inCircle = true), emptyList(), s))
        // A favourite who isn't in the Circle is silenced: the Circle, not the stars.
        assertEquals(Decision.Block(BlockAction.SILENCE, BlockReason.OFF_HOURS), CallPolicy.evaluate(facts(inCircle = false, starred = true), emptyList(), s))
        // Membership that couldn't be read lets them ring.
        assertEquals(Decision.Allow, CallPolicy.evaluate(facts(inCircle = false, failed = true), emptyList(), s))
    }

    @Test fun a_situation_letting_the_circle_ring_sets_off_hours_and_puts_them_back() {
        val meeting = Situations.builtIns().first { it.id == Situations.MEETING }.withWho(Who.CIRCLE)
        assertEquals(Who.CIRCLE, meeting.who)
        val before = Situations.base(app.parley.common.situations.SituationState(), app.parley.common.situations.Behaviour())
        val on = Situations.apply(before, meeting)
        assertTrue(on.offHours.enabled)
        assertTrue(on.offHours.circle)
        assertEquals(OffHoursAllow.FAVOURITES, on.offHours.allow)
        assertEquals(before.offHours, Situations.restore(before, on, on).offHours)
        // Back to Favourites alone: the flag goes.
        assertFalse(Situations.apply(before, meeting.withWho(Who.FAVOURITES)).offHours.circle)
        assertNull(meeting.withWho(null).ring)
    }

    @Test fun auto_answer_with_a_headset_answers_the_chosen_who() {
        val cfg = CallExtrasConfig(autoAnswerHeadset = true)
        val known = AutoAnswer.Facts(knownCaller = true, headsetConnected = true)
        // As it always was: everyone you know.
        assertEquals(AutoAnswer.Reason.HEADSET, AutoAnswer.reason(cfg, known))
        val circle = cfg.copy(autoAnswerWho = Who.CIRCLE)
        assertNull(AutoAnswer.reason(circle, known))
        assertEquals(AutoAnswer.Reason.HEADSET, AutoAnswer.reason(circle, known.copy(inCircle = true)))
        assertEquals(AutoAnswer.Reason.HEADSET, AutoAnswer.reason(cfg.copy(autoAnswerWho = Who.label("Family")), known.copy(labels = setOf("Family"))))
        // Everyone is never a stranger.
        assertNull(AutoAnswer.reason(cfg.copy(autoAnswerWho = Who.EVERYONE), known.copy(knownCaller = false)))
    }

    @Test fun the_drive_profile_answers_its_who() {
        val cfg = DriveProfileConfig(answerFavourites = true)
        assertEquals(Who.FAVOURITES, DriveProfile.answerScope(cfg, true)?.who)
        val circle = DriveProfile.answerScope(cfg.copy(answerWho = Who.CIRCLE), true)
        assertEquals(Who.CIRCLE, circle?.who)
        val facts = AutoAnswer.Facts(knownCaller = true, inCircle = true, drive = circle)
        assertEquals(AutoAnswer.Reason.DRIVING, AutoAnswer.reason(CallExtrasConfig(), facts))
        assertNull(AutoAnswer.reason(CallExtrasConfig(), facts.copy(inCircle = false, favourite = true)))
        // Off ("Nobody"): the who is kept but nobody is answered that way.
        assertNull(DriveProfile.answerScope(cfg.copy(answerFavourites = false, answerWho = Who.CIRCLE), true))
    }

    @Test fun a_label_followed_or_gone() {
        assertEquals(Who.label("Home"), WhoRules.renamed(Who.label("Family"), mapOf("Family" to "Home")))
        assertEquals(Who.CIRCLE, WhoRules.renamed(Who.CIRCLE, mapOf("Family" to "Home")))
        assertEquals(Who.FAVOURITES, WhoRules.labelsDeleted(Who.label("Family"), setOf("Family")))
        assertEquals(Who.label("Work"), WhoRules.labelsDeleted(Who.label("Work"), setOf("Family")))
        assertNull(WhoRules.labelsDeleted(null, setOf("Family")))
    }
}
