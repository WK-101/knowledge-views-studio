package app.parley.common.sync.shared

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.KdfParams
import app.parley.common.backup.KdfPolicy
import app.parley.common.sync.shared.SharedLabelMembership.Event
import app.parley.common.sync.shared.SharedLabelMembership.State
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who is a member, the key-change state machine, invitations and history. */
class SharedLabelMembershipTest {
    private val label = SharedLabelFiles.newId()
    private val ana = TestSigner()
    private val sam = TestSigner()
    private val kim = TestSigner()
    private val eve = TestSigner()

    private fun journal(
        s: TestSigner,
        name: String,
        epoch: Int = 1,
        ticket: Ticket? = null,
        carried: List<Carried> = emptyList(),
        left: Boolean = false,
    ): Journal {
        val j = Journal(s.publicKey, name, epoch, ticket, carried, left, emptyList())
        return SharedLabelFiles.readJournal(label, SharedLabelFiles.journalName(s.publicKey), SharedLabelFiles.writeJournal(s, label, j)!!)!!
    }

    private fun invite(by: TestSigner, epoch: Int = 1) = SharedLabelFiles.ticket(by, label, epoch, SharedLabelFiles.newId())!!

    @Test fun members_are_the_anchor_and_everyone_a_member_let_in() {
        val journals = listOf(
            journal(ana, "Ana"),
            journal(sam, "Sam", ticket = invite(ana)),
            journal(kim, "Kim", ticket = invite(sam)), // let in by Sam, who was let in by Ana
        )
        val m = SharedLabelRoster.members(label, 1, ana.publicKey, "Ana", journals.reversed())
        assertEquals(listOf("Ana", "Sam", "Kim").sorted(), m.map { it.name }.sorted())
        assertTrue(m.single { it.name == "Ana" }.anchor)
        assertEquals(sam.hex, m.single { it.name == "Kim" }.invitedBy)
    }

    @Test fun a_journal_without_a_members_ticket_is_not_a_member() {
        val selfMade = SharedLabelFiles.ticket(eve, label, 1, SharedLabelFiles.newId())!!
        val otherLabel = SharedLabelFiles.ticket(ana, SharedLabelFiles.newId(), 1, SharedLabelFiles.newId())!!
        val oldEpoch = invite(ana, epoch = 1)
        val m = SharedLabelRoster.members(
            label, 2, ana.publicKey, "Ana",
            listOf(journal(ana, "Ana", 2), journal(eve, "Eve", 2, selfMade), journal(sam, "Sam", 2, otherLabel), journal(kim, "Kim", 2, oldEpoch)),
        )
        assertEquals(listOf("Ana"), m.map { it.name })
    }

    @Test fun carried_members_stay_after_a_key_change_and_leavers_go() {
        val anchor = journal(ana, "Ana", 2, carried = listOf(Carried(sam.publicKey, "Sam"), Carried(kim.publicKey, "Kim")))
        val m = SharedLabelRoster.members(label, 2, ana.publicKey, "Ana", listOf(anchor, journal(kim, "Kim", 2, left = true)))
        assertEquals(listOf("Ana", "Sam"), m.map { it.name })
        assertTrue(m.single { it.name == "Sam" }.awaitingKey)
    }

    @Test fun the_key_change_state_machine() {
        var s: State = State.Active(1)
        s = SharedLabelMembership.next(s, Event.HeaderRead(1, keyOpens = true))
        assertEquals(State.Active(1), s)
        s = SharedLabelMembership.next(s, Event.HeaderRead(2, keyOpens = false))
        assertEquals(State.KeyChanged(1, 2), s)
        assertFalse(SharedLabelMembership.syncs(s))
        // An older invitation doesn't bring it back; the new one does.
        assertEquals(State.KeyChanged(1, 2), SharedLabelMembership.next(s, Event.InvitationOpened(0)))
        s = SharedLabelMembership.next(s, Event.InvitationOpened(2))
        assertEquals(State.Active(2), s)
        assertEquals(State.Active(3), SharedLabelMembership.next(s, Event.KeyRotated))
        assertEquals(State.Left, SharedLabelMembership.next(s, Event.Leave))
        assertEquals(State.Left, SharedLabelMembership.next(State.Left, Event.HeaderRead(5, true)))
    }

