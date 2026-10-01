package app.parley.common.backup

import app.parley.common.backup.Fixtures.contact
import app.parley.common.backup.Fixtures.email
import app.parley.common.backup.Fixtures.phone
import app.parley.common.people.AccountKey
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWatchdogTest {
    private val google = AccountKey("com.google", "me@example.com")
    private val work = AccountKey("com.google", "work@example.com")
    private val dav = AccountKey("bitfire.at.davdroid", "home")

    private fun people(prefix: String, n: Int, account: AccountKey = google) =
        (1..n).map {
            val number = "+44 7700 9%05d".format(java.util.Locale.ROOT, it)
            contact("$prefix$it", "Person $prefix$it", phone(number), account = account.type to account.name, sourceId = "src-$prefix$it")
        }

    private fun accounts(counts: Map<AccountKey, Int>, syncOff: Set<AccountKey> = emptySet(), master: Boolean = true, signedIn: Set<AccountKey> = counts.keys) =
        WatchAccounts(signedIn = signedIn, counts = counts, syncOff = syncOff, masterSyncOn = master)

    private fun input(
        diff: SnapshotDiff,
        before: WatchAccounts?,
        after: WatchAccounts,
        userKeys: Set<String> = emptySet(),
        acknowledged: Set<String> = emptySet(),
    ) = SyncWatchdog.Input(since = 1_000, now = 2_000, diff = diff, userKeys = userKeys, acknowledged = acknowledged, before = before, after = after)

    private fun removed(list: List<app.parley.common.record.ContactRecord>) = SnapshotDiff(emptyList(), list, emptyList())

    @Test fun largeExternalLossIsReportedWithItsContacts() {
        val gone = people("g", 142)
        val events = SyncWatchdog.check(input(removed(gone), accounts(mapOf(google to 300)), accounts(mapOf(google to 158))))
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals(WatchKind.CONTACTS_VANISHED, e.kind)
        assertEquals(142, e.count)
        assertEquals(300, e.total)
        assertEquals(google, e.account)
        assertEquals(gone.map { it.key }.sorted(), e.keys)
        assertTrue(e.restorable)
    }

    @Test fun smallLossesStayQuiet() {
        // 9 contacts: below the absolute minimum.
        assertTrue(SyncWatchdog.check(input(removed(people("a", 9)), accounts(mapOf(google to 20)), accounts(mapOf(google to 11)))).isEmpty())
        // 12 of 1000: below 5 %.
        assertTrue(SyncWatchdog.check(input(removed(people("b", 12)), accounts(mapOf(google to 1000)), accounts(mapOf(google to 988)))).isEmpty())
        // 50 of 1000 is exactly 5 %: reported.
        assertEquals(1, SyncWatchdog.check(input(removed(people("c", 50)), accounts(mapOf(google to 1000)), accounts(mapOf(google to 950)))).size)
    }

    @Test fun parleysOwnDeletionsAndAcknowledgedOnesNeverCount() {
        val gone = people("d", 20)
        val keys = gone.map { it.key }.toSet()
        assertTrue(SyncWatchdog.check(input(removed(gone), accounts(mapOf(google to 40)), accounts(mapOf(google to 20)), userKeys = keys)).isEmpty())
        assertTrue(SyncWatchdog.check(input(removed(gone), accounts(mapOf(google to 40)), accounts(mapOf(google to 20)), acknowledged = keys)).isEmpty())
        // Half were deleted in Parley: the other 10 still make 25 %.
        val half = keys.take(10).toSet()
        val e = SyncWatchdog.check(input(removed(gone), accounts(mapOf(google to 40)), accounts(mapOf(google to 20)), userKeys = half)).single()
        assertEquals(10, e.count)
        assertTrue(e.keys.none { it in half })
    }

    @Test fun aContactBackUnderANewKeyIsNotALoss() {
        val gone = people("k", 15)
        // The same raw contacts (same source ids) under new keys, e.g. after the provider re-joined them.
        val back = gone.map { it.copy(key = it.key + "-new") }
        val diff = SnapshotDiff(back, gone, emptyList())
        assertTrue(SyncWatchdog.check(input(diff, accounts(mapOf(google to 15)), accounts(mapOf(google to 15)))).isEmpty())
        // Without source ids, account + name + numbers recognise them.
        val local = (1..12).map { contact("l$it", "Local $it", phone("0770090000$it"), account = null to null) }
        val relocal = local.map { it.copy(key = "x" + it.key) }
        assertTrue(SyncWatchdog.vanished(SnapshotDiff(relocal, local, emptyList())).isEmpty())
    }

    @Test fun aWholeAccountEmptyingIsReportedEvenWhenSmall() {
        val gone = people("w", 4, work)
        val e = SyncWatchdog.check(input(removed(gone), accounts(mapOf(google to 500, work to 4)), accounts(mapOf(google to 500, work to 0)))).single()
        assertEquals(WatchKind.ACCOUNT_EMPTIED, e.kind)
        assertEquals(work, e.account)
        assertEquals(4, e.count)
        // Two contacts emptying an account is not news.
        assertTrue(SyncWatchdog.check(input(removed(people("t", 2, work)), accounts(mapOf(work to 2)), accounts(mapOf(work to 0)))).isEmpty())
    }

    @Test fun aRemovedAccountIsReportedOnceItHeldContacts() {
        val gone = people("r", 3, dav)
        val before = accounts(mapOf(google to 100, dav to 3))
        val after = accounts(mapOf(google to 100), signedIn = setOf(google))
        val e = SyncWatchdog.check(input(removed(gone), before, after)).single()
        assertEquals(WatchKind.ACCOUNT_REMOVED, e.kind)
        assertEquals(dav, e.account)
        assertEquals(3, e.count)
        // An empty account going away is not a contacts matter.
        val empty = SyncWatchdog.check(input(removed(emptyList()), accounts(mapOf(google to 100, dav to 0)), after))
        assertTrue(empty.isEmpty())
    }

    @Test fun syncSwitchedOffIsReportedOnTheTransitionOnly() {
        val before = accounts(mapOf(google to 100, work to 0))
        val after = accounts(mapOf(google to 100, work to 0), syncOff = setOf(google, work))
        val events = SyncWatchdog.check(input(removed(emptyList()), before, after))
        // Work holds no contacts: not reported.
        assertEquals(listOf(WatchKind.SYNC_OFF), events.map { it.kind })
        assertEquals(google, events.single().account)
        assertTrue(!events.single().restorable)
        // Already off at the baseline: nothing new.
        assertTrue(SyncWatchdog.check(input(removed(emptyList()), after, after)).isEmpty())
        // First run: no baseline, no switch told.
        assertTrue(SyncWatchdog.check(input(removed(emptyList()), null, after)).isEmpty())
        // Unknown sync settings never count as off.
        assertTrue(SyncWatchdog.check(input(removed(emptyList()), before, after.copy(syncKnown = false))).isEmpty())
    }

    @Test fun masterSyncOffIsReported() {
        val before = accounts(mapOf(google to 10))
        val e = SyncWatchdog.check(input(removed(emptyList()), before, before.copy(masterSyncOn = false))).single()
        assertEquals(WatchKind.MASTER_SYNC_OFF, e.kind)
    }

    @Test fun manyContactsLosingNumbersIsReported() {
        val olds = people("n", 12)
        val changes = olds.map { r ->
            val after = r.copy(raws = r.raws.map { raw -> raw.copy(rows = raw.rows.filter { it.mimeType != Mime.PHONE } + email("${r.key}@x.com")) })
            Snapshots.diffRecords(r, after)
        }
        val diff = SnapshotDiff(emptyList(), emptyList(), changes)
        val e = SyncWatchdog.check(input(diff, accounts(mapOf(google to 100)), accounts(mapOf(google to 100)))).single()
        assertEquals(WatchKind.NUMBERS_LOST, e.kind)
        assertEquals(12, e.count)
        // A number reformatted (removed and added) is not a loss.
        val reformatted = olds.map { r ->
            val rows = r.raws.single().rows.map { if (it.mimeType == Mime.PHONE) phone("+44 " + it["data1"]) else it }
            val after = r.copy(raws = listOf(r.raws.single().copy(rows = rows)))
            Snapshots.diffRecords(r, after)
        }
        assertTrue(SyncWatchdog.numberLosses(SnapshotDiff(emptyList(), emptyList(), reformatted)).isEmpty())
    }

    @Test fun lostNumbersComparesDigits() {
        val before = contact("a", "A", phone("+44 7700 900001"), phone("07700 900002"), phone("+44 (7700) 900001"))
        val now = contact("a", "A", phone("447700900001"))
        val lost = SyncWatchdog.lostNumbers(before, now)
        assertEquals(listOf("07700 900002"), lost.map { it["data1"] })
    }

    @Test fun mostImportantFirstAndFingerprintsStable() {
        val before = accounts(mapOf(google to 100, dav to 3))
        val after = accounts(mapOf(google to 60), syncOff = setOf(google), signedIn = setOf(google))
        val diff = removed(people("v", 40) + people("r", 3, dav))
        val events = SyncWatchdog.check(input(diff, before, after))
        assertEquals(listOf(WatchKind.ACCOUNT_REMOVED, WatchKind.CONTACTS_VANISHED, WatchKind.SYNC_OFF), events.map { it.kind })
        val again = SyncWatchdog.check(input(diff, before, after))
        assertEquals(events.map { it.fingerprint }, again.map { it.fingerprint })
        assertNotEquals(events[0].fingerprint, events[1].fingerprint)
    }

    @Test fun freshDropsWhatWasSaid() {
        val e = SyncWatchdog.check(input(removed(people("f", 20)), accounts(mapOf(google to 40)), accounts(mapOf(google to 20)))).single()
        assertEquals(listOf(e), SyncWatchdog.fresh(listOf(e, e), emptySet()))
        assertTrue(SyncWatchdog.fresh(listOf(e), setOf(e.fingerprint)).isEmpty())
    }

    @Test fun pruneAcknowledgedKeepsRecentOnes() {
        val kept = SyncWatchdog.pruneAcknowledged(mapOf("a" to 100L, "b" to 900L), now = 1_000, keepMs = 500)
        assertEquals(setOf("b"), kept.keys)
    }

    @Test fun memorySaysEachEventOnceAndRemembersDismissals() {
        val after = accounts(mapOf(google to 20))
        val e = SyncWatchdog.check(input(removed(people("m", 20)), accounts(mapOf(google to 40)), after)).single()
        val (m1, fresh1) = SyncWatchMemory().afterRun(listOf(e), after, newest = 5_000, at = 6_000)
        assertEquals(listOf(e), fresh1)
        assertEquals(listOf(e), m1.pending)
        assertEquals(5_000, m1.baseline)
        assertEquals(after, m1.accounts!!.toWatch())
        // The next run finds the same event again (an older baseline): nothing new is said.
        val (m2, fresh2) = m1.afterRun(listOf(e), after, newest = 7_000, at = 8_000)
        assertTrue(fresh2.isEmpty())
        assertEquals(1, m2.pending.size)
        // "It was me": the card goes, the contacts stay acknowledged, and the memory survives a round trip.
        val m3 = SyncWatchMemory.decode(m2.dismiss(e, 9_000).encode())
        assertTrue(m3.pending.isEmpty())
        assertEquals(listOf(e), m3.reopen(e).pending)
        assertEquals(m3.reopen(e), m3.reopen(e).reopen(e))
        assertTrue(e.keys.all { it in m3.acknowledged })
        assertTrue(SyncWatchdog.check(input(removed(people("m", 20)), accounts(mapOf(google to 40)), after, acknowledged = m3.acknowledged.keys)).isEmpty())
    }

    @Test fun unreadableMemoryStartsFresh() {
        assertEquals(SyncWatchMemory(), SyncWatchMemory.decode("{not json"))
        assertEquals(SyncWatchMemory(), SyncWatchMemory.decode(null))
    }
}
