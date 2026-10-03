package com.wkhan.hexis.web.server

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-poll hub: version-based so a change between a reload and the next poll is never missed. */
class ChangeHubTest {

    @Test fun await_timesOut_whenNothingChanges() = runBlocking {
        val hub = ChangeHub()
        assertNull(hub.await(emptyMap(), timeoutMs = 150))
    }

    @Test fun await_returnsImmediately_whenAlreadyNewer() = runBlocking {
        val hub = ChangeHub()
        hub.publish("tasks") // change happened before this poll subscribes
        val now = hub.await(emptyMap(), timeoutMs = 2_000)
        assertEquals(1L, now?.get("tasks"))
    }

    @Test fun await_wakesOnConcurrentChange() = runBlocking {
        val hub = ChangeHub()
        val waiter = async { hub.await(emptyMap(), timeoutMs = 2_000) }
        delay(100) // let the waiter suspend first
        hub.publish("notes")
        val now = waiter.await()
        assertEquals(1L, now?.get("notes"))
    }

    @Test fun await_returnsImmediately_whenPastClientVersion() = runBlocking {
        val hub = ChangeHub()
        hub.publish("tasks") // v1
        hub.publish("tasks") // v2
        // Client last saw v1; the hub is already at v2, so await must not block.
        val now = hub.await(mapOf("tasks" to 1L), timeoutMs = 2_000)
        assertTrue((now?.get("tasks") ?: 0L) >= 2L)
    }

    @Test fun await_blocks_whenClientIsCurrent() = runBlocking {
        val hub = ChangeHub()
        hub.publish("tasks") // v1
        // Client already saw v1 and nothing new → times out.
        assertNull(hub.await(mapOf("tasks" to 1L), timeoutMs = 150))
    }
}
