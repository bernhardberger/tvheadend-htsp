package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal class HtspLifecycleRegressionTest : HtspServiceLifecycleFixture() {
    @Test
    fun disconnectRetiresAnAttemptQueuedBeforeConnectingStatePublication() = runBlocking {
        val service = service(socketFactory = { error("Superseded attempt must not create a socket") })
        val owner = serviceConnectionOwner(service)
        val mutex = owner.javaClass.getDeclaredField("admission").apply { isAccessible = true }
            .get(owner) as kotlinx.coroutines.sync.Mutex
        mutex.lock()
        val connect = async(start = CoroutineStart.UNDISPATCHED) { service.connect(HtspEndpoint("127.0.0.1", 1)) }
        try {
            assertTrue(service.disconnect(), "An admitted attempt is not already disconnected")
        } finally {
            mutex.unlock()
        }
        try {
            assertEquals(HtspConnectOutcome.Failed(HtspTransportFailure(HtspTransportFailureKind.SUPERSEDED)), connect.await())
        } finally {
            service.close()
        }
    }

    @Test
    fun closeReclaimsOldReaderWhileReplacementIsPausedAfterAdmission() = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val pause = AtomicBoolean(false)
            val admitted = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val socket = java.net.Socket()
            val service = service(socketFactory = { socket }, afterConnectionAdmission = {
                if (pause.get()) { admitted.complete(Unit); resume.await() }
            })
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val reader = serviceReaderJob(service)
            pause.set(true)
            val replacement = async { service.connect(HtspEndpoint("127.0.0.1", server.port), HtspConnectOptions(forceReconnect = true)) }
            admitted.await()
            val close = async(Dispatchers.IO) { service.close() }
            try {
                assertTrue(withTimeout(2_000L) { close.await() })
                assertTrue(socket.isClosed)
                assertTrue(reader.isCompleted)
                assertEquals(0, serviceOwnedJobCount(service))
            } finally {
                resume.complete(Unit)
                replacement.await()
                close.await()
            }
        }
    }

    @Test
    fun publicAndImplementationApisDoNotRunConsumerBlocksUnderTheGenerationLock() {
        for (type in listOf(HtspConnection::class.java, HtspService::class.java)) {
            assertTrue(type.methods.none { it.name == "commitIfCurrent" || it.name == "commitIfLive" })
        }
    }

    @Test
    fun subscriptionCollectionWithoutLiveTransportTerminatesNormally() = runBlocking {
        val service = service()
        val expected = listOf(HtspSubscriptionEvent.Terminated(HtspSubscriptionTermination.GENERATION_LOST))
        assertEquals(expected, service.subscriptionEvents(1L).toList())
        assertEquals(expected, service.subscriptionEvents(1L, HtspConnectionGeneration()).toList())
        service.close()
        assertEquals(expected, service.subscriptionEvents(1L).toList())
    }

    @Test
    fun handshakeEofFailsPromptlyAndErrorStaysUntilExplicitDisconnect() {
        val responseGate = CountDownLatch(1)
        FakeHtspServer(respondToHello = true, authenticateResponseGate = responseGate).use { server ->
            val failures = java.util.concurrent.CopyOnWriteArrayList<HtspTransportEvent>()
            val service = service(afterPublicationCurrencyCheck = { failures += it })
            runBlocking {
                val connect = async(Dispatchers.IO) {
                    service.connect(HtspEndpoint("127.0.0.1", server.port),
                        HtspConnectOptions(responseTimeoutMs = 10_000L))
                }
                assertTrue(server.authenticateRequestReceived.await(1, TimeUnit.SECONDS))
                val reader = serviceReaderJob(service)
                server.closeClientTransport()
                responseGate.countDown()
                val outcome = withTimeout(1_000L) { connect.await() } as HtspConnectOutcome.Failed
                withTimeout(1_000L) { reader.join() }
                assertTrue(reader.isCompleted)
                // The reader has exited; the synchronous publication probe cannot miss a queued event.
                assertTrue(failures.isEmpty())
                val error = HtspConnectionState.Error(outcome.failure)
                assertEquals(error, service.connectionState.value)
                delay(50L)
                assertEquals(error, service.connectionState.value)
                service.disconnect()
                assertEquals(HtspConnectionState.Disconnected, service.connectionState.value)
            }
        }
    }

    @Test
    fun connectReturnsItsOwnCapturedSnapshotAfterConcurrentReplacement() {
        FakeHtspServer(respondToHello = true).use { first ->
            FakeHtspServer(respondToHello = true).use { second ->
                val captured = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val firstOnly = AtomicBoolean(true)
                val service = service(afterConnectedSnapshot = {
                    if (firstOnly.compareAndSet(true, false)) {
                        captured.complete(Unit)
                        resume.await()
                    }
                })
                runBlocking {
                    val connect = async { service.connect(HtspEndpoint("127.0.0.1", first.port)) }
                    withTimeout(1_000L) { captured.await() }
                    val old = service.liveConnection.value
                    val newer = service.connect(HtspEndpoint("127.0.0.1", second.port)) as HtspConnectOutcome.Connected
                    resume.complete(Unit)
                    assertSame(old, (connect.await() as HtspConnectOutcome.Connected).connection)
                    assertNotSame(old, newer.connection)
                    assertSame(newer.connection, service.liveConnection.value)
                    service.close()
                }
            }
        }
    }

    @Test
    fun lateObserversExpireAtDeadlineAndRetirementCancelsTheirExpiryJobs() {
        FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(null, null, null)).use { server ->
            val written = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val expire = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val clock = java.util.concurrent.atomic.AtomicLong()
            val firstWrite = AtomicBoolean(true)
            val service = service(
                requestNanoTime = clock::get,
                beforeLateReplyExpiry = { expire.receive() },
                afterRequestFrameWritten = {
                    if (it == "unsubscribe") {
                        if (firstWrite.compareAndSet(true, false)) clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(101L))
                        written.trySend(Unit)
                    }
                },
            )
            @Suppress("UNCHECKED_CAST")
            val observers = HtspService::class.java.getDeclaredField("lateReplyObservers")
                .apply { isAccessible = true }.get(service) as Map<Int, Any>
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.subscriptionEvents(1L).collect() }
                assertSame(HtspResult.Timeout, service.unsubscribe(1L, timeoutMs = 100L))
                written.receive()
                assertTrue(observers.isEmpty())
                repeat(2) { index ->
                    // Freeze the request clock until cancellation has installed the observer.
                    val call = async(Dispatchers.IO) { service.unsubscribe(1L, timeoutMs = 60_000L) }
                    withTimeout(2_000L) { written.receive() }
                    call.cancelAndJoin()
                    assertEquals(1, observers.size)
                    val observer = observers.values.single()
                    val expiry = observer.javaClass.getDeclaredField("expiry")
                        .apply { isAccessible = true }.get(observer) as Job
                    if (index == 1) {
                        service.disconnect()
                    } else {
                        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(60_001L))
                        expire.send(Unit)
                    }
                    withTimeout(1_000L) { expiry.join() }
                    assertTrue(expiry.isCompleted)
                    assertTrue(observers.isEmpty())
                }
                collector.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun conditionalTeardownReportsChangesAndCancelledOwnersStillCleanUp() = runBlocking {
        for (close in listOf(false, true)) {
            FakeHtspServer(respondToHello = true).use { server ->
                val socket = java.net.Socket()
                val service = service(socketFactory = { socket })
                assertFalse(service.disconnect())
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                assertFalse(service.disconnect(HtspConnectionGeneration()))
                assertFalse(service.close(HtspConnectionGeneration()))
                val owner = launch {
                    currentCoroutineContext().cancel()
                    if (close) service.close() else service.disconnect()
                }
                owner.join()
                assertTrue(socket.isClosed)
                assertNull(service.liveConnection.value)
                assertFalse(service.disconnect())
                if (close) {
                    val job = HtspService::class.java.getDeclaredField("serviceJob").apply { isAccessible = true }.get(service) as Job
                    assertTrue(job.isCompleted)
                    assertFalse(service.close())
                } else {
                    assertTrue(service.close())
                    assertFalse(service.close())
                }
            }
        }
    }

    @Test
    fun connectFailuresAreStickyButNeverUnsolicitedLiveFailureEvents() = runBlocking {
        FakeHtspServer(respondToHello = true, authFields = mapOf("noaccess" to 1L)).use { server ->
            val events = java.util.concurrent.CopyOnWriteArrayList<HtspTransportEvent>()
            val services = listOf(
                service(socketFactory = { throw java.io.IOException("fixture") }, afterPublicationCurrencyCheck = { events += it }),
                service(afterPublicationCurrencyCheck = { events += it }),
            )
            for (service in services) {
                val outcome = service.connect(HtspEndpoint("127.0.0.1", server.port)) as HtspConnectOutcome.Failed
                assertEquals(HtspConnectionState.Error(outcome.failure), service.connectionState.value)
                assertTrue(events.isEmpty())
                assertTrue(service.disconnect())
                assertFalse(service.disconnect())
                service.close()
            }
        }
    }

    @Test
    fun unavailableTransportWithoutRecordedErrorReturnsTypedConnectFailure(): Unit = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val acknowledged = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val socket = java.net.Socket()
            val service = service(socketFactory = { socket }, afterAuthenticationAcknowledgement = {
                acknowledged.complete(Unit)
                resume.await()
            })
            val connect = async { service.connect(HtspEndpoint("127.0.0.1", server.port)) }
            try {
                withTimeout(2_000L) { acknowledged.await() }
                val reader = serviceReaderJob(service)
                HtspService::class.java.getDeclaredMethod("markTransportGone", java.net.Socket::class.java,
                    HtspSubscriptionTermination::class.java).apply { isAccessible = true }
                    .invoke(service, socket, HtspSubscriptionTermination.LOCAL_RETIREMENT)
                withTimeout(2_000L) { reader.join() }
                assertEquals(HtspConnectionState.Disconnected, service.currentConnectionState())
                resume.complete(Unit)
                val failure = HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE)
                assertEquals(HtspConnectOutcome.Failed(failure), withTimeout(2_000L) { connect.await() })
                assertEquals(HtspConnectionState.Error(failure), service.connectionState.value)
            } finally {
                resume.complete(Unit)
                service.close()
            }
        }
    }

    @Test
    fun timeoutAndWriteFailurePublishConsistentStateAndExactlyOneRecordedFailure(): Unit = runBlocking {
        for (writeFailure in listOf(false, true)) {
            FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(null)).use { server ->
                val failWrites = AtomicBoolean(false)
                val socket = object : java.net.Socket() {
                    override fun getOutputStream(): java.io.OutputStream = object : java.io.FilterOutputStream(super.getOutputStream()) {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            if (failWrites.get()) throw java.io.IOException("fixture write failure")
                            out.write(bytes, offset, length)
                        }
                    }
                }
                val reading = CountDownLatch(1)
                val resumeReader = CountDownLatch(1)
                val frames = java.util.concurrent.atomic.AtomicInteger()
                val failures = java.util.concurrent.CopyOnWriteArrayList<HtspTransportEvent.ConnectionFailure>()
                val service = service(socketFactory = { socket }, beforeFrameRead = {
                    if (frames.incrementAndGet() == 3) {
                        reading.countDown()
                        check(resumeReader.await(5, TimeUnit.SECONDS))
                    }
                }, afterPublicationCurrencyCheck = { if (it is HtspTransportEvent.ConnectionFailure) failures += it })
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                assertTrue(reading.await(2, TimeUnit.SECONDS))
                val reader = serviceReaderJob(service)
                val pairs = java.util.concurrent.CopyOnWriteArrayList<Pair<HtspConnectionState, HtspLiveConnection?>>()
                val stateObserver = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    service.connectionState.collect { pairs += it to service.liveConnection.value }
                }
                val liveObserver = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    service.liveConnection.collect { pairs += service.connectionState.value to it }
                }
                try {
                    failWrites.set(writeFailure)
                    val result = runCatching { service.request(
                        "getProfiles",
                        policy = HtspReplyPolicy(timeoutMs = 100L),
                    ) }
                    assertInstanceOf(java.io.IOException::class.java, result.exceptionOrNull())
                    val failure = HtspTransportFailure(if (writeFailure) HtspTransportFailureKind.TRANSPORT_UNAVAILABLE
                        else HtspTransportFailureKind.CONNECTION_TIMEOUT)
                    assertEquals(HtspConnectionState.Error(failure), service.connectionState.value)
                    assertNull(service.liveConnection.value)
                    assertTrue(failures.isEmpty(), "Reader has not yet run failAll")
                    resumeReader.countDown()
                    withTimeout(2_000L) { reader.join() }
                    assertEquals(listOf(failure), failures.map { it.failure })
                    assertEquals(HtspConnectionState.Error(failure), service.connectionState.value)
                    assertTrue(pairs.none { (state, live) -> state is HtspConnectionState.Connected && live == null })
                } finally {
                    resumeReader.countDown()
                    stateObserver.cancelAndJoin()
                    liveObserver.cancelAndJoin()
                    service.close()
                }
            }
        }
    }

    @Test
    fun readerFailureAfterAuthAcknowledgementIsNotSupersession(): Unit = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val acknowledged = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val service = service(afterAuthenticationAcknowledgement = { acknowledged.complete(Unit); resume.await() })
            val connect = async { service.connect(HtspEndpoint("127.0.0.1", server.port)) }
            acknowledged.await()
            val reader = serviceReaderJob(service)
            server.closeClientTransport()
            withTimeout(1_000L) { reader.join() }
            val error = service.connectionState.value as HtspConnectionState.Error
            resume.complete(Unit)
            assertEquals(HtspConnectOutcome.Failed(error.failure), connect.await())
            assertNotEquals(HtspTransportFailureKind.SUPERSEDED, error.failure.kind)
            assertEquals(error, service.connectionState.value)
            service.close()
        }
    }

    @Test
    fun liveReaderFailureTransitionsDirectlyToStickyError(): Unit = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val states = java.util.concurrent.CopyOnWriteArrayList<HtspConnectionState>()
            val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                service.connectionState.collect { states += it }
            }
            withTimeout(1_000L) { while (states.isEmpty()) yield() }
            val reader = serviceReaderJob(service)
            server.closeClientTransport()
            withTimeout(1_000L) { reader.join() }
            withTimeout(1_000L) { while (states.lastOrNull() !is HtspConnectionState.Error) yield() }
            assertEquals(2, states.size)
            assertInstanceOf(HtspConnectionState.Connected::class.java, states.first())
            assertInstanceOf(HtspConnectionState.Error::class.java, states.last())
            collector.cancelAndJoin()
            service.close()
        }
    }

    @Test
    fun fencedDisconnectLosingCaptureRaceReturnsFalse(): Unit = runBlocking {
        FakeHtspServer(respondToHello = true).use { first ->
            FakeHtspServer(respondToHello = true).use { second ->
                val admitted = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val service = service(afterTeardownAdmission = { admitted.complete(Unit); resume.await() })
                val old = (service.connect(HtspEndpoint("127.0.0.1", first.port)) as HtspConnectOutcome.Connected).connection.generation
                val disconnect = async { service.disconnect(old) }
                admitted.await()
                val newer = (service.connect(HtspEndpoint("127.0.0.1", second.port)) as HtspConnectOutcome.Connected).connection
                resume.complete(Unit)
                assertFalse(disconnect.await())
                assertSame(newer, service.liveConnection.value)
                service.close()
            }
        }
    }
}
