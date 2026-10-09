package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.jsonapi.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest

internal class HtspServiceHandshakeFactsTest : HtspServiceLifecycleFixture() {

    @Test
    fun requestedProtocolVersionHonorsFloorOnConstructionAndCopy() {
        assertEquals(36, MINIMUM_HTSP_PROTOCOL_VERSION)
        assertThrows(IllegalArgumentException::class.java) {
            HtspConnectOptions(requestedProtocolVersion = 35)
        }
        val options = HtspConnectOptions(requestedProtocolVersion = 36)
        assertEquals(36, options.requestedProtocolVersion)
        assertEquals(options, HtspConnectOptions().copy(requestedProtocolVersion = 36))
        assertThrows(IllegalArgumentException::class.java) {
            options.copy(requestedProtocolVersion = 35)
        }
    }

    @Test
    fun v35ServerIsRejectedBeforeSendingCredentials() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf("htspversion" to 35L, "challenge" to ByteArray(32)),
        ).use { server ->
            val service = service()
            runBlocking {
                val rejectionState = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    service.connectionState.first { it is HtspConnectionState.Error }
                }
                val outcome = service.connect(
                    HtspEndpoint("127.0.0.1", server.port, "viewer", "secret"),
                    HtspConnectOptions(responseTimeoutMs = 1_000),
                )
                assertEquals(
                    HtspConnectOutcome.Failed(
                        HtspTransportFailure(HtspTransportFailureKind.UNSUPPORTED_SERVER_VERSION),
                    ),
                    outcome,
                )
                assertEquals(
                    HtspConnectionState.Error(
                        HtspTransportFailure(HtspTransportFailureKind.UNSUPPORTED_SERVER_VERSION),
                    ),
                    withTimeout(1_000) { rejectionState.await() },
                )
                assertTrue(service.connectionState.value is HtspConnectionState.Error)
                // Join the fixture reader after transport closure, before inspecting captured requests.
                server.close()
                assertEquals(listOf("hello"), server.handshakeMethods)
                assertEquals(1L, server.authenticateRequestReceived.count)
                assertTrue(server.handshakeFields.values.none { "username" in it || "digest" in it })
                assertNull(service.liveConnection.value)
                service.disconnect()
            }
        }
    }

    @Test
    fun v36ServerConnectsWithCredentials() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf("htspversion" to 36L, "challenge" to ByteArray(32)),
        ).use { server ->
            val service = service()
            runBlocking {
                val outcome = service.connect(
                    HtspEndpoint("127.0.0.1", server.port, "viewer", "secret"),
                    HtspConnectOptions(responseTimeoutMs = 1_000),
                ) as HtspConnectOutcome.Connected
                assertEquals(36, outcome.connection.protocolVersion)
                assertEquals(listOf("hello", "authenticate"), server.handshakeMethods)
                assertEquals("viewer", server.handshakeFields["authenticate"]?.get("username"))
                assertNotNull(server.handshakeFields["authenticate"]?.get("digest"))
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
    fun credentialedAccessDenialIsAuthenticationRejected() {
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("noaccess" to 1),
        ).use { server ->
            val service = service()

            val outcome = runBlocking {
                service.connect(
                    endpoint = HtspEndpoint(
                        host = "127.0.0.1",
                        port = server.port,
                        username = "viewer",
                        password = "secret",
                    ),
                    options = HtspConnectOptions(responseTimeoutMs = 1_000),
                )
            }

            assertEquals(
                HtspConnectOutcome.Failed(
                    HtspTransportFailure(HtspTransportFailureKind.AUTHENTICATION_REJECTED),
                ),
                outcome,
            )
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

                val hello = requireNotNull(server.handshakeFields["hello"])
                assertEquals(44L, hello["htspversion"])
                assertEquals("Kotlin HTSP client", hello["clientname"])
                assertTrue(!hello.containsKey("clientversion"))
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

                val state = service.connectionState.value as HtspConnectionState.Connected
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
        val connectedClass = HtspConnectionState.Connected::class.java

        assertTrue(connectedClass.declaredMethods.none { method -> method.name == "getServerFacts" })
        assertTrue(connectedClass.declaredFields.none { field -> field.name == "serverFacts" })
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

                val state = service.connectionState.value as HtspConnectionState.Connected
                val attemptId = service.currentConnectionAttemptId()
                val facts = requireNotNull(service.serverFactsForLiveConnectionAttempt(attemptId))
                assertEquals("tvh-fixture", facts.serverName)
                assertEquals("4.3-fixture", facts.serverVersion)
                assertEquals("/tvheadend", facts.webRoot)
                assertEquals("en_US", facts.language)
                assertEquals(listOf("timeshift", "htsp"), facts.serverCapabilities)
                assertEquals(19L, facts.apiVersion)
                assertEquals(true, facts.admin)
                assertEquals(true, facts.streaming)
                assertEquals(true, facts.dvr)
                assertEquals(false, facts.failedDvr)
                assertEquals(false, facts.anonymous)
                assertEquals(0L, facts.limitAll)
                assertEquals(2L, facts.limitDvr)
                assertEquals(5L, facts.limitStreaming)
                assertEquals(1L, facts.uiLevel)
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
                assertTrue(service.connectionState.value !is HtspConnectionState.Connected)
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
                assertEquals(true, facts.streaming)
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
            val outcome = runBlocking {
                service.connect(
                    endpoint = HtspEndpoint("127.0.0.1", server.port),
                    options = HtspConnectOptions(responseTimeoutMs = 1_000),
                )
            }

            assertEquals(
                HtspConnectOutcome.Failed(
                    HtspTransportFailure(HtspTransportFailureKind.AUTHENTICATION_REJECTED),
                ),
                outcome,
            )
            assertEquals(listOf("hello", "authenticate"), server.handshakeMethods)
        }
    }
}
