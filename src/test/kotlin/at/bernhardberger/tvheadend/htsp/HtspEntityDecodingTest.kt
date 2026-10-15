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
        listOf("dvrId", "status", "statusError").forEach { malformed(fields + (it to 1L)) }
        malformed((fields - "id") + ("dvrId" to 1L))
        malformed((fields - "state") + ("status" to "recording"))
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
            listOf(36L, 40L).forEach { decoded(fields - uuid, it) }
            listOf(41L, 44L).forEach { malformed(fields - uuid, it) }
            listOf(36L, 41L, 44L).forEach { version ->
                decoded(fields, version)
                malformed(fields + (uuid to 1L), version)
            }
        }
        listOf("autorecEntryAdd", "autorecEntryUpdate").forEach { method ->
            val fields = metadataFixture(method) - "broadcastType"
            listOf(36L, 38L).forEach { decoded(fields, it) }
            listOf(39L, 44L).forEach { malformed(fields, it) }
        }
    }

    @Test
    fun dvrAgeRatingIsOptionalAtV36AndRequiredFromV37WithStrictPresentValues() {
        listOf("dvrEntryAdd", "dvrEntryUpdate").forEach { method ->
            listOf(36L, 37L, 44L).forEach { version ->
                val fields = metadataFixture(method) - "ageRating"
                val compatible = if (version == 36L) fields - "idStr" else fields
                if (version == 36L) {
                    val entry = entity(decoded(compatible, version)) as at.bernhardberger.tvheadend.htsp.model.HtspDvrEntry
                    assertNull(entry.ageRating)
                } else malformed(compatible, version)
                assertEquals(12L, (entity(decoded(compatible + ("ageRating" to 12L), version))
                    as at.bernhardberger.tvheadend.htsp.model.HtspDvrEntry).ageRating)
                listOf(null, "12", -1L, 0x1_0000_0000L).forEach {
                    malformed(compatible + ("ageRating" to it), version)
                }
            }
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
    fun channelRepliesAndPushedSnapshotsShareCanonicalFieldsAndVersionValidation() {
        listOf(36L, 41L, 44L).forEach { version ->
            val fields = (metadataFixture("channelAdd") - "method") + mapOf(
                "channelNumber" to 7L, "number" to 99L, "channelIcon" to "icon", "channelNumberMinor" to 3L,
                "services" to listOf(mapOf("name" to "service", "type" to "HDTV", "content" to 2L)),
            )
            val reply = reply(GetChannelRequest(1L), fields, version).channel
            assertEquals(reply, (decoded(fields + ("method" to "channelAdd"), version) as HtspChannelAddMessage).channel)
            assertEquals(reply, (decoded(fields + ("method" to "channelUpdate"), version) as HtspChannelUpdateMessage).channel)
            val noUuid = fields - "channelIdStr"
            assertEquals(HtspResult.MalformedReply,
                classifyHtspReply(HtspWireReply(fields - "channelNumber"), GetChannelRequest(1L), version))
            if (version < 41) assertNull(reply(GetChannelRequest(1L), noUuid, version).channel.channelUuid)
            else assertEquals(HtspResult.MalformedReply, classifyHtspReply(HtspWireReply(noUuid), GetChannelRequest(1L), version))
        }
    }

    @Test
    fun allEventReplyPathsMatchPushedSnapshotsAtTheNegotiatedVersion() {
        val fields = (metadataFixture("eventAdd") - "method") + mapOf(
            "channelId" to 2L, "title" to "title", "genre" to "genre", "episodeId" to 3L, "serieslinkId" to 4L,
            "category" to listOf("category"), "keyword" to listOf("keyword"), "credits" to mapOf("Person" to "actor"),
            "episode" to mapOf("season" to 5L, "number" to 6L),
            "seasonNumber" to 7L, "episodeNumber" to 8L, "serieslinkUri" to "series", "episodeUri" to "episode",
        )
        listOf(36L, 41L, 44L).forEach { version ->
            val event = reply(GetEventRequest(1L), fields, version).event
            assertEquals(7L, event.seasonNumber)
            assertEquals(8L, event.episodeNumber)
            assertEquals("series", event.seriesLinkUri)
            assertEquals("episode", event.episodeUri)
            assertEquals(event, (decoded(fields + ("method" to "eventAdd"), version) as HtspEventAddMessage).event)
            assertEquals(event, (decoded(fields + ("method" to "eventUpdate"), version) as HtspEventUpdateMessage).event)
            assertEquals(listOf(event), reply(GetEventsRequest(), mapOf("events" to listOf(fields)), version).events)
            assertEquals(listOf(event), (reply(EpgQueryRequest("title", full = true),
                mapOf("events" to listOf(fields)), version) as EpgQueryResponse.Events).events)
            assertEquals(HtspResult.MalformedReply,
                classifyHtspReply(HtspWireReply(fields - "start" + ("startTime" to 2L)), GetEventRequest(1L), version))
        }
    }

    @Test
    fun removedNamesAreIgnoredInFullSnapshotsAndCannotSupplyRequiredFields() {
        val aliases = mapOf(
            "channel" to mapOf("tags" to listOf("tagIds", "channelTags"),
                "channelNumber" to listOf("lcn", "channelNum", "channelno", "number")),
            "tag" to mapOf("tagId" to listOf("id"), "tagName" to listOf("name"), "tagIndex" to listOf("index")),
            "event" to mapOf("eventId" to listOf("id"), "start" to listOf("startTime"), "stop" to listOf("stopTime")),
            "dvrEntry" to mapOf("id" to listOf("dvrId"), "state" to listOf("status")),
        )
        aliases.forEach { (family, fields) ->
            listOf("Add", "Update").forEach { suffix ->
                listOf(36L, 44L).forEach { version ->
                    val full = metadataFixture(family + suffix)
                    fields.forEach { (canonical, names) -> names.forEach { alias ->
                        assertEquals(entity(decoded(full, version)), entity(decoded(full + (alias to Any()), version)), alias)
                        malformed((full - canonical) + (alias to full[canonical]), version)
                    } }
                }
            }
        }
        val optionalAliases = mapOf(
            "event" to mapOf("channel" to 9L, "content" to 10L, "eventTitle" to "title", "name" to "title",
                "genre" to "genre", "episodeId" to 11L, "serieslinkId" to 12L, "seriesLinkId" to 13L,
                "episode" to mapOf("season" to 1L, "number" to 2L, "count" to 3L, "part" to 4L, "partCount" to 5L),
                "season" to 6L, "part" to 7L),
            "dvrEntry" to mapOf("channelId" to 9L, "statusError" to "error", "playCount" to 10L, "playPosition" to 11L),
        )
        optionalAliases.forEach { (family, fields) -> listOf("Add", "Update").forEach { suffix ->
            val full = metadataFixture(family + suffix)
            assertEquals(entity(decoded(full)), entity(decoded(full + fields)))
        } }
        malformed(metadataFixture("eventAdd") + ("category" to "not-a-list"))
        val dvr = decoded(metadataFixture("dvrEntryAdd") + ("files" to listOf(mapOf("path" to "ignored"))))
            as HtspDvrEntryAddMessage
        assertNull(dvr.entry.files!!.single().path)
    }

    @Test
    fun recordingFilesIgnoreUnevidencedIdsAndRetainEmittedFields() {
        val file = mapOf("filename" to "recording.ts", "start" to 1L, "stop" to 2L, "size" to 3L)
        listOf("dvrEntryAdd", "dvrEntryUpdate").forEach { method ->
            listOf(36L, 44L).forEach { version ->
                val fields = metadataFixture(method)
                val expected = at.bernhardberger.tvheadend.htsp.model.HtspDvrRecordingFile("recording.ts", 1L, 2L, 3L)
                listOf(file, file + ("id" to 7L), file + ("id" to "ignored")).forEach { wireFile ->
                    val entry = entity(decoded(fields + ("files" to listOf(wireFile)), version))
                        as at.bernhardberger.tvheadend.htsp.model.HtspDvrEntry
                    assertEquals(expected, entry.files!!.single())
                }
            }
        }
    }

    @Test
    fun seasonCountOnlyComesFromItsOwnFlatField() {
        val fields = (metadataFixture("eventAdd") - "method") + mapOf(
            "episodeCount" to 8L, "episode" to mapOf("count" to 99L),
        )
        listOf(36L, 44L).forEach { version ->
            assertNull(reply(GetEventRequest(1L), fields, version).event.seasonCount)
            assertEquals(8L, reply(GetEventRequest(1L), fields, version).event.episodeCount)
            assertEquals(2L, reply(GetEventRequest(1L), fields + ("seasonCount" to 2L), version).event.seasonCount)
            malformed(fields + mapOf("method" to "eventAdd", "seasonCount" to "invalid"), version)
        }
    }

    @Test
    fun deletesAndSubscriptionMessagesRequireTheirOwnCanonicalNames() {
        listOf(Triple("tagDelete", "tagId", "id"), Triple("eventDelete", "eventId", "id"),
            Triple("dvrEntryDelete", "id", "dvrId")).forEach { (method, canonical, alias) ->
            malformed(mapOf("method" to method, alias to 1L))
            decoded(mapOf("method" to method, canonical to 1L, alias to "ignored"))
        }
        listOf("subscriptionStart", "subscriptionStop", "subscriptionStatus").forEach { method ->
            malformed(mapOf("method" to method, "id" to 1L))
            assertEquals(decoded(mapOf("method" to method, "subscriptionId" to 1L)),
                decoded(mapOf("method" to method, "subscriptionId" to 1L, "state" to "ignored", "error" to "ignored")))
        }
    }

    @Test
    fun autorecUnsetTimesAreNullAndSetTimesAreMinutesSinceMidnight() {
        listOf("autorecEntryAdd", "autorecEntryUpdate").forEach { method ->
            listOf(36L, 44L).forEach { version ->
                val full = metadataFixture(method)
                listOf("approxTime", "start", "startWindow").forEach { field ->
                    listOf(0L, 1439L).forEach { decoded(full + (field to it), version) }
                    listOf(-2L, 1440L, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1L).forEach {
                        malformed(full + (field to it), version)
                    }
                }
                val unset = entity(decoded(full + mapOf("approxTime" to -1L, "start" to -1L, "startWindow" to -1L), version))
                    as at.bernhardberger.tvheadend.htsp.model.HtspAutorecEntry
                assertNull(unset.approximateStartMinutesSinceMidnight)
                assertNull(unset.startMinutesSinceMidnight)
                assertNull(unset.startWindowEndMinutesSinceMidnight)
            }
        }
    }

    private fun decoded(fields: Map<String, Any?>, version: Long = 44): HtspServerMessage {
        val result = decodeHtspServerMessage(fields, version)
        assertTrue(result is HtspServerMessageDecoded, "${fields["method"]} at v$version must decode")
        return (result as HtspServerMessageDecoded).message
    }

    private fun malformed(fields: Map<String, Any?>, version: Long = 44) {
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

    private fun <T> reply(request: HtspRequest<T>, fields: Map<String, Any?>, version: Long = 44): T =
        (classifyHtspReply(HtspWireReply(fields), request, version) as HtspResult.Ok).value
}
