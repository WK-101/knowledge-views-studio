package app.parley.common.people

import app.parley.common.people.ThreeWayMerge.Result
import app.parley.common.people.ThreeWayMerge.Side
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreeWayMergeTest {
    @Test fun a_field_changed_on_one_side_takes_that_side() {
        assertEquals(Result.Resolved("Ada L.", Side.MINE), ThreeWayMerge.merge("Ada", "Ada L.", "Ada"))
        assertEquals(Result.Resolved("Countess", Side.THEIRS), ThreeWayMerge.merge("Ada", "Ada", "Countess"))
        assertEquals(Result.Resolved("same", Side.THEIRS), ThreeWayMerge.merge("old", "same", "same"))
    }

    @Test fun both_sides_changing_a_field_differently_is_a_conflict() {
        assertEquals(Result.Conflict("mine", "theirs"), ThreeWayMerge.merge("old", "mine", "theirs"))
    }

    @Test fun without_a_base_every_difference_is_a_conflict() {
        assertEquals(Result.Conflict("a", "b"), ThreeWayMerge.merge(null, "a", "b"))
        assertEquals(Result.Resolved("a", Side.THEIRS), ThreeWayMerge.merge(null, "a", "a"))
    }

    @Test fun sides_use_picks_for_conflicts_and_the_merge_for_the_rest() {
        val fields = mapOf(
            "name" to Triple<String?, String, String>("Ada", "Ada L.", "Ada"),
            "note" to Triple<String?, String, String>("n", "n", "n2"),
            "phone" to Triple<String?, String, String>("1", "2", "3"),
            "email" to Triple<String?, String, String>("x", "y", "z"),
        )
        val sides = ThreeWayMerge.sides(fields, mapOf("phone" to Side.THEIRS), fallback = Side.MINE, hasBase = true)
        assertEquals(mapOf("name" to Side.MINE, "note" to Side.THEIRS, "phone" to Side.THEIRS, "email" to Side.MINE), sides)
    }

    @Test fun rebased_rows_keep_only_ids_that_still_exist() {
        data class Row(val id: Long?, val v: String)
        val mine = listOf(Row(1, "a"), Row(2, "b"), Row(null, "c"), Row(1, "dup"))
        val out = ThreeWayMerge.rebaseIds(mine, setOf(1L, 5L), { it.id }) { r, i -> r.copy(id = i) }
        assertEquals(listOf(Row(1, "a"), Row(null, "b"), Row(null, "c"), Row(null, "dup")), out)
    }
}
