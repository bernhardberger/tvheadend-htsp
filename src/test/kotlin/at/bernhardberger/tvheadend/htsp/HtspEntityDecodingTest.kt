package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspWireReply
import at.bernhardberger.tvheadend.htsp.connection.classifyHtspReply
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HtspEntityDecodingTest {
    @Test
    fun fullUpdatesReplaceOptionalValuesInsteadOfRetainingClearedState() {
        val cases = listOf(
            Triple("channel", "channelIcon", "icon"), Triple("tag", "tagIcon", "icon"),
            Triple("event", "title", "programme"), Triple("dvrEntry", "title", "recording"),
            Triple("autorecEntry", "title", "rule"), Triple("timerecEntry", "name", "rule"),
        )
        cases.forEach { (family, field, value) ->
            val added = entity(decoded(metadataFixture("${family}Add") + (field to value)))
            val updated = entity(decoded(metadataFixture("${family}Update")))
            val emptyAdd = entity(decoded(metadataFixture("${family}Add")))
            assertNotEquals(added, updated, family)
            assertEquals(emptyAdd, updated, family)
        }
    }

    @Test
    fun channelNowNextIsAnExactSparseShapeIncludingZeroClears() {
        val fields = mapOf("method" to "channelUpdate", "channelId" to 1L, "eventId" to 0L, "nextEventId" to 2L)
        assertEquals(HtspChannelNowNextMessage(1L, 0L, 2L), decoded(fields))
        assertEquals(HtspChannelNowNextMessage(1L, 0L, 2L), decoded(fields, 36))
        (fields.keys - "method").forEach { malformed(fields - it) }
        malformed(fields + ("unexpected" to 1L))
        malformed(fields + ("channelIcon" to "damaged-full"))
        malformed(metadataFixture("channelUpdate") - "channelName")
        malformed(fields + ("channelName" to "incomplete-full"))
        assertEquals(HtspServerMessageUnknownMethod, decodeHtspServerMessage(fields + ("seq" to 1L)))
    }

    @Test
    fun dvrStatsOnlyCarriesTheFieldsOutsideTheStatsonlyGate() {
        // htsp_server.c:977–980,1157–1201: every non-identity field here is outside !statsonly.
        val fields = mapOf("method" to "dvrEntryUpdate", "id" to 1L, "idStr" to "uuid", "state" to "recording")
        assertEquals(HtspDvrEntryStatsMessage(1L, "uuid", "recording"), decoded(fields))
        assertEquals(
            HtspDvrEntryStatsMessage(1L, "uuid", "recording", false, "error", "subscription", 2L, 3L, 4L),
            decoded(fields + mapOf("duplicate" to 0L, "error" to "error", "subscriptionError" to "subscription",
                "streamErrors" to 2L, "dataErrors" to 3L, "dataSize" to 4L)),
        )
        malformed(fields - "state")
        malformed(fields - "id")
        malformed(fields + ("unexpected" to 1L))
        malformed(fields + ("title" to "damaged-full"))
        malformed(metadataFixture("dvrEntryUpdate") - "enabled")
        malformed(fields + ("enabled" to 1L))
        listOf("duplicate", "error", "subscriptionError", "streamErrors", "dataErrors", "dataSize").forEach {
            malformed(fields + (it to emptyList<Any>()))
        }
        assertEquals("HtspDvrEntryStatsMessage(<redacted>)", decoded(fields + ("error" to "private")).toString())
        assertThrows(IllegalArgumentException::class.java) {
            (decoded(fields) as HtspDvrEntryStatsMessage).copy(streamErrors = -1L)
        }
        typedHtspServerMessageInventory.filter { it.sparseMessageType != null }.forEach {
            val sparse = if (it.method == "channelUpdate") {
                mapOf("method" to it.method, "channelId" to 1L, "eventId" to 0L, "nextEventId" to 0L)
            } else fields
            assertEquals(it.sparseMessageType, decoded(sparse)::class.simpleName)
        }
    }

    @Test
    fun tagMembersDistinguishesNotIncludedFromEmptyAndPresent() {
        val initial = (decoded(metadataFixture("tagAdd")) as HtspTagAddMessage).tag
        assertNull(initial.channelIds)
        val empty = (decoded(metadataFixture("tagUpdate") + ("members" to emptyList<Long>())) as HtspTagUpdateMessage).tag
        assertEquals(emptyList<Long>(), empty.channelIds)
        val populated = (decoded(metadataFixture("tagUpdate") + ("members" to listOf(2L, 3L))) as HtspTagUpdateMessage).tag
        assertEquals(listOf(2L, 3L), populated.channelIds)
        assertNotEquals(initial, empty)
        malformed(metadataFixture("tagUpdate") + ("members" to null))
    }

    @Test
    fun versionIntroducedFieldsUseNegotiatedVersionOnAddsFullUpdatesAndStats() {
        val cases = listOf(
            metadataFixture("channelAdd") to "channelIdStr", metadataFixture("channelUpdate") to "channelIdStr",
            metadataFixture("tagAdd") to "tagIdStr", metadataFixture("tagUpdate") to "tagIdStr",
            metadataFixture("dvrEntryAdd") to "idStr", metadataFixture("dvrEntryUpdate") to "idStr",
            mapOf("method" to "dvrEntryUpdate", "id" to 1L, "state" to "scheduled", "idStr" to "uuid") to "idStr",
        )
        cases.forEach { (fields, uuid) ->
            listOf(36, 40).forEach { decoded(fields - uuid, it) }
            listOf(41, 44).forEach { malformed(fields - uuid, it) }
            listOf(36, 41, 44).forEach { version ->
                decoded(fields, version)
                malformed(fields + (uuid to 1L), version)
            }
        }
        listOf("autorecEntryAdd", "autorecEntryUpdate").forEach { method ->
            val fields = metadataFixture(method) - "broadcastType"
            listOf(36, 38).forEach { decoded(fields, it) }
            listOf(39, 44).forEach { malformed(fields, it) }
        }
    }

    @Test
    fun everyFullBuilderRequiresTheSameUnconditionalFieldsForAddAndUpdate() {
        listOf("channel", "tag", "event", "dvrEntry", "autorecEntry", "timerecEntry").forEach { family ->
            listOf("Add", "Update").forEach { suffix ->
                val fields = metadataFixture(family + suffix)
                decoded(fields)
                (fields.keys - "method").forEach { key -> malformed(fields - key) }
            }
        }
        val timer = (decoded(metadataFixture("timerecEntryUpdate")) as HtspTimerecEntryUpdateMessage).entry
        assertNull(timer.startMinutesSinceMidnight)
        assertNull(timer.stopMinutesSinceMidnight)
        assertNull(timer.channelId)
        assertNull(timer.name)
        assertNull(timer.title)
    }

    @Test
    fun channelRepliesAndPushedSnapshotsShareFieldsAliasesAndVersionValidation() {
        listOf(36, 41, 44).forEach { version ->
            val fields = (metadataFixture("channelAdd") - "method" - "channelNumber") + mapOf(
                "number" to 7L, "channelIcon" to "icon", "channelNumberMinor" to 3L,
                "services" to listOf(mapOf("name" to "service", "type" to "HDTV", "content" to 2L)),
            )
            val reply = reply(GetChannelRequest(1L), fields, version).channel
            assertEquals(reply, (decoded(fields + ("method" to "channelAdd"), version) as HtspChannelAddMessage).channel)
            assertEquals(reply, (decoded(fields + ("method" to "channelUpdate"), version) as HtspChannelUpdateMessage).channel)
            val noUuid = fields - "channelIdStr"
            if (version < 41) assertNull(reply(GetChannelRequest(1L), noUuid, version).channel.channelUuid)
            else assertEquals(HtspResult.MalformedReply, classifyHtspReply(HtspWireReply(noUuid), GetChannelRequest(1L), version))
        }
    }

    @Test
    fun allEventReplyPathsIncludeTheSameFieldsAsPushedSnapshots() {
        val fields = (metadataFixture("eventAdd") - "method") + mapOf(
            "channelId" to 2L, "title" to "title", "genre" to "genre", "episodeId" to 3L, "serieslinkId" to 4L,
            "category" to listOf("category"), "keyword" to listOf("keyword"), "credits" to mapOf("Person" to "actor"),
            "episode" to mapOf("season" to 5L, "number" to 6L),
        )
        val event = reply(GetEventRequest(1L), fields).event
        assertEquals("genre", event.genre)
        assertEquals(3L, event.episodeId)
        assertEquals(4L, event.seriesLinkId)
        assertEquals(event, (decoded(fields + ("method" to "eventAdd")) as HtspEventAddMessage).event)
        assertEquals(event, (decoded(fields + ("method" to "eventUpdate")) as HtspEventUpdateMessage).event)
        assertEquals(listOf(event), reply(GetEventsRequest(), mapOf("events" to listOf(fields))).events)
        assertEquals(listOf(event), (reply(EpgQueryRequest("title", full = true), mapOf("events" to listOf(fields)))
            as EpgQueryResponse.Events).events)
    }

    private fun decoded(fields: Map<String, Any?>, version: Int = 44): HtspServerMessage {
        val result = decodeHtspServerMessage(fields, version)
        assertTrue(result is HtspServerMessageDecoded, "${fields["method"]} at v$version must decode")
        return (result as HtspServerMessageDecoded).message
    }

    private fun malformed(fields: Map<String, Any?>, version: Int = 44) {
        assertEquals(HtspServerMessageMalformedKnownMessage, decodeHtspServerMessage(fields, version),
            "${fields.keys} at v$version must fail")
    }

    private fun entity(message: HtspServerMessage): Any = when (message) {
        is HtspChannelAddMessage -> message.channel
        is HtspChannelUpdateMessage -> message.channel
        is HtspTagAddMessage -> message.tag
        is HtspTagUpdateMessage -> message.tag
        is HtspEventAddMessage -> message.event
        is HtspEventUpdateMessage -> message.event
        is HtspDvrEntryAddMessage -> message.entry
        is HtspDvrEntryUpdateMessage -> message.entry
        is HtspAutorecEntryAddMessage -> message.entry
        is HtspAutorecEntryUpdateMessage -> message.entry
        is HtspTimerecEntryAddMessage -> message.entry
        is HtspTimerecEntryUpdateMessage -> message.entry
        else -> error("not full metadata")
    }

    private fun <T> reply(request: HtspRequest<T>, fields: Map<String, Any?>, version: Int = 44): T =
        (classifyHtspReply(HtspWireReply(fields), request, version) as HtspResult.Ok).value
}
