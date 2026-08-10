package at.bernhardberger.tvheadend.htsp

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
                "subscriptionSpeed",
                "timeshiftStatus",
                "subscriptionSkip",
            ),
            typedHtspServerMessageCatalogForTest().map { it.first },
        )
        assertEquals(23, typedHtspServerMessageCatalogForTest().map { it.second }.toSet().size)
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
            "at.bernhardberger.tvheadend.htsp.GeneratedHtspServerMessageDispatchKt",
        )
        assertTrue(
            "P5 requires the production-named internal decoder",
            dispatchClass.declaredMethods.any { method ->
                method.name == "decodeHtspServerMessage" &&
                    method.parameterTypes.contentEquals(arrayOf(Map::class.java))
            },
        )
        Class.forName("at.bernhardberger.tvheadend.htsp.HtspServerMessageDecodeResult-internal")
    }

    @Test
    fun productionOutcomeIsNotJavaSourceApi() {
        val (exitCode, diagnostics) = compileJavaSource(
            sourceName = "ForbiddenOutcome.java",
            sourceText = """
                import at.bernhardberger.tvheadend.htsp.HtspServerMessageDecodeResult;

                final class ForbiddenOutcome {
                    HtspServerMessageDecodeResult outcome;
                }
            """.trimIndent(),
        )

        assertTrue(diagnostics, exitCode != 0)
        assertTrue(diagnostics, diagnostics.contains("HtspServerMessageDecodeResult"))
        assertTrue(diagnostics, diagnostics.contains("cannot find symbol", ignoreCase = true))
    }

    @Test
    fun productionDecoderIsNotJavaSourceApiWithoutNamingItsOutcome() {
        val (exitCode, diagnostics) = compileJavaSource(
            sourceName = "ForbiddenDecoder.java",
            sourceText = """
                import at.bernhardberger.tvheadend.htsp.GeneratedHtspServerMessageDispatchKt;

                final class ForbiddenDecoder {
                    Object decode() {
                        return GeneratedHtspServerMessageDispatchKt.decodeHtspServerMessage(
                            java.util.Collections.emptyMap()
                        );
                    }
                }
            """.trimIndent(),
        )

        assertTrue(diagnostics, exitCode != 0)
        assertTrue(diagnostics, diagnostics.contains("decodeHtspServerMessage"))
        assertTrue(diagnostics, diagnostics.contains("cannot find symbol", ignoreCase = true))
    }

    @Test
    fun internalDecodeResultSupportsAnExhaustiveProductionNamedWhen() {
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
                "${fields["method"]} must accept its evidenced partial/lower-version shape",
                true,
                decodeHtspServerMessage(fields) is HtspServerMessageDecoded,
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
            Class.forName("at.bernhardberger.tvheadend.htsp.HtspField")
        }
    }

    @Test
    fun everyFiniteDispatchBranchDecodesASourceValidMinimalFixture() {
        typedHtspServerMessageCatalogForTest().forEach { (method, messageType) ->
            val result = decodeHtspServerMessage(minimalFixture(method))
            assertTrue("$method must decode", result is HtspServerMessageDecoded)
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
            "expected malformed for $fields",
            decodeHtspServerMessage(fields) is HtspServerMessageMalformedKnownMessage,
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

    private fun compileJavaSource(sourceName: String, sourceText: String): Pair<Int, String> {
        val directory = Files.createTempDirectory("htsp-server-message-java-source").toFile()
        return try {
            val source = directory.resolve(sourceName).apply { writeText(sourceText) }
            val diagnostics = ByteArrayOutputStream()
            val exitCode = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                diagnostics,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                directory.absolutePath,
                source.absolutePath,
            )
            exitCode to diagnostics.toString(Charsets.UTF_8)
        } finally {
            directory.deleteRecursively()
        }
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
