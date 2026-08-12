package at.bernhardberger.tvheadend.htsp

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtspConnectionProbeTest {

    @Test
    fun test_usesInjectedIdentityCountsInitialChannelsAndClosesTransport() {
        ProbeHtspServer(channelIds = listOf(11, 22)).use { server ->
            val identity = HtspClientIdentity(
                clientName = "SDK probe test / 9.8.7",
                clientVersion = "9.8.7",
            )

            val result = runBlocking {
                withTimeout(3_000L) {
                    HtspConnectionProbe(
                        ioDispatcher = Dispatchers.IO,
                        clientIdentity = identity,
                    ).test(
                        host = "127.0.0.1",
                        port = server.port,
                        username = "",
                        password = "",
                    )
                }
            }

            assertEquals(
                HtspProbeSuccess(serverVersion = 43, channelCount = 2),
                result,
            )
            assertTrue(server.transportClosed.await(1, TimeUnit.SECONDS))
            assertEquals("SDK probe test / 9.8.7", server.helloFields["clientname"])
            assertEquals(false, server.helloFields.containsKey("clientversion"))
            assertEquals(listOf("hello", "authenticate", "enableAsyncMetadata"), server.methods)
        }
    }

    @Test
    fun test_reportsZeroChannelsAndStillClosesTransport() {
        ProbeHtspServer(channelIds = emptyList()).use { server ->
            val result = runBlocking {
                withTimeout(3_000L) {
                    HtspConnectionProbe(
                        ioDispatcher = Dispatchers.IO,
                        clientIdentity = HtspClientIdentity("probe", "test"),
                    ).test(
                        host = "127.0.0.1",
                        port = server.port,
                        username = "",
                        password = "",
                    )
                }
            }

            assertEquals(
                HtspTransportFailure(HtspTransportFailureKind.ZERO_CHANNELS),
                (result as HtspProbeFailure).failure,
            )
            assertTrue(server.transportClosed.await(1, TimeUnit.SECONDS))
        }
    }

    private class ProbeHtspServer(
        private val channelIds: List<Int>,
    ) : Closeable {
        private val serverSocket = ServerSocket(0)
        @Volatile
        private var clientSocket: Socket? = null
        val methods = mutableListOf<String>()
        val helloFields = mutableMapOf<String, Any?>()
        val transportClosed = CountDownLatch(1)

        private val serverThread = thread(
            start = true,
            isDaemon = true,
            name = "probe-fake-htsp-server",
        ) {
            try {
                val client = serverSocket.accept()
                clientSocket = client
                val input = client.getInputStream()
                val output = client.getOutputStream()

                repeat(3) { requestIndex ->
                    val request = HtspCodec.readMessage(input)
                    val method = requireNotNull(request.method)
                    methods += method
                    if (requestIndex == 0) helloFields += request.fields

                    val responseFields = mutableMapOf<String, Any?>(
                        "seq" to requireNotNull(request.seq),
                    )
                    if (method == "hello") {
                        responseFields["htspversion"] = 43
                        responseFields["challenge"] = ByteArray(32)
                    }
                    if (method == "authenticate") responseFields["dvr"] = 1
                    HtspCodec.writeMessage(output, method, responseFields)

                    if (method == "enableAsyncMetadata") {
                        channelIds.forEach { channelId ->
                            HtspCodec.writeMessage(
                                output,
                                "channelAdd",
                                mapOf("channelId" to channelId),
                            )
                        }
                        HtspCodec.writeMessage(output, "initialSyncCompleted", emptyMap())
                    }
                    output.flush()
                }

                while (input.read() >= 0) {
                    // Probe close must terminate the transport.
                }
            } catch (_: Throwable) {
                // Closing either side is the expected termination path.
            } finally {
                transportClosed.countDown()
            }
        }

        val port: Int = serverSocket.localPort

        override fun close() {
            runCatching { clientSocket?.close() }
            runCatching { serverSocket.close() }
            serverThread.join(1_000L)
        }
    }
}
