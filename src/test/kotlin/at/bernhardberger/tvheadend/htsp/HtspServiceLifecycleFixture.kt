package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.jsonapi.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.Dispatchers
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal abstract class HtspServiceLifecycleFixture {

    protected fun service(
        afterConnectionAdmission: suspend () -> Unit = {},
        afterTransportInstallation: suspend () -> Unit = {},
        afterTeardownAdmission: suspend () -> Unit = {},
        beforeTypedRecapture: suspend (HtspRequest<*>) -> Unit = {},
        beforeTypedEventPublication: (HtspTransportEvent.ServerMessage) -> Unit = {},
    ) = HtspService(
        ioDispatcher = Dispatchers.IO,
        afterConnectionAdmission = afterConnectionAdmission,
        afterTransportInstallation = afterTransportInstallation,
        afterTeardownAdmission = afterTeardownAdmission,
        beforeTypedRecapture = beforeTypedRecapture,
        beforeTypedEventPublication = beforeTypedEventPublication,
    )

    protected fun muxPacketFields(payloadByte: Byte): Map<String, Any?> = mapOf(
        "subscriptionId" to 1L,
        "frametype" to 1L,
        "stream" to 0L,
        "duration" to 40L,
        "payload" to byteArrayOf(payloadByte),
    )

    protected fun serviceOwnedJobCount(service: HtspService): Int =
        service.javaClass.getDeclaredField("serviceJob").let { field ->
            field.isAccessible = true
            (field.get(service) as kotlinx.coroutines.Job).children.count()
        }

    protected class FakeHtspServer(
        private val respondToHello: Boolean,
        private val authFields: Map<String, Any?> = emptyMap(),
        private val helloReplyFields: Map<String, Any?> = mapOf(
            "htspversion" to 43,
            "challenge" to ByteArray(32),
        ),
        private val authenticateResponseGate: CountDownLatch? = null,
        private val captureOnePostHandshakeRequest: Boolean = false,
        private val postHandshakeReplyFields: Map<String, Any?>? = null,
        private val additionalPostHandshakeReplyFields: List<Map<String, Any?>> = emptyList(),
        private val postHandshakeReplyPlan: List<Map<String, Any?>?>? = null,
        private val expectedConnections: Int = 1,
    ) : Closeable {
        private val serverSocket = ServerSocket(0)
        private val stop = CountDownLatch(1)
        @Volatile
        private var clientSocket: Socket? = null
        private val clientSockets = CopyOnWriteArrayList<Socket>()
        @Volatile
        private var postHandshakeRequest: HtspWireMessage? = null
        private val postHandshakeRequests = CopyOnWriteArrayList<HtspWireMessage>()
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
                        if (postHandshakeReplyPlan != null) {
                            postHandshakeReplyPlan.forEach { replyFields ->
                                val request = HtspCodec.readMessage(client.getInputStream())
                                postHandshakeRequests += request
                                if (postHandshakeRequest == null) postHandshakeRequest = request
                                postHandshakeRequestReceived.countDown()
                                replyFields?.let { replyToPostHandshakeRequest(request, it) }
                            }
                        } else if (captureOnePostHandshakeRequest) {
                            postHandshakeRequest = HtspCodec.readMessage(client.getInputStream())
                            postHandshakeRequests += checkNotNull(postHandshakeRequest)
                            postHandshakeRequestReceived.countDown()
                            postHandshakeReplyFields?.let(::replyToCapturedPostHandshakeRequest)
                            additionalPostHandshakeReplyFields.forEach { replyFields ->
                                val additionalRequest = HtspCodec.readMessage(client.getInputStream())
                                replyToPostHandshakeRequest(additionalRequest, replyFields)
                            }
                        }
                    }
                }
                stop.await()
            }
        }

        val port: Int = serverSocket.localPort

        fun sendServerMessage(method: String, fields: Map<String, Any?> = emptyMap()) {
            val output = checkNotNull(clientSocket).getOutputStream()
            HtspCodec.writeMessage(
                output = output,
                method = method,
                fields = fields,
            )
            output.flush()
        }

        fun replyToCapturedPostHandshakeRequest(
            replyFields: Map<String, Any?> = emptyMap(),
        ) {
            val request = checkNotNull(postHandshakeRequest)
            replyToPostHandshakeRequest(request, replyFields)
        }

        fun awaitPostHandshakeRequestCount(count: Int, timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (postHandshakeRequests.size < count && System.nanoTime() < deadline) {
                Thread.sleep(1L)
            }
            return postHandshakeRequests.size >= count
        }

        fun postHandshakeRequest(index: Int): HtspWireMessage = postHandshakeRequests[index]

        fun postHandshakeMethods(): List<String> =
            postHandshakeRequests.map { request -> requireNotNull(request.method) }

        fun replyToPostHandshakeRequest(
            index: Int,
            replyFields: Map<String, Any?> = emptyMap(),
        ) {
            replyToPostHandshakeRequest(postHandshakeRequests[index], replyFields)
        }

        private fun replyToPostHandshakeRequest(
            request: HtspWireMessage,
            replyFields: Map<String, Any?>,
        ) {
            val output = checkNotNull(clientSocket).getOutputStream()
            HtspCodec.writeMessage(
                output = output,
                method = requireNotNull(request.method),
                fields = mapOf("seq" to requireNotNull(request.seq)) + replyFields,
            )
            output.flush()
        }

        fun capturedPostHandshakeRequest(): HtspWireMessage = checkNotNull(postHandshakeRequest)

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
