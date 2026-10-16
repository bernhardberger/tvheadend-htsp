package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference

internal class HtspMetadataBatchesTest : HtspServiceLifecycleFixture() {
    @Test
    fun validatesEagerlyAndEachIdleCollectionRegistersAndCancelsIndependently() = runBlocking {
        val service = service()
        try {
            for (invalid in listOf(0, -1, Int.MIN_VALUE, 4097, Int.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) { service.eventBatches(invalid) }
            }
            val flow = service.eventBatches(4096)
            assertEquals(0, service.metadataCollectorCount())
            val first = launch(start = CoroutineStart.UNDISPATCHED) { flow.collect {} }
            val second = launch(start = CoroutineStart.UNDISPATCHED) { flow.collect {} }
            assertEquals(2, service.metadataCollectorCount())
            first.cancelAndJoin()
            assertTrue(first.isCancelled)
            assertEquals(1, service.metadataCollectorCount())
            second.cancelAndJoin()
            assertTrue(second.isCancelled)
            assertEquals(0, service.metadataCollectorCount())
        } finally {
            service.close()
        }
    }

    @Test
    fun batchesMatchEventsIncludingMalformedAndOverflowAcrossSizeBoundaries() = runBlocking {
        for (limit in listOf(1, 128, 512)) {
            val published = Channel<Unit>(Channel.UNLIMITED)
            FakeHtspServer(respondToHello = true).use { server ->
                val service = service(
                    metadataEventBufferCapacity = 601,
                    afterPublicationCurrencyCheck = { published.trySend(Unit) },
                )
                val connected = service.connect(HtspEndpoint("127.0.0.1", server.port)) as HtspConnectOutcome.Connected
                val release = CompletableDeferred<Unit>()
                val firstEvent = CompletableDeferred<Unit>()
                val firstBatch = CompletableDeferred<Unit>()
                val eventList = mutableListOf<HtspTransportEvent>()
                val batchList = mutableListOf<List<HtspTransportEvent>>()
                val events = async(start = CoroutineStart.UNDISPATCHED) {
                    service.events.take(603).collect {
                        eventList += it
                        if (eventList.size == 1) { firstEvent.complete(Unit); release.await() }
                    }
                }
                val batches = async(start = CoroutineStart.UNDISPATCHED) {
                    // Exercise the inherited default argument as well as explicit limits.
                    val flow = if (limit == 512) service.eventBatches() else service.eventBatches(limit)
                    flow.take(1 + (602 + limit - 1) / limit).collect {
                        batchList += it
                        if (batchList.size == 1) { firstBatch.complete(Unit); release.await() }
                    }
                }
                try {
                    withTimeout(10_000L) {
                        server.sendServerMessage("channelAdd", channelFixture(1L))
                        firstEvent.await()
                        firstBatch.await() // A partial batch emits without waiting to fill.
                        for (id in 2L..601L) {
                            server.sendServerMessage("channelAdd", channelFixture(id))
                            if (id == 101L) repeat(2) { server.sendServerMessage("channelAdd") }
                        }
                        server.sendServerMessage("channelAdd", channelFixture(602L))
                        server.sendServerMessage("channelAdd")
                        repeat(605) { published.receive() }
                        assertEquals(2, service.metadataCollectorCount()) // Fence all offers before draining.
                        release.complete(Unit)
                        events.await()
                        batches.await()
                    }
                    assertEquals(eventList, batchList.flatten())
                    assertEquals((1L..601L).toList(), eventList.filterIsInstance<HtspTransportEvent.ServerMessage>().map {
                        (it.message as HtspChannelAddMessage).channel.channelId
                    })
                    assertEquals(HtspTransportEvent.MalformedMetadata(connected.connection.generation, 2L), eventList[101])
                    assertEquals(HtspTransportEvent.MetadataOverflow(connected.connection.generation, 2L), eventList.last())
                    assertTrue(batchList.all { it.isNotEmpty() && it.size <= limit })
                    assertEquals(minOf(limit, 602), batchList[1].size)
                    assertEquals(0, service.metadataCollectorCount())
                } finally {
                    events.cancelAndJoin()
                    batches.cancelAndJoin()
                    service.close()
                }
            }
        }
    }

    @Test
    fun queuedFailureAndReplacementMetadataNeverShareABatch() = runBlocking {
        val published = Channel<Unit>(Channel.UNLIMITED)
        FakeHtspServer(respondToHello = true).use { oldServer ->
            FakeHtspServer(respondToHello = true).use { newServer ->
                val service = service(afterPublicationCurrencyCheck = { published.trySend(Unit) })
                val old = service.connect(HtspEndpoint("127.0.0.1", oldServer.port)) as HtspConnectOutcome.Connected
                val firstReceived = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val received = mutableListOf<List<HtspTransportEvent>>()
                val collector = async(start = CoroutineStart.UNDISPATCHED) {
                    service.eventBatches().take(3).collect { batch ->
                        received += batch
                        if (received.size == 1) { firstReceived.complete(Unit); release.await() }
                    }
                }
                try {
                    withTimeout(5_000L) {
                        oldServer.sendServerMessage("channelAdd", channelFixture(1L))
                        firstReceived.await()
                        oldServer.sendServerMessage("channelAdd", channelFixture(2L))
                        repeat(2) { published.receive() }
                        assertEquals(1, service.metadataCollectorCount())
                        oldServer.closeClientTransport()
                        published.receive()
                        assertEquals(1, service.metadataCollectorCount())
                        val newer = service.connect(HtspEndpoint("127.0.0.1", newServer.port)) as HtspConnectOutcome.Connected
                        newServer.sendServerMessage("channelAdd", channelFixture(3L))
                        newServer.sendServerMessage("initialSyncCompleted")
                        repeat(2) { published.receive() }
                        assertEquals(1, service.metadataCollectorCount())
                        release.complete(Unit)
                        collector.await()
                        assertEquals(listOf(1, 2, 2), received.map { it.size })
                        assertTrue(received[1].last() is HtspTransportEvent.ConnectionFailure)
                        received.take(2).flatten().forEach { assertSame(old.connection.generation, it.generation) }
                        received.last().forEach { assertSame(newer.connection.generation, it.generation) }
                        assertEquals(0, service.metadataCollectorCount())
                    }
                } finally {
                    collector.cancelAndJoin()
                    service.close()
                }
            }
        }
    }

