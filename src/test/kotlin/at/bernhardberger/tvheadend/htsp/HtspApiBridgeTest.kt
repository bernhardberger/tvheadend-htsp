package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.jsonapi.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(HtspJsonApi::class)
class HtspApiBridgeTest {
    @Test
    fun requestPreservesExactPathAndArgsOmissionVersusOrderedRecursiveObject() {
        assertEquals(linkedMapOf("path" to ""), HtspRequestCodecs.encode(ApiRequest("")))
        assertEquals(
            linkedMapOf("path" to "/arbitrary//path?x=1"),
            HtspRequestCodecs.encode(ApiRequest("/arbitrary//path?x=1")),
        )

        val args = htspApiObject(
            "emptyObject" to htspApiObject(),
            "emptyList" to htspApiList(),
            "nested" to htspApiList(
                HtspApiString(""),
                htspApiObject("signed" to HtspApiLong(Long.MIN_VALUE)),
            ),
        )
        val encoded = HtspRequestCodecs.encode(ApiRequest("endpoint", args))
        assertEquals(listOf("path", "args"), encoded.keys.toList())
        assertEquals(
            linkedMapOf(
                "emptyObject" to linkedMapOf<String, Any?>(),
                "emptyList" to emptyList<Any?>(),
                "nested" to listOf("", linkedMapOf("signed" to Long.MIN_VALUE)),
            ),
            encoded["args"],
        )
    }

