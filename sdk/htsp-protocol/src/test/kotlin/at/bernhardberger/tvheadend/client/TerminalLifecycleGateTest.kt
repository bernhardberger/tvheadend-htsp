package at.bernhardberger.tvheadend.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TerminalLifecycleGateTest {
    @Test
    fun closeCannotBeOvertakenByAQueuedAdmission() {
        val gate = TerminalLifecycleGate("runtime is closed")
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val admitted = executor.submit<Long> {
                gate.admit {
                    admissionEntered.countDown()
                    assertTrue(releaseAdmission.await(1, TimeUnit.SECONDS))
                    1L
                }
            }
            assertTrue(admissionEntered.await(1, TimeUnit.SECONDS))

            val terminal = executor.submit<Long?> {
                gate.close { 2L }.also { closeCompleted.countDown() }
            }
            assertFalse(closeCompleted.await(100, TimeUnit.MILLISECONDS))

            releaseAdmission.countDown()
            assertEquals(1L, admitted.get(1, TimeUnit.SECONDS))
            assertEquals(2L, terminal.get(1, TimeUnit.SECONDS) ?: fail("Close must win"))
            assertTrue(closeCompleted.await(1, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) {
                gate.admit { 3L }
            }
        } finally {
            releaseAdmission.countDown()
            executor.shutdownNow()
        }
    }
}