    @Test fun a_rotation_carries_who_stays_and_resigns_the_rest() {
        val members = SharedLabelRoster.members(
            label, 1, ana.publicKey, "Ana",
            listOf(journal(ana, "Ana"), journal(sam, "Sam", ticket = invite(ana)), journal(kim, "Kim", ticket = invite(ana))),
        )
        val r = SharedLabelMembership.rotation(members, ana.hex, setOf(kim.hex))
        assertEquals(listOf("Sam"), r.carried.map { it.name })
        assertTrue(r.resign(kim.hex))
        assertTrue(r.resign(eve.hex))
        assertFalse(r.resign(sam.hex))
        assertFalse(r.resign(ana.hex))
    }

    @Test fun invitations_open_with_their_passcode_or_passphrase_only() {
        val (_, key) = SharedLabelCrypto.newHeader(label, 1, "pass".toCharArray(), KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS))
        val i = Invitation(label, "Family", "Syncthing › Family", 1, key, ana.publicKey, "Ana", ana.publicKey, "Ana", invite(ana))
        val link = SharedLabelInvites.qrLink(i, "AB12-CD34")
        assertTrue(SharedLabelInvites.isLink(link))
        val back = SharedLabelInvites.fromLink(link, "ab12cd34")
        assertNotNull(back)
        assertEquals("Family", back!!.title)
        assertEquals("Syncthing › Family", back.folderHint)
        assertArrayEquals(key, back.key)
        assertEquals(i.inviterFingerprint, back.inviterFingerprint)
        assertNull(SharedLabelInvites.fromLink(link, "WRONG"))

        val cheap = KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS)
        val file = SharedLabelInvites.file(i, "family pass".toCharArray(), cheap)
        assertNotNull(SharedLabelInvites.fromFile(file, "family pass".toCharArray(), KdfPolicy.exactly(cheap)))
        assertNull(SharedLabelInvites.fromFile(file, "nope".toCharArray(), KdfPolicy.exactly(cheap)))
    }

    @Test fun an_invitation_with_a_ticket_for_another_label_is_refused() {
        val foreign = SharedLabelFiles.ticket(ana, SharedLabelFiles.newId(), 1, SharedLabelFiles.newId())!!
        val i = Invitation(label, "Family", "", 1, ByteArray(32), ana.publicKey, "Ana", ana.publicKey, "Ana", foreign)
        assertNull(SharedLabelInvites.decode(SharedLabelInvites.encode(i)))
    }

    @Test fun history_lines_merge_newest_first_and_say_what_changed() {
        val sid = SharedLabelFiles.newId()
        val kept = listOf(HistoryItem(sam.hex, "Sam", 1, sid, "Dr Lee", ChangeKind.ADDED, emptySet(), 100))
        val incoming = listOf(
            HistoryItem(sam.hex, "Sammy", 1, sid, "Dr Lee", ChangeKind.ADDED, emptySet(), 100),
            HistoryItem(sam.hex, "Sammy", 2, sid, "Dr Lee", ChangeKind.EDITED, setOf(CardField.PHONES), 300),
            HistoryItem(ana.hex, "Ana", 1, sid, "Dr Lee", ChangeKind.EDITED, setOf(CardField.NOTE, CardField.EMAILS), 200),
        )
        val all = SharedLabelHistory.merge(kept, incoming)
        assertEquals(listOf(300L, 200L, 100L), all.map { it.at })
        assertTrue(all.filter { it.memberHex == sam.hex }.all { it.memberName == "Sammy" })
        assertEquals(SharedLabelHistory.Phrase.Changed(CardField.PHONES), SharedLabelHistory.phrase(all[0]))
        assertEquals(SharedLabelHistory.Phrase.ChangedTwo(CardField.EMAILS, CardField.NOTE), SharedLabelHistory.phrase(all[1]))
        assertEquals(SharedLabelHistory.Phrase.Added, SharedLabelHistory.phrase(all[2]))
        assertEquals(1, SharedLabelHistory.filter(all, ana.hex).size)
    }
}
