package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberPassportTest {
    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_700_000_000_000L
    private fun line(n: String) = PassportLine("+44$n", "0$n")

    private val mumOld = line("7700900001")
    private val mumNew = line("7700900999")

    @Test fun the_first_sweep_only_takes_stock_so_nothing_already_saved_ever_warns() {
        val s = NumberPassport.sweep(PassportState(), mapOf("mum" to listOf(mumOld)), emptyMap(), t0)
        assertEquals(t0, s.since)
        assertTrue(mumOld.key in s.known)
        assertTrue(s.stamps.isEmpty())
        assertTrue(NumberPassport.unattributed(PassportState(), mapOf("mum" to listOf(mumOld))).isEmpty())
    }

    @Test fun another_app_changing_a_number_is_stamped_with_the_older_one() {
        val base = NumberPassport.sweep(PassportState(), mapOf("mum" to listOf(mumOld)), emptyMap(), t0)
        val now = mapOf("mum" to listOf(mumNew))
        assertEquals(mapOf("mum" to listOf(mumNew)), NumberPassport.unattributed(base, now))
        val s = NumberPassport.sweep(base, now, mapOf("mum" to Attribution(NumberOrigin.OTHER_APP, t0 + day)), t0 + 2 * day)
        val stamp = s.stamps.getValue(mumNew.key)
        assertEquals(NumberOrigin.OTHER_APP, stamp.origin)
        assertEquals(t0 + day, stamp.since)
        assertEquals("mum", stamp.contact)
        assertEquals(mumOld.number, stamp.older)
        assertFalse(mumOld.key in s.known)
        assertTrue(NumberPassport.warns(stamp, t0 + 3 * day, callsSince = 0))
    }

    @Test fun unknown_origin_and_mass_changes_never_warn() {
        val base = NumberPassport.sweep(PassportState(), mapOf("mum" to listOf(mumOld)), emptyMap(), t0)
        val s = NumberPassport.sweep(base, mapOf("mum" to listOf(mumOld, mumNew)), emptyMap(), t0 + day)
        assertEquals(NumberOrigin.UNKNOWN, s.stamps.getValue(mumNew.key).origin)
        assertFalse(NumberPassport.warns(s.stamps[mumNew.key], t0 + day, 0))
        assertFalse(NumberPassport.warns(null, t0, 0))
        // An account added at once: many contacts change, which is no targeted change.
        val many = (1..NumberPassport.MASS_CHANGE + 1).associate { "c$it" to listOf(line("77009100%02d".format(it))) }
        val origins = many.keys.associateWith { Attribution(NumberOrigin.SYNC, t0) }
        val mass = NumberPassport.sweep(base, many + ("mum" to listOf(mumOld)), origins, t0 + day)
        assertTrue(mass.stamps.values.all { it.origin == NumberOrigin.UNKNOWN })
    }

    @Test fun your_own_saves_are_stamped_you_and_paste_counts_as_not_you() {
        val base = NumberPassport.sweep(PassportState(), mapOf("mum" to listOf(mumOld)), emptyMap(), t0)
        val typed = NumberPassport.saved(base, "mum", listOf(mumOld), listOf(mumOld, mumNew), t0 + day) { NumberOrigin.YOU }
        assertEquals(NumberOrigin.YOU, typed.stamps.getValue(mumNew.key).origin)
        assertFalse(NumberPassport.warns(typed.stamps[mumNew.key], t0 + day, 0))
        // The next sweep sees nothing new: Parley's own write isn't taken for another app's.
        assertTrue(NumberPassport.unattributed(typed, mapOf("mum" to listOf(mumOld, mumNew))).isEmpty())
        val pasted = NumberPassport.saved(base, "mum", listOf(mumOld), listOf(mumNew), t0 + day) { NumberOrigin.PASTED }
        val stamp = pasted.stamps.getValue(mumNew.key)
        assertEquals(mumOld.number, stamp.older)
        assertTrue(NumberPassport.warns(stamp, t0 + day, 0))
    }

    @Test fun bulk_writes_keep_their_origin_through_the_next_sweep() {
        val base = NumberPassport.sweep(PassportState(), mapOf("mum" to listOf(mumOld)), emptyMap(), t0)
        val written = NumberPassport.written(base, listOf(mumNew, mumOld), NumberOrigin.IMPORTED, t0 + day)
        assertNull(written.stamps[mumOld.key])
        val s = NumberPassport.sweep(written, mapOf("mum" to listOf(mumOld), "ana" to listOf(mumNew)), emptyMap(), t0 + day)
        val stamp = s.stamps.getValue(mumNew.key)
        assertEquals(NumberOrigin.IMPORTED, stamp.origin)
        assertEquals("ana", stamp.contact)
        assertEquals(t0 + day, stamp.since)
    }

    @Test fun the_warning_is_for_fourteen_days_and_the_first_few_calls() {
        val stamp = NumberStamp(t0, NumberOrigin.PASTED, "mum")
        assertTrue(NumberPassport.warns(stamp, t0 + 13 * day, 2))
        assertFalse(NumberPassport.warns(stamp, t0 + 15 * day, 0))
        assertFalse(NumberPassport.warns(stamp, t0 + day, NumberPassport.QUIET_AFTER_CALLS))
        assertFalse(NumberPassport.warns(stamp.copy(origin = NumberOrigin.CARD), t0 + day, 0))
        assertFalse(NumberPassport.warns(stamp.copy(origin = NumberOrigin.RESTORED), t0 + day, 0))
    }

    @Test fun the_older_number_is_the_replaced_one_else_another_settled_number() {
        val stamp = NumberStamp(t0, NumberOrigin.SYNC, "mum")
        val other = line("7700900555")
        val fresh = line("7700900666")
        val offered = NumberPassport.olderNumber(
            stamp, mumNew.key,
            listOf(mumNew to stamp, fresh to NumberStamp(t0, NumberOrigin.PASTED), other to null),
            t0 + day,
        )
        assertEquals(other.number, offered)
        assertEquals(mumOld.number, NumberPassport.olderNumber(stamp.copy(older = mumOld.number), mumNew.key, emptyList(), t0))
        assertNull(NumberPassport.olderNumber(stamp, mumNew.key, listOf(mumNew to stamp), t0))
    }

    @Test fun it_survives_its_own_encoding_and_a_damaged_document_starts_empty() {
        val s = NumberPassport.saved(PassportState(since = t0), "mum", emptyList(), listOf(mumNew), t0) { NumberOrigin.SCANNED }
        assertEquals(s, NumberPassport.decode(NumberPassport.encode(s)))
        assertEquals(PassportState(), NumberPassport.decode("{not json"))
    }

    @Test fun a_number_unseen_while_it_rings_warns_only_for_a_change_after_the_last_look() {
        val looked = t0 + day
        assertEquals(NumberOrigin.OTHER_APP, NumberPassport.unseen(Attribution(NumberOrigin.OTHER_APP, looked + 1_000), looked, looked + 2_000)?.origin)
        // Older than the last look: the passport catching up (a new SIM reading national numbers differently), not a change.
        assertNull(NumberPassport.unseen(Attribution(NumberOrigin.SYNC, looked - 1_000), looked, looked + 2_000))
        assertNull(NumberPassport.unseen(Attribution(NumberOrigin.OTHER_APP, null), looked, looked + 2_000))
        assertNull(NumberPassport.unseen(Attribution(NumberOrigin.UNKNOWN, looked + 1_000), looked, looked + 2_000))
        assertNull(NumberPassport.unseen(Attribution(NumberOrigin.OTHER_APP, looked + 1_000), null, looked + 2_000))
    }
}
