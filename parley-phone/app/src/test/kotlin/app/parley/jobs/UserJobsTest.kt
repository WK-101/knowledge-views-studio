package app.parley.jobs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserJobsTest {
    private val notified = ArrayList<UserJobs.Finished>()

    // Jobs run in backgroundScope, which advanceUntilIdle leaves alone: runCurrent runs them.
    private fun TestScope.jobs() = UserJobs(backgroundScope) { notified += it }

    @Test fun a_job_shows_while_running_with_its_progress() = runTest {
        val jobs = jobs()
        val gate = CompletableDeferred<Unit>()
        jobs.start(UserJobs.Kind.EXPORT, "Exporting…", { "failed" }) { p ->
            p.update(3, 12)
            gate.await()
            "Exported 12 contacts"
        }
        runCurrent()
        val running = jobs.running.value.single()
        assertEquals(UserJobs.Kind.EXPORT, running.kind)
        assertEquals(0.25f, running.fraction!!, 0.001f)
        assertTrue(jobs.isRunning(UserJobs.Kind.EXPORT))
        assertFalse(jobs.isRunning(UserJobs.Kind.IMPORT))
        gate.complete(Unit)
        runCurrent()
        assertTrue(jobs.running.value.isEmpty())
    }

    @Test fun with_no_screen_listening_the_end_is_a_notification() = runTest {
        val jobs = jobs()
        jobs.start(UserJobs.Kind.IMPORT, "Importing…", { "failed" }) { "Imported 3 of 3" }
        runCurrent()
        assertEquals(listOf("Imported 3 of 3"), notified.map { it.message })
        assertFalse(notified.single().failed)
    }

    @Test fun with_a_screen_listening_the_end_goes_there() = runTest {
        val jobs = jobs()
        val shown = ArrayList<String>()
        val listener = backgroundScope.launch(StandardTestDispatcher(testScheduler)) { jobs.finished.collect { shown += it.message } }
        runCurrent()
        jobs.start(UserJobs.Kind.EXPORT, "Exporting…", { "failed" }) { "Exported 1 contact" }
        runCurrent()
        assertEquals(listOf("Exported 1 contact"), shown)
        assertTrue(notified.isEmpty())
        listener.cancelAndJoin()
    }

    @Test fun a_failure_is_said_in_the_words_given_never_the_exception() = runTest {
        val jobs = jobs()
        jobs.start(UserJobs.Kind.EXPORT, "Exporting…", { "Export failed: there isn't enough space on the phone" }) {
            throw java.io.IOException("write failed: ENOSPC")
        }
        runCurrent()
        val f = notified.single()
        assertTrue(f.failed)
        assertEquals("Export failed: there isn't enough space on the phone", f.message)
        assertTrue(jobs.running.value.isEmpty())
    }

    @Test fun a_cancelled_job_ends_silently() = runTest {
        val jobs = jobs()
        val job = jobs.start(UserJobs.Kind.SHARE, "Preparing…", { "failed" }) {
            CompletableDeferred<Unit>().await()
            "never"
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(notified.isEmpty())
        assertTrue(jobs.running.value.isEmpty())
    }

    @Test fun a_job_with_nothing_to_say_says_nothing() = runTest {
        val jobs = jobs()
        jobs.start(UserJobs.Kind.SHARE, "Preparing…", { "failed" }) { null }
        runCurrent()
        assertTrue(notified.isEmpty())
    }

    private class FakeHost : UserJobs.Host {
        val kept = ArrayList<Long>()
        val ended = ArrayList<Long>()
        val discarded = ArrayList<String>()
        override fun keepAlive(job: UserJobs.Running, output: String?, token: String) {
            kept += job.id
        }
        override fun ended(id: Long, token: String) {
            ended += id
        }
        override fun discard(output: String) {
            discarded += output
        }
    }

    @Test fun each_job_is_kept_running_until_it_ends() = runTest {
        val host = FakeHost()
        val jobs = UserJobs(backgroundScope, host) { notified += it }
        val gate = CompletableDeferred<Unit>()
        jobs.start(UserJobs.Kind.EXPORT, "Exporting…", { "failed" }, output = "content://doc/1") { gate.await(); "done" }
        runCurrent()
        assertEquals(listOf(1L), host.kept)
        assertTrue(host.ended.isEmpty())
        assertTrue(jobs.job(1, jobs.token) != null)
        // A request left by another (stopped) process never finds this process's job.
        assertNull(jobs.job(1, "another process"))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1L), host.ended)
        assertNull(jobs.job(1, jobs.token))
        // A finished export keeps its file.
        assertTrue(host.discarded.isEmpty())
    }

    @Test fun a_failed_or_cancelled_export_removes_its_half_written_file() = runTest {
        val host = FakeHost()
        val jobs = UserJobs(backgroundScope, host) { notified += it }
        jobs.start(UserJobs.Kind.EXPORT, "Exporting…", { "failed" }, output = "content://doc/failed") { throw java.io.IOException("ENOSPC") }
        val cancelled = jobs.start(UserJobs.Kind.EXPORT, "Exporting…", { "failed" }, output = "content://doc/cancelled") {
            CompletableDeferred<Unit>().await()
            "never"
        }
        runCurrent()
        cancelled.cancelAndJoin()
        assertEquals(listOf("content://doc/failed", "content://doc/cancelled"), host.discarded)
        // An import writes no file of its own: nothing to remove.
        jobs.start(UserJobs.Kind.IMPORT, "Importing…", { "failed" }) { throw java.io.IOException("bad") }
        runCurrent()
        assertEquals(2, host.discarded.size)
    }

    @Test fun a_prepared_file_is_handed_over_only_with_the_end_message() = runTest {
        val jobs = jobs()
        jobs.prepare(UserJobs.Kind.SHARE, "Preparing…", { "failed" }) {
            UserJobs.Ready("Your file is ready. Tap to share it.", UserJobs.Opener("calls.csv", "text/csv"))
        }
        runCurrent()
        val f = notified.single()
        assertEquals(UserJobs.Opener("calls.csv", "text/csv"), f.opener)
        assertFalse(f.failed)
    }
}
