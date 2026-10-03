package app.parley.common.people

import app.parley.common.people.RelationMirror.Gender
import app.parley.common.people.RelationMirror.Row
import app.parley.common.people.RelationMirror.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationMirrorTest {
    private fun inv(key: String, g: Gender = Gender.UNKNOWN) = RelationMirror.inverse(RelationTypes.byKey(key)!!, g)?.key

    @Test fun parents_and_children_mirror_without_guessing_a_gender() {
        assertEquals("child", inv("mother"))
        assertEquals("child", inv("father"))
        assertEquals("child", inv("parent"))
        assertEquals("daughter", inv("mother", Gender.FEMALE))
        assertEquals("son", inv("father", Gender.MALE))
        assertEquals("parent", inv("child"))
        assertEquals("parent", inv("son"))
        assertEquals("mother", inv("daughter", Gender.FEMALE))
    }

    @Test fun symmetric_and_paired_types() {
        assertEquals("spouse", inv("spouse"))
        assertEquals("spouse", inv("wife"))
        assertEquals("husband", inv("wife", Gender.MALE))
        assertEquals("partner", inv("partner"))
        assertEquals("domestic-partner", inv("domestic-partner"))
        assertEquals("sibling", inv("brother"))
        assertEquals("sibling", inv("sister"))
        assertEquals("sister", inv("brother", Gender.FEMALE))
        assertEquals("friend", inv("friend"))
        assertEquals("relative", inv("relative"))
        assertEquals("assistant", inv("manager"))
        assertEquals("manager", inv("assistant"))
        assertEquals("tenant", inv("landlord"))
        assertEquals("grandchild", inv("grandmother"))
        assertEquals("relative", inv("aunt"))
        assertEquals("niece", inv("aunt", Gender.FEMALE))
    }

    @Test fun types_without_a_fair_opposite_write_nothing() {
        assertNull(inv("doctor"))
        assertNull(inv("met"))
        assertNull(inv("referred-by"))
        assertNull(inv("emergency"))
        // Every inverse is a real type.
        RelationTypes.all.forEach { t -> RelationMirror.inverse(t)?.let { assertTrue(RelationTypes.byKey(it.key) != null) } }
    }

    @Test fun custom_labels_are_mirrored_as_written() {
        assertEquals(Row("Sam", null, "Fishing buddy"), RelationMirror.reciprocal(null, " Fishing buddy ", "Sam"))
        assertEquals(Row("Sam", "child", null), RelationMirror.reciprocal("mother", null, "Sam"))
        assertNull(RelationMirror.reciprocal(null, "", "Sam"))
        assertNull(RelationMirror.reciprocal("mother", null, " "))
    }

    private val child = Row("Sam", "child", null)

    @Test fun adds_the_reciprocal_once() {
        val steps = RelationMirror.plan(mapOf("ana" to child), emptyMap()) { emptyList() }
        assertEquals(listOf(Step.Add("ana", child)), steps)
        // Already there (written by the user, or earlier): nothing to do.
        assertEquals(emptyList<Step>(), RelationMirror.plan(mapOf("ana" to child), emptyMap()) { listOf(Row("sam", "child", null)) })
    }

    @Test fun never_overrides_what_the_user_wrote() {
        // Ana already calls Sam her "Son" (user-authored): no second row.
        assertEquals(emptyList<Step>(), RelationMirror.plan(mapOf("ana" to child), emptyMap()) { listOf(Row("Sam", "son", null)) })
        // The user changed the row Parley added: removal and retyping leave it alone.
        val edited = { _: String -> listOf(Row("Sam", "son", null)) }
        assertEquals(emptyList<Step>(), RelationMirror.plan(emptyMap(), mapOf("ana" to child), edited))
        assertEquals(emptyList<Step>(), RelationMirror.plan(mapOf("ana" to Row("Sam", "friend", null)), mapOf("ana" to child), edited))
        // Read-only or gone: left alone.
        assertEquals(emptyList<Step>(), RelationMirror.plan(mapOf("ana" to child), mapOf("ana" to child)) { null })
    }

    @Test fun type_changes_and_removals_follow_parleys_own_rows() {
        val have = { _: String -> listOf(child, Row("Bo", "friend", null)) }
        val friend = Row("Sam", "friend", null)
        assertEquals(listOf(Step.Change("ana", child, friend)), RelationMirror.plan(mapOf("ana" to friend), mapOf("ana" to child), have))
        assertEquals(listOf(Step.Remove("ana", child)), RelationMirror.plan(emptyMap(), mapOf("ana" to child), have))
        // A rename of Sam renames the row Parley added.
        val renamed = Row("Samuel", "child", null)
        assertEquals(listOf(Step.Change("ana", child, renamed)), RelationMirror.plan(mapOf("ana" to renamed), mapOf("ana" to child), have))
    }

    @Test fun steps_apply_and_undo_in_place() {
        val rows = listOf(Row("Bo", "friend", null), child)
        val ident = { r: Row -> r }
        val make = { r: Row, _: Row? -> r }
        val change = Step.Change("ana", child, Row("Sam", "friend", null))
        val changed = RelationMirror.apply(change, rows, ident, make)
        assertEquals(listOf(Row("Bo", "friend", null), Row("Sam", "friend", null)), changed)
        assertEquals(rows, RelationMirror.apply(RelationMirror.undo(change), changed, ident, make))
        val removed = RelationMirror.apply(Step.Remove("ana", child), rows, ident, make)
        assertEquals(listOf(Row("Bo", "friend", null)), removed)
        assertEquals(rows, RelationMirror.apply(RelationMirror.undo(Step.Remove("ana", child)), removed, ident, make))
    }

    @Test fun the_record_keeps_only_rows_parley_still_owns() {
        val done = listOf<Step>(Step.Add("ana", child))
        assertEquals(mapOf("ana" to child), RelationMirror.record(mapOf("ana" to child), emptyMap(), { emptyList() }, done))
        // Changed by the user since: forgotten once Sam no longer names Ana.
        assertEquals(emptyMap<String, Row>(), RelationMirror.record(emptyMap(), mapOf("ana" to child), { listOf(Row("Sam", "son", null)) }, emptyList()))
        assertEquals(emptyMap<String, Row>(), RelationMirror.record(emptyMap(), mapOf("ana" to child), { listOf(child) }, listOf(Step.Remove("ana", child))))
    }

    @Test fun the_record_round_trips() {
        val list = listOf(
            RelationMirror.Created("k\t1", 1, "k2", 2, Row("Sam\nX", "child", null)),
            RelationMirror.Created("k1", 1, "k3", 3, Row("Sam", null, "Fishing buddy")),
        )
        assertEquals(list, RelationMirror.decode(RelationMirror.encode(list)))
        assertEquals(emptyList<RelationMirror.Created>(), RelationMirror.decode("garbage"))
    }

    // ---- Relations with a private contact

    private val none = emptyList<RelationMirror.Shown>()
    private val ana = incoming("parley-private:7", "Ana", true, "mother")
    private val sam = incoming("0r5-abc", "Sam", false, "spouse")

    private fun incoming(owner: String, name: String, private: Boolean, type: String) =
        RelationMirror.Incoming(owner, name, private, Row("Bob", type, null))

    private fun shown(vararg i: RelationMirror.Incoming, own: List<Row> = emptyList(), selfPrivate: Boolean = false, privateShown: Boolean = true) =
        RelationMirror.fromOthers(i.toList(), own, selfPrivate, privateShown)

    @Test fun a_private_contacts_relation_shows_on_the_other_page_without_being_written() {
        // Ana (private) says "Mother: Bob"; Bob's page (a device contact) shows "Child: Ana".
        assertEquals(listOf(RelationMirror.Shown("parley-private:7", Row("Ana", "child", null))), shown(ana))
        // And the other way round: a device contact's relation to a private one shows on the private page.
        assertEquals(listOf(RelationMirror.Shown("0r5-abc", Row("Sam", "spouse", null))), shown(sam, selfPrivate = true))
    }

    @Test fun relations_between_device_contacts_are_written_so_not_shown_twice() {
        assertEquals(none, shown(sam))
    }

    @Test fun discreet_mode_hides_relations_with_private_contacts() {
        assertEquals(none, shown(ana, privateShown = false))
        assertEquals(none, shown(sam, selfPrivate = true, privateShown = false))
    }

    @Test fun a_person_already_named_or_without_an_opposite_is_left_out() {
        assertEquals(none, shown(ana, own = listOf(Row("ana", "daughter", null))))
        // A doctor's patient has no fair opposite.
        assertEquals(none, shown(incoming("parley-private:7", "Ana", true, "doctor")))
        // The same contact naming this one twice shows once.
        assertEquals(1, shown(ana, incoming("parley-private:7", "Ana", true, "friend")).size)
    }
}
