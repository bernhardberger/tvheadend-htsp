package at.bernhardberger.tvheadend.htsp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HtspServiceLifecycleTest {

    @Test
    fun identicalEndpointReusesOnlyTheSameLiveConnectionIdentity() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                val endpoint = HtspEndpoint("127.0.0.1", server.port, "viewer", "secret")
                val first = service.connect(endpoint) as HtspConnectOutcome.Connected
                val reused = service.connect(endpoint) as HtspConnectOutcome.Connected

                assertSame(first.connection.generation, reused.connection.generation)
                assertEquals(listOf("hello", "authenticate"), server.handshakeMethods)
                service.disconnect()
            }
        }
    }

    @Test
    fun changedAddressReconnectsWithoutExplicitForce() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val first = service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port, "viewer", "secret"),
                    ) as HtspConnectOutcome.Connected
                    val replacement = service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port, "viewer", "secret"),
                    ) as HtspConnectOutcome.Connected

                    assertNotSame(first.connection.generation, replacement.connection.generation)
                    assertEquals(listOf("hello", "authenticate"), replacementServer.handshakeMethods)
                    service.disconnect()
                }
            }
        }
    }

    @Test
    fun changedCredentialsReconnectWithoutRenderingCredentialIdentity() {
        FakeHtspServer(respondToHello = true, expectedConnections = 2).use { server ->
            val service = service()
            runBlocking {
                val first = service.connect(
                    HtspEndpoint("127.0.0.1", server.port, "viewer-a", "secret-a"),
                ) as HtspConnectOutcome.Connected
                val replacement = service.connect(
                    HtspEndpoint("127.0.0.1", server.port, "viewer-b", "secret-b"),
                ) as HtspConnectOutcome.Connected

                assertNotSame(first.connection.generation, replacement.connection.generation)
                assertEquals(
                    listOf("hello", "authenticate", "hello", "authenticate"),
                    server.handshakeMethods,
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun staleDisconnectCannotRetireReplacementButCurrentAndOwnerGlobalDisconnectCan() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true, expectedConnections = 2).use { replacementServer ->
                val service = service()
                runBlocking {
                    val stale = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val current = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation

                    val failure = runCatching { service.disconnect(stale) }.exceptionOrNull()
                    assertTrue(failure is CancellationException)
                    assertSame(current, service.liveConnection.value?.generation)
                    assertEquals(replacementServer.port, (service.state.value as ConnectionState.Connected).port)

                    service.disconnect(current)
                    assertNull(service.liveConnection.value)

                    service.connect(HtspEndpoint("127.0.0.1", replacementServer.port))
                    service.disconnect()
                    assertNull(service.liveConnection.value)
                }
            }
        }
    }

    @Test
    fun admittedDisconnectCannotRetireReplacementEstablishedBeforeTransportOwnership() {
        val teardownAdmitted = CompletableDeferred<Unit>()
        val resumeTeardown = CompletableDeferred<Unit>()
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(
                respondToHello = true,
                captureOnePostHandshakeRequest = true,
            ).use { replacementServer ->
                val service = service(
                    afterTeardownAdmission = {
                        teardownAdmitted.complete(Unit)
                        resumeTeardown.await()
                    },
                )
                runBlocking {
                    val stale = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val disconnect = async(Dispatchers.IO) {
                        runCatching { service.disconnect(stale) }.exceptionOrNull()
                    }
                    withTimeout(1_000L) { teardownAdmitted.await() }

                    val replacement = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val pendingRequest = async(Dispatchers.IO) {
                        service.request(
                            method = "replacementRequest",
                            timeoutMs = 5_000L,
                            disconnectOnTimeout = false,
                        )
                    }
                    assertTrue(
                        replacementServer.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS),
                    )

                    resumeTeardown.complete(Unit)
                    val failure = withTimeout(1_000L) { disconnect.await() }

                    assertTrue(failure is CancellationException)
                    assertSame(replacement, service.liveConnection.value?.generation)
                    assertEquals(
                        replacementServer.port,
                        (service.state.value as ConnectionState.Connected).port,
                    )
                    replacementServer.replyToCapturedPostHandshakeRequest()
                    assertEquals(
                        "replacementRequest",
                        withTimeout(1_000L) { pendingRequest.await() }.method,
                    )

                    val replacementEvent = async(start = CoroutineStart.UNDISPATCHED) {
                        service.controlEvents.first { event ->
                            event is HtspControlEvent.ServerMessage &&
                                event.msg.method == "replacementMarker"
                        }
                    }
                    replacementServer.sendServerMessage("replacementMarker")
                    assertTrue(
                        withTimeout(1_000L) { replacementEvent.await() } is
                            HtspControlEvent.ServerMessage,
                    )

                    service.disconnect()
                    assertNull(service.liveConnection.value)
                    assertTrue(service.state.value is ConnectionState.Disconnected)
                }
            }
        }
    }

    @Test
    fun staleCloseCannotRetireOrTerminallyCloseReplacementButCurrentAndOwnerGlobalCloseCan() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val stale = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val current = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation

                    val failure = runCatching { service.close(stale) }.exceptionOrNull()
                    assertTrue(failure is CancellationException)
                    assertSame(current, service.liveConnection.value?.generation)
                    assertTrue(service.connect(HtspEndpoint("127.0.0.1", replacementServer.port)) is HtspConnectOutcome.Connected)

                    service.close(current)
                    assertNull(service.liveConnection.value)
                    assertTrue(
                        service.connect(HtspEndpoint("127.0.0.1", replacementServer.port)) is
                            HtspConnectOutcome.Failed,
                    )
                }
            }
        }

        val ownerGlobal = service()
        runBlocking { ownerGlobal.close() }
        assertTrue(runBlocking {
            ownerGlobal.connect(HtspEndpoint("127.0.0.1", 9982)) is HtspConnectOutcome.Failed
        })
    }

    @Test
    fun cancelledConnectPropagatesWithoutPublishingTransportError() {
        FakeHtspServer(respondToHello = false).use { server ->
            val service = service()
            runBlocking {
                val observed = CopyOnWriteArrayList<ConnectionState>()
                val collector = launch {
                    service.state.collect { observed += it }
                }
                val connection = launch(Dispatchers.IO) {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 5_000,
                        soTimeoutMs = 50,
                    )
                }
                withTimeout(1_000L) {
                    service.state.first { it is ConnectionState.Connecting }
                }

                connection.cancelAndJoin()
                delay(50L)

                assertTrue(observed.none { it is ConnectionState.Error })
                assertTrue(service.state.value is ConnectionState.Disconnected)
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun replacementConnectCannotBeOverwrittenByCancelledAttemptState() {
        FakeHtspServer(respondToHello = false).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val observed = CopyOnWriteArrayList<ConnectionState>()
                    val collector = launch {
                        service.state.collect { observed += it }
                    }
                    val first = launch(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = firstServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 5_000,
                            soTimeoutMs = 50,
                        )
                    }
                    withTimeout(1_000L) {
                        service.state.first {
                            it is ConnectionState.Connecting && it.port == firstServer.port
                        }
                    }

                    first.cancel()
                    val replacement = async(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = replacementServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 1_000,
                            soTimeoutMs = 50,
                            forceReconnect = true,
                        )
                    }
                    replacement.await()
                    first.join()
                    delay(50L)

                    val replacementStart = observed.indexOfFirst {
                        it is ConnectionState.Connecting && it.port == replacementServer.port
                    }
                    assertTrue(replacementStart >= 0)
                    assertTrue(observed.drop(replacementStart).none { it is ConnectionState.Error })
                    assertEquals(
                        replacementServer.port,
                        (service.state.value as ConnectionState.Connected).port,
                    )
                    service.disconnect()
                    collector.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun cancelledReplacementWaitingForConnectOwnerLeavesDisconnectedState() {
        FakeHtspServer(respondToHello = false).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val first = launch(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = firstServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 5_000,
                            soTimeoutMs = 50,
                        )
                    }
                    withTimeout(1_000L) {
                        service.state.first { it is ConnectionState.Connecting }
                    }
                    val firstAttempt = service.currentConnectionAttemptId()

                    val replacement = launch(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = replacementServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 1_000,
                            soTimeoutMs = 50,
                            forceReconnect = true,
                        )
                    }
                    withTimeout(1_000L) {
                        while (service.currentConnectionAttemptId() == firstAttempt) delay(1L)
                    }

                    replacement.cancelAndJoin()
                    withTimeout(1_000L) { first.join() }

                    assertTrue(service.state.value is ConnectionState.Disconnected)
                }
            }
        }
    }

    @Test
    fun supersededReaderCannotPublishOldServerMessage() {
        val releaseOldAuthentication = CountDownLatch(1)
        FakeHtspServer(
            respondToHello = true,
            authenticateResponseGate = releaseOldAuthentication,
        ).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val events = CopyOnWriteArrayList<HtspControlEvent>()
                    val collector = launch {
                        service.controlEvents.collect { events += it }
                    }
                    val first = launch(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = firstServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 5_000,
                            soTimeoutMs = 50,
                        )
                    }
                    assertTrue(firstServer.authenticateRequestReceived.await(1, TimeUnit.SECONDS))
                    val firstAttempt = service.currentConnectionAttemptId()

                    val replacement = async(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = replacementServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 1_000,
                            soTimeoutMs = 50,
                            forceReconnect = true,
                        )
                    }
                    withTimeout(1_000L) {
                        while (service.currentConnectionAttemptId() == firstAttempt) delay(1L)
                    }

                    firstServer.sendServerMessage("oldServerMarker")
                    delay(50L)
                    assertTrue(
                        events.none {
                            it is HtspControlEvent.ServerMessage &&
                                it.msg.method == "oldServerMarker"
                        }
                    )

                    first.cancel()
                    releaseOldAuthentication.countDown()
                    first.join()
                    replacement.await()
                    service.disconnect()
                    collector.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun staleSubscriptionCommandCannotUseReplacementTransport() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    service.connect(
                        host = "127.0.0.1",
                        port = firstServer.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                    val staleAttempt = service.currentConnectionAttemptId()
                    service.connect(
                        host = "127.0.0.1",
                        port = replacementServer.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                        forceReconnect = true,
                    )

                    val failure = runCatching {
                        service.requestForConnectionAttempt(
                            expectedConnectionAttemptId = staleAttempt,
                            method = "subscriptionSpeed",
                            fields = mapOf("subscriptionId" to 1, "speed" to 0),
                        )
                    }.exceptionOrNull()

                    assertTrue(failure is kotlinx.coroutines.CancellationException)
                    assertEquals(
                        listOf("hello", "authenticate"),
                        replacementServer.handshakeMethods,
                    )
                    service.disconnect()
                }
            }
        }
    }

    @Test
    fun controlAndMuxEventsCarrySharedReaderOrder() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val control = async(start = CoroutineStart.UNDISPATCHED) {
                    service.controlEvents.first {
                        it is HtspControlEvent.ServerMessage &&
                            it.msg.method == "subscriptionSkip"
                    } as HtspControlEvent.ServerMessage
                }
                val mux = async(start = CoroutineStart.UNDISPATCHED) {
                    service.muxEvents.first { it.msg.method == "muxpkt" }
                }

                server.sendServerMessage("subscriptionSkip")
                server.sendServerMessage("muxpkt")

                val controlEvent = withTimeout(1_000L) { control.await() }
                val muxEvent = withTimeout(1_000L) { mux.await() }
                assertEquals(controlEvent.connectionAttemptId, muxEvent.connectionAttemptId)
                assertTrue(controlEvent.messageSequence < muxEvent.messageSequence)
                assertTrue(muxEvent.muxSequence > 0L)
                assertEquals(
                    muxEvent.muxSequence,
                    service.currentMuxSequenceForConnectionAttempt(muxEvent.connectionAttemptId),
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun connectFailureCompletesWhenServerDoesNotAnswerHello() {
        FakeHtspServer(respondToHello = false).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Throwable?> {
                    runBlocking {
                        runCatching {
                            service().connect(
                                host = "127.0.0.1",
                                port = server.port,
                                connectTimeoutMs = 1_000,
                                responseTimeoutMs = 100,
                                soTimeoutMs = 50,
                            )
                        }.exceptionOrNull()
                    }
                }.get(2, TimeUnit.SECONDS)

                assertNotNull(result)
            } finally {
                server.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun disconnectCompletesWhileReaderIsIdle() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
            }

            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Boolean> {
                    runBlocking {
                        service.disconnect()
                        true
                    }
                }.get(2, TimeUnit.SECONDS)

                assertTrue(result)
            } finally {
                server.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun optionalRequestTimeoutLeavesSharedConnectionOpen() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val failure = runCatching {
                    service.request(
                        method = "getEvents",
                        timeoutMs = 100,
                        disconnectOnTimeout = false,
                    )
                }.exceptionOrNull()

                assertNotNull(failure)
                assertTrue(failure is HtspRequestTimeoutException)
                assertTrue(service.state.value is ConnectionState.Connected)
                service.disconnect()
            }
        }
    }

    @Test
    fun lateReplyAfterOptionalRequestTimeoutIsNotPublishedAsControlEvent() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val failure = runCatching {
                    service.request(
                        method = "getEvents",
                        timeoutMs = 100,
                        disconnectOnTimeout = false,
                    )
                }.exceptionOrNull()
                assertTrue(failure is HtspRequestTimeoutException)
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))

                val unexpectedEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(250L) { service.controlEvents.first() }
                }
                server.replyToCapturedPostHandshakeRequest()

                assertNull(unexpectedEvent.await())
                assertTrue(service.state.value is ConnectionState.Connected)
                service.disconnect()
            }
        }
    }

    @Test
    fun helloWithoutServerVersionIsRejectedBeforeAuthentication() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf("challenge" to ByteArray(32)),
        ).use { server ->
            val service = service()

            val failure = runBlocking {
                runCatching {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                }.exceptionOrNull()
            }

            assertNotNull(failure)
            assertEquals(listOf("hello"), server.handshakeMethods)
        }
    }

    @Test
    fun helloWithMalformedChallengeIsRejectedBeforeAuthentication() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(31),
            ),
        ).use { server ->
            val service = service()

            val failure = runBlocking {
                runCatching {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                }.exceptionOrNull()
            }

            assertNotNull(failure)
            assertEquals(listOf("hello"), server.handshakeMethods)
        }
    }

    @Test
    fun authenticationErrorReplyDoesNotEstablishAConnection() {
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("error" to "server-provided detail"),
        ).use { server ->
            val service = service()

            val failure = runBlocking {
                runCatching {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        username = "viewer",
                        password = "secret",
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                }.exceptionOrNull()
            }

            assertTrue(requireNotNull(failure).message.orEmpty().contains("authentication failed"))
            assertEquals(listOf("hello", "authenticate"), server.handshakeMethods)
        }
    }

    @Test
    fun credentialAuthenticationUsesExactPasswordUtf8BytesThenSessionChallenge() {
        val sessionChallenge = ByteArray(32) { index -> index.toByte() }
        val password = "  sëcret\t"
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to sessionChallenge,
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "viewer",
                    password = password,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val auth = requireNotNull(server.handshakeFields["authenticate"])
                assertEquals("viewer", auth["username"])
                assertArrayEquals(
                    MessageDigest.getInstance("SHA-1").digest(
                        password.toByteArray() + sessionChallenge,
                    ),
                    auth["digest"] as ByteArray,
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun callerTimeoutIsNotConvertedToRequestTimeout() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val failure = runCatching {
                    withTimeout(50L) {
                        service.request(
                            method = "getEvents",
                            timeoutMs = 500L,
                            disconnectOnTimeout = false,
                        )
                    }
                }.exceptionOrNull()

                assertTrue(failure is TimeoutCancellationException)
                assertTrue(service.state.value is ConnectionState.Connected)
                service.disconnect()
            }
        }
    }

    @Test
    fun missingInitialSyncMarkerIsTransportTimeoutNotCallerCancellation() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val sync = async(Dispatchers.IO) {
                    try {
                        service.enableAsyncMetadataAndWaitInitialSync(timeoutMs = 250L)
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                server.replyToCapturedPostHandshakeRequest()

                val failure = sync.await()
                assertTrue(failure is SocketTimeoutException)
                assertTrue(failure !is TimeoutCancellationException)
                withTimeout(1_000L) {
                    service.state.first { it is ConnectionState.Disconnected }
                }
            }
        }
    }

    @Test
    fun callerTimeoutDuringInitialSyncStillPropagatesWithoutClosingTransport() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val sync = async(Dispatchers.IO) {
                    try {
                        withTimeout(250L) {
                            service.enableAsyncMetadataAndWaitInitialSync(timeoutMs = 5_000L)
                        }
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                server.replyToCapturedPostHandshakeRequest()

                assertTrue(sync.await() is TimeoutCancellationException)
                assertTrue(service.state.value is ConnectionState.Connected)
                service.disconnect()
            }
        }
    }

    @Test
    fun cancelledWrittenRequestLeavesAttemptLiveUntilTransportDisconnects() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val attemptId = service.currentConnectionAttemptId()
                val request = launch(Dispatchers.IO) {
                    service.requestForConnectionAttempt(
                        expectedConnectionAttemptId = attemptId,
                        method = "subscribe",
                        fields = mapOf("subscriptionId" to 42),
                        timeoutMs = 5_000L,
                    )
                }

                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                request.cancelAndJoin()

                assertEquals(
                    HtspConnectionAttemptStatus.LIVE,
                    service.connectionAttemptStatus(attemptId),
                )
                service.disconnect()
                assertEquals(
                    HtspConnectionAttemptStatus.REPLACED,
                    service.connectionAttemptStatus(attemptId),
                )
            }
        }
    }

    @Test
    fun subscriptionFilterRequestPreservesCommandFieldOrderThroughCodec() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val attemptId = service.currentConnectionAttemptId()
                val update = async(Dispatchers.IO) {
                    service.updateSubscriptionStreamFilter(
                        expectedConnectionAttemptId = attemptId,
                        subscriptionId = 23,
                        enabledStreamIndices = listOf(4, 1, 4),
                        disabledStreamIndices = listOf(7, 2),
                    )
                }

                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                val request = server.capturedPostHandshakeRequest()
                assertEquals("subscriptionFilterStream", request.method)
                assertNotNull(request.seq)
                assertEquals(
                    listOf("method", "seq", "subscriptionId", "enable", "disable"),
                    request.fields.keys.toList(),
                )
                assertEquals(23L, request.fields["subscriptionId"])
                assertEquals(listOf(4L, 1L, 4L), request.fields["enable"])
                assertEquals(listOf(7L, 2L), request.fields["disable"])

                server.replyToCapturedPostHandshakeRequest()
                assertEquals(Unit, update.await())
                service.disconnect()
            }
        }
    }

    @Test
    fun failedCurrentTransportBecomesGoneWithoutWaitingForANewAttempt() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val attemptId = service.currentConnectionAttemptId()

                server.closeClientTransport()
                withTimeout(1_000L) {
                    while (
                        service.connectionAttemptStatus(attemptId) ==
                        HtspConnectionAttemptStatus.LIVE
                    ) {
                        delay(1L)
                    }
                }

                assertEquals(
                    HtspConnectionAttemptStatus.GONE,
                    service.connectionAttemptStatus(attemptId),
                )
            }
        }
    }

    @Test
    fun anonymousConnectStillAuthenticatesAndReadsDvrRight() {
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("dvr" to 1, "streaming" to 1),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val state = service.state.value as ConnectionState.Connected
                assertEquals(true, state.dvrAccess)
                assertEquals(listOf("hello", "authenticate"), server.handshakeMethods)
                // No credentials configured: authenticate must stay bare so the server
                // keeps the address-based anonymous rights.
                assertNull(server.handshakeFields["authenticate"]?.get("username"))
                service.disconnect()
            }
        }
    }

    @Test
    fun transportStateOmitsServerFacts() {
        val connectedClass = ConnectionState.Connected::class.java

        assertTrue(connectedClass.declaredMethods.none { method -> method.name == "getServerFacts" })
        assertTrue(connectedClass.declaredFields.none { field -> field.name == "serverFacts" })
    }

    @Test
    fun negativeU32HandshakeFactsStayUnknown() {
        val facts = htspServerFactsFromHandshake(
            hello = HtspMessage(
                method = "hello",
                seq = 1,
                fields = mapOf("api_version" to (-1).toByte()),
            ),
            auth = HtspMessage(
                method = "authenticate",
                seq = 2,
                fields = mapOf(
                    "limitall" to (-1).toShort(),
                    "limitdvr" to -1,
                    "limitstreaming" to -1L,
                    "uilevel" to (-1).toByte(),
                ),
            ),
        )

        assertNull(facts.apiVersion)
        assertNull(facts.limitAll)
        assertNull(facts.limitDvr)
        assertNull(facts.limitStreaming)
        assertNull(facts.uiLevel)
    }

    @Test
    fun successfulHandshakePublishesStrictOptionalServerFactsWithoutSecrets() {
        val mutableCapabilities = mutableListOf("timeshift", "htsp")
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 44,
                "challenge" to ByteArray(32) { index -> index.toByte() },
                "servername" to "tvh-fixture",
                "serverversion" to "4.3-fixture",
                "webroot" to "/tvheadend",
                "language" to "en_US",
                "servercapability" to mutableCapabilities,
                "api_version" to 19,
            ),
            authFields = mapOf(
                "admin" to 1,
                "streaming" to 1,
                "dvr" to 1,
                "faileddvr" to 0,
                "anonymous" to 0,
                "limitall" to 0,
                "limitdvr" to 2,
                "limitstreaming" to 5,
                "uilevel" to 1,
                "uilanguage" to "de_DE",
                // Secrets and non-fact fields must never surface through the internal handoff.
                "noaccess" to 0,
                "digest" to ByteArray(20),
                "username" to "should-not-publish",
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "viewer",
                    password = "secret",
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val state = service.state.value as ConnectionState.Connected
                val attemptId = service.currentConnectionAttemptId()
                val facts = requireNotNull(service.serverFactsForLiveConnectionAttempt(attemptId))
                assertEquals("tvh-fixture", facts.serverName)
                assertEquals("4.3-fixture", facts.serverVersion)
                assertEquals("/tvheadend", facts.webRoot)
                assertEquals("en_US", facts.language)
                assertEquals(listOf("timeshift", "htsp"), facts.serverCapabilities)
                assertEquals(19, facts.apiVersion)
                assertEquals(true, facts.admin)
                assertEquals(true, facts.streaming)
                assertEquals(true, facts.dvr)
                assertEquals(false, facts.failedDvr)
                assertEquals(false, facts.anonymous)
                assertEquals(0, facts.limitAll)
                assertEquals(2, facts.limitDvr)
                assertEquals(5, facts.limitStreaming)
                assertEquals(1, facts.uiLevel)
                assertEquals("de_DE", facts.uiLanguage)
                // Existing DVR-capability derivation remains independent of the observation.
                assertEquals(true, state.dvrAccess)

                mutableCapabilities += "mutated-after-decode"
                assertEquals(listOf("timeshift", "htsp"), facts.serverCapabilities)

                // Public facts expose only safe identity/access observations.
                assertEquals(
                    HtspServerFacts(
                        serverName = "tvh-fixture",
                        serverVersion = "4.3-fixture",
                        webRoot = "/tvheadend",
                        language = "en_US",
                        serverCapabilities = listOf("timeshift", "htsp"),
                        apiVersion = 19,
                        admin = true,
                        streaming = true,
                        dvr = true,
                        failedDvr = false,
                        anonymous = false,
                        limitAll = 0,
                        limitDvr = 2,
                        limitStreaming = 5,
                        uiLevel = 1,
                        uiLanguage = "de_DE",
                    ),
                    facts,
                )
                val serialized = facts.toString()
                assertTrue(!serialized.contains("viewer"))
                assertTrue(!serialized.contains("secret"))
                assertTrue(!serialized.contains("should-not-publish"))
                assertTrue(!serialized.contains("challenge"))
                assertTrue(!serialized.contains("digest"))

                service.disconnect()
                assertTrue(service.state.value !is ConnectionState.Connected)
                assertNull(service.serverFactsForLiveConnectionAttempt(attemptId))
            }
        }
    }

    @Test
    fun omittedAndMalformedHandshakeFieldsStayExplicitlyUnknown() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
                "servername" to "",
                "serverversion" to 12,
                "webroot" to listOf("/not-a-string"),
                "language" to null,
                "servercapability" to emptyList<Any?>(),
                "api_version" to "19",
            ),
            authFields = mapOf(
                "admin" to 0,
                "streaming" to 2,
                "dvr" to "1",
                "faileddvr" to 1L,
                "anonymous" to true,
                "limitall" to -1,
                "limitdvr" to 1.5,
                "limitstreaming" to Long.MAX_VALUE,
                "uilevel" to "high",
                "uilanguage" to ByteArray(2),
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val facts = requireNotNull(
                    service.serverFactsForLiveConnectionAttempt(service.currentConnectionAttemptId()),
                )
                // Empty string is an observed wire value, not unknown.
                assertEquals("", facts.serverName)
                assertNull(facts.serverVersion)
                assertNull(facts.webRoot)
                assertNull(facts.language)
                // Empty capability list is distinct from unknown/absent.
                assertEquals(emptyList<String>(), facts.serverCapabilities)
                assertNull(facts.apiVersion)
                assertEquals(false, facts.admin)
                assertNull(facts.streaming)
                assertNull(facts.dvr)
                assertEquals(true, facts.failedDvr)
                assertNull(facts.anonymous)
                assertNull(facts.limitAll)
                assertNull(facts.limitDvr)
                assertNull(facts.limitStreaming)
                assertNull(facts.uiLevel)
                assertNull(facts.uiLanguage)
                service.disconnect()
            }
        }
    }

    @Test
    fun mixedTypeServerCapabilityListStaysUnknown() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
                "servercapability" to listOf("ok", 3),
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val facts = requireNotNull(
                    service.serverFactsForLiveConnectionAttempt(service.currentConnectionAttemptId()),
                )
                assertNull(facts.serverCapabilities)
                service.disconnect()
            }
        }
    }

    @Test
    fun absentOptionalHandshakeFieldsPublishUnknownFactsNotSyntheticDefaults() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
            ),
            authFields = emptyMap(),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val facts = requireNotNull(
                    service.serverFactsForLiveConnectionAttempt(service.currentConnectionAttemptId()),
                )
                assertEquals(HtspServerFacts(), facts)
                service.disconnect()
            }
        }
    }

    @Test
    fun anonymousConnectFailsWhenServerGrantsNoAccess() {
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("noaccess" to 1),
        ).use { server ->
            val service = service()
            val failure = runBlocking {
                runCatching {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                }.exceptionOrNull()
            }

            assertNotNull(failure)
            assertTrue(requireNotNull(failure).message.orEmpty().contains("noaccess=1"))
        }
    }

    private fun service(
        afterTeardownAdmission: suspend () -> Unit = {},
    ) = HtspService(
        ioDispatcher = Dispatchers.IO,
        afterTeardownAdmission = afterTeardownAdmission,
    )

    private class FakeHtspServer(
        private val respondToHello: Boolean,
        private val authFields: Map<String, Any?> = emptyMap(),
        private val helloReplyFields: Map<String, Any?> = mapOf(
            "htspversion" to 43,
            "challenge" to ByteArray(32),
        ),
        private val authenticateResponseGate: CountDownLatch? = null,
        private val captureOnePostHandshakeRequest: Boolean = false,
        private val expectedConnections: Int = 1,
    ) : Closeable {
        private val serverSocket = ServerSocket(0)
        private val stop = CountDownLatch(1)
        @Volatile
        private var clientSocket: Socket? = null
        private val clientSockets = CopyOnWriteArrayList<Socket>()
        @Volatile
        private var postHandshakeRequest: HtspMessage? = null
        val authenticateRequestReceived = CountDownLatch(1)
        val postHandshakeRequestReceived = CountDownLatch(1)
        /** Methods the client sent during the handshake, in order. */
        val handshakeMethods = mutableListOf<String>()
        val handshakeFields = mutableMapOf<String, Map<String, Any?>>()
        private val serverThread = thread(
            start = true,
            isDaemon = true,
            name = "fake-htsp-server",
        ) {
            runCatching {
                repeat(expectedConnections) {
                    val client = serverSocket.accept()
                    clientSocket = client
                    clientSockets += client
                    if (respondToHello) {
                        // The client always sends hello then authenticate, with or without
                        // credentials; anything after that is left unanswered on purpose.
                        repeat(2) {
                            val request = HtspCodec.readMessage(client.getInputStream())
                            val method = requireNotNull(request.method)
                            handshakeMethods += method
                            handshakeFields[method] = request.fields
                            if (method == "authenticate") {
                                authenticateRequestReceived.countDown()
                                authenticateResponseGate?.await()
                            }
                            val fields = mutableMapOf<String, Any?>(
                                "seq" to requireNotNull(request.seq),
                            )
                            if (method == "authenticate") {
                                fields += authFields
                            } else {
                                fields += helloReplyFields
                            }
                            HtspCodec.writeMessage(
                                output = client.getOutputStream(),
                                method = method,
                                fields = fields,
                            )
                            client.getOutputStream().flush()
                        }
                        if (captureOnePostHandshakeRequest) {
                            postHandshakeRequest = HtspCodec.readMessage(client.getInputStream())
                            postHandshakeRequestReceived.countDown()
                        }
                    }
                }
                stop.await()
            }
        }

        val port: Int = serverSocket.localPort

        fun sendServerMessage(method: String) {
            val output = checkNotNull(clientSocket).getOutputStream()
            HtspCodec.writeMessage(
                output = output,
                method = method,
                fields = emptyMap(),
            )
            output.flush()
        }

        fun replyToCapturedPostHandshakeRequest() {
            val request = checkNotNull(postHandshakeRequest)
            val output = checkNotNull(clientSocket).getOutputStream()
            HtspCodec.writeMessage(
                output = output,
                method = requireNotNull(request.method),
                fields = mapOf("seq" to requireNotNull(request.seq)),
            )
            output.flush()
        }

        fun capturedPostHandshakeRequest(): HtspMessage = checkNotNull(postHandshakeRequest)

        fun closeClientTransport() {
            runCatching { clientSocket?.close() }
        }

        override fun close() {
            stop.countDown()
            clientSockets.forEach { client -> runCatching { client.close() } }
            runCatching { serverSocket.close() }
            serverThread.join(1_000)
        }
    }
}
