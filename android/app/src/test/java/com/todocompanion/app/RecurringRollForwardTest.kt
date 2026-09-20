package com.todocompanion.app

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.recurrence.Freq
import com.todocompanion.app.domain.recurrence.Recur
import com.todocompanion.app.domain.recurrence.Recurrence
import com.todocompanion.app.domain.task.RecurringRollForward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Pure-JVM unit tests for the roll-forward decision extracted from AppViewModel (no ViewModel, no DB, no
 * Android). The prize assertion is the SAME-DELTA invariant: when a repeat rolls, due, start and deadline
 * all move by an identical delta — the regression that once left the deadline frozen on the first
 * occurrence, reading as permanently overdue.
 */
class RecurringRollForwardTest {

    private val zone = ZoneId.of("UTC")
    private val due = Instant.parse("2026-01-01T09:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private val daily = Recurrence.encode(Recur(Freq.DAILY)) // FREQ=DAILY;INT=1

    private fun task(
        rrule: String? = daily,
        dueDate: Long? = due,
        startDate: Long? = due - 2 * day,
        deadlineDate: Long? = due + 3 * day,
        completed: Boolean = false,
    ) = TaskEntity(
        id = "t1", listId = "L", title = "Water the plants", createdAt = 0L, updatedAt = 0L,
        rrule = rrule, dueDate = dueDate, startDate = startDate, deadlineDate = deadlineDate,
        completed = completed, completedAt = if (completed) due else null,
    )

    @Test fun `completing a repeat shifts due, start and deadline by the same delta`() {
        val roll = RecurringRollForward.onComplete(task(), zone, due)
        assertNotNull(roll); roll!!
        assertTrue("advances forward", roll.delta > 0)
        assertEquals("due moved by delta", roll.delta, roll.next.dueDate!! - due)
        assertEquals("start moved by the SAME delta", roll.delta, roll.next.startDate!! - (due - 2 * day))
        assertEquals("deadline moved by the SAME delta", roll.delta, roll.next.deadlineDate!! - (due + 3 * day))
    }

    @Test fun `rolled task is reopened, not left completed`() {
        val roll = RecurringRollForward.onComplete(task(), zone, due)!!
        assertFalse(roll.next.completed)
        assertNull(roll.next.completedAt)
    }

    @Test fun `does not roll when already completed, unscheduled, or non-repeating`() {
        assertNull("already completed → normal toggle off", RecurringRollForward.onComplete(task(completed = true), zone, due))
        assertNull("no due date → cannot advance", RecurringRollForward.onComplete(task(dueDate = null), zone, due))
        assertNull("no recurrence → plain complete", RecurringRollForward.onComplete(task(rrule = null), zone, due))
    }

    @Test fun `does not roll once the recurrence has ended`() {
        val lastOne = Recurrence.encode(Recur(Freq.DAILY, count = 1)) // one occurrence left
        assertNull("ended → caller completes normally", RecurringRollForward.onComplete(task(rrule = lastOne), zone, due))
        assertNull("skip on an ended repeat cannot advance", RecurringRollForward.onSkip(task(rrule = lastOne), zone, due))
    }

    @Test fun `skip advances the dates but leaves the completed flag untouched`() {
        val done = RecurringRollForward.onSkip(task(completed = true), zone, due)
        assertNotNull(done); done!!
        val (next, delta) = done
        assertTrue(delta > 0)
        assertEquals(delta, next.dueDate!! - due)
        assertTrue("skip must not reopen a completed task", next.completed)
    }

    @Test fun `subtask reset policy`() {
        val kids = listOf(
            TaskEntity(id = "a", listId = "L", title = "a", createdAt = 0L, updatedAt = 0L, completed = true),
            TaskEntity(id = "b", listId = "L", title = "b", createdAt = 0L, updatedAt = 0L, completed = true),
            TaskEntity(id = "c", listId = "L", title = "c", createdAt = 0L, updatedAt = 0L, completed = false),
        )
        assertEquals("keep resets nothing", emptyList<TaskEntity>(), RecurringRollForward.subtasksToReset("keep", kids))
        assertEquals("all resets every done child", listOf("a", "b"), RecurringRollForward.subtasksToReset("all", kids).map { it.id })
        assertEquals("allDone waits until every child is done", emptyList<TaskEntity>(), RecurringRollForward.subtasksToReset("allDone", kids))

        val allDoneKids = kids.dropLast(1) // a, b — both done
        assertEquals("allDone fires once all are done", listOf("a", "b"), RecurringRollForward.subtasksToReset("allDone", allDoneKids).map { it.id })
        assertEquals("no children → nothing to reset", emptyList<TaskEntity>(), RecurringRollForward.subtasksToReset("allDone", emptyList()))
    }
}
