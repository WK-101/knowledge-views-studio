package app.parley.common.sync.shared

import app.parley.common.AllowReason
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.CallPolicy
import app.parley.common.Decision
import app.parley.common.IncomingCallFacts
import app.parley.common.PolicyClock
import app.parley.common.ScreeningSettings
import app.parley.common.VerdictKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyShieldTest {
    private val labelKey = ByteArray(32) { it.toByte() }
    private val labelId = "0123456789abcdef0123456789abcdef"
    private val key = FamilyShield.key(labelKey, labelId)
    private val number = "+447700900123"

    // ---------------------------------------------------------------- hashing and keying

    @Test fun a_number_hashes_the_same_under_one_key_and_differently_under_another() {
        val h = FamilyShield.hash(key, number)!!
        assertTrue(FamilyShield.isHash(h))
        assertEquals(h, FamilyShield.hash(FamilyShield.key(labelKey.copyOf(), labelId), number))
        // Another label's key, the same key for another label, another number: all different.
        assertNotEquals(h, FamilyShield.hash(FamilyShield.key(ByteArray(32) { 7 }, labelId), number))
        assertNotEquals(h, FamilyShield.hash(FamilyShield.key(labelKey, "f".repeat(32)), number))
        assertNotEquals(h, FamilyShield.hash(key, "+447700900124"))
        // The key is never the label key itself.
        assertFalse(key.contentEquals(labelKey))
    }

    @Test fun only_e164_numbers_are_hashed() {
        assertNull(FamilyShield.hash(key, "07700900123"))
        assertNull(FamilyShield.hash(key, "+0123456789"))
        assertNull(FamilyShield.hash(key, "112"))
        assertNull(FamilyShield.hash(key, "+44 7700 900123"))
    }

    @Test fun outgoing_keeps_each_number_once_with_its_strongest_kind_in_a_fixed_order() {
        val own = listOf(
            OwnVerdict(number, ShieldKind.BLOCKED, 10),
            OwnVerdict(number, ShieldKind.SCAM, 5),
            OwnVerdict("+12025550143", ShieldKind.SPAM_LIKELY, 20),
            OwnVerdict("not a number", ShieldKind.SCAM, 30),
        )
        val out = FamilyShield.outgoing(key, own)
        assertEquals(2, out.size)
        val v = out.single { it.hash == FamilyShield.hash(key, number) }
        assertEquals(ShieldKind.SCAM, v.kind)
        assertEquals(10L, v.at)
        assertEquals(out, FamilyShield.outgoing(key, own.reversed()))
        assertEquals(FamilyShield.digest(out), FamilyShield.digest(FamilyShield.outgoing(key, own.reversed())))
        assertEquals("", FamilyShield.digest(null))
        assertNotEquals("", FamilyShield.digest(emptyList()))
    }

    @Test fun outgoing_is_capped_to_the_newest() {
        val own = (0 until FamilyShield.MAX_VERDICTS + 10).map { OwnVerdict("+4477009" + (10000 + it), ShieldKind.BLOCKED, it.toLong()) }
        val out = FamilyShield.outgoing(key, own)
        assertEquals(FamilyShield.MAX_VERDICTS, out.size)
        assertTrue(out.none { it.at < 10 })
    }

    // ---------------------------------------------------------------- the journal carries them, signed

    @Test fun a_journal_carries_the_verdicts_signed_and_an_older_one_none() {
        val ana = TestSigner()
        val verdicts = FamilyShield.outgoing(key, listOf(OwnVerdict(number, ShieldKind.SCAM, 42)))
        val j = Journal(ana.publicKey, "Ana", 1, null, emptyList(), false, emptyList(), at = 1, shield = verdicts)
        val name = SharedLabelFiles.journalName(ana.publicKey)
        val bytes = SharedLabelFiles.writeJournal(ana, labelId, j)!!
        // Only the hash travels: no digits of the number.
        assertFalse(String(bytes).contains("7700900123"))
        assertEquals(verdicts, SharedLabelFiles.readJournal(labelId, name, bytes)!!.shield)
        // Changing a verdict breaks the signature.
        val forged = String(bytes).replace("\\\"k\\\":\\\"s\\\"", "\\\"k\\\":\\\"b\\\"")
        assertNotEquals(String(bytes), forged)
        assertNull(SharedLabelFiles.readJournal(labelId, name, forged.toByteArray()))
        // A journal without the field (older versions, or the shield off): none.
        val plain = SharedLabelFiles.writeJournal(ana, labelId, Journal(ana.publicKey, "Ana", 1, null, emptyList(), false, emptyList(), at = 1))!!
        assertNull(SharedLabelFiles.readJournal(labelId, name, plain)!!.shield)
    }

    // ---------------------------------------------------------------- merging several members

    @Test fun verdicts_of_several_members_merge_by_number() {
        val h1 = FamilyShield.hash(key, number)!!
        val h2 = FamilyShield.hash(key, "+12025550143")!!
        val merged = FamilyShield.merge(
            mapOf(
                "ana" to listOf(ShieldVerdict(h1, ShieldKind.BLOCKED, 10), ShieldVerdict(h2, ShieldKind.SPAM_LIKELY, 3)),
                "sam" to listOf(ShieldVerdict(h1, ShieldKind.SCAM, 5), ShieldVerdict(h1, ShieldKind.SCAM, 6)),
                "bob" to listOf(ShieldVerdict(h1, ShieldKind.SPAM_LIKELY, 20)),
            ),
        )
        assertEquals(ShieldMatch(ShieldKind.SCAM, 3, 20), merged[h1])
        assertEquals(ShieldMatch(ShieldKind.SPAM_LIKELY, 1, 3), merged[h2])
    }

    @Test fun of_several_labels_the_one_that_does_most_wins() {
        val warn = FamilyHit("Family", ShieldKind.SCAM, 1, ShieldMode.WARN)
        val block = FamilyHit("School", ShieldKind.SPAM_LIKELY, 1, ShieldMode.BLOCK)
        assertEquals(block, FamilyShield.strongest(listOf(warn, block)))
        val scam = FamilyHit("A", ShieldKind.SCAM, 1, ShieldMode.WARN)
        assertEquals(scam, FamilyShield.strongest(listOf(FamilyHit("B", ShieldKind.BLOCKED, 3, ShieldMode.WARN), scam)))
        assertNull(FamilyShield.strongest(emptyList()))
    }

    // ---------------------------------------------------------------- withdrawing, block rules

    @Test fun block_rules_add_and_take_away_and_a_withdrawal_sticks() {
        var own = FamilyShieldOwn.withRules(emptyList(), setOf(number, "+12025550143"), now = 100)
        assertEquals(2, own.size)
        assertTrue(own.all { it.fromRule && it.kind == ShieldKind.BLOCKED && it.at == 100L })
        // Withdrawn: kept as withdrawn, so the rule doesn't share it again.
        own = FamilyShieldOwn.withdraw(own, number)
        own = FamilyShieldOwn.withRules(own, setOf(number, "+12025550143"), now = 200)
        assertTrue(own.single { it.e164 == number }.withdrawn)
        // Unblocked: gone, withdrawn or not.
        own = FamilyShieldOwn.withRules(own, setOf("+12025550143"), now = 300)
        assertNull(own.firstOrNull { it.e164 == number })
        // Marked by hand: shared again (a withdrawn rule number too), and kept when the rule goes.
        own = FamilyShieldOwn.mark(own, "+12025550143", ShieldKind.SCAM, now = 400)
        own = FamilyShieldOwn.withRules(own, emptySet(), now = 500)
        assertEquals(listOf(ShieldOwn("+12025550143", ShieldKind.SCAM, 400, fromRule = false)), own)
        // A mark withdrawn isn't shared, and goes with the next look once the number isn't blocked.
        own = FamilyShieldOwn.withdraw(own, "+12025550143")
        assertTrue(own.single().withdrawn)
        assertTrue(FamilyShieldOwn.withRules(own, emptySet(), now = 600).isEmpty())
    }

    @Test fun a_blocked_number_marked_then_withdrawn_stays_withdrawn() {
        var own = FamilyShieldOwn.withRules(emptyList(), setOf(number), now = 100)
        own = FamilyShieldOwn.mark(own, number, ShieldKind.SCAM, now = 200)
        own = FamilyShieldOwn.withdraw(own, number)
        repeat(2) { own = FamilyShieldOwn.withRules(own, setOf(number), now = 300L + it) }
        assertTrue(own.single { it.e164 == number }.withdrawn)
        assertEquals(1, own.count { it.e164 == number })
    }

    @Test fun trimming_never_drops_a_withdrawn_number_that_is_still_blocked() {
        val blocked = (0 until FamilyShield.MAX_VERDICTS * 2 + 10).map { "+1202555%04d".format(it) }.toSet()
        val old = blocked.first()
        var own = FamilyShieldOwn.withRules(emptyList(), setOf(old), now = 1)
        own = FamilyShieldOwn.withdraw(own, old)
        own = FamilyShieldOwn.withRules(own, blocked, now = 2)
        assertEquals(FamilyShield.MAX_VERDICTS * 2, own.size)
        assertTrue(own.single { it.e164 == old }.withdrawn)
        own = FamilyShieldOwn.withRules(own, blocked, now = 3)
        assertTrue(own.single { it.e164 == old }.withdrawn)
    }

    // ---------------------------------------------------------------- screening

    private val now = PolicyClock.of(1_700_000_000_000L)

    private fun facts(hit: FamilyHit?, contact: Boolean = false, emergency: Boolean = false) =
        IncomingCallFacts(number = number, hidden = false, isContact = contact, isEmergency = emergency, countryIso = "GB", family = hit)

    private fun hit(mode: ShieldMode, kind: ShieldKind = ShieldKind.BLOCKED) = FamilyHit("Family", kind, 1, mode)

    @Test fun outcome_never_touches_emergency_numbers_or_saved_contacts() {
        assertEquals(FamilyShield.Outcome.NONE, FamilyShield.outcome(hit(ShieldMode.BLOCK), isEmergency = true, isContact = false))
        assertEquals(FamilyShield.Outcome.NONE, FamilyShield.outcome(hit(ShieldMode.BLOCK), isEmergency = false, isContact = true))
        assertEquals(FamilyShield.Outcome.NONE, FamilyShield.outcome(null, isEmergency = false, isContact = false))
        assertEquals(FamilyShield.Outcome.WARN, FamilyShield.outcome(hit(ShieldMode.WARN), isEmergency = false, isContact = false))
        assertEquals(FamilyShield.Outcome.SILENCE, FamilyShield.outcome(hit(ShieldMode.SILENCE), isEmergency = false, isContact = false))
        assertEquals(FamilyShield.Outcome.BLOCK, FamilyShield.outcome(hit(ShieldMode.BLOCK), isEmergency = false, isContact = false))
    }

    @Test fun warn_only_rings_with_the_warning() {
        val r = CallPolicy.decide(facts(hit(ShieldMode.WARN)), emptyList(), ScreeningSettings(), now)
        assertEquals(Decision.Allow, r.decision)
        assertEquals(VerdictKind.LIKELY_SPAM, r.verdict?.kind)
        assertEquals("Blocked by someone in Family", r.verdict?.text)
    }

    @Test fun silence_and_block_follow_the_label_choice() {
        val silenced = CallPolicy.decide(facts(hit(ShieldMode.SILENCE, ShieldKind.SCAM)), emptyList(), ScreeningSettings(), now)
        assertEquals(Decision.Block(BlockAction.SILENCE, BlockReason.FAMILY_SHIELD), silenced.decision)
        assertEquals("Called a scam by someone in Family", silenced.verdict?.text)
        val blocked = CallPolicy.decide(facts(hit(ShieldMode.BLOCK, ShieldKind.SPAM_LIKELY)), emptyList(), ScreeningSettings(), now)
        assertEquals(Decision.Block(BlockAction.REJECT, BlockReason.FAMILY_SHIELD), blocked.decision)
        assertEquals("Called spam by someone in Family", blocked.verdict?.text)
        assertTrue(BlockReason.FAMILY_SHIELD.soft)
        assertFalse(BlockReason.FAMILY_SHIELD.expectedMayOverride)
    }

    @Test fun saved_contacts_and_emergency_numbers_always_ring() {
        val contact = CallPolicy.decide(facts(hit(ShieldMode.BLOCK), contact = true), emptyList(), ScreeningSettings(), now)
        assertEquals(Decision.Allow, contact.decision)
        assertEquals(AllowReason.CONTACT, contact.allowedBy)
        assertNull(contact.verdict)
        val emergency = CallPolicy.decide(facts(hit(ShieldMode.BLOCK), emergency = true), emptyList(), ScreeningSettings(), now)
        assertEquals(Decision.Allow, emergency.decision)
        assertEquals(AllowReason.EMERGENCY, emergency.allowedBy)
    }

    @Test fun no_hit_changes_nothing() {
        val r = CallPolicy.decide(facts(null), emptyList(), ScreeningSettings(), now)
        assertEquals(Decision.Allow, r.decision)
        assertNull(r.verdict)
        assertNotNull(r.trace)
    }
}
