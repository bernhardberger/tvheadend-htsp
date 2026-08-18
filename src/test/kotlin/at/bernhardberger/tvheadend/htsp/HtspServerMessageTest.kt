package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtspServerMessageTest {
    @Test
    fun catalogContainsExactlyTheAssignedTypedServerMessages() {
        assertEquals(
            listOf(
                "channelAdd",
                "channelUpdate",
                "channelDelete",
                "tagAdd",
                "tagUpdate",
                "tagDelete",
                "dvrEntryAdd",
                "dvrEntryUpdate",
                "dvrEntryDelete",
                "autorecEntryAdd",
                "autorecEntryUpdate",
                "autorecEntryDelete",
                "timerecEntryAdd",
                "timerecEntryUpdate",
                "timerecEntryDelete",
                "eventAdd",
                "eventUpdate",
                "eventDelete",
                "initialSyncCompleted",
                "muxpkt",
                "queueStatus",
                "subscriptionStart",
                "subscriptionStop",
                "subscriptionGrace",
                "subscriptionStatus",
                "signalStatus",
                "descrambleInfo",
                "subscriptionSpeed",
                "timeshiftStatus",
                "subscriptionSkip",
            ),
            typedHtspServerMessageInventory.map { it.method },
        )
        assertEquals(30, typedHtspServerMessageInventory.map { it.messageType }.toSet().size)
    }

    @Test
    fun descrambleInfoDecodesTheCompletePinnedShapeStrictly() {
        val message = decodeMessage(
            mapOf(
                "method" to "descrambleInfo",
                "subscriptionId" to 0xffff_ffffL,
                "pid" to 0L,
                "caid" to 0xffff_ffffL,
                "provid" to 1L,
                "ecmtime" to 2L,
                "hops" to 3L,
                "cardsystem" to "system",
                "reader" to "reader",
                "from" to "source",
                "protocol" to "protocol",
            ),
        ) as HtspDescrambleInfoMessage

        assertEquals(0xffff_ffffL, message.subscriptionId)
        assertEquals(0L, message.pid)
        assertEquals(0xffff_ffffL, message.conditionalAccessId)
        assertEquals(1L, message.providerId)
        assertEquals(2L, message.ecmTime)
        assertEquals(3L, message.hopCount)
        assertEquals("system", message.cardSystem)
        assertEquals("reader", message.reader)
        assertEquals("source", message.source)
        assertEquals("protocol", message.protocol)

        val required = listOf("subscriptionId", "pid", "caid", "provid", "ecmtime", "hops")
        val complete = minimalFixture("descrambleInfo")
        required.forEach { field -> assertMalformed(complete - field) }
        listOf(
            complete + ("subscriptionId" to -1L),
            complete + ("pid" to 0x1_0000_0000L),
            complete + ("caid" to 1),
            complete + ("provid" to "1"),
            complete + ("ecmtime" to null),
            complete + ("hops" to -1L),
            complete + ("cardsystem" to 1L),
            complete + ("reader" to null),
            complete + ("from" to listOf("source")),
            complete + ("protocol" to false),
        ).forEach(::assertMalformed)
    }

    @Test
    fun subscriptionStreamMetadataAndTimeshiftSpeedDecodeStrictlyAndDefensively() {
        val metadata = byteArrayOf(1, 2, 3)
        val start = decodeMessage(
            mapOf(
                "method" to "subscriptionStart",
                "subscriptionId" to 1L,
                "streams" to listOf(mapOf("index" to 2L, "type" to "H264", "meta" to metadata)),
            ),
        ) as HtspSubscriptionStartMessage
        metadata[0] = 9
        assertTrue(start.streams!!.single().codecMetadata!!.toByteArray().contentEquals(byteArrayOf(1, 2, 3)))

        val timeshift = decodeMessage(
            minimalFixture("timeshiftStatus") + ("speed" to Int.MIN_VALUE.toLong()),
        ) as HtspTimeshiftStatusMessage
        assertEquals(Int.MIN_VALUE, timeshift.speed)
        assertEquals(null, (decodeMessage(minimalFixture("timeshiftStatus")) as HtspTimeshiftStatusMessage).speed)

        assertMalformed(
            minimalFixture("subscriptionStart") +
                ("streams" to listOf(mapOf("index" to 0L, "type" to "H264", "meta" to "bad"))),
        )
        assertMalformed(minimalFixture("timeshiftStatus") + ("speed" to Int.MAX_VALUE.toLong() + 1L))
        assertMalformed(minimalFixture("timeshiftStatus") + ("speed" to 1))
    }

    @Test
    fun timerecMessagesDecodeCompleteAddPartialUpdateAndExactDelete() {
        val add = decodeMessage(
            mapOf(
                "method" to "timerecEntryAdd",
                "id" to "rule-1",
                "enabled" to 1L,
                "name" to "Weekdays",
                "title" to "News",
                "channel" to 7L,
                "start" to 360L,
                "stop" to 420L,
                "daysOfWeek" to 31L,
                "priority" to 2L,
                "retention" to 14L,
                "directory" to "Archive",
                "owner" to "owner",
                "creator" to "creator",
                "configId" to "config",
                "comment" to "comment",
                "removal" to 9L,
            ),
        ) as HtspTimerecEntryAddMessage
        assertEquals("rule-1", add.id)
        assertEquals(true, add.enabled)
        assertEquals(7, add.channelId)
        assertEquals(360, add.startMinutesSinceMidnight)
        assertEquals(420, add.stopMinutesSinceMidnight)
        assertEquals(31L, add.daysOfWeekMask)
        assertFalse(add.javaClass.declaredMethods.any { it.name.contains("removal", ignoreCase = true) })

        val update = decodeMessage(
            mapOf(
                "method" to "timerecEntryUpdate",
                "id" to "rule-1",
                "enabled" to 0L,
                "title" to "",
                "channel" to 0L,
                "start" to 0L,
                "stop" to 1_440L,
            ),
        ) as HtspTimerecEntryUpdateMessage
        assertEquals("rule-1", update.id)
        assertEquals(false, update.enabled)
        assertEquals("", update.title)
        assertEquals(0, update.channelId)
        assertEquals(null, update.name)

        assertEquals(
            HtspTimerecEntryDeleteMessage(""),
            decodeMessage(mapOf("method" to "timerecEntryDelete", "id" to "")),
        )
    }

    @Test
    fun timerecMessagesRejectIncompleteOrOutOfRangeKnownShapes() {
        assertMalformed(mapOf("method" to "timerecEntryAdd", "id" to "rule"))
        assertMalformed(minimalFixture("timerecEntryAdd") + ("enabled" to 2L))
        assertMalformed(minimalFixture("timerecEntryAdd") + ("channel" to (Int.MAX_VALUE.toLong() + 1L)))
        assertMalformed(minimalFixture("timerecEntryAdd") + ("start" to -1L))
        assertMalformed(minimalFixture("timerecEntryAdd") + ("stop" to 1_441L))
        assertMalformed(mapOf("method" to "timerecEntryUpdate", "id" to 1L, "priority" to -1L))
        assertMalformed(mapOf("method" to "timerecEntryDelete", "id" to 1L))
    }

    @Test
    fun timerecMalformedOptionalsAreOmittedWhileValidSiblingsSurvive() {
        val add = decodeMessage(
            minimalFixture("timerecEntryAdd") + mapOf(
                "daysOfWeek" to -1L,
                "priority" to "invalid",
                "retention" to 0x1_0000_0000L,
                "directory" to listOf("invalid"),
                "owner" to null,
                "creator" to 3L,
                "configId" to false,
                "comment" to "kept",
            ),
        ) as HtspTimerecEntryAddMessage
        assertEquals(null, add.daysOfWeekMask)
        assertEquals(null, add.priority)
        assertEquals(null, add.retentionDays)
        assertEquals(null, add.directory)
        assertEquals(null, add.owner)
        assertEquals(null, add.creator)
        assertEquals(null, add.configId)
        assertEquals("kept", add.comment)

        val update = decodeMessage(
            mapOf(
                "method" to "timerecEntryUpdate",
                "id" to "rule",
                "enabled" to 2L,
                "name" to 7L,
                "title" to "updated",
                "channel" to -1L,
                "start" to 1_441L,
                "stop" to "invalid",
                "daysOfWeek" to -1L,
                "priority" to 0x1_0000_0000L,
                "retention" to null,
                "directory" to "kept",
            ),
        ) as HtspTimerecEntryUpdateMessage
        assertEquals(null, update.enabled)
        assertEquals(null, update.name)
        assertEquals("updated", update.title)
        assertEquals(null, update.channelId)
        assertEquals(null, update.startMinutesSinceMidnight)
        assertEquals(null, update.stopMinutesSinceMidnight)
        assertEquals(null, update.daysOfWeekMask)
        assertEquals(null, update.priority)
        assertEquals(null, update.retentionDays)
        assertEquals("kept", update.directory)
    }

    @Test
    fun autorecMessagesDecodeRequiredAddPartialUpdateAndExactDeleteStrictly() {
        val addFields = linkedMapOf<String, Any?>(
            "method" to "autorecEntryAdd",
            "id" to "",
            "enabled" to 0L,
            "maxDuration" to 0xffff_ffffL,
            "minDuration" to 0L,
            "retention" to 0xffff_ffffL,
            "removal" to 0L,
            "daysOfWeek" to 0xffff_ffffL,
            "approxTime" to Int.MIN_VALUE.toLong(),
            "start" to -1L,
            "startWindow" to Int.MAX_VALUE.toLong(),
            "priority" to 0xffff_ffffL,
            "startExtra" to Long.MIN_VALUE,
            "stopExtra" to Long.MAX_VALUE,
            "dupDetect" to 0xffff_ffffL,
            "maxCount" to 0L,
            "broadcastType" to 0xffff_ffffL,
            "comment" to "",
            "name" to "",
            "owner" to "",
            "creator" to "",
            "title" to "",
            "fulltext" to 0L,
            "mergetext" to 1L,
            "directory" to "",
            "channel" to 0xffff_ffffL,
            "serieslinkUri" to "",
            "configId" to "",
        )
        val add = decodeMessage(addFields) as HtspAutorecEntryAddMessage
        assertEquals(
            HtspAutorecEntryAddMessage(
                id = "",
                enabled = false,
                maxDurationSeconds = 0xffff_ffffL,
                minDurationSeconds = 0L,
                retentionDays = 0xffff_ffffL,
                removalDays = 0L,
                daysOfWeekMask = 0xffff_ffffL,
                approximateStartMinutesSinceMidnight = Int.MIN_VALUE,
                startMinutesSinceMidnight = -1,
                startWindowEndMinutesSinceMidnight = Int.MAX_VALUE,
                priority = 0xffff_ffffL,
                startExtraMinutes = Long.MIN_VALUE,
                stopExtraMinutes = Long.MAX_VALUE,
                duplicateDetection = 0xffff_ffffL,
                maximumRecordingCount = 0L,
                broadcastType = 0xffff_ffffL,
                comment = "",
                title = "",
                fullText = false,
                mergeText = true,
                name = "",
                directory = "",
                owner = "",
                creator = "",
                channelId = 0xffff_ffffL,
                seriesLinkUri = "",
                configId = "",
            ),
            add,
        )

        val requiredAddFields = listOf(
            "id", "enabled", "maxDuration", "minDuration", "retention", "removal",
            "daysOfWeek", "approxTime", "start", "startWindow", "priority",
            "startExtra", "stopExtra", "dupDetect", "maxCount", "broadcastType",
            "comment", "name", "owner", "creator",
        )
        requiredAddFields.forEach { requiredName ->
            assertMalformed(addFields - requiredName)
        }

        val update = decodeMessage(
            mapOf(
                "method" to "autorecEntryUpdate",
                "id" to "rule",
                "minDuration" to 0L,
                "approxTime" to Int.MIN_VALUE.toLong(),
                "start" to Int.MAX_VALUE.toLong(),
                "startExtra" to Long.MIN_VALUE,
                "channel" to 0xffff_ffffL,
                "title" to "",
            ),
        ) as HtspAutorecEntryUpdateMessage
        assertEquals(
            HtspAutorecEntryUpdateMessage(
                id = "rule",
                minDurationSeconds = 0L,
                approximateStartMinutesSinceMidnight = Int.MIN_VALUE,
                startMinutesSinceMidnight = Int.MAX_VALUE,
                startExtraMinutes = Long.MIN_VALUE,
                channelId = 0xffff_ffffL,
                title = "",
            ),
            update,
        )

        assertEquals(
            HtspAutorecEntryDeleteMessage(""),
            decodeMessage(mapOf("method" to "autorecEntryDelete", "id" to "")),
        )

        listOf(
            addFields + ("enabled" to 2L),
            addFields + ("maxDuration" to 0x1_0000_0000L),
            addFields + ("start" to (Int.MAX_VALUE.toLong() + 1L)),
            addFields + ("stopExtra" to 1),
            addFields + ("title" to null),
            addFields + ("fulltext" to 2L),
            mapOf("method" to "autorecEntryUpdate"),
            mapOf("method" to "autorecEntryUpdate", "id" to 1L),
            mapOf("method" to "autorecEntryUpdate", "id" to "rule", "channel" to -1L),
            mapOf("method" to "autorecEntryUpdate", "id" to "rule", "startWindow" to Long.MIN_VALUE),
            mapOf("method" to "autorecEntryUpdate", "id" to "rule", "comment" to listOf("bad")),
            mapOf("method" to "autorecEntryUpdate", "id" to "rule", "mergetext" to 2L),
            mapOf("method" to "autorecEntryDelete"),
            mapOf("method" to "autorecEntryDelete", "id" to 1L),
        ).forEach(::assertMalformed)

        assertTrue(
            decodeHtspServerMessage(addFields + ("seq" to 1L)) is HtspServerMessageUnknownMethod,
        )
    }

    @Test
    fun decoderDistinguishesDecodedUnknownMalformedAndSeqBearingInput() {
        val decoded = decodeHtspServerMessage(linkedMapOf("method" to "initialSyncCompleted"))
        assertEquals(
            HtspServerMessageDecoded(HtspInitialSyncCompletedMessage),
            decoded,
        )
        assertTrue(
            decodeHtspServerMessage(linkedMapOf("method" to "notAssigned")) is
                HtspServerMessageUnknownMethod,
        )
        assertTrue(decodeHtspServerMessage(emptyMap()) is HtspServerMessageUnknownMethod)
        assertTrue(
            decodeHtspServerMessage(mapOf("method" to 1L)) is HtspServerMessageUnknownMethod,
        )
        assertTrue(
            decodeHtspServerMessage(linkedMapOf("method" to "channelDelete")) is
                HtspServerMessageMalformedKnownMessage,
        )
        assertTrue(
            decodeHtspServerMessage(
                linkedMapOf("method" to "initialSyncCompleted", "seq" to 1L),
            ) is HtspServerMessageUnknownMethod,
        )
        assertTrue(decoded is HtspServerMessageDecoded)
    }

    @Test
    fun productionDecoderEntryPointAndTypedOutcomeExist() {
        val dispatchClass = Class.forName(
            "at.bernhardberger.tvheadend.htsp.messages.HtspServerMessageDispatchKt",
        )
        assertTrue(
            dispatchClass.declaredMethods.any { method ->
                method.name == "decodeHtspServerMessage" &&
                    method.parameterTypes.contentEquals(arrayOf(Map::class.java))
            },
            "S2 requires the production-named public decoder",
        )
        Class.forName("at.bernhardberger.tvheadend.htsp.messages.HtspServerMessageDecodeResult")
        Class.forName("at.bernhardberger.tvheadend.htsp.messages.HtspServerMessageDecoded")
        Class.forName("at.bernhardberger.tvheadend.htsp.messages.HtspServerMessageUnknownMethod")
        Class.forName("at.bernhardberger.tvheadend.htsp.messages.HtspServerMessageMalformedKnownMessage")
    }

    @Test
    fun publicDecodeResultSupportsAnExhaustiveProductionNamedWhen() {
        assertEquals("decoded", describeDecodeResult(decodeHtspServerMessage(minimalFixture("channelDelete"))))
        assertEquals("unknown", describeDecodeResult(decodeHtspServerMessage(mapOf("method" to "unknown"))))
        assertEquals("malformed", describeDecodeResult(decodeHtspServerMessage(mapOf("method" to "channelDelete"))))
    }

    @Test
    fun lowerVersionAndPartialAddShapesAreAccepted() {
        val validPartialShapes = listOf(
            mapOf("method" to "channelAdd", "channelId" to 1L, "channelName" to "Channel"),
            mapOf("method" to "tagAdd", "tagId" to 2L, "tagName" to "Tag"),
            mapOf(
                "method" to "dvrEntryAdd",
                "id" to 3L,
                "start" to 10L,
                "stop" to 20L,
                "state" to "scheduled",
            ),
            mapOf("method" to "subscriptionStart", "subscriptionId" to 4L),
            mapOf("method" to "signalStatus", "subscriptionId" to 5L, "feStatus" to "LOCK"),
        )
        validPartialShapes.forEach { fields ->
            assertEquals(
                true,
                decodeHtspServerMessage(fields) is HtspServerMessageDecoded,
                "${fields["method"]} must accept its evidenced partial/lower-version shape",
            )
        }
    }

    @Test
    fun addShapesOmitUnevidencedNamesAndConditionalEventChannelButPresentValuesStayStrict() {
        val channel = decodeMessage(mapOf("method" to "channelAdd", "channelId" to 1L))
            as HtspChannelAddMessage
        assertEquals(null, channel.channelName)

        val tag = decodeMessage(mapOf("method" to "tagAdd", "tagId" to 2L))
            as HtspTagAddMessage
        assertEquals(null, tag.tagName)

        val event = decodeMessage(
            mapOf("method" to "eventAdd", "eventId" to 3L, "start" to 4L, "stop" to 5L),
        ) as HtspEventAddMessage
        assertEquals(null, event.event.channelId)
        assertEquals(null, HtspChannelAddMessage(channelId = 6L).channelName)
        assertEquals(null, HtspTagAddMessage(tagId = 7L).tagName)

        listOf(
            mapOf("method" to "channelAdd", "channelId" to 1L, "channelName" to null),
            mapOf("method" to "channelAdd", "channelId" to 1L, "channelName" to 2L),
            mapOf("method" to "tagAdd", "tagId" to 2L, "tagName" to null),
            mapOf("method" to "tagAdd", "tagId" to 2L, "tagName" to 3L),
            mapOf(
                "method" to "eventAdd",
                "eventId" to 3L,
                "channelId" to null,
                "start" to 4L,
                "stop" to 5L,
            ),
            mapOf(
                "method" to "eventAdd",
                "eventId" to 3L,
                "channelId" to "4",
                "start" to 4L,
                "stop" to 5L,
            ),
        ).forEach(::assertMalformed)
    }

    @Test
    fun compatibilityAliasesAndMissingP5ObservationsAreNormalized() {
        val channel = decodeMessage(
            mapOf(
                "method" to "channelAdd",
                "channelId" to 1L,
                "channelName" to "Channel",
                "lcn" to 17L,
            ),
        ) as HtspChannelAddMessage
        assertEquals(17L, channel.channelNumber)

        val tag = decodeMessage(
            mapOf("method" to "tagAdd", "id" to 2L, "name" to "Tag", "index" to 3L),
        ) as HtspTagAddMessage
        assertEquals(2L, tag.tagId)
        assertEquals("Tag", tag.tagName)
        assertEquals(3L, tag.tagIndex)

        val dvr = decodeMessage(
            mapOf(
                "method" to "dvrEntryAdd",
                "dvrId" to 4L,
                "seasonNumber" to 1L,
                "episodeNumber" to 2L,
                "episodeCount" to 3L,
                "partNumber" to 4L,
                "partCount" to 5L,
                "status" to "recording",
                "statusError" to "none",
                "playPosition" to 6L,
                "playCount" to 7L,
            ),
        )
        dvr as HtspDvrEntryAddMessage
        assertEquals(1L, dvr.seasonNumber)
        assertEquals(2L, dvr.episodeNumber)
        assertEquals(3L, dvr.episodeCount)
        assertEquals(4L, dvr.partNumber)
        assertEquals(5L, dvr.partCount)
        assertEquals("recording", dvr.state)
        assertEquals("none", dvr.error)
        assertEquals(6L, dvr.playPositionSeconds)
        assertEquals(7L, dvr.playCount)

        val start = decodeMessage(
            mapOf(
                "method" to "subscriptionStart",
                "id" to 8L,
                "state" to "running",
                "error" to "tuningFailed",
            ),
        )
        start as HtspSubscriptionStartMessage
        assertEquals("running", start.status)
        assertEquals("tuningFailed", start.subscriptionError)
    }

    @Test
    fun compatibilityAliasPrecedenceIsOrderedAndFirstPresentIsStrict() {
        val channel = decodeMessage(
            mapOf(
                "method" to "channelAdd",
                "channelId" to 1L,
                "channelName" to "Channel",
                "channelNumber" to 10L,
                "number" to 11L,
                "lcn" to 12L,
                "channelNum" to 13L,
                "channelno" to 14L,
                "tagIds" to listOf(1L),
                "tags" to listOf(2L),
                "channelTags" to listOf(3L),
            ),
        ) as HtspChannelAddMessage
        assertEquals(10L, channel.channelNumber)
        assertEquals(listOf(1L), channel.tagIds)
        assertMalformed(
            mapOf(
                "method" to "channelAdd",
                "channelId" to 1L,
                "channelName" to "Channel",
                "channelNumber" to 10,
                "number" to 11L,
            ),
        )

        val tag = decodeMessage(
            mapOf(
                "method" to "tagAdd",
                "tagId" to 20L,
                "id" to 21L,
                "tagName" to "primary",
                "name" to "fallback",
                "tagIndex" to 22L,
                "index" to 23L,
            ),
        ) as HtspTagAddMessage
        assertEquals(20L, tag.tagId)
        assertEquals("primary", tag.tagName)
        assertEquals(22L, tag.tagIndex)

        val dvr = decodeMessage(
            mapOf(
                "method" to "dvrEntryUpdate",
                "id" to 30L,
                "dvrId" to 31L,
                "channelId" to 32L,
                "channel" to 33L,
                "state" to "primary-state",
                "status" to "fallback-state",
                "error" to "primary-error",
                "statusError" to "fallback-error",
                "playposition" to 34L,
                "playPosition" to 35L,
                "playcount" to 36L,
                "playCount" to 37L,
            ),
        ) as HtspDvrEntryUpdateMessage
        assertEquals(30L, dvr.entryId)
        assertEquals(32L, dvr.channelId)
        assertEquals("primary-state", dvr.state)
        assertEquals("primary-error", dvr.error)
        assertEquals(34L, dvr.playPositionSeconds)
        assertEquals(36L, dvr.playCount)

        val status = decodeMessage(
            mapOf(
                "method" to "subscriptionStatus",
                "subscriptionId" to 40L,
                "id" to 41L,
                "state" to "primary-state",
                "status" to "fallback-state",
                "subscriptionError" to "primary-error",
                "error" to "fallback-error",
            ),
        ) as HtspSubscriptionStatusMessage
        assertEquals(40L, status.subscriptionId)
        assertEquals("primary-state", status.status)
        assertEquals("primary-error", status.subscriptionError)
    }

    @Test
    fun eventAliasesAndNestedEpisodeObservationsPreserveFullAddAndPartialUpdate() {
        val added = decodeMessage(
            mapOf(
                "method" to "eventAdd",
                "id" to 1L,
                "channel" to 2L,
                "startTime" to 3L,
                "stopTime" to 4L,
                "eventTitle" to "Title",
                "genre" to "News",
                "content" to 5L,
                "episode" to mapOf(
                    "season" to 6L,
                    "number" to 7L,
                    "count" to 8L,
                    "part" to 9L,
                    "partCount" to 10L,
                ),
                "episodeId" to 11L,
                "seriesLinkId" to 12L,
            ),
        ) as HtspEventAddMessage
        assertEquals(1L, added.event.eventId)
        assertEquals(2L, added.event.channelId)
        assertEquals(3L, added.event.start)
        assertEquals(4L, added.event.stop)
        assertEquals("Title", added.event.title)
        assertEquals("News", added.genre)
        assertEquals(5L, added.event.contentType)
        assertEquals(6L, added.event.seasonNumber)
        assertEquals(7L, added.event.episodeNumber)
        assertEquals(8L, added.event.episodeCount)
        assertEquals(9L, added.event.partNumber)
        assertEquals(10L, added.event.partCount)
        assertEquals(11L, added.episodeId)
        assertEquals(12L, added.seriesLinkId)

        val update = decodeMessage(mapOf("method" to "eventUpdate", "id" to 1L))
            as HtspEventUpdateMessage
        assertEquals(null, update.channelId)
        assertEquals(null, update.start)
        assertEquals(null, update.title)
        assertMalformed(
            mapOf(
                "method" to "eventUpdate",
                "eventId" to 1,
                "id" to 1L,
            ),
        )
        assertMalformed(
            mapOf("method" to "eventUpdate", "eventId" to 1L, "episode" to null),
        )
    }

    @Test
    fun omittedVersionGatedFieldsAreAcceptedButPresentValuesStayStrict() {
        assertMalformed(
            mapOf(
                "method" to "channelAdd",
                "channelId" to 1L,
                "channelName" to "Channel",
                "channelNumberMinor" to 1,
            ),
        )
        assertMalformed(
            mapOf("method" to "tagAdd", "tagId" to 1L, "tagName" to "Tag", "tagIndex" to -1L),
        )
        assertMalformed(
            mapOf(
                "method" to "subscriptionStart",
                "subscriptionId" to 1L,
                "streams" to listOf(mapOf("index" to 0L)),
            ),
        )
        assertMalformed(
            mapOf("method" to "subscriptionStart", "subscriptionId" to 1L, "sourceinfo" to null),
        )
        assertMalformed(
            mapOf("method" to "signalStatus", "subscriptionId" to 1L, "feAbsoluteSNR" to 1),
        )
        assertMalformed(
            mapOf("method" to "dvrEntryAdd", "id" to 1L, "seasonNumber" to "1"),
        )
    }

    @Test
    fun publicUpdatesUseNullablePropertiesAndHtspFieldIsGone() {
        val update = decodeMessage(mapOf("method" to "channelUpdate", "channelId" to 3L))
            as HtspChannelUpdateMessage
        assertEquals(null, update.channelName)
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("at.bernhardberger.tvheadend.htsp.messages.HtspField")
        }
    }

    @Test
    fun everyFiniteDispatchBranchDecodesASourceValidMinimalFixture() {
        typedHtspServerMessageInventory.forEach { (method, messageType) ->
            val result = decodeHtspServerMessage(minimalFixture(method))
            assertTrue(result is HtspServerMessageDecoded, "$method must decode")
            assertEquals(
                messageType,
                (result as HtspServerMessageDecoded).message::class.simpleName,
            )
        }
    }

    @Test
    fun scalarTypesRangesAndRequiredFieldsAreStrict() {
        assertMalformed(mapOf("method" to "channelDelete", "channelId" to -1L))
        assertMalformed(mapOf("method" to "channelDelete", "channelId" to 0x1_0000_0000L))
        assertMalformed(mapOf("method" to "channelDelete", "channelId" to 1))
        assertMalformed(mapOf("method" to "channelDelete", "channelId" to "1"))
        assertMalformed(mapOf("method" to "subscriptionSpeed", "subscriptionId" to 1L, "speed" to 0x8000_0000L))
        assertMalformed(mapOf("method" to "subscriptionSpeed", "subscriptionId" to 1L))
        assertThrows(IllegalArgumentException::class.java) { HtspChannelDeleteMessage(-1L) }

        val speed = decodeMessage(
            mapOf("method" to "subscriptionSpeed", "subscriptionId" to 0xffff_ffffL, "speed" to -100L),
        ) as HtspSubscriptionSpeedMessage
        assertEquals(0xffff_ffffL, speed.subscriptionId)
        assertEquals(-100, speed.speed)

        val packet = decodeMessage(
            minimalFixture("muxpkt") + mapOf("dts" to Long.MIN_VALUE, "pts" to Long.MAX_VALUE),
        ) as HtspMuxPacketMessage
        assertEquals(Long.MIN_VALUE, packet.decodingTimestamp)
        assertEquals(Long.MAX_VALUE, packet.presentationTimestamp)
    }

    @Test
    fun updatesPreserveAbsentPresentEmptyAndZero() {
        val absent = decodeMessage(mapOf("method" to "channelUpdate", "channelId" to 3L))
            as HtspChannelUpdateMessage
        assertEquals(null, absent.channelName)
        assertEquals(null, absent.currentEventId)
        assertEquals(null, absent.tagIds)

        val present = decodeMessage(
            mapOf(
                "method" to "channelUpdate",
                "channelId" to 3L,
                "channelName" to "",
                "eventId" to 0L,
                "tags" to emptyList<Long>(),
            ),
        ) as HtspChannelUpdateMessage
        assertEquals("", present.channelName)
        assertEquals(0L, present.currentEventId)
        assertEquals(emptyList<Long>(), present.tagIds)

        assertMalformed(mapOf("method" to "channelUpdate", "channelId" to 3L, "channelName" to null))
        assertMalformed(mapOf("method" to "eventUpdate", "eventId" to 4L, "category" to listOf("ok", null)))
        assertMalformed(mapOf("method" to "dvrEntryUpdate", "id" to 5L, "enabled" to null))
    }

    @Test
    fun orderedDuplicateListsAreDefensiveAndUnmodifiableIncludingNestedMaps() {
        val tags = arrayListOf(4L, 4L, 2L)
        val services = arrayListOf<Map<String, Any?>>(
            mapOf("name" to "one", "type" to "SDTV", "content" to 1L),
            mapOf("name" to "one", "type" to "SDTV", "content" to 1L),
        )
        val fixture = minimalFixture("channelAdd").toMutableMap().apply {
            this["tags"] = tags
            this["services"] = services
        }
        val message = decodeMessage(fixture) as HtspChannelAddMessage
        tags.clear()
        services.clear()
        assertEquals(listOf(4L, 4L, 2L), message.tagIds)
        assertEquals(listOf("one", "one"), message.services!!.map { it.name })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (message.tagIds as MutableList<Long>).add(9L)
        }

        val streams = arrayListOf<Map<String, Any?>>(
            mapOf("index" to 1L, "type" to "H264"),
            mapOf("index" to 1L, "type" to "H264"),
        )
        val startFixture = minimalFixture("subscriptionStart").toMutableMap().apply { this["streams"] = streams }
        val start = decodeMessage(startFixture) as HtspSubscriptionStartMessage
        streams.clear()
        assertEquals(listOf(1L, 1L), start.streams!!.map { it.streamIndex })

        val categories = arrayListOf("news", "news")
        val eventUpdate = decodeMessage(
            mapOf("method" to "eventUpdate", "eventId" to 1L, "category" to categories),
        ) as HtspEventUpdateMessage
        categories.clear()
        assertEquals(listOf("news", "news"), eventUpdate.categories)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (eventUpdate.categories as MutableList<String>).add("sport")
        }
    }

    @Test
    fun dvrFilesExposeCanonicalPathsForTypedPlaybackSelectionAndRemainDefensive() {
        val firstFile = linkedMapOf<String, Any?>("filename" to "")
        val secondFile = linkedMapOf<String, Any?>("path" to "/recordings/second.ts")
        val sourceFiles = arrayListOf<Map<String, Any?>>(firstFile, secondFile, secondFile)
        val message = decodeMessage(
            mapOf("method" to "dvrEntryAdd", "id" to 1L, "files" to sourceFiles),
        ) as HtspDvrEntryAddMessage

        sourceFiles.clear()
        firstFile["filename"] = "/mutated.ts"
        secondFile["path"] = "/mutated.ts"
        assertEquals(listOf("", "/recordings/second.ts", "/recordings/second.ts"), message.files!!.map { it.path })
        assertEquals("/recordings/second.ts", canonicalPlaybackPath(message))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (message.files as MutableList<HtspDvrRecordingFile>).add(message.files.first())
        }

        val topLevelFallback = decodeMessage(
            mapOf(
                "method" to "dvrEntryAdd",
                "id" to 2L,
                "files" to listOf(mapOf("filename" to ""), mapOf("path" to "   ")),
                "path" to "/recordings/top-level.ts",
            ),
        ) as HtspDvrEntryAddMessage
        assertEquals("/recordings/top-level.ts", canonicalPlaybackPath(topLevelFallback))

        val aliasPrecedence = decodeMessage(
            mapOf(
                "method" to "dvrEntryAdd",
                "id" to 3L,
                "files" to listOf(
                    mapOf("filename" to "/recordings/filename.ts", "path" to "/recordings/path.ts"),
                ),
            ),
        ) as HtspDvrEntryAddMessage
        assertEquals("/recordings/filename.ts", aliasPrecedence.files!!.single().path)

        assertMalformed(
            mapOf(
                "method" to "dvrEntryAdd",
                "id" to 4L,
                "files" to listOf(mapOf("filename" to null, "path" to "/recordings/path.ts")),
            ),
        )
        assertMalformed(
            mapOf(
                "method" to "dvrEntryAdd",
                "id" to 5L,
                "files" to listOf(mapOf("filename" to 1L, "path" to "/recordings/path.ts")),
            ),
        )
    }

    @Test
    fun binaryDataCopiesInputAndAccessAndHasRedactedContentValueSemantics() {
        val input = byteArrayOf(1, 2, 3)
        val fixture = minimalFixture("muxpkt").toMutableMap().apply { this["payload"] = input }
        val first = decodeMessage(fixture) as HtspMuxPacketMessage
        input[0] = 9
        val access = first.payload.toByteArray()
        access[1] = 9
        assertTrue(first.payload.toByteArray().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(HtspBinary(byteArrayOf(1, 2, 3)), first.payload)
        assertEquals(HtspBinary(byteArrayOf(1, 2, 3)).hashCode(), first.payload.hashCode())
        assertNotEquals(HtspBinary(byteArrayOf(1, 2)), first.payload)
        assertEquals("HtspBinary(size=3)", first.payload.toString())
        assertFalse(first.payload.toString().contains("1, 2, 3"))

        val meta = byteArrayOf(7, 8)
        val startFixture = minimalFixture("subscriptionStart").toMutableMap().apply { this["meta"] = meta }
        val start = decodeMessage(startFixture) as HtspSubscriptionStartMessage
        meta[0] = 0
        assertTrue(start.codecMetadata!!.toByteArray().contentEquals(byteArrayOf(7, 8)))
    }

    @Test
    fun publicModelsDoNotDeclareRawMapDecoderMethods() {
        val publicTypes = listOf(
            HtspServerMessage::class.java,
            HtspChannelAddMessage::class.java,
            HtspDvrEntryUpdateMessage::class.java,
            HtspAutorecEntryAddMessage::class.java,
            HtspAutorecEntryUpdateMessage::class.java,
            HtspAutorecEntryDeleteMessage::class.java,
            HtspMuxPacketMessage::class.java,
            HtspSubscriptionStartMessage::class.java,
        )
        publicTypes.flatMap { it.declaredMethods.asList() }.forEach { method ->
            assertFalse(method.parameterTypes.any { Map::class.java.isAssignableFrom(it) })
            assertFalse(method.returnType == Map::class.java)
            assertFalse(method.name.contains("decode", ignoreCase = true))
            assertFalse(method.name.contains("mapper", ignoreCase = true))
        }
    }

    private fun decodeMessage(fields: Map<String, Any?>): HtspServerMessage {
        return when (val result = decodeHtspServerMessage(fields)) {
            is HtspServerMessageDecoded -> result.message
            else -> throw AssertionError("expected decoded but was $result")
        }
    }

    private fun assertMalformed(fields: Map<String, Any?>) {
        assertTrue(
            decodeHtspServerMessage(fields) is HtspServerMessageMalformedKnownMessage,
            "expected malformed for $fields",
        )
    }

    private fun canonicalPlaybackPath(message: HtspDvrEntryAddMessage): String? =
        message.files?.firstOrNull { file -> file.path?.isNotBlank() == true }?.path
            ?: message.path?.takeIf { path -> path.isNotBlank() }

    private fun describeDecodeResult(result: HtspServerMessageDecodeResult): String = when (result) {
        is HtspServerMessageDecoded -> "decoded"
        is HtspServerMessageUnknownMethod -> "unknown"
        is HtspServerMessageMalformedKnownMessage -> "malformed"
    }

    private fun minimalFixture(method: String): Map<String, Any?> = when (method) {
        "channelAdd" -> mapOf(
            "method" to method,
            "channelId" to 1L,
            "channelIdStr" to "channel-uuid",
            "channelNumber" to 2L,
            "channelName" to "Channel",
            "eventId" to 3L,
            "nextEventId" to 4L,
            "services" to listOf(mapOf("name" to "Service", "type" to "SDTV", "content" to 1L)),
            "tags" to listOf(7L, 7L),
        )
        "channelUpdate" -> mapOf("method" to method, "channelId" to 1L)
        "channelDelete" -> mapOf("method" to method, "channelId" to 1L)
        "tagAdd" -> mapOf(
            "method" to method,
            "tagId" to 1L,
            "tagIdStr" to "tag-uuid",
            "tagIndex" to 2L,
            "tagName" to "Tag",
            "tagTitledIcon" to 0L,
        )
        "tagUpdate" -> mapOf("method" to method, "tagId" to 1L)
        "tagDelete" -> mapOf("method" to method, "tagId" to 1L)
        "dvrEntryAdd" -> mapOf(
            "method" to method,
            "id" to 1L,
            "idStr" to "dvr-uuid",
            "enabled" to 1L,
            "start" to -1L,
            "stop" to 2L,
            "startExtra" to 0L,
            "stopExtra" to 0L,
            "retention" to 0L,
            "removal" to 0L,
            "priority" to 0L,
            "contentType" to 0L,
            "ageRating" to 0L,
            "state" to "scheduled",
        )
        "dvrEntryUpdate" -> mapOf("method" to method, "id" to 1L)
        "dvrEntryDelete" -> mapOf("method" to method, "id" to 1L)
        "autorecEntryAdd" -> mapOf(
            "method" to method,
            "id" to "rule",
            "enabled" to 1L,
            "maxDuration" to 0L,
            "minDuration" to 0L,
            "retention" to 0L,
            "removal" to 0L,
            "daysOfWeek" to 0L,
            "approxTime" to -1L,
            "start" to -1L,
            "startWindow" to -1L,
            "priority" to 0L,
            "startExtra" to 0L,
            "stopExtra" to 0L,
            "dupDetect" to 0L,
            "maxCount" to 0L,
            "broadcastType" to 0L,
            "comment" to "",
            "name" to "",
            "owner" to "",
            "creator" to "",
        )
        "autorecEntryUpdate" -> mapOf("method" to method, "id" to "rule")
        "autorecEntryDelete" -> mapOf("method" to method, "id" to "rule")
        "timerecEntryAdd" -> mapOf(
            "method" to method,
            "id" to "rule",
            "enabled" to 1L,
            "name" to "Rule",
            "title" to "Title",
            "channel" to 1L,
            "start" to 0L,
            "stop" to 1_440L,
        )
        "timerecEntryUpdate" -> mapOf("method" to method, "id" to "rule")
        "timerecEntryDelete" -> mapOf("method" to method, "id" to "rule")
        "eventAdd" -> mapOf(
            "method" to method,
            "eventId" to 1L,
            "channelId" to 2L,
            "start" to -1L,
            "stop" to 2L,
        )
        "eventUpdate" -> mapOf("method" to method, "eventId" to 1L)
        "eventDelete" -> mapOf("method" to method, "eventId" to 1L)
        "initialSyncCompleted" -> mapOf("method" to method)
        "muxpkt" -> mapOf(
            "method" to method,
            "subscriptionId" to 1L,
            "frametype" to 0L,
            "stream" to 0L,
            "duration" to 0L,
            "payload" to byteArrayOf(),
        )
        "queueStatus" -> mapOf(
            "method" to method,
            "subscriptionId" to 1L,
            "packets" to 0L,
            "bytes" to 0L,
            "Bdrops" to 0L,
            "Pdrops" to 0L,
            "Idrops" to 0L,
        )
        "subscriptionStart" -> mapOf(
            "method" to method,
            "subscriptionId" to 1L,
            "streams" to listOf(mapOf("index" to 0L, "type" to "H264")),
            "sourceinfo" to emptyMap<String, Any?>(),
        )
        "subscriptionStop", "subscriptionStatus" -> mapOf("method" to method, "subscriptionId" to 1L)
        "subscriptionGrace" -> mapOf("method" to method, "subscriptionId" to 1L, "graceTimeout" to 5L)
        "signalStatus" -> mapOf(
            "method" to method,
            "subscriptionId" to 1L,
            "feStatus" to "LOCK",
        )
        "descrambleInfo" -> mapOf(
            "method" to method,
            "subscriptionId" to 1L,
            "pid" to 2L,
            "caid" to 3L,
            "provid" to 4L,
            "ecmtime" to 5L,
            "hops" to 6L,
        )
        "subscriptionSpeed" -> mapOf("method" to method, "subscriptionId" to 1L, "speed" to -100L)
        "timeshiftStatus" -> mapOf(
            "method" to method,
            "subscriptionId" to 1L,
            "full" to 0L,
            "shift" to -1L,
        )
        "subscriptionSkip" -> mapOf("method" to method, "subscriptionId" to 1L)
        else -> error("fixture missing for $method")
    }
}
