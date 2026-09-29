package app.parley.common.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerChoicesTest {
    @Test fun codec_drops_empty_choices() {
        val m = mapOf(
            "k1" to CallerChoice(vibration = "heartbeat"), "k2" to CallerChoice(autoAnswer = true),
            "k3" to CallerChoice(), "" to CallerChoice(autoAnswer = true),
        )
        val back = CallerChoices.decode(CallerChoices.encode(m))
        assertEquals(setOf("k1", "k2"), back.keys)
        assertEquals("heartbeat", back.getValue("k1").vibration)
        assertEquals(emptyMap<String, CallerChoice>(), CallerChoices.decode("{broken"))
    }

    @Test fun choices_follow_a_new_key_without_overwriting_what_is_there() {
        val m = mapOf("old" to CallerChoice("double", true), "new" to CallerChoice("long", false))
        val moved = CallerChoices.rekey(m, "old", "new")
        assertEquals(CallerChoice("long", true), moved.getValue("new"))
        assertFalse("old" in moved)
        val toPrivate = CallerChoices.rekey(mapOf("old" to CallerChoice("double", true)), "old", "parley-private:3")
        assertEquals(mapOf("parley-private:3" to CallerChoice("double", true)), toPrivate)
        assertEquals(m, CallerChoices.rekey(m, "missing", "new"))
    }

    @Test fun label_policy_keeps_vibration_and_auto_answer() {
        val p = mapOf("Family" to LabelPolicy(vibration = "heartbeat"), "Work" to LabelPolicy(autoAnswer = true))
        val back = LabelPolicies.decode(LabelPolicies.encode(p))
        assertEquals(p, back)
        assertTrue(CallerChoices.labelAutoAnswer(setOf("Work", "Gym"), back))
        assertFalse(CallerChoices.labelAutoAnswer(setOf("Family"), back))
        assertEquals(p.keys, LabelPolicies.renamed(back, emptyMap()).keys)
    }

    @Test fun restored_choices_never_replace_ones_made_here() {
        val here = mapOf("a" to CallerChoice("long"))
        val restored = mapOf("a" to CallerChoice("double"), "b" to CallerChoice(autoAnswer = true))
        assertEquals(mapOf("a" to CallerChoice("long"), "b" to CallerChoice(autoAnswer = true)), CallerChoices.merge(here, restored))
    }
}
