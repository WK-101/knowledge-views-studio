package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TipsTest {
    @Test fun tips_round_trip_and_drop_junk() {
        val seen = setOf(Tips.HEADER_SEARCH, Tips.KEYPAD_SPEED_DIAL)
        assertEquals(seen, Tips.decode(Tips.encode(seen)))
        assertEquals(setOf("ok_1", "x"), Tips.decode("ok_1, Bad Id,,x,y;z"))
        assertEquals(emptySet<String>(), Tips.decode(null))
    }

    @Test fun one_tip_at_a_time() {
        val requested = listOf(Tips.HEADER_SEARCH, Tips.RECENTS_SWIPE)
        assertEquals(Tips.HEADER_SEARCH, Tips.visible(requested, emptySet(), null))
        assertEquals(Tips.RECENTS_SWIPE, Tips.visible(requested, setOf(Tips.HEADER_SEARCH), null))
        // The one already showing keeps its place even if an earlier one asks later.
        assertEquals(Tips.RECENTS_SWIPE, Tips.visible(requested, emptySet(), Tips.RECENTS_SWIPE))
        assertNull(Tips.visible(requested, requested.toSet(), null))
    }

    @Test fun concept_explainers_stay_dismissed() {
        // Once dismissed, an explainer's id must survive the stored round trip, or it would come back.
        val concepts = setOf(
            Tips.CONCEPT_PRIVATE, Tips.CONCEPT_TEMPORARY, Tips.CONCEPT_CIRCLE, Tips.CONCEPT_LABELS,
            Tips.CONCEPT_FAVOURITES, Tips.CONCEPT_HISTORY_UNDO, Tips.TO_CALL,
        )
        assertEquals(7, concepts.size)
        assertEquals(concepts, Tips.decode(Tips.encode(concepts)))
        assertNull(Tips.visible(listOf(Tips.CONCEPT_CIRCLE), Tips.decode(Tips.encode(concepts)), null))
    }
}
