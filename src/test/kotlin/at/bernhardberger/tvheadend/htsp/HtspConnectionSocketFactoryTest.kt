package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeResponse
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import at.bernhardberger.tvheadend.htsp.wire.HtspWireMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtspConnectionSocketFactoryTest {
    @Test
    fun publicSocketFactoryScriptsConnectHandshakeAndTypedRequestWithoutNetwork() = runBlocking {
        val socket = ScriptedSocket(
            expectedHost = "127.0.0.1",
            expectedPort = 9_982,
            responses = listOf(
                loadHtspGoldenFrame("scripted-hello-response.hex"),
                loadHtspGoldenFrame("scripted-authenticate-response.hex"),
                loadHtspGoldenFrame("scripted-get-sys-time-response.hex"),
            ),
        )
        val factoryCalls = AtomicInteger()
        val connection = createHtspConnection(
            ioDispatcher = Dispatchers.IO,
            clientIdentity = HtspClientIdentity(
                clientName = "socket-seam-client",
                clientVersion = "test",
            ),
            socketFactory = {
                factoryCalls.incrementAndGet()
                socket
            },
        )

        try {
            withTimeout(5_000L) {
                val outcome = connection.connect(
                    endpoint = HtspEndpoint("127.0.0.1", 9_982),
                    options = HtspConnectOptions(
                        connectTimeoutMs = 1_234,
                        responseTimeoutMs = 1_000L,
                        socketReadTimeoutMs = 2_345,
                        socketBufferBytes = 1_024,
                    ),
                )
                assertTrue(outcome is HtspConnectOutcome.Connected)
                val live = (outcome as HtspConnectOutcome.Connected).connection
                assertEquals(43, live.protocolVersion)
                assertEquals(true, live.dvrAccess)
                assertEquals(true, live.serverFacts.streaming)
                assertEquals(true, live.serverFacts.dvr)
                assertEquals(
                    HtspResult.Ok(
                        GetSysTimeResponse(
                            unixTimeSeconds = 1_723_456_789L,
                            legacyTimezoneHoursWestOfGmt = -2,
                            gmtOffsetMinutes = 120,
                        ),
                    ),
                    connection.getSysTime(
                        timeoutMs = 1_000L,
                        expectedGeneration = live.generation,
                    ),
                )
            }
        } finally {
            connection.close()
        }

        assertEquals(1, factoryCalls.get())
        assertTrue(socket.expectedAddressObserved)
        assertEquals(1_234, socket.connectTimeoutMs)
        assertEquals(2_345, socket.readTimeoutMs)
        assertTrue(socket.tcpNoDelayEnabled)
        assertTrue(socket.keepAliveEnabled)
        assertTrue(socket.isClosed)

        val requests = socket.requests()
        assertEquals(listOf("hello", "authenticate", "getSysTime"), requests.map { it.method })
        assertEquals(listOf(1, 2, 3), requests.map { it.seq })
        assertEquals(43L, requests[0].fields["htspversion"])
        assertEquals("socket-seam-client", requests[0].fields["clientname"])
        assertEquals(setOf("method", "seq"), requests[1].fields.keys)
        assertEquals(setOf("method", "seq"), requests[2].fields.keys)
    }

    @Test
    fun throwingPublicSocketFactoryReturnsFailureAndRestoresDisconnectedState() = runBlocking {
        val factoryCalls = AtomicInteger()
        val connection = createHtspConnection(
            ioDispatcher = Dispatchers.IO,
            socketFactory = {
                factoryCalls.incrementAndGet()
                throw IOException("Scripted socket construction failure")
            },
        )

        try {
            assertEquals(
                HtspConnectOutcome.Failed(
                    HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
                ),
                connection.connect(HtspEndpoint("127.0.0.1", 9_982)),
            )
            assertSame(HtspConnectionState.Disconnected, connection.connectionState.value)
            assertEquals(1, factoryCalls.get())
        } finally {
            connection.close()
        }
    }

    private class ScriptedSocket(
        private val expectedHost: String,
        private val expectedPort: Int,
        private val responses: List<ByteArray>,
    ) : Socket() {
        private val responseInput = PipedInputStream(4_096)
        private val responseOutput = PipedOutputStream(responseInput)
        private val requestBuffer = ByteArrayOutputStream()
        private val requestMessages = CopyOnWriteArrayList<HtspWireMessage>()
        private val responseIndex = AtomicInteger()
        private val connected = AtomicBoolean()
        private val closed = AtomicBoolean()

        @Volatile
        var expectedAddressObserved: Boolean = false
            private set

        @Volatile
        var connectTimeoutMs: Int? = null
            private set

        @Volatile
        var readTimeoutMs: Int? = null
            private set

        @Volatile
        var tcpNoDelayEnabled: Boolean = false
            private set

        @Volatile
        var keepAliveEnabled: Boolean = false
            private set

        private val requestOutput = object : OutputStream() {
            override fun write(value: Int) {
                synchronized(requestBuffer) {
                    requestBuffer.write(value)
                }
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                synchronized(requestBuffer) {
                    requestBuffer.write(bytes, offset, length)
                }
            }

            override fun flush() {
                val frame = synchronized(requestBuffer) {
                    if (requestBuffer.size() == 0) return
                    requestBuffer.toByteArray().also { requestBuffer.reset() }
                }
                requestMessages += HtspCodec.readMessage(ByteArrayInputStream(frame))
                val response = checkNotNull(responses.getOrNull(responseIndex.getAndIncrement())) {
                    "Unexpected scripted request"
                }
                responseOutput.write(response)
                responseOutput.flush()
            }
        }

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val address = endpoint as? InetSocketAddress
            expectedAddressObserved =
                address?.hostString == expectedHost && address.port == expectedPort
            connectTimeoutMs = timeout
            connected.set(true)
        }

        override fun getInputStream(): InputStream = responseInput

        override fun getOutputStream(): OutputStream = requestOutput

        override fun setTcpNoDelay(on: Boolean) {
            tcpNoDelayEnabled = on
        }

        override fun setKeepAlive(on: Boolean) {
            keepAliveEnabled = on
        }

        override fun setSoTimeout(timeout: Int) {
            readTimeoutMs = timeout
        }

        override fun isConnected(): Boolean = connected.get()

        override fun isClosed(): Boolean = closed.get()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            responseOutput.close()
            responseInput.close()
        }

        fun requests(): List<HtspWireMessage> = requestMessages.toList()
    }
}
