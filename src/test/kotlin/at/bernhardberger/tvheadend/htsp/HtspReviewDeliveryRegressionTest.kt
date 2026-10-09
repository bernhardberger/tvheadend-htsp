package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class HtspReviewDeliveryRegressionTest : HtspServiceLifecycleFixture() {
    @Test
    fun nativeStateFlowsRunOnSubscriptionBeforeReplay(): Unit = runBlocking {
        val service = service()
        try {
            for (flow in listOf(service.connectionState, service.liveConnection)) {
                val order = mutableListOf<String>()
                flow.onSubscription { order += "subscription" }.onEach { order += "replay" }.first()
                assertEquals(listOf("subscription", "replay"), order)
            }
        } finally {
            service.close()
        }
    }

    @Test
    fun concurrentStateFlushesDiscardOlderSnapshots(): Unit = runBlocking {
        val ready = List(20) { CountDownLatch(1) }
        val release = List(20) { CountDownLatch(1) }
        val service = service(beforeStatePublication = { state ->
            if (state is HtspConnectionState.Connecting && state.port % 2 == 1) {
                val index = state.port / 2
                ready[index].countDown()
                check(release[index].await(5, TimeUnit.SECONDS))
            }
        })
        val publish = HtspService::class.java.getDeclaredMethod(
            "publishConnectionState", Long::class.javaPrimitiveType, HtspConnectionState::class.java,
        ).apply { isAccessible = true }
        val observed = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            service.connectionState.collect { if (it is HtspConnectionState.Connecting) observed += it.port }
        }
        try {
            repeat(20) { index ->
                val old = HtspConnectionState.Connecting("fixture", index * 2 + 1)
                val newer = old.copy(port = old.port + 1)
                val first = async(Dispatchers.IO) { publish.invoke(service, 0L, old) }
                try {
                    assertTrue(ready[index].await(2, TimeUnit.SECONDS))
                    assertEquals(old, service.currentConnectionState(), "Internal truth does not wait for publication")
                    assertNotEquals(old, service.connectionState.value)
                    val second = async(Dispatchers.IO) { publish.invoke(service, 0L, newer) }
                    withTimeout(2_000L) { second.await() }
                    assertEquals(newer, service.connectionState.value)
                } finally {
                    release[index].countDown()
                    withTimeout(2_000L) { first.await() }
                }
                assertEquals(newer, service.connectionState.value)
                assertNull(service.liveConnection.value)
            }
            assertEquals((1..20).map { it * 2 }, observed)
        } finally {
            release.forEach { it.countDown() }
            collector.cancelAndJoin()
            service.close()
        }
    }

    @Test
    fun slowImmediateStateCollectorAllowsNewInternalTruthAndSerializesPublicDelivery(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val newerReady = CountDownLatch(1)
        val service = service(beforeStatePublication = {
            if (it is HtspConnectionState.Connecting && it.port == 2) newerReady.countDown()
        })
        val publish = HtspService::class.java.getDeclaredMethod(
            "publishConnectionState", Long::class.javaPrimitiveType, HtspConnectionState::class.java,
        ).apply { isAccessible = true }
        val old = HtspConnectionState.Connecting("fixture", 1)
        val newer = old.copy(port = 2)
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            service.connectionState.collect {
                if (it == old) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        try {
            val first = async(Dispatchers.IO) { publish.invoke(service, 0L, old) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) { publish.invoke(service, 0L, newer) }
            assertTrue(newerReady.await(2, TimeUnit.SECONDS))
            assertEquals(newer, service.currentConnectionState())
            assertEquals(old, service.connectionState.value)
            release.countDown()
            withTimeout(2_000L) { first.await(); second.await() }
            assertEquals(newer, service.connectionState.value)
        } finally {
            release.countDown()
            collector.cancelAndJoin()
            service.close()
        }
    }

    @Test
    fun unconfinedDisconnectDuringEitherStatePublicationConvergesWithoutDeadlock(): Unit = runBlocking {
        for (fromLiveConnection in listOf(false, true)) {
            FakeHtspServer(respondToHello = true).use { server ->
                val service = service()
                val disconnected = CompletableDeferred<Boolean>()
                val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    if (fromLiveConnection) service.liveConnection.first { it != null }
                    else service.connectionState.first { it is HtspConnectionState.Connected }
                    disconnected.complete(service.disconnect())
                }
                try {
                    val connect = async(Dispatchers.IO) { service.connect(HtspEndpoint("127.0.0.1", server.port)) }
                    assertTrue(withTimeout(2_000L) { disconnected.await() })
                    withTimeout(2_000L) { connect.await() }
                    assertEquals(HtspConnectionState.Disconnected, service.connectionState.value)
                    assertNull(service.liveConnection.value)
                    assertEquals(service.currentConnectionState(), service.connectionState.value)
                } finally {
                    observer.cancelAndJoin()
                    service.close()
                }
            }
        }
    }

    @Test
    fun unconfinedStateCollectorsNeverRunUnderTheLifecycleLock() = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val lock = HtspService::class.java.getDeclaredField("connectionAttemptLock").apply { isAccessible = true }.get(service)
            val stateReady = CompletableDeferred<Unit>()
            val liveReady = CompletableDeferred<Unit>()
            val state = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                service.connectionState.onEach {
                    assertFalse(Thread.holdsLock(lock))
                    if (it is HtspConnectionState.Connected) stateReady.complete(Unit)
                }
                    .first { it is HtspConnectionState.Error }
                Thread.holdsLock(lock)
            }
            val live = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                service.liveConnection.onEach {
                    assertFalse(Thread.holdsLock(lock))
                    if (it != null) liveReady.complete(Unit)
                }.first { it == null }
                Thread.holdsLock(lock)
            }
            try {
                withTimeout(2_000L) { stateReady.await(); liveReady.await() }
                server.closeClientTransport()
                assertFalse(withTimeout(2_000L) { state.await() }, "state collector ran under lifecycle lock")
                assertFalse(withTimeout(2_000L) { live.await() }, "live collector ran under lifecycle lock")
            } finally {
                state.cancelAndJoin()
                live.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun unconfinedRpcCompletionNeverRunsUnderLifecycleLock() = runBlocking {
        FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(null)).use { server ->
            val service = service()
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val lock = HtspService::class.java.getDeclaredField("connectionAttemptLock").apply { isAccessible = true }.get(service)
            val call = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                val reply = service.execute(GetProfilesRequest())
                reply to Thread.holdsLock(lock)
            }
            try {
                assertTrue(server.awaitPostHandshakeRequestCount(1, 2_000L))
                // Probe completion itself as well as the caller: this also detects an inline
                // completion if the write continuation has not yet reached await().
                val pending = HtspService::class.java.getDeclaredField("pending").apply { isAccessible = true }
                    .get(service) as Map<*, *>
                val request = pending.values.single()!!
                val response = request.javaClass.getDeclaredField("def").apply { isAccessible = true }
                    .get(request) as CompletableDeferred<*>
                val completionUnderLock = CompletableDeferred<Boolean>()
                response.invokeOnCompletion { completionUnderLock.complete(Thread.holdsLock(lock)) }
                server.replyToPostHandshakeRequest(0, mapOf("profiles" to emptyList<Any>()))
                val (reply, underLock) = withTimeout(2_000L) { call.await() }
                assertInstanceOf(HtspResult.Ok::class.java, reply)
                assertFalse(underLock, "RPC continuation ran under lifecycle lock")
                assertFalse(completionUnderLock.await(), "Reply deferred completed under lifecycle lock")
            } finally {
                call.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun unconfinedFirstAndBlockingCollectorsNeverRunOnReaderOrUnderLock(): Unit = runBlocking {
        FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(mapOf("profiles" to emptyList<Any>()))).use { server ->
            val service = service()
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val lock = HtspService::class.java.getDeclaredField("connectionAttemptLock").apply { isAccessible = true }.get(service)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { service.events.first() }
            val blocking = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                service.events.collect {
                    assertFalse(Thread.holdsLock(lock))
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val packet = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                service.subscriptionEvents(1L).first().also { assertFalse(Thread.holdsLock(lock)) }
            }
            try {
                server.sendServerMessage("channelAdd", mapOf("channelId" to 1L))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                withTimeout(2_000L) { first.await() }
                assertInstanceOf(HtspResult.Ok::class.java, service.execute(GetProfilesRequest(), timeoutMs = 2_000L))
                server.sendServerMessage("muxpkt", muxPacketFields(1))
                withTimeout(2_000L) { packet.await() }
                assertInstanceOf(HtspConnectionState.Connected::class.java, service.connectionState.value)
            } finally {
                release.countDown()
                blocking.cancelAndJoin()
                packet.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun synchronouslyBlockingUnconfinedSubscriptionCollectorDoesNotBlockReader() = runBlocking {
        FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(mapOf("profiles" to emptyList<Any>()))).use { server ->
            val service = service()
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                service.subscriptionEvents(1L).collect {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val other = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { service.subscriptionEvents(2L).first() }
            try {
                server.sendServerMessage("muxpkt", muxPacketFields(1))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertInstanceOf(HtspResult.Ok::class.java, service.execute(GetProfilesRequest(), timeoutMs = 2_000L))
                server.sendServerMessage("muxpkt", muxPacketFields(2, subscriptionId = 2L))
                withTimeout(2_000L) { other.await() }
                assertNotNull(service.liveConnection.value)
            } finally {
                release.countDown()
                collector.cancelAndJoin()
                other.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun reconnectInsideUnconfinedEmitPreservesOtherCollectorsGenerationOrder(): Unit = runBlocking {
        FakeHtspServer(respondToHello = true).use { first ->
            FakeHtspServer(respondToHello = true).use { second ->
                val service = service()
                val old = (service.connect(HtspEndpoint("127.0.0.1", first.port)) as HtspConnectOutcome.Connected).connection.generation
                val reconnected = CompletableDeferred<HtspConnectionGeneration>()
                val reconnecting = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    service.events.take(1).collect {
                        val next = service.connect(HtspEndpoint("127.0.0.1", second.port)) as HtspConnectOutcome.Connected
                        reconnected.complete(next.connection.generation)
                    }
                }
                val observed = async(start = CoroutineStart.UNDISPATCHED) { service.events.take(2).toList() }
                first.sendServerMessage("channelAdd", mapOf("channelId" to 1L))
                val newer = withTimeout(2_000L) { reconnected.await() }
                second.sendServerMessage("channelAdd", mapOf("channelId" to 2L))
                assertEquals(listOf(old, newer), withTimeout(2_000L) { observed.await() }.map { it.generation })
                reconnecting.join()
                service.close()
            }
        }
    }

    @Test
    fun acknowledgedDvrMutationSurvivesGenerationReplacementBeforeReturn(): Unit = runBlocking {
        FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(mapOf("success" to 1L, "id" to 42L))).use { first ->
            FakeHtspServer(respondToHello = true).use { second ->
                val reply = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val service = service(beforeTypedRecapture = { if (it is AddDvrEntryRequest) { reply.complete(Unit); resume.await() } })
                val old = (service.connect(HtspEndpoint("127.0.0.1", first.port)) as HtspConnectOutcome.Connected).connection.generation
                val call = async { service.execute(AddDvrEntryRequest(AddDvrEntrySelector.Event(1L)), expectedGeneration = old) }
                withTimeout(2_000L) { reply.await() }
                service.connect(HtspEndpoint("127.0.0.1", second.port))
                resume.complete(Unit)
                assertEquals(HtspResult.Ok(AddDvrEntryResponse(42L)), call.await())
                service.close()
            }
        }
    }

    @Test
    fun veryLargeTimeoutKeepsLateObserverUntilRetirement() = runBlocking {
        FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(null)).use { server ->
            val written = CompletableDeferred<Unit>()
            val expiryStarted = CompletableDeferred<Unit>()
            val resumeExpiry = CompletableDeferred<Unit>()
            val service = service(
                afterRequestFrameWritten = { if (it == "subscribe") written.complete(Unit) },
                beforeLateReplyExpiry = { expiryStarted.complete(Unit); resumeExpiry.await() },
            )
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.subscriptionEvents(1L).collect() }
            val call = async { service.subscribe(1L, channelId = 1L, timeoutMs = Long.MAX_VALUE) }
            try {
                withTimeout(2_000L) { written.await() }
                call.cancelAndJoin()
                withTimeout(2_000L) { expiryStarted.await() }
                @Suppress("UNCHECKED_CAST")
                val observers = HtspService::class.java.getDeclaredField("lateReplyObservers").apply { isAccessible = true }
                    .get(service) as Map<Int, Any>
                val observer = observers.values.single()
                val deadline = observer.javaClass.getDeclaredField("deadlineNanos").apply { isAccessible = true }.getLong(observer)
                val expiry = observer.javaClass.getDeclaredField("expiry").apply { isAccessible = true }.get(observer) as Job
                assertTrue(deadline - System.nanoTime() > TimeUnit.DAYS.toNanos(365L))
                resumeExpiry.complete(Unit)
                assertNull(withTimeoutOrNull(100L) { expiry.join(); true }, "A distant deadline must not overflow the expiry delay")
                service.disconnect()
                withTimeout(2_000L) { expiry.join() }
                assertTrue(observers.isEmpty())
            } finally {
                resumeExpiry.complete(Unit)
                call.cancelAndJoin()
                collector.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun delayedExpiryDispatchCannotExtendObserverDeadlineOrApplyLateRefusal() = runBlocking {
        for (lateReply in listOf(false, true)) {
            FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(null)).use { server ->
                val written = CompletableDeferred<Unit>()
                val expiryStarted = CompletableDeferred<Unit>()
                val resumeExpiry = CompletableDeferred<Unit>()
                val clock = java.util.concurrent.atomic.AtomicLong()
                val service = service(
                    requestNanoTime = clock::get,
                    afterRequestFrameWritten = { if (it == "subscribe") written.complete(Unit) },
                    beforeLateReplyExpiry = { expiryStarted.complete(Unit); resumeExpiry.await() },
                )
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val packet = async(start = CoroutineStart.UNDISPATCHED) { service.subscriptionEvents(1L).first() }
                val call = async { service.subscribe(1L, channelId = 1L, timeoutMs = 60_000L) }
                withTimeout(2_000L) { written.await() }
                call.cancelAndJoin()
                withTimeout(2_000L) { expiryStarted.await() }
                @Suppress("UNCHECKED_CAST")
                val observers = HtspService::class.java.getDeclaredField("lateReplyObservers").apply { isAccessible = true }.get(service) as Map<Int, Any>
                val observer = observers.values.single()
                val deadline = observer.javaClass.getDeclaredField("deadlineNanos").apply { isAccessible = true }.getLong(observer)
                val expiry = observer.javaClass.getDeclaredField("expiry").apply { isAccessible = true }.get(observer) as Job
                clock.set(deadline)
                if (lateReply) {
                    assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))
                    server.replyToPostHandshakeRequest(0, mapOf("error" to "refused"))
                    server.sendServerMessage("muxpkt", muxPacketFields(1))
                    assertInstanceOf(HtspSubscriptionEvent.Packet::class.java, withTimeout(1_000L) { packet.await() })
                } else {
                    resumeExpiry.complete(Unit)
                    withTimeout(2_000L) { expiry.join() }
                    assertTrue(observers.isEmpty())
                }
                packet.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun serviceBudgetsFrameBodiesWithoutCountingFourByteHeaders(): Unit = runBlocking {
        val budget = 1024L * 1024L
        val encoded = java.io.ByteArrayOutputStream()
        at.bernhardberger.tvheadend.htsp.wire.HtspCodec.writeMessage(encoded, "channelAdd", mapOf("channelId" to 1L, "channelName" to ""))
        val fields = mapOf("channelId" to 1L, "channelName" to "x".repeat(budget.toInt() - (encoded.size() - 4)))
        encoded.reset()
        at.bernhardberger.tvheadend.htsp.wire.HtspCodec.writeMessage(encoded, "channelAdd", fields)
        assertEquals(budget + 4L, encoded.size().toLong())
        FakeHtspServer(respondToHello = true).use { server ->
            val queued = CountDownLatch(2)
            val service = service(eventBufferOptions = HtspEventBufferOptions(metadataQueueBytes = budget),
                afterPublicationCurrencyCheck = { queued.countDown() })
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val events = async(start = CoroutineStart.UNDISPATCHED) { service.events.take(2).toList() }
            server.sendRaw(encoded.toByteArray())
            server.sendRaw(encoded.toByteArray())
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            val received = withTimeout(2_000L) { events.await() }
            assertInstanceOf(HtspTransportEvent.ServerMessage::class.java, received.first())
            assertEquals(1L, (received.last() as HtspTransportEvent.MetadataOverflow).droppedCount)
            service.close()
        }
    }

    @Test
    fun initialSyncOverflowAtCapacityOneFinishesWithoutWaitingForTimeout(): Unit = runBlocking {
        val queued = CountDownLatch(3)
        FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(emptyMap())).use { server ->
            val service = service(metadataEventBufferCapacity = 1, afterPublicationCurrencyCheck = { queued.countDown() })
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val sync = async(start = CoroutineStart.UNDISPATCHED) { service.enableAsyncMetadataAwaitingInitialSync(timeoutMs = 30_000L) }
            // Keep the collector's dispatcher blocked until the final publication starts.
            repeat(3) { server.sendServerMessage("channelAdd", mapOf("channelId" to it.toLong())) }
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            // Polling the queue takes the lock, so collection waits for that publication to finish.
            assertSame(HtspResult.TransportUnavailable, withTimeout(2_000L) { sync.await() })
            assertNotNull(service.liveConnection.value)
            service.close()
        }
    }
}
