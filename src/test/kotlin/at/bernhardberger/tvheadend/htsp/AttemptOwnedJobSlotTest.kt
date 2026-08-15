package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptOwnedJobSlotTest {
    @Test
    fun stalePrecheckedAttemptCannotReplaceTheCurrentAttemptsJob() {
        val lock = Any()
        var currentAttemptId = 1L
        val slot = AttemptOwnedJobSlot(lock) { attemptId ->
            attemptId == currentAttemptId
        }
        val staleCandidate = Job()
        val replacement = Job()

        val staleAttemptPassedTheFormerDetachedCheck = synchronized(lock) {
            currentAttemptId == 1L
        }
        synchronized(lock) {
            currentAttemptId = 2L
        }
        val replacementResult = slot.replaceIfCurrent(
            attemptId = 2L,
            candidate = replacement,
        )
        val staleResult = slot.replaceIfCurrent(
            attemptId = 1L,
            candidate = staleCandidate,
        )

        assertTrue(staleAttemptPassedTheFormerDetachedCheck)
        assertTrue(replacementResult.accepted)
        assertFalse(staleResult.accepted)
        assertSame(replacement, slot.detach())
    }

    @Test
    fun attemptHandoffRejectsSchedulerThatArrivesAfterTheDetachBoundary() = runTest {
        val lock = Any()
        var currentAttemptId = 1L
        val slot = AttemptOwnedJobSlot(lock) { attemptId ->
            attemptId == currentAttemptId
        }
        val installedJob = Job()
        val staleCandidate = Job()
        val schedulerReachedBoundary = CompletableDeferred<Unit>()
        val releaseScheduler = CompletableDeferred<Unit>()
        val staleConnectionCalls = AtomicInteger()
        assertTrue(slot.replaceIfCurrent(attemptId = 1L, candidate = installedJob).accepted)

        val staleScheduler = launch(start = CoroutineStart.UNDISPATCHED) {
            schedulerReachedBoundary.complete(Unit)
            releaseScheduler.await()
            if (slot.replaceIfCurrent(attemptId = 1L, candidate = staleCandidate).accepted) {
                staleConnectionCalls.incrementAndGet()
            }
        }
        schedulerReachedBoundary.await()

        val handoff = slot.updateAttemptAndDetach {
            currentAttemptId = 2L
            currentAttemptId
        }
        releaseScheduler.complete(Unit)
        staleScheduler.join()

        assertEquals(2L, handoff.value)
        assertSame(installedJob, handoff.previous)
        assertEquals(0, staleConnectionCalls.get())
        assertSame(null, slot.detach())
    }
}
