package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HtspPreReleaseSliceTest {
    private fun decode(method: String, fields: Map<String, Any?>): HtspServerMessage =
        (decodeHtspServerMessage(fields + ("method" to method)) as HtspServerMessageDecoded).message

    @Test
    fun nonzeroFlagsIncludeRawAndNormalizedProducers() {
        assertEquals(true, (decode("eventUpdate", mapOf("eventId" to 1L, "isNew" to 2L)) as HtspEventUpdateMessage).isNew)
        assertEquals(true, (decode("timeshiftStatus", mapOf("subscriptionId" to 1L, "full" to 2L, "shift" to 0L)) as HtspTimeshiftStatusMessage).full)
        assertEquals(true, (decode("dvrEntryUpdate", mapOf("id" to 1L, "duplicate" to 2L)) as HtspDvrEntryUpdateMessage).duplicate)
    }

    @Test
    fun creationOptionsEncodeAndTitleIsRequired() {
        listOf(null, "").forEach { title ->
            assertThrows(IllegalArgumentException::class.java) {
                AddDvrEntryRequest(AddDvrEntrySelector.ExplicitChannelTime(1, 2, 3), title = title)
            }
        }
        assertEquals(" ", HtspRequestCodecs.encode(
            AddDvrEntryRequest(AddDvrEntrySelector.ExplicitChannelTime(1, 2, 3), title = " "),
        )["title"])
        val request = AddDvrEntryRequest(AddDvrEntrySelector.Event(1), enabled = true,
            startExtraMinutes = -2, stopExtraMinutes = 3, priority = 4,
            retentionDays = 5, removalDays = 6, comment = "note")
        assertEquals(mapOf("eventId" to 1L, "enabled" to 1L, "startExtra" to -2L,
            "stopExtra" to 3L, "priority" to 4L, "retention" to 5L, "removal" to 6L,
            "comment" to "note"), HtspRequestCodecs.encode(request))
    }

    @Test
    fun requestAndSocketBounds() {
        listOf(0L, 1439L).forEach {
            AddTimerecEntryRequest("title", startMinutesSinceMidnight = it, stopMinutesSinceMidnight = it)
            UpdateTimerecEntryRequest("id", startMinutesSinceMidnight = it, stopMinutesSinceMidnight = it)
        }
        listOf(-1L, 1440L, 4_294_967_295L).forEach {
            assertThrows(IllegalArgumentException::class.java) { AddTimerecEntryRequest("title", startMinutesSinceMidnight = it) }
            assertThrows(IllegalArgumentException::class.java) { AddTimerecEntryRequest("title", stopMinutesSinceMidnight = it) }
            assertThrows(IllegalArgumentException::class.java) { UpdateTimerecEntryRequest("id", startMinutesSinceMidnight = it) }
            assertThrows(IllegalArgumentException::class.java) { UpdateTimerecEntryRequest("id", stopMinutesSinceMidnight = it) }
        }
        listOf(1, 16 * 1024 * 1024).forEach { HtspConnectOptions(socketBufferBytes = it) }
        listOf(0, 16 * 1024 * 1024 + 1).forEach {
            assertThrows(IllegalArgumentException::class.java) { HtspConnectOptions(socketBufferBytes = it) }
        }
    }

    @Test
    fun binaryCopiesDirectlyIntoHeapAndDirectBuffers() {
        val binary = HtspBinary(byteArrayOf(1, 2, 3))
        listOf(ByteBuffer.allocate(5), ByteBuffer.allocateDirect(5)).forEach { destination ->
            destination.position(1)
            binary.copyInto(destination)
            assertEquals(4, destination.position())
            assertEquals(1.toByte(), destination.get(1))
            assertEquals(3.toByte(), destination.get(3))
            assertThrows(BufferOverflowException::class.java) { binary.copyInto(destination) }
            assertEquals(4, destination.position())
        }
    }

    @Test
    fun newDvrAndTimerecFieldsAreStrict() {
        val add = decode("dvrEntryAdd", mapOf("id" to 1L, "seasonCount" to 2L, "episode" to "S1E1")) as HtspDvrEntryAddMessage
        assertEquals(2L, add.seasonCount)
        assertEquals("S1E1", add.episodeOnscreen)
        val rule = decode("timerecEntryAdd", mapOf("id" to "rule", "enabled" to 1L,
            "start" to 0L, "stop" to 1L, "removal" to 3L)) as HtspTimerecEntryAddMessage
        assertEquals(3L, rule.removalDays)
        val dvr = decode("dvrEntryUpdate", mapOf("id" to 1L, "seasonCount" to 4L, "episode" to "S1E2",
            "files" to listOf(mapOf("info" to listOf(mapOf("type" to "H264", "duration" to 90000L,
                "aspect_num" to 16L, "aspect_den" to 9L)))))) as HtspDvrEntryUpdateMessage
        assertEquals(4L, dvr.seasonCount)
        assertEquals("S1E2", dvr.episodeOnscreen)
        val stream = dvr.files!!.single().info!!.streams.single()
        assertEquals(1_000_000L, stream.frameDurationUs)
        assertEquals(9L, stream.aspectDenominator)
        assertEquals(4_294_967_295L, (decode("timerecEntryUpdate", mapOf("id" to "rule",
            "removal" to 4_294_967_295L)) as HtspTimerecEntryUpdateMessage).removalDays)
        listOf("seasonCount" to "4", "episode" to 2L, "files" to listOf(mapOf("info" to "wrong"))).forEach {
            assertEquals(HtspServerMessageMalformedKnownMessage,
                decodeHtspServerMessage(mapOf("method" to "dvrEntryUpdate", "id" to 1L) + it))
        }
        assertEquals(HtspServerMessageMalformedKnownMessage, decodeHtspServerMessage(mapOf(
            "method" to "timerecEntryUpdate", "id" to "rule", "removal" to -1L)))
    }

    @Test
    fun commercialAdviceUsesSharedConstants() {
        val base = mapOf("method" to "muxpkt", "subscriptionId" to 1L, "stream" to 0L,
            "payload" to byteArrayOf(), "duration" to 0L, "frametype" to 73L)
        listOf(null, -1L, 4_294_967_296L, "1", true).forEach {
            assertEquals(HtspServerMessageMalformedKnownMessage, decodeHtspServerMessage(base + ("com" to it)))
        }
        listOf(0L, 1L, 2L, 3L).zip(HtspCommercialAdvice.entries).forEach { (code, expected) ->
            val packet = decode("muxpkt", mapOf("subscriptionId" to 1L, "stream" to 0L,
                "payload" to byteArrayOf(), "duration" to 0L, "frametype" to 73L, "com" to code)) as HtspMuxPacketMessage
            assertSame(expected, packet.commercialAdvice)
        }
    }

    @Test
    fun programmeAndHbbtvSchemasAreStrictAndImmutable() {
        val names = mutableMapOf("Person" to "actor")
        val credits = decodeProgrammeCredits(mapOf("credits" to names), "credits") { error("malformed") }!!
        names.clear()
        assertEquals(listOf(HtspProgrammeCredit("Person", "actor")), credits.entries)
        assertThrows(UnsupportedOperationException::class.java) { (credits.entries as MutableList).clear() }
        val application = mapOf("title" to listOf(mapOf("name" to "App", "lang" to "eng")),
            "url" to "relative", "visibility" to "all")
        val hbbtv = decodeHbbtvApplications(mapOf("hbbtv" to mapOf("0" to listOf(application)))) { error("malformed") }!!
        assertEquals("App", hbbtv.sections["0"]!!.single().titles.single().name)
        val channel = decode("channelUpdate", mapOf("channelId" to 1L, "services" to listOf(
            mapOf("name" to "service", "type" to "HDTV", "content" to 1L,
                "hbbtv" to mapOf("0" to listOf(application)))))) as HtspChannelUpdateMessage
        assertEquals("relative", channel.services!!.single().hbbtv!!.sections["0"]!!.single().url)
        assertThrows(UnsupportedOperationException::class.java) { (hbbtv.sections as MutableMap).clear() }
        assertThrows(IllegalStateException::class.java) {
            decodeProgrammeCredits(mapOf("credits" to mapOf("Person" to 1L)), "credits") { error("malformed") }
        }
        assertThrows(IllegalStateException::class.java) {
            decodeHbbtvApplications(mapOf("hbbtv" to "wrong")) { error("malformed") }
        }
        val event = decode("eventUpdate", mapOf("eventId" to 1L, "credits" to mapOf("Person" to "actor"))) as HtspEventUpdateMessage
        assertEquals(credits, event.credits)
        assertEquals(HtspServerMessageMalformedKnownMessage, decodeHtspServerMessage(mapOf(
            "method" to "eventUpdate", "eventId" to 1L, "credits" to mapOf("Person" to 1L))))
    }

    @Test
    fun nestedMetadataHasStructuralEqualityAndLegacyFieldsAreOptional() {
        val dvrFields = mapOf("id" to 1L, "files" to listOf(mapOf("info" to listOf(
            mapOf("language" to "eng"), mapOf("type" to 2L, "language" to "deu"), "bad entry",
            mapOf("type" to "H264", "width" to 1920L),
        ))))
        val first = decode("dvrEntryAdd", dvrFields) as HtspDvrEntryAddMessage
        val second = decode("dvrEntryAdd", dvrFields)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        val info = first.files!!.single().info!!
        assertEquals(listOf(
            HtspDvrRecordingStream(language = "eng"),
            HtspDvrRecordingStream(language = "deu"),
            HtspDvrRecordingStream(),
            HtspDvrRecordingStream(type = "H264", width = 1920L),
        ), info.streams)
        assertFalse(info.toString().contains("eng"))
        assertThrows(UnsupportedOperationException::class.java) { (info.streams as MutableList).clear() }

        val fields = channelFields(mapOf("0" to listOf(mapOf(
            "title" to listOf(mapOf("name" to "App")),
        ))))
        val channel = decode("channelAdd", fields) as HtspChannelAddMessage
        assertEquals(channel, decode("channelAdd", fields))
        assertEquals(channel.hashCode(), decode("channelAdd", fields).hashCode())
        val app = channel.services!!.single().hbbtv!!.sections["0"]!!.single()
        assertNull(app.url)
        assertNull(app.visibility)
        assertNull(app.titles.single().language)
        assertThrows(UnsupportedOperationException::class.java) { (app.titles as MutableList).clear() }
        val reply = HtspRequestCodecs.decode(GetChannelRequest(1), fields, 44)
        val repeated = HtspRequestCodecs.decode(GetChannelRequest(1), fields, 44)
        assertEquals(reply, repeated)
        assertEquals(reply.hashCode(), repeated.hashCode())
    }

    @Test
    fun recordingStreamInvalidFieldsBecomeNullWithoutMovingStreams() {
        val numericFields = listOf("audio_type", "audio_version", "width", "height", "duration",
            "aspect_num", "aspect_den", "composition_id", "ancillary_id")
        listOf(null, "wrong", true, -1L, 4_294_967_296L).forEach { invalid ->
            val invalidFields = numericFields.associateWith { invalid } + mapOf("type" to 2L, "language" to 2L)
            listOf("dvrEntryAdd", "dvrEntryUpdate").forEach { method ->
                val message = decode(method, mapOf("id" to 1L, "files" to listOf(mapOf(
                    "info" to listOf(invalidFields, null, mapOf("type" to "AAC", "audio_type" to 1L)),
                ))))
                val files = when (message) {
                    is HtspDvrEntryAddMessage -> message.files
                    is HtspDvrEntryUpdateMessage -> message.files
                    else -> error("unexpected message")
                }
                assertEquals(listOf(HtspDvrRecordingStream(), HtspDvrRecordingStream(),
                    HtspDvrRecordingStream(type = "AAC", audioType = 1L)), files!!.single().info!!.streams)
            }
        }
    }

    @Test
    fun creditsDecodeInEventRepliesAndEventAddWithStrictTypes() {
        val base = mapOf("eventId" to 1L, "start" to 2L, "stop" to 3L)
        val fields = base + ("credits" to mapOf("Person" to "actor"))
        val expected = HtspProgrammeCredits(listOf(HtspProgrammeCredit("Person", "actor")))
        assertEquals(expected, HtspRequestCodecs.decode(GetEventRequest(1), fields, 44).event.credits)
        assertEquals(expected, HtspRequestCodecs.decode(GetEventsRequest(), mapOf("events" to listOf(fields)), 44).events.single().credits)
        assertEquals(expected, (decode("eventAdd", fields) as HtspEventAddMessage).event.credits)
        listOf("wrong", mapOf("Person" to 2L)).forEach { malformed ->
            val bad = base + ("credits" to malformed)
            assertThrows(HtspProtocolMappingException::class.java) { HtspRequestCodecs.decode(GetEventRequest(1), bad, 44) }
            assertThrows(HtspProtocolMappingException::class.java) {
                HtspRequestCodecs.decode(GetEventsRequest(), mapOf("events" to listOf(bad)), 44)
            }
            assertEquals(HtspServerMessageMalformedKnownMessage, decodeHtspServerMessage(bad + ("method" to "eventAdd")))
        }
    }

    @Test
    fun getChannelHbbtvDecodesValidAndRejectsMalformedOuterType() {
        val fields = channelFields(mapOf("0" to listOf(mapOf("url" to "relative", "visibility" to "all"))))
        val response = HtspRequestCodecs.decode(GetChannelRequest(1), fields, 44)
        assertEquals("relative", response.channel.services.single().hbbtv!!.sections["0"]!!.single().url)
        assertThrows(HtspProtocolMappingException::class.java) {
            HtspRequestCodecs.decode(GetChannelRequest(1), channelFields("wrong"), 44)
        }
    }

    @Test
    fun creditListSupportsRepeatedNamesWhileWireMapKeepsLastRole() {
        val entries = mutableListOf(HtspProgrammeCredit("Person", "actor"), HtspProgrammeCredit("Person", "director"))
        val credits = HtspProgrammeCredits(entries)
        entries.clear()
        assertEquals(2, credits.entries.size)
        assertFalse(credits.entries.toString().contains("Person"))
        assertEquals(credits, HtspProgrammeCredits(credits.entries))
        assertEquals(credits.hashCode(), HtspProgrammeCredits(credits.entries).hashCode())

        fun field(type: Int, name: String, value: ByteArray): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(output).apply {
                writeByte(type)
                writeByte(name.length)
                writeInt(value.size)
                write(name.toByteArray())
                write(value)
            }
            return output.toByteArray()
        }
        val body = field(1, "credits", field(3, "Person", "actor".toByteArray()) +
            field(3, "Person", "director".toByteArray()))
        val frame = ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
        val decoded = HtspCodec.readMessage(frame.inputStream())
        assertEquals(listOf(HtspProgrammeCredit("Person", "director")),
            decodeProgrammeCredits(decoded.fields, "credits") { error("malformed") }!!.entries)
    }

    private fun channelFields(hbbtv: Any): Map<String, Any?> = mapOf(
        "channelId" to 1L, "channelIdStr" to "uuid", "channelNumber" to 1L,
        "channelName" to "channel", "eventId" to 0L, "nextEventId" to 0L, "tags" to emptyList<Long>(),
        "services" to listOf(mapOf("name" to "service", "type" to "HDTV", "content" to 1L, "hbbtv" to hbbtv)),
    )
}
