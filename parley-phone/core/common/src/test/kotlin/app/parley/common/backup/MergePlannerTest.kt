package app.parley.common.backup

import app.parley.common.backup.Fixtures.contact
import app.parley.common.backup.Fixtures.email
import app.parley.common.backup.Fixtures.note
import app.parley.common.backup.Fixtures.phone
import app.parley.common.backup.Fixtures.photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePlannerTest {
    private fun single(existing: List<app.parley.common.record.ContactRecord>, b: app.parley.common.record.ContactRecord) =
        MergePlanner.plan(existing, listOf(b)).actions.single()

    @Test fun unmatchedIsNew() {
        val a = single(listOf(contact("k1", "Ada Lovelace", phone("+44 20 7946 0000"))), contact("k2", "Bob Smith", phone("555 123 4567")))
        assertTrue(a is MergeAction.New)
    }

    @Test fun identicalBySourceIdDespiteDifferentKey() {
        val e = contact("local-key", "Ada Lovelace", phone("+44 20 7946 0000"), sourceId = "abc")
        val b = contact("other-key", "Ada Lovelace", phone("+44 20 7946 0000"), sourceId = "abc")
        val a = single(listOf(e), b) as MergeAction.Identical
        assertEquals(MatchedBy.SOURCE_ID, a.matchedBy)
        assertEquals(e, a.existing)
    }

    @Test fun sourceIdRequiresSameAccount() {
        val e = contact("k1", "Ada Lovelace", sourceId = "abc", account = "com.google" to "a@x.com")
        val b = contact("k2", "Ada Lovelace", sourceId = "abc", account = "com.google" to "b@x.com")
        val a = single(listOf(e), b)
        // Falls through to the fingerprint (name) match rather than the source id.
        assertEquals(MatchedBy.FINGERPRINT, (a as MergeAction.Identical).matchedBy)
    }

    @Test fun matchOrderPrefersSourceIdThenKey() {
        val byKey = contact("K", "Ada Lovelace")
        val bySource = contact("other", "Ada Lovelace", sourceId = "s1")
        val b = contact("K", "Ada Lovelace", sourceId = "s1")
        val a = single(listOf(byKey, bySource), b) as MergeAction.Identical
        assertEquals(bySource, a.existing)
        assertEquals(MatchedBy.SOURCE_ID, a.matchedBy)

        val a2 = single(listOf(contact("x", "Ada Lovelace"), byKey), contact("K", "Ada Lovelace")) as MergeAction.Identical
        assertEquals(byKey, a2.existing)
        assertEquals(MatchedBy.KEY, a2.matchedBy)
    }

    @Test fun enrichAddsOnlyMissingRowsAndIgnoresReformattedPhones() {
        val e = contact("k1", "Ada Lovelace", phone("+44 20 7946 0000"), email("ADA@example.com"))
        val b = contact("k1", "Ada Lovelace", phone("020 7946 0000"), email("ada@example.com"), email("work@example.com"), note("met at conf"))
        val a = single(listOf(e), b) as MergeAction.Enrich
        assertEquals(listOf(email("work@example.com"), note("met at conf")), a.missingRows)
    }

    @Test fun fingerprintMatchByPhone() {
        val e = contact("dev1", "A L", phone("+1 (650) 555-0100"), account = "local" to null)
        val b = contact("dev2", "Ada", phone("650-555-0100"), email("ada@x.com"))
        val a = single(listOf(e), b)
        assertTrue(a is MergeAction.Conflict)
        a as MergeAction.Conflict
        assertEquals(MatchedBy.FINGERPRINT, a.matchedBy)
        assertTrue(a.reasons.first().startsWith("Different name"))
        assertTrue(email("ada@x.com") in a.missingRows)
    }

    @Test fun fingerprintMatchByEmailAndNameTokens() {
        val e = contact("dev1", "Lovelace Ada", email("Ada@Example.com"))
        val b = contact("dev2", "Lovelace Ada", email("ada@example.com"), phone("555 000 1111"))
        val a = single(listOf(e), b) as MergeAction.Enrich
        assertEquals(MatchedBy.FINGERPRINT, a.matchedBy)
        assertEquals(listOf(phone("555 000 1111")), a.missingRows)

        // Accent- and word-order-insensitive name key.
        val e2 = contact("x", "José García")
        val b2 = contact("y", "garcia jose")
        val a2 = single(listOf(e2), b2)
        assertEquals(MatchedBy.FINGERPRINT, (a2 as MergeAction.Conflict).matchedBy) // same person, spelled differently
    }

    @Test fun bestFingerprintCandidateWins() {
        val weak = contact("a", "Ada Lovelace")
        val strong = contact("b", "Ada Lovelace", phone("+1 650 555 0100"), email("ada@x.com"))
        val b = contact("c", "Ada Lovelace", phone("650 555 0100"), email("ada@x.com"))
        val a = single(listOf(weak, strong), b) as MergeAction.Identical
        assertEquals(strong, a.existing)
    }

    @Test fun conflictOnSameKeyDifferentName() {
        val e = contact("k1", "Ada Lovelace", phone("111 222 3333"))
        val b = contact("k1", "Augusta King", phone("111 222 3333"))
        val a = single(listOf(e), b) as MergeAction.Conflict
        assertEquals(MatchedBy.KEY, a.matchedBy)
        assertTrue(a.missingRows.isEmpty()) // the name row is single-valued, never added
    }

    @Test fun differentPhotoIsConflictSamePhotoIsIdentical() {
        val p1 = ByteArray(10) { 1 }
        val p2 = ByteArray(10) { 2 }
        val e = contact("k1", "Ada Lovelace", photo(p1))
        assertTrue(single(listOf(e), contact("k1", "Ada Lovelace", photo(p1.copyOf()))) is MergeAction.Identical)
        val c = single(listOf(e), contact("k1", "Ada Lovelace", photo(p2))) as MergeAction.Conflict
        assertEquals(listOf("Different photo"), c.reasons)
        // No photo locally: the photo is simply added.
        val en = single(listOf(contact("k1", "Ada Lovelace")), contact("k1", "Ada Lovelace", photo(p2))) as MergeAction.Enrich
        assertEquals(1, en.missingRows.size)
    }

    @Test fun duplicateBackupsDoNotAddTheSameRowTwice() {
        val e = contact("k1", "Ada Lovelace")
        val b1 = contact("k1", "Ada Lovelace", email("new@x.com"))
        val b2 = contact("k1b", "Ada Lovelace", email("new@x.com"))
        val plan = MergePlanner.plan(listOf(e), listOf(b1, b2))
        assertTrue(plan.actions[0] is MergeAction.Enrich)
        assertTrue(plan.actions[1] is MergeAction.Identical)
    }

    @Test fun modesAndSummary() {
        val existing = listOf(contact("k1", "Ada Lovelace", phone("111 222 3333")), contact("k2", "Bob Smith"))
        val backup = listOf(
            contact("k1", "Ada Lovelace", phone("111 222 3333")), // identical
            contact("k2", "Bob Smith", email("bob@x.com")), // enrich (1 row)
            contact("k3", "Carol Jones", phone("999 888 7777")), // new (2 rows incl. name)
            contact("k4", "Ada Byron", phone("111-222-3333")), // conflict via phone
        )
        val merge = MergePlanner.plan(existing, backup)
        assertEquals(MergeSummary(new = 1, identical = 1, enrich = 1, conflict = 1, rowsToAdd = 3, toDelete = 0), merge.summary)
        assertTrue(merge.toDelete.isEmpty())

        val addAll = MergePlanner.plan(existing, backup, RestoreMode.ADD_ALL)
        assertEquals(4, addAll.summary.new)
        assertTrue(addAll.toDelete.isEmpty())

        val replace = MergePlanner.plan(existing, backup, RestoreMode.REPLACE)
        assertEquals(4, replace.summary.new)
        assertEquals(existing, replace.toDelete)
    }

    @Test fun mergeNeverProposesDeletion() {
        val e = contact("k1", "Ada Lovelace", phone("111 222 3333"), email("a@x.com"), note("keep me"))
        val b = contact("k1", "Ada Lovelace")
        assertTrue(single(listOf(e), b) is MergeAction.Identical)
    }
}
