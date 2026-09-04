package com.todocompanion.app

import com.todocompanion.app.domain.done.Accomplishment
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.DoneRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Track 2.6 — the structured (Julia Evans) brag document assembler. */
class BragDocTest {

    private fun acc(
        refId: String, epochDay: Long, kind: DoneKind = DoneKind.TASK, listId: String? = "l1",
        durationMin: Int = 0, isWin: Boolean = false,
        outcome: String? = null, praise: String? = null, learned: String? = null,
    ) = Accomplishment(
        kind = kind, refId = refId, title = refId, whenMillis = epochDay * 86_400_000L,
        epochDay = epochDay, durationMin = durationMin, listId = listId, isWin = isWin,
        outcome = outcome, praise = praise, learned = learned,
    )

    private val names = mapOf("l1" to "Website", "l2" to "Docs")
    private val today = LocalDate.of(2026, 9, 4)

    @Test fun assemblesAllFourSectionsFromRealData() {
        val items = listOf(
            acc("Launch redesign", 110, listId = "l1", durationMin = 180, isWin = true,
                outcome = "cut bounce rate 20%", praise = "the team loved it"),
            acc("Fix nav bug", 111, listId = "l1", durationMin = 30, learned = "test on small screens first"),
            acc("Write API guide", 112, listId = "l2", durationMin = 90),
            acc("Ship v2", 113, kind = DoneKind.GOAL, listId = null, outcome = "quarterly goal met"),
        )
        val md = DoneRecord.bragDocMarkdown(items, names, start = 100, end = 120, today = today)

        assertTrue(md.contains("# Brag document"))
        // Projects grouped by list, Website first (2 items) then Docs.
        assertTrue(md.contains("## Projects"))
        assertTrue(md.contains("### Website"))
        assertTrue(md.contains("### Docs"))
        assertTrue(md.indexOf("### Website") < md.indexOf("### Docs"))
        // Outcome ("why it mattered") and win star carried through.
        assertTrue(md.contains("⭐ Launch redesign — cut bounce rate 20%"))
        // Collaboration & mentorship pulls the praise quote.
        assertTrue(md.contains("## Collaboration & mentorship"))
        assertTrue(md.contains("the team loved it"))
        // What I learned pulls the lesson.
        assertTrue(md.contains("## What I learned"))
        assertTrue(md.contains("test on small screens first"))
        // Goals advanced lists the completed goal with its outcome.
        assertTrue(md.contains("## Goals advanced"))
        assertTrue(md.contains("Ship v2 — quarterly goal met"))
    }

    @Test fun windowFiltersOutsideItems() {
        val items = listOf(
            acc("in range", 105, outcome = "counts"),
            acc("too early", 50, outcome = "should be excluded"),
            acc("too late", 300, outcome = "should be excluded"),
        )
        val md = DoneRecord.bragDocMarkdown(items, names, start = 100, end = 120, today = today)
        assertTrue(md.contains("in range"))
        assertFalse(md.contains("should be excluded"))
    }

    @Test fun emptySectionsShowGracefulPlaceholders() {
        val md = DoneRecord.bragDocMarkdown(emptyList(), names, start = 100, end = 120, today = today)
        assertTrue(md.contains("No projects recorded in this range yet."))
        assertTrue(md.contains("## Collaboration & mentorship"))
        assertTrue(md.contains("## What I learned"))
        assertTrue(md.contains("## Goals advanced"))
    }
}
