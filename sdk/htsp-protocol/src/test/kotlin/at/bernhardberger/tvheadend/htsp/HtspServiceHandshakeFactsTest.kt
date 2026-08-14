package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.jsonapi.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

internal class HtspServiceHandshakeFactsTest : HtspServiceLifecycleFixture() {

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

                val hello = requireNotNull(server.handshakeFields["hello"])
                assertEquals(43L, hello["htspversion"])
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
            hello = HtspWireMessage(
                method = "hello",
                seq = 1,
                fields = mapOf("api_version" to (-1).toByte()),
            ),
            auth = HtspWireMessage(
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
}
