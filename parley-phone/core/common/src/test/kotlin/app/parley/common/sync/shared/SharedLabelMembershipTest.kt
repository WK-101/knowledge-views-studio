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

    private val now = 1_800_000_000_000L
    private val week = SharedLabelInvites.TTL_MS

    private fun invite(by: TestSigner, epoch: Int = 1, id: String = SharedLabelFiles.newId(), expires: Long = now + week) =
        SharedLabelFiles.ticket(by, label, epoch, id, expires)!!

    @Test fun members_are_the_anchor_and_everyone_a_member_let_in() {
        val journals = listOf(
            journal(ana, "Ana"),
            journal(sam, "Sam", ticket = invite(ana)),
            journal(kim, "Kim", ticket = invite(sam)), // let in by Sam, who was let in by Ana
        )
        val m = SharedLabelRoster.members(label, 1, ana.publicKey, "Ana", journals.reversed(), now)
        assertEquals(listOf("Ana", "Sam", "Kim").sorted(), m.map { it.name }.sorted())
        assertTrue(m.single { it.name == "Ana" }.anchor)
        assertEquals(sam.hex, m.single { it.name == "Kim" }.invitedBy)
    }

    @Test fun a_journal_without_a_members_ticket_is_not_a_member() {
        val selfMade = SharedLabelFiles.ticket(eve, label, 1, SharedLabelFiles.newId(), now + week)!!
        val otherLabel = SharedLabelFiles.ticket(ana, SharedLabelFiles.newId(), 1, SharedLabelFiles.newId(), now + week)!!
        val oldEpoch = invite(ana, epoch = 1)
        val m = SharedLabelRoster.members(
            label, 2, ana.publicKey, "Ana",
            listOf(journal(ana, "Ana", 2), journal(eve, "Eve", 2, selfMade), journal(sam, "Sam", 2, otherLabel), journal(kim, "Kim", 2, oldEpoch)),
            now,
        )
        assertEquals(listOf("Ana"), m.map { it.name })
    }

    @Test fun carried_members_stay_after_a_key_change_and_leavers_go() {
        val anchor = journal(ana, "Ana", 2, carried = listOf(Carried(sam.publicKey, "Sam"), Carried(kim.publicKey, "Kim")))
        val m = SharedLabelRoster.members(label, 2, ana.publicKey, "Ana", listOf(anchor, journal(kim, "Kim", 2, left = true)), now)
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
            now,
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
        val back = SharedLabelInvites.fromLink(link, "ab12cd34", now)
        assertNotNull(back)
        assertEquals("Family", back!!.title)
        assertEquals("Syncthing › Family", back.folderHint)
        assertArrayEquals(key, back.key)
        assertEquals(i.inviterFingerprint, back.inviterFingerprint)
        assertNull(SharedLabelInvites.fromLink(link, "WRONG", now))

        val cheap = KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS)
        val file = SharedLabelInvites.file(i, "family pass".toCharArray(), cheap)
        assertNotNull(SharedLabelInvites.fromFile(file, "family pass".toCharArray(), KdfPolicy.exactly(cheap), now))
        assertNull(SharedLabelInvites.fromFile(file, "nope".toCharArray(), KdfPolicy.exactly(cheap), now))
    }

    @Test fun invitations_expire_and_qr_codes_are_hard_to_guess() {
        // L3: a week, then a new invitation is needed.
        val (_, key) = SharedLabelCrypto.newHeader(label, 1, "pass".toCharArray(), KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS))
        val i = Invitation(label, "Family", "", 1, key, ana.publicKey, "Ana", ana.publicKey, "Ana", invite(ana))
        assertNotNull(SharedLabelInvites.decode(SharedLabelInvites.encode(i), now + week - 1))
        assertNull(SharedLabelInvites.decode(SharedLabelInvites.encode(i), now + week + 1))
        // The QR passcode: at least 64 bits, under the backup's scrypt (not a fast PBKDF2).
        val code = SharedLabelInvites.newPasscode()
        val letters = code.filter { it.isLetterOrDigit() }
        assertTrue(letters.length * kotlin.math.ln(30.0) / kotlin.math.ln(2.0) >= 64)
        assertEquals(BackupCrypto.DEFAULT_KDF, SharedLabelInvites.QR_KDF)
        assertTrue(SharedLabelInvites.QR_KDF is KdfParams.Scrypt)
        assertNotNull(SharedLabelInvites.fromLink(SharedLabelInvites.qrLink(i, code), code.lowercase(), now))
    }

    @Test fun an_invitation_lets_in_one_member_and_not_after_it_expired() {
        // L3: Sam and Eve both show the same invitation: neither is let in, unless one was already counted.
        val id = SharedLabelFiles.newId()
        val twice = listOf(journal(ana, "Ana"), journal(sam, "Sam", ticket = invite(ana, id = id)), journal(eve, "Mom", ticket = invite(ana, id = id)))
        assertEquals(listOf("Ana"), SharedLabelRoster.members(label, 1, ana.publicKey, "Ana", twice, now).map { it.name })
        assertEquals(listOf("Ana", "Sam"), SharedLabelRoster.members(label, 1, ana.publicKey, "Ana", twice, now, known = setOf(sam.hex)).map { it.name })
        // An expired invitation lets nobody new in (a phone that was off gets a week's grace); a member stays.
        val late = listOf(journal(ana, "Ana"), journal(kim, "Kim", ticket = invite(ana, expires = now - SharedLabelRoster.LATE_GRACE_MS - 1)))
        assertEquals(listOf("Ana"), SharedLabelRoster.members(label, 1, ana.publicKey, "Ana", late, now).map { it.name })
        assertEquals(2, SharedLabelRoster.members(label, 1, ana.publicKey, "Ana", late, now, known = setOf(kim.hex)).size)
        val offline = listOf(journal(ana, "Ana"), journal(kim, "Kim", ticket = invite(ana, expires = now - 1)))
        assertEquals(2, SharedLabelRoster.members(label, 1, ana.publicKey, "Ana", offline, now).size)
    }

    @Test fun a_key_change_is_signed_over_the_new_header() {
        // M3: the new anchor signs the new header; anyone else's signature, or another header, doesn't pass.
        val (header, _) = SharedLabelCrypto.newHeader(label, 2, "pass".toCharArray(), KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS))
        val sig = SharedLabelFiles.readHeaderSig(label, SharedLabelFiles.writeHeaderSig(ana, label, 2, header)!!)!!
        assertEquals(ana.hex, sig.signerHex)
        assertEquals(2, sig.epoch)
        assertEquals(SharedLabelFiles.headerHash(header), sig.headerHash)
        assertNull(SharedLabelFiles.readHeaderSig(SharedLabelFiles.newId(), SharedLabelFiles.writeHeaderSig(ana, label, 2, header)!!))
        val forged = String(SharedLabelFiles.writeHeaderSig(ana, label, 2, header)!!).replace("\\\"epoch\\\":2", "\\\"epoch\\\":3")
        assertNull(SharedLabelFiles.readHeaderSig(label, forged.toByteArray()))
    }

    @Test fun joined_labels_never_land_in_a_label_of_the_same_name() {
        // M4.
        val suffixed = { n: Int -> if (n == 1) "Family (shared)" else "Family (shared $n)" }
        assertEquals("Family", SharedLabelTitles.fresh("Family", listOf("Work"), suffixed))
        assertEquals("Family (shared)", SharedLabelTitles.fresh("Family", listOf("Family", "Work"), suffixed))
        assertEquals("Family (shared 2)", SharedLabelTitles.fresh(" Family", listOf("Family", "Family (shared)"), suffixed))
    }

    @Test fun an_invitation_with_a_ticket_for_another_label_is_refused() {
        val foreign = SharedLabelFiles.ticket(ana, SharedLabelFiles.newId(), 1, SharedLabelFiles.newId(), now + week)!!
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
