package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowOrderTest {
    @Test fun rows_in_saved_order_are_left_alone() {
        assertEquals(emptySet<Long>(), RowOrder.rewrite(listOf(3L, 7L, 9L)))
        // New rows go after the saved ones anyway.
        assertEquals(emptySet<Long>(), RowOrder.rewrite(listOf(3L, 7L, null, null)))
        assertEquals(emptySet<Long>(), RowOrder.rewrite(emptyList()))
    }

    @Test fun a_row_moved_up_writes_again_only_what_follows_it() {
        // 9 moved to the top: 3 and 7 must come after it, so they are written again (in that order).
        assertEquals(setOf(3L, 7L), RowOrder.rewrite(listOf(9L, 3L, 7L)))
        // 7 moved down below 9: only 7 is written again.
        assertEquals(setOf(7L), RowOrder.rewrite(listOf(3L, 9L, 7L)))
    }

    @Test fun a_new_row_moved_above_saved_ones_takes_them_along() {
        assertEquals(setOf(3L, 7L), RowOrder.rewrite(listOf(null, 3L, 7L)))
        assertEquals(setOf(7L), RowOrder.rewrite(listOf(3L, null, 7L)))
    }

    @Test fun read_only_rows_are_never_written_again() {
        assertEquals(emptySet<Long>(), RowOrder.rewrite(listOf(9L, 3L, 7L), locked = setOf(3L)))
        // A read-only row in the kept start is fine.
        assertEquals(setOf(7L), RowOrder.rewrite(listOf(3L, 9L, 7L), locked = setOf(3L)))
        assertFalse(RowOrder.canReorder(listOf(3L, null), setOf(3L)))
        assertTrue(RowOrder.canReorder(listOf(3L, null), setOf(4L)))
    }

    @Test fun swap_moves_two_rows_and_ignores_bad_indices() {
        assertEquals(listOf("b", "a", "c"), RowOrder.swap(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "b"), RowOrder.swap(listOf("a", "b"), 0, 5))
    }

    @Test fun row_keys_follow_a_swap() {
        val k = RowKeys()
        val keys = k.keys("phone", 3)
        k.swapped("phone", 0, 2)
        assertEquals(listOf(keys[2], keys[1], keys[0]), k.keys("phone", 3))
        k.swapped("phone", 0, 9)
        k.swapped("nope", 0, 1)
        assertEquals(listOf(keys[2], keys[1], keys[0]), k.keys("phone", 3))
    }

    @Test fun row_edits_write_a_moved_row_again_in_place() {
        val a = RowEdits.Row(1, "im", mapOf("data1" to "a"))
        val b = RowEdits.Row(2, "im", mapOf("data1" to "b"))
        val ops = RowEdits.plan(listOf(a, b), listOf(b, a), setOf("im"), rewrite = RowOrder.rewrite(listOf(2L, 1L)))
        assertEquals(listOf(RowEdits.Op.Delete(1, "im"), RowEdits.Op.Insert("im", mapOf("data1" to "a"), replaces = 1)), ops)
    }
}
