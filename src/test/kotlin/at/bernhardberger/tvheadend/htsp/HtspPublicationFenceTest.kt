package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.HelloRequest
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class HtspPublicationFenceTest : HtspServiceLifecycleFixture() {
    @Test
    fun readerCurrencyCheckAndEnqueueExcludeReconnectAdmission() = assertFence(false)

    @Test
    fun directHelloCurrencyCheckAndEnqueueExcludeReconnectAdmission() = assertFence(true)

    private fun assertFence(directHello: Boolean) {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf("htspversion" to 35L, "challenge" to ByteArray(32)),
        ).use { server ->
            FakeHtspServer(respondToHello = true).use { replacement ->
                val checked = CountDownLatch(1)
                val release = CountDownLatch(1)
                var old: HtspConnectionGeneration? = null
                lateinit var service: HtspService
                service = service(afterPublicationCurrencyCheck = { event ->
                    if (event.generation === old) {
                        val lock = service.attemptLockForTest()
                        assertTrue(Thread.holdsLock(lock))
                        checked.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                })
                runBlocking {
                    old = (service.connect(HtspEndpoint("127.0.0.1", server.port)) as HtspConnectOutcome.Connected)
                        .connection.generation
                    val events = CopyOnWriteArrayList<HtspTransportEvent>()
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        service.events.collect { events += it }
                    }
                    val hello = if (directHello) async(Dispatchers.IO) {
                        runCatching { service.execute(HelloRequest(36L, "fence-test")) }
                    } else null
                    if (!directHello) server.sendServerMessage("channelAdd", mapOf("channelId" to 1L))
                    try {
                        assertTrue(checked.await(2, TimeUnit.SECONDS))
                        val thread = AtomicReference<Thread>()
                        val reconnect = async(Dispatchers.IO) {
                            thread.set(Thread.currentThread())
                            service.connect(HtspEndpoint("127.0.0.1", replacement.port))
                        }
                        withTimeout(2_000L) {
                            val lock = service.attemptLockForTest()
                            val bean = java.lang.management.ManagementFactory.getThreadMXBean()
                            while (true) {
                                val info = thread.get()?.let { bean.getThreadInfo(it.id) }
                                if (info?.threadState == Thread.State.BLOCKED &&
                                    info.lockInfo?.identityHashCode == System.identityHashCode(lock) &&
                                    info.lockInfo?.className == lock.javaClass.name) break
                                delay(1L)
                            }
                        }
                        assertFalse(reconnect.isCompleted)
                        release.countDown()
                        val newer = (withTimeout(2_000L) { reconnect.await() } as HtspConnectOutcome.Connected)
                            .connection.generation
                        hello?.await()
                        replacement.sendServerMessage("channelAdd", mapOf("channelId" to 2L))
                        withTimeout(2_000L) { while (events.none { it.generation === newer }) delay(1L) }
                        assertSame(old, events.first().generation)
                        val firstNew = events.indexOfFirst { it.generation === newer }
                        assertTrue(events.drop(firstNew).none { it.generation === old })
                    } finally {
                        release.countDown()
                        collector.cancelAndJoin()
                        service.close()
                    }
                }
            }
        }
    }
}