    @Test
    fun suspendedBatchReleasesAllEntriesAndOverflowSnapshotsRemainImmutable() = runBlocking {
        for (limitBytes in listOf(false, true)) {
            val padding = if (limitBytes) ByteArray(600_000) else ByteArray(0)
            val fields = (1L..7L).associateWith { channelFixture(it) + ("padding" to padding) }
            val frameBytes = HtspCodec.encode("channelAdd", fields.getValue(1L)).size - Int.SIZE_BYTES
            fields.values.forEach { assertEquals(frameBytes, HtspCodec.encode("channelAdd", it).size - Int.SIZE_BYTES) }
            val published = Channel<Unit>(Channel.UNLIMITED)
            FakeHtspServer(respondToHello = true).use { server ->
                val service = service(
                    metadataEventBufferCapacity = if (limitBytes) 100 else 2,
                    eventBufferOptions = HtspEventBufferOptions(metadataQueueBytes = if (limitBytes) 2L * frameBytes else 8L * 1024 * 1024),
                    afterPublicationCurrencyCheck = { published.trySend(Unit) },
                )
                val live = service.connect(HtspEndpoint("127.0.0.1", server.port)) as HtspConnectOutcome.Connected
                val gates = List(3) { CompletableDeferred<Unit>() }
                val received = Channel<List<HtspTransportEvent>>(Channel.UNLIMITED)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    var index = 0
                    service.eventBatches().collect { batch ->
                        received.send(batch)
                        gates[index++].await()
                    }
                }
                try {
                    withTimeout(10_000L) {
                        server.sendServerMessage("channelAdd", fields.getValue(1L))
                        assertEquals(1, received.receive().size)
                        for (id in 2L..4L) server.sendServerMessage("channelAdd", fields.getValue(id))
                        repeat(4) { published.receive() }
                        assertEquals(1, service.metadataCollectorCount())
                        gates[0].complete(Unit)
                        val second = received.receive()
                        val snapshot = second.toList()
                        assertEquals(3, second.size)
                        assertEquals(HtspTransportEvent.MetadataOverflow(live.connection.generation, 1L), second.last())
                        assertThrows(UnsupportedOperationException::class.java) {
                            (second as MutableList<HtspTransportEvent>).clear()
                        }
                        // Both 2 and 3 are released while emit(second) suspends: 5 and 6 fit, 7 drops.
                        for (id in 5L..7L) server.sendServerMessage("channelAdd", fields.getValue(id))
                        repeat(3) { published.receive() }
                        assertEquals(1, service.metadataCollectorCount())
                        assertEquals(snapshot, second)
                        gates[1].complete(Unit)
                        val third = received.receive()
                        assertFalse(second === third)
                        assertEquals(listOf(5L, 6L), third.filterIsInstance<HtspTransportEvent.ServerMessage>().map {
                            (it.message as HtspChannelAddMessage).channel.channelId
                        })
                        assertEquals(HtspTransportEvent.MetadataOverflow(live.connection.generation, 1L), third.last())
                        collector.cancelAndJoin() // Cancellation during emit discards the collector's remaining work.
                        assertTrue(collector.isCancelled)
                        assertEquals(0, service.metadataCollectorCount())
                    }
                } finally {
                    collector.cancelAndJoin()
                    service.close()
                }
            }
        }
    }

    @Test
    fun emittedBatchIsNotRetainedWhileLaterBatchSuspends() = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val firstReceived = CompletableDeferred<Unit>()
            val secondReceived = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reference: WeakReference<List<HtspTransportEvent>>? = null
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                service.eventBatches().collect { batch ->
                    if (reference == null) {
                        reference = WeakReference(batch)
                        firstReceived.complete(Unit)
                    } else {
                        secondReceived.complete(Unit)
                        release.await()
                    }
                }
            }
            try {
                withTimeout(5_000L) {
                    server.sendServerMessage("channelAdd", channelFixture(1L))
                    firstReceived.await()
                    server.sendServerMessage("channelAdd", channelFixture(2L))
                    secondReceived.await()
                    repeat(50) {
                        if (checkNotNull(reference).get() != null) { System.gc(); delay(20L) }
                    }
                    assertNull(checkNotNull(reference).get())
                }
            } finally {
                collector.cancelAndJoin()
                assertEquals(0, service.metadataCollectorCount())
                service.close()
            }
        }
    }
}