    @Test
    fun actualCodecRoundTripPreservesEveryClosedValueAndUuidWireIdentity() {
        val binarySource = byteArrayOf(1, 2, 3)
        val uuidSource = ByteArray(16) { it.toByte() }
        val request = ApiRequest(
            path = "",
            args = htspApiObject(
                "string" to HtspApiString("Živě"),
                "minimum" to HtspApiLong(Long.MIN_VALUE),
                "maximum" to HtspApiLong(Long.MAX_VALUE),
                "emptyBinary" to HtspApiBinary(byteArrayOf()),
                "binary" to HtspApiBinary(binarySource),
                "false" to HtspApiBoolean(false),
                "true" to HtspApiBoolean(true),
                "uuid" to HtspApiUuid(uuidSource),
                "list" to htspApiList(),
                "object" to htspApiObject(),
            ),
        )
        binarySource.fill(9)
        uuidSource.fill(9)

        val decodedRequestFields = codecRoundTrip("api", HtspRequestCodecs.encode(request))
        val decodedArgs = decodedRequestFields["args"] as LinkedHashMap<*, *>
        assertTrue(decodedArgs["binary"] is ByteArray)
        assertTrue(decodedArgs["uuid"] is HtspWireUuid)
        assertArrayEquals(byteArrayOf(), decodedArgs["emptyBinary"] as ByteArray)
        assertArrayEquals(byteArrayOf(1, 2, 3), decodedArgs["binary"] as ByteArray)
        assertArrayEquals(ByteArray(16) { it.toByte() }, (decodedArgs["uuid"] as HtspWireUuid).bytes())

        val response = HtspRequestCodecs.decode(
            request,
            linkedMapOf("seq" to 1L, "response" to decodedArgs),
            44,
        ) as ApiResponse.Payload
        val payload = response.value as HtspApiObject
        assertEquals(HtspApiString("Živě"), payload["string"])
        assertEquals(HtspApiLong(Long.MIN_VALUE), payload["minimum"])
        assertEquals(HtspApiLong(Long.MAX_VALUE), payload["maximum"])
        assertEquals(HtspApiBoolean(false), payload["false"])
        assertEquals(HtspApiBoolean(true), payload["true"])
        assertEquals(htspApiList(), payload["list"])
        assertEquals(htspApiObject(), payload["object"])
        assertArrayEquals(byteArrayOf(), (payload["emptyBinary"] as HtspApiBinary).bytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), (payload["binary"] as HtspApiBinary).bytes())
        assertArrayEquals(ByteArray(16) { it.toByte() }, (payload["uuid"] as HtspApiUuid).bytes())
        assertNotSame(decodedArgs["binary"], (payload["binary"] as HtspApiBinary).bytes())
        val exposedBinary = (payload["binary"] as HtspApiBinary).bytes()
        exposedBinary.fill(8)
        assertArrayEquals(byteArrayOf(1, 2, 3), (payload["binary"] as HtspApiBinary).bytes())
    }

    @Test
    fun malformedUuidReplyIsServerErrorWithoutRetiringTheCurrentTransport() = runTest {
        val malformedBytes = ByteArray(15) { it.toByte() }
        val malformedUuid = rawField(type = 8, name = "uuid", data = malformedBytes)
        val response = rawField(type = 1, name = "response", data = malformedUuid)
        val message = framedMessage(response)

        val decoded = HtspCodec.readMessage(ByteArrayInputStream(message))
        val decodedResponse = decoded.fields["response"] as Map<*, *>
        val wireUuid = decodedResponse["uuid"] as HtspWireUuid
        assertArrayEquals(malformedBytes, wireUuid.bytes())
        val exposed = wireUuid.bytes()
        exposed.fill(99)
        assertArrayEquals(malformedBytes, wireUuid.bytes())

        val transport = FakeApiTransport(24, HtspWireReply(decoded.fields))
        val caller = HtspTypedRequestCaller(transport)
        assertSame(HtspResult.ServerError, caller.call(ApiRequest("malformed-uuid")))

        transport.reply = HtspWireReply(linkedMapOf("response" to linkedMapOf("ok" to true)))
        assertEquals(
            HtspResult.Ok(ApiResponse.Payload(htspApiObject("ok" to HtspApiBoolean(true)))),
            caller.call(ApiRequest("still-current")),
        )
        assertEquals(2, transport.dispatches)
    }

    @Test
    fun containersAreOrderedDefensiveImmutableSnapshotsIncludingRecursiveConstruction() {
        val nestedSource = arrayOf<HtspApiValue>(HtspApiLong(1L))
        val nested = htspApiList(*nestedSource)
        val objectSource = arrayOf(
            "first" to nested,
            "second" to HtspApiString("value"),
        )
        val value = htspApiObject(*objectSource)
        nestedSource[0] = HtspApiLong(2L)
        objectSource[0] = "changed" to HtspApiLong(3L)

        assertEquals(listOf("first", "second"), value.keys.toList())
        assertEquals(1, nested.size)
        assertEquals(HtspApiLong(1L), nested[0])
        assertEquals(HtspApiLong(1L), (value["first"] as HtspApiList)[0])
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (value.keys as MutableSet<String>).add("third")
        }
        val publicTypes = (HtspApiObject::class.java.constructors.filter { !it.isSynthetic }
                .flatMap { it.parameterTypes.asList() } +
            HtspApiObject::class.java.declaredMethods.filter { !it.isSynthetic && '$' !in it.name }
                .flatMap { listOf(it.returnType) + it.parameterTypes } +
            HtspApiList::class.java.constructors.filter { !it.isSynthetic }
                .flatMap { it.parameterTypes.asList() } +
            HtspApiList::class.java.declaredMethods.filter { !it.isSynthetic && '$' !in it.name }
                .flatMap { listOf(it.returnType) + it.parameterTypes })
        assertTrue(publicTypes.none { it == Map::class.java || it == List::class.java })
    }

    @Test
    fun versionGateDispatchAndReplyTopologyUseSharedTypedOutcomes() = runTest {
        val v23 = FakeApiTransport(23, HtspWireReply(linkedMapOf("response" to linkedMapOf<String, Any?>())))
        assertSame(HtspResult.NotSupported, HtspTypedRequestCaller(v23).call(ApiRequest("")))
        assertEquals(0, v23.dispatches)

        val transport = FakeApiTransport(24, HtspWireReply(linkedMapOf("response" to linkedMapOf("x" to 1L))))
        val caller = HtspTypedRequestCaller(transport)
        assertEquals(
            HtspResult.Ok(ApiResponse.Payload(htspApiObject("x" to HtspApiLong(1L)))),
            caller.call(ApiRequest("exact")),
        )
        assertEquals(1, transport.dispatches)
        assertEquals("api", transport.lastMethod)
        assertEquals(linkedMapOf("path" to "exact"), transport.lastFields)

        transport.reply = HtspWireReply(linkedMapOf("response" to listOf("", true, Long.MAX_VALUE)))
        assertEquals(
            HtspResult.Ok(
                ApiResponse.Payload(
                    htspApiList(HtspApiString(""), HtspApiBoolean(true), HtspApiLong(Long.MAX_VALUE)),
                ),
            ),
            caller.call(ApiRequest("list")),
        )
        transport.reply = HtspWireReply(linkedMapOf("seq" to 4L))
        assertEquals(HtspResult.Ok(ApiResponse.NoPayload), caller.call(ApiRequest("bodyless")))
        transport.reply = HtspWireReply(linkedMapOf("seq" to 4L, "noaccess" to 0L))
        assertEquals(HtspResult.Ok(ApiResponse.NoPayload), caller.call(ApiRequest("bodyless")))

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1L))
        assertSame(HtspResult.AccessDenied, caller.call(ApiRequest("denied")))
        transport.reply = HtspWireReply(linkedMapOf("error" to "Method not found"))
        assertSame(HtspResult.NotSupported, caller.call(ApiRequest("unknown")))
        transport.reply = HtspWireReply(linkedMapOf("error" to "path /missing was not found"))
        assertSame(HtspResult.ServerError, caller.call(ApiRequest("missing")))
        transport.reply = HtspWireReply(linkedMapOf("response" to linkedMapOf("error" to "not found")))
        assertEquals(
            HtspResult.Ok(ApiResponse.Payload(htspApiObject("error" to HtspApiString("not found")))),
            caller.call(ApiRequest("missing")),
        )
    }

    @Test
    fun malformedValuesTopologyAndUnexpectedFieldsAreRejected() = runTest {
        val request = ApiRequest("x")
        val malformedResponses = listOf(
            linkedMapOf<String, Any?>("response" to 1.0),
            linkedMapOf<String, Any?>("response" to 1.0f),
            linkedMapOf<String, Any?>("response" to null),
            linkedMapOf<String, Any?>("response" to linkedMapOf("x" to null)),
            linkedMapOf<String, Any?>("response" to linkedMapOf<Any?, Any?>(1L to "x")),
            linkedMapOf<String, Any?>("response" to ByteArray(16), "unexpected" to "binary-not-uuid"),
            linkedMapOf<String, Any?>("response" to "x", "unexpected" to 1L),
            linkedMapOf<String, Any?>("unexpected" to 1L),
        )
        val transport = FakeApiTransport(24, HtspWireReply(linkedMapOf()))
        val caller = HtspTypedRequestCaller(transport)
        malformedResponses.forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(fields.toString(), HtspResult.ServerError, caller.call(request))
        }
    }

    @Test
    fun catalogIsExact39AndApiSurfaceRequiresTheJsonApiOptIn() {
        assertEquals(39, typedHtspRequestCatalog.size)
        assertEquals("api", typedHtspRequestCatalog.single { it.method == "api" }.method)
        assertEquals(HtspAccess.ACCESS_ANONYMOUS, ApiRequest("").access)
        assertEquals(24, ApiRequest("").minimumProtocolVersion)
    }

    private fun codecRoundTrip(method: String, fields: Map<String, Any?>): Map<String, Any?> {
        val output = ByteArrayOutputStream()
        HtspCodec.writeMessage(output, method, fields)
        return HtspCodec.readMessage(ByteArrayInputStream(output.toByteArray())).fields
            .filterKeys { it != "method" }
    }

    private fun rawField(type: Int, name: String, data: ByteArray): ByteArray {
        val nameBytes = name.toByteArray()
        return ByteArrayOutputStream().apply {
            write(type)
            write(nameBytes.size)
            write((data.size ushr 24) and 0xff)
            write((data.size ushr 16) and 0xff)
            write((data.size ushr 8) and 0xff)
            write(data.size and 0xff)
            write(nameBytes)
            write(data)
        }.toByteArray()
    }

    private fun framedMessage(body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write((body.size ushr 24) and 0xff)
        write((body.size ushr 16) and 0xff)
        write((body.size ushr 8) and 0xff)
        write(body.size and 0xff)
        write(body)
    }.toByteArray()

    private class FakeApiTransport(
        private val version: Int,
        var reply: HtspWireReply,
    ) : HtspRequestTransport {
        private val generation = HtspCapturedGeneration(
            HtspConnectionGeneration.create(),
            version,
            Any(),
        )
        var dispatches = 0
        var lastMethod: String? = null
        var lastFields: LinkedHashMap<String, Any?>? = null

        override fun captureGeneration(): HtspCapturedGeneration = generation

        override suspend fun dispatch(
            generation: HtspCapturedGeneration,
            method: String,
            fields: LinkedHashMap<String, Any?>,
            timeoutMs: Long,
        ): HtspWireReply {
            dispatches += 1
            lastMethod = method
            lastFields = LinkedHashMap(fields)
            return reply
        }

        override fun isCurrent(generation: HtspCapturedGeneration): Boolean = generation === this.generation
    }
}
