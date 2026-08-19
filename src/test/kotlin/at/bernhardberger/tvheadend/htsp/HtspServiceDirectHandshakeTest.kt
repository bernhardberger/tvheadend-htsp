package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.jsonapi.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class HtspServiceDirectHandshakeTest : HtspServiceLifecycleFixture() {

    @Test
    fun executingHelloRecapturesCurrentGenerationWithoutDisturbingAccessOrFutureVersionGates() {
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("dvr" to 1, "streaming" to 1),
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf(
                "htspversion" to 44L,
                "challenge" to ByteArray(32) { index -> (index + 1).toByte() },
                "servername" to "recaptured-server",
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
                val generation = requireNotNull(service.liveConnection.value).generation

                assertEquals(
                    HtspResult.Ok(
                        HelloResponse(
                            htspVersion = 44L,
                            serverName = "recaptured-server",
                            serverVersion = null,
                            challenge = HtspBinary(ByteArray(32) { index -> (index + 1).toByte() }),
                            webRoot = null,
                            language = null,
                            serverCapabilities = null,
                            apiVersion = null,
                        ),
                    ),
                    service.execute(
                        request = HelloRequest(
                            htspVersion = 2L,
                            clientName = "recapture-client",
                        ),
                        timeoutMs = 1_000L,
                        expectedGeneration = generation,
                    ),
                )

                assertEquals(
                    mapOf("htspversion" to 2L, "clientname" to "recapture-client"),
                    server.capturedPostHandshakeRequest().fields.filterKeys { key ->
                        key != "method" && key != "seq"
                    },
                )
                val live = requireNotNull(service.liveConnection.value)
                assertSame(generation, live.generation)
                assertEquals(2, live.protocolVersion)
                assertEquals("recaptured-server", live.serverFacts.serverName)
                assertEquals(true, live.serverFacts.dvr)
                assertEquals(true, live.dvrAccess)
                val state = service.connectionState.value as HtspConnectionState.Connected
                assertEquals(2, state.htspVersion)
                assertEquals(true, state.dvrAccess)
                assertSame(HtspResult.NotSupported, service.getSysTime(expectedGeneration = generation))
                service.disconnect()
            }
        }
    }

    @Test
    fun executingAuthenticateRecapturesOnlyAccessFactsOnTheCurrentGeneration() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
                "servername" to "stable-server",
            ),
            authFields = mapOf("admin" to 0, "dvr" to 0, "streaming" to 1),
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf(
                "noaccess" to 0L,
                "admin" to 1L,
                "dvr" to 1L,
                "streaming" to 1L,
                "limitdvr" to 7L,
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
                val before = requireNotNull(service.liveConnection.value)

                assertEquals(
                    HtspResult.Ok(
                        AuthenticateResponse(
                            noAccess = false,
                            admin = true,
                            streaming = true,
                            dvr = true,
                            failedDvr = null,
                            anonymous = null,
                            limitAll = null,
                            limitDvr = 7L,
                            limitStreaming = null,
                            uiLevel = null,
                            uiLanguage = null,
                        ),
                    ),
                    service.execute(
                        request = AuthenticateRequest(),
                        timeoutMs = 1_000L,
                        expectedGeneration = before.generation,
                    ),
                )

                assertEquals(
                    emptyMap<String, Any?>(),
                    server.capturedPostHandshakeRequest().fields.filterKeys { key ->
                        key != "method" && key != "seq"
                    },
                )
                val after = requireNotNull(service.liveConnection.value)
                assertSame(before.generation, after.generation)
                assertEquals(before.protocolVersion, after.protocolVersion)
                assertEquals("stable-server", after.serverFacts.serverName)
                assertEquals(true, after.serverFacts.admin)
                assertEquals(true, after.serverFacts.dvr)
                assertEquals(7, after.serverFacts.limitDvr)
                assertEquals(true, after.dvrAccess)
                assertEquals(
                    true,
                    (service.connectionState.value as HtspConnectionState.Connected).dvrAccess,
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun deniedDirectAuthenticateClearsStaleAccessWithoutClearingHelloFacts() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
                "servername" to "preserved-server",
            ),
            authFields = mapOf("admin" to 1, "dvr" to 1, "streaming" to 1),
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf("noaccess" to 1L),
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
                val before = requireNotNull(service.liveConnection.value)

                assertSame(
                    HtspResult.AccessDenied,
                    service.authenticate(1_000L, before.generation),
                )

                val after = requireNotNull(service.liveConnection.value)
                assertSame(before.generation, after.generation)
                assertEquals(before.protocolVersion, after.protocolVersion)
                assertEquals("preserved-server", after.serverFacts.serverName)
                assertNull(after.serverFacts.admin)
                assertNull(after.serverFacts.streaming)
                assertNull(after.serverFacts.dvr)
                assertNull(after.dvrAccess)
                assertNull((service.connectionState.value as HtspConnectionState.Connected).dvrAccess)
                service.disconnect()
            }
        }
    }

    @Test
    fun directHelloDoesNotCancelAnUnrelatedInFlightRequest() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            additionalPostHandshakeReplyFields = listOf(
                mapOf(
                    "htspversion" to 44L,
                    "challenge" to ByteArray(32),
                ),
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
                val generation = requireNotNull(service.liveConnection.value).generation
                val ordinary = async(Dispatchers.IO) {
                    service.getProfiles(timeoutMs = 5_000L, expectedGeneration = generation)
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))

                assertTrue(
                    service.hello(43L, "parallel-client", 1_000L, generation) is HtspResult.Ok,
                )
                assertTrue(!ordinary.isCompleted)
                server.replyToCapturedPostHandshakeRequest()
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    withTimeout(1_000L) { ordinary.await() },
                )
                assertSame(generation, service.liveConnection.value?.generation)
                service.disconnect()
            }
        }
    }

    @Test
    fun directAuthenticateDoesNotCancelAnUnrelatedInFlightRequest() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            additionalPostHandshakeReplyFields = listOf(
                mapOf(
                    "admin" to 0L,
                    "streaming" to 1L,
                    "dvr" to 1L,
                ),
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
                val generation = requireNotNull(service.liveConnection.value).generation
                val ordinary = async(Dispatchers.IO) {
                    service.getProfiles(timeoutMs = 5_000L, expectedGeneration = generation)
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))

                assertTrue(
                    service.authenticate(timeoutMs = 1_000L, expectedGeneration = generation) is
                        HtspResult.Ok,
                )
                assertTrue(!ordinary.isCompleted)
                server.replyToCapturedPostHandshakeRequest()
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    withTimeout(1_000L) { ordinary.await() },
                )
                assertSame(generation, service.liveConnection.value?.generation)
                service.disconnect()
            }
        }
    }

    @Test
    fun overlappingDirectHelloCommitsInDispatchOrderWithoutBlockingOrdinaryRequests() {
        val firstRecaptureReached = CompletableDeferred<Unit>()
        val resumeFirstRecapture = CompletableDeferred<Unit>()
        val helloRecaptures = AtomicInteger()
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf(
                    "htspversion" to 2L,
                    "challenge" to ByteArray(32) { 1 },
                    "servername" to "first-direct-hello",
                ),
                null,
                mapOf(
                    "htspversion" to 3L,
                    "challenge" to ByteArray(32) { 2 },
                    "servername" to "second-direct-hello",
                ),
            ),
        ).use { server ->
            val service = service(
                beforeTypedRecapture = { request ->
                    if (request is HelloRequest && helloRecaptures.incrementAndGet() == 1) {
                        firstRecaptureReached.complete(Unit)
                        resumeFirstRecapture.await()
                    }
                },
            )
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation
                val first = async(Dispatchers.IO) {
                    service.hello(2L, "first-client", 5_000L, generation)
                }
                withTimeout(1_000L) { firstRecaptureReached.await() }

                val ordinary = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.getProfiles(timeoutMs = 5_000L, expectedGeneration = generation)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(2, 1_000L))
                assertEquals("getProfiles", server.postHandshakeRequest(1).method)
                val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.hello(3L, "second-client", 5_000L, generation)
                }
                assertTrue(!server.awaitPostHandshakeRequestCount(3, 150L))
                assertTrue(!ordinary.isCompleted)
                assertTrue(!second.isCompleted)

                resumeFirstRecapture.complete(Unit)
                assertTrue(first.await() is HtspResult.Ok)
                assertTrue(server.awaitPostHandshakeRequestCount(3, 1_000L))
                assertEquals(
                    listOf("hello", "getProfiles", "hello"),
                    server.postHandshakeMethods(),
                )
                assertTrue(second.await() is HtspResult.Ok)

                val live = requireNotNull(service.liveConnection.value)
                assertSame(generation, live.generation)
                assertEquals(3, live.protocolVersion)
                assertEquals("second-direct-hello", live.serverFacts.serverName)
                assertTrue(!ordinary.isCompleted)
                server.replyToPostHandshakeRequest(1)
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    withTimeout(1_000L) { ordinary.await() },
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun overlappingDirectAuthenticateCommitsInDispatchOrderWithoutBlockingOrdinaryRequests() {
        val firstRecaptureReached = CompletableDeferred<Unit>()
        val resumeFirstRecapture = CompletableDeferred<Unit>()
        val authenticateRecaptures = AtomicInteger()
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf("admin" to 1L, "streaming" to 1L, "dvr" to 0L),
                null,
                mapOf("admin" to 0L, "streaming" to 1L, "dvr" to 1L, "limitdvr" to 9L),
            ),
        ).use { server ->
            val service = service(
                beforeTypedRecapture = { request ->
                    if (
                        request is AuthenticateRequest &&
                        authenticateRecaptures.incrementAndGet() == 1
                    ) {
                        firstRecaptureReached.complete(Unit)
                        resumeFirstRecapture.await()
                    }
                },
            )
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation
                val first = async(Dispatchers.IO) {
                    service.authenticate(5_000L, generation)
                }
                withTimeout(1_000L) { firstRecaptureReached.await() }

                val ordinary = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.getProfiles(timeoutMs = 5_000L, expectedGeneration = generation)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(2, 1_000L))
                assertEquals("getProfiles", server.postHandshakeRequest(1).method)
                val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.authenticate(5_000L, generation)
                }
                assertTrue(!server.awaitPostHandshakeRequestCount(3, 150L))
                assertTrue(!ordinary.isCompleted)
                assertTrue(!second.isCompleted)

                resumeFirstRecapture.complete(Unit)
                assertTrue(first.await() is HtspResult.Ok)
                assertTrue(server.awaitPostHandshakeRequestCount(3, 1_000L))
                assertEquals(
                    listOf("authenticate", "getProfiles", "authenticate"),
                    server.postHandshakeMethods(),
                )
                assertTrue(second.await() is HtspResult.Ok)

                val live = requireNotNull(service.liveConnection.value)
                assertSame(generation, live.generation)
                assertEquals(false, live.serverFacts.admin)
                assertEquals(true, live.serverFacts.dvr)
                assertEquals(9, live.serverFacts.limitDvr)
                assertEquals(true, live.dvrAccess)
                assertTrue(!ordinary.isCompleted)
                server.replyToPostHandshakeRequest(1)
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    withTimeout(1_000L) { ordinary.await() },
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun cancelledHandshakeWaitingForSerializationDoesNotRetireItsGeneration() {
        val firstRecaptureReached = CompletableDeferred<Unit>()
        val resumeFirstRecapture = CompletableDeferred<Unit>()
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf("htspversion" to 44L, "challenge" to ByteArray(32)),
                emptyMap(),
            ),
        ).use { server ->
            val service = service(
                beforeTypedRecapture = { request ->
                    if (request is HelloRequest && !firstRecaptureReached.isCompleted) {
                        firstRecaptureReached.complete(Unit)
                        resumeFirstRecapture.await()
                    }
                },
            )
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation
                val first = async(Dispatchers.IO) {
                    service.hello(44L, "first-client", 5_000L, generation)
                }
                withTimeout(1_000L) { firstRecaptureReached.await() }
                val waiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.authenticate(5_000L, generation)
                }

                waiting.cancel()
                val cancellation = runCatching { waiting.await() }.exceptionOrNull()
                assertTrue(cancellation is CancellationException)
                assertTrue(!server.awaitPostHandshakeRequestCount(2, 150L))
                assertTrue(service.isCurrent(generation))

                resumeFirstRecapture.complete(Unit)
                assertTrue(first.await() is HtspResult.Ok)
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    service.getProfiles(1_000L, generation),
                )
                assertTrue(service.isCurrent(generation))
                service.disconnect()
            }
        }
    }

    @Test
    fun timedOutWrittenDirectHelloRetiresOnlyItsCapturedGeneration() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null),
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
                val generation = requireNotNull(service.liveConnection.value).generation

                assertSame(HtspResult.Timeout, service.hello(44L, "timeout-client", 100L, generation))
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))
                assertTrue(service.isCurrent(generation))
                assertNull(service.liveConnection.value)
                assertNull(service.commitIfLive(generation) { it })
                assertSame(HtspResult.TransportUnavailable, service.getProfiles())
                assertEquals(1, server.postHandshakeMethods().size)
                withTimeout(1_000L) {
                    service.connectionState.first { state -> state is HtspConnectionState.Disconnected }
                }
            }
        }
    }

    @Test
    fun cancelledWrittenDirectHelloRetiresOnlyItsCapturedGeneration() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null),
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
                val generation = requireNotNull(service.liveConnection.value).generation
                val observedCancellation = CompletableDeferred<Throwable>()
                val call = launch(Dispatchers.IO) {
                    try {
                        service.hello(44L, "cancelled-client", 5_000L, generation)
                    } catch (failure: Throwable) {
                        observedCancellation.complete(failure)
                        throw failure
                    }
                }
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))

                call.cancel()
                call.join()
                assertTrue(withTimeout(1_000L) { observedCancellation.await() } is CancellationException)
                assertTrue(service.isCurrent(generation))
                assertNull(service.liveConnection.value)
                assertNull(service.commitIfLive(generation) { it })
                assertSame(HtspResult.TransportUnavailable, service.getProfiles())
                assertEquals(1, server.postHandshakeMethods().size)
                withTimeout(1_000L) {
                    service.connectionState.first { state -> state is HtspConnectionState.Disconnected }
                }
            }
        }
    }

    @Test
    fun malformedWrittenDirectHelloRetiresGenerationInsteadOfKeepingStaleChallenge() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf("htspversion" to 44L, "challenge" to ByteArray(31)),
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
                val generation = requireNotNull(service.liveConnection.value).generation

                assertSame(HtspResult.ServerError, service.hello(44L, "malformed-client", 1_000L, generation))
                assertTrue(service.isCurrent(generation))
                assertNull(service.liveConnection.value)
                assertNull(service.commitIfLive(generation) { it })
                assertSame(HtspResult.TransportUnavailable, service.getProfiles())
                withTimeout(1_000L) {
                    service.connectionState.first { state -> state is HtspConnectionState.Disconnected }
                }
            }
        }
    }

    @Test
    fun directHelloAboveIntRangeKeepsExactResponseButMakesVersionPreflightUnknown() {
        val recapturedChallenge = ByteArray(32) { index -> (index + 3).toByte() }
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf(
                    "htspversion" to 0xffff_ffffL,
                    "challenge" to recapturedChallenge,
                    "servername" to "u32-server",
                ),
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
                val generation = requireNotNull(service.liveConnection.value).generation

                val result = service.hello(
                    htspVersion = 0xffff_ffffL,
                    clientName = "u32-client",
                    timeoutMs = 1_000L,
                    expectedGeneration = generation,
                )
                assertTrue(result is HtspResult.Ok)
                assertEquals(0xffff_ffffL, (result as HtspResult.Ok).value.htspVersion)
                assertEquals(HtspBinary(recapturedChallenge), result.value.challenge)
                val live = requireNotNull(service.liveConnection.value)
                assertSame(generation, live.generation)
                assertNull(live.protocolVersion)
                assertEquals("u32-server", live.serverFacts.serverName)
                assertNull((service.connectionState.value as HtspConnectionState.Connected).htspVersion)
                assertSame(HtspResult.NotSupported, service.getSysTime(expectedGeneration = generation))
                assertEquals(listOf("hello"), server.postHandshakeMethods())
                service.disconnect()
            }
        }
    }

    @Test
    fun staleHelloRecaptureCannotMutateAReplacementGeneration() {
        val recaptureReached = CompletableDeferred<Unit>()
        val resumeRecapture = CompletableDeferred<Unit>()
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf(
                "htspversion" to 2L,
                "challenge" to ByteArray(32),
                "servername" to "stale-server",
            ),
        ).use { firstServer ->
            FakeHtspServer(
                respondToHello = true,
                helloReplyFields = mapOf(
                    "htspversion" to 43,
                    "challenge" to ByteArray(32),
                    "servername" to "replacement-server",
                ),
            ).use { replacementServer ->
                val service = service(
                    beforeTypedRecapture = { request ->
                        if (request is HelloRequest) {
                            recaptureReached.complete(Unit)
                            resumeRecapture.await()
                        }
                    },
                )
                runBlocking {
                    service.connect(
                        host = "127.0.0.1",
                        port = firstServer.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                    val staleGeneration = requireNotNull(service.liveConnection.value).generation
                    val staleHello = async(Dispatchers.IO) {
                        runCatching {
                            service.hello(2L, "stale-client", 1_000L, staleGeneration)
                        }.exceptionOrNull()
                    }
                    withTimeout(1_000L) { recaptureReached.await() }

                    service.connect(
                        host = "127.0.0.1",
                        port = replacementServer.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                    val replacement = requireNotNull(service.liveConnection.value)
                    resumeRecapture.complete(Unit)

                    assertTrue(withTimeout(1_000L) { staleHello.await() } is CancellationException)
                    val current = requireNotNull(service.liveConnection.value)
                    assertSame(replacement.generation, current.generation)
                    assertEquals(43, current.protocolVersion)
                    assertEquals("replacement-server", current.serverFacts.serverName)
                    service.disconnect()
                }
            }
        }
    }
}
