package at.bernhardberger.tvheadend.htsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Modifier

class HtspProtocolCoreTest {
    @Test
    fun authenticationPolicyRequiresBothTrimmedCredentials() {
        assertTrue(HtspAuthenticationPolicy.shouldAuthenticate(" user ", " pass "))
        assertTrue(!HtspAuthenticationPolicy.shouldAuthenticate(null, null))
        assertTrue(!HtspAuthenticationPolicy.shouldAuthenticate("user", ""))
        assertTrue(!HtspAuthenticationPolicy.shouldAuthenticate("", "pass"))
        assertTrue(!HtspAuthenticationPolicy.shouldAuthenticate("  ", "  "))
    }

    @Test
    fun catalogContainsExactlyTheAssignedTypedRequests() {
        assertEquals(
            listOf(
                "getProfiles",
                "getDiskSpace",
                "getSysTime",
                "getChannel",
                "getEvent",
                "getEvents",
                "getDvrConfigs",
                "addDvrEntry",
                "updateDvrEntry",
                "stopDvrEntry",
                "cancelDvrEntry",
                "deleteDvrEntry",
                "getDvrCutpoints",
                "subscribe",
                "unsubscribe",
                "subscriptionChangeWeight",
                "subscriptionSeek",
                "subscriptionSpeed",
                "subscriptionLive",
                "subscriptionFilterStream",
            ),
            typedHtspRequestCatalog.map { it.method },
        )
        assertEquals(
            listOf(
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_RECORDER,
                HtspAccess.ACCESS_HTSP_RECORDER,
                HtspAccess.ACCESS_HTSP_RECORDER,
                HtspAccess.ACCESS_HTSP_RECORDER,
                HtspAccess.ACCESS_HTSP_RECORDER,
                HtspAccess.ACCESS_HTSP_RECORDER,
                HtspAccess.ACCESS_HTSP_RECORDER,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
                HtspAccess.ACCESS_HTSP_STREAMING,
            ),
            typedHtspRequestCatalog.map { it.access },
        )
        val requests: List<HtspRequest<*>> = listOf(
            GetProfilesRequest(),
            GetDiskSpaceRequest(),
            GetSysTimeRequest(),
            GetChannelRequest(0L),
            GetEventRequest(0L),
            GetEventsRequest(),
            GetDvrConfigsRequest(),
            AddDvrEntryRequest(AddDvrEntrySelector.Event(0L)),
            UpdateDvrEntryRequest(0L),
            StopDvrEntryRequest(0L),
            CancelDvrEntryRequest(0L),
            DeleteDvrEntryRequest(0L),
            GetDvrCutpointsRequest(0L),
            SubscribeRequest(0L, SubscribeChannel.Id(0L)),
            UnsubscribeRequest(0L),
            SubscriptionChangeWeightRequest(0L),
            SubscriptionSeekRequest(0L, SubscriptionSeekPosition.Time(0L)),
            SubscriptionSpeedRequest(0L, 0),
            SubscriptionLiveRequest(0L),
            SubscriptionFilterStreamRequest(0L),
        )
        assertEquals(
            typedHtspRequestCatalog.map { Triple(it.method, it.access, it.minimumProtocolVersion) },
            requests.map { Triple(it.method, it.access, it.minimumProtocolVersion) },
        )
    }

    @Test
    fun primitivePreflightsVersionAndClassifiesReplies() = runTest {
        val transport = FakeProtocolTransport(version = 13)
        val connection = HtspConnection.create(transport)

        assertSame(HtspResult.NotSupported, connection.call(GetChannelRequest(channelId = 7L)))
        assertEquals(0, transport.dispatches)

        transport.version = 44
        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1L))
        assertSame(HtspResult.AccessDenied, connection.call(GetChannelRequest(channelId = 7L)))
        assertEquals(1, transport.dispatches)

        transport.reply = HtspWireReply(linkedMapOf("error" to "server detail"))
        assertEquals(
            HtspResult.ServerError("server detail"),
            connection.call(GetChannelRequest(channelId = 7L)),
        )
        assertEquals(2, transport.dispatches)

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1))
        assertEquals(HtspResult.ServerError(), connection.call(GetChannelRequest(channelId = 7L)))
        assertEquals(3, transport.dispatches)

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 0L))
        assertEquals(HtspResult.ServerError(), connection.call(GetChannelRequest(channelId = 7L)))
        assertEquals(4, transport.dispatches)
    }

    @Test
    fun primitiveMapsSuccessTimeoutTransportAndAbsentGeneration() = runTest {
        val transport = FakeProtocolTransport(version = null)
        val connection = HtspConnection.create(transport)
        transport.reply = HtspWireReply(
            linkedMapOf("eventId" to 0xffff_ffffL, "start" to Long.MIN_VALUE, "stop" to Long.MAX_VALUE),
        )

        assertSame(HtspResult.NotSupported, connection.call(GetProfilesRequest()))
        assertEquals(0, transport.dispatches)

        val success = connection.call(GetEventRequest(eventId = 0xffff_ffffL))
        assertEquals(
            HtspResult.Ok(
                GetEventResponse(
                    HtspEvent(
                        eventId = 0xffff_ffffL,
                        channelId = null,
                        start = Long.MIN_VALUE,
                        stop = Long.MAX_VALUE,
                        title = null,
                        subtitle = null,
                        summary = null,
                        description = null,
                        categories = null,
                        keywords = null,
                        seriesLinkUri = null,
                        episodeUri = null,
                        contentType = null,
                        ageRating = null,
                        ratingLabel = null,
                        ratingIcon = null,
                        ratingAuthority = null,
                        ratingCountry = null,
                        starRating = null,
                        copyrightYear = null,
                        firstAired = null,
                        isNew = null,
                        seasonNumber = null,
                        seasonCount = null,
                        episodeNumber = null,
                        episodeCount = null,
                        partNumber = null,
                        partCount = null,
                        episodeOnscreen = null,
                        image = null,
                        dvrId = null,
                        nextEventId = null,
                    ),
                ),
            ),
            success,
        )
        assertEquals(1, transport.dispatches)

        transport.failure = HtspCallTimeoutException()
        assertSame(HtspResult.Timeout, connection.call(GetEventRequest(eventId = 1L)))
        transport.failure = IOException("closed")
        assertSame(HtspResult.TransportUnavailable, connection.call(GetEventRequest(eventId = 1L)))

        transport.failure = null
        transport.captureAvailable = false
        assertSame(HtspResult.TransportUnavailable, connection.call(GetEventRequest(eventId = 1L)))
        assertEquals(3, transport.dispatches)
    }

    @Test
    fun primitivePreservesCancellationIdentityAndOpaqueGeneration() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspConnection.create(transport)
        val firstGeneration = connection.generation
        assertEquals("HtspConnectionGeneration", firstGeneration.toString())

        val cancellation = CancellationException("caller cancellation")
        transport.failure = cancellation
        val failure = runCatching {
            connection.call(GetProfilesRequest())
        }.exceptionOrNull()
        assertSame(cancellation, failure)

        transport.failure = null
        transport.replace()
        assertNotSame(firstGeneration, connection.generation)
    }

    @Test
    fun primitiveRejectsAReplacedGenerationAsCancellation() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspConnection.create(transport)
        transport.replaceAfterDispatch = true

        val failure = runCatching {
            connection.call(GetProfilesRequest())
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, transport.dispatches)
    }

    @Test
    fun primitiveRejectsAReplacedGenerationBeforeMappingAnOwnedTimeout() = runTest {
        val transport = FakeProtocolTransport(version = 44).apply {
            failure = HtspCallTimeoutException()
            replaceBeforeFailure = true
        }

        val failure = runCatching {
            HtspConnection.create(transport).call(GetProfilesRequest())
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, transport.dispatches)
    }

    @Test
    fun primitiveRejectsAReplacedGenerationBeforeMappingATransportFailure() = runTest {
        val transport = FakeProtocolTransport(version = 44).apply {
            failure = IOException("closed")
            replaceBeforeFailure = true
        }

        val failure = runCatching {
            HtspConnection.create(transport).call(GetProfilesRequest())
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, transport.dispatches)
    }

    @Test
    fun publicRequestClassesExposeNoRawEnvelopeOrReplyMapperMethods() {
        val requestClasses = listOf(
            GetProfilesRequest::class.java,
            GetDiskSpaceRequest::class.java,
            GetSysTimeRequest::class.java,
            GetChannelRequest::class.java,
            GetEventRequest::class.java,
            GetEventsRequest::class.java,
            GetDvrConfigsRequest::class.java,
            AddDvrEntryRequest::class.java,
            UpdateDvrEntryRequest::class.java,
            StopDvrEntryRequest::class.java,
            CancelDvrEntryRequest::class.java,
            DeleteDvrEntryRequest::class.java,
            GetDvrCutpointsRequest::class.java,
            SubscribeRequest::class.java,
            UnsubscribeRequest::class.java,
            SubscriptionChangeWeightRequest::class.java,
            SubscriptionSeekRequest::class.java,
            SubscriptionSpeedRequest::class.java,
            SubscriptionLiveRequest::class.java,
            SubscriptionFilterStreamRequest::class.java,
        )

        val leakedMethods = requestClasses.flatMap { requestClass ->
            requestClass.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    (
                        method.name.contains("requestFields") ||
                            method.name.contains("decodeReply") ||
                            method.parameterTypes.any(Map::class.java::isAssignableFrom)
                        )
            }.map { method -> "${requestClass.simpleName}.${method.name}" }
        }

        assertEquals(emptyList<String>(), leakedMethods)
    }

    @Test
    fun requestShapesPreserveSelectorsRangesOrderAndOmission() = runTest {
        assertEquals(
            linkedMapOf("eventId" to 0xffff_ffffL, "configName" to ""),
            AddDvrEntryRequest(
                selector = AddDvrEntrySelector.Event(0xffff_ffffL),
                configName = "",
            ).let(HtspRequestCodecs::encode),
        )
        assertEquals(
            listOf("channelId", "start", "stop"),
            AddDvrEntryRequest(
                selector = AddDvrEntrySelector.ExplicitChannelTime(
                    channelId = 0L,
                    start = Long.MIN_VALUE,
                    stop = Long.MAX_VALUE,
                ),
            ).let(HtspRequestCodecs::encode).keys.toList(),
        )
        assertEquals(
            linkedMapOf("subscriptionId" to 1L, "channelName" to ""),
            HtspRequestCodecs.encode(SubscribeRequest(1L, SubscribeChannel.Name(""))),
        )
        assertEquals(
            linkedMapOf("subscriptionId" to 2L, "time" to Long.MIN_VALUE),
            HtspRequestCodecs.encode(
                SubscriptionSeekRequest(2L, SubscriptionSeekPosition.Time(Long.MIN_VALUE)),
            ),
        )
        assertEquals(
            linkedMapOf("subscriptionId" to 2L, "size" to Long.MAX_VALUE),
            HtspRequestCodecs.encode(
                SubscriptionSeekRequest(2L, SubscriptionSeekPosition.Size(Long.MAX_VALUE)),
            ),
        )

        val mutable = mutableListOf(2L, 2L, 1L)
        val filter = SubscriptionFilterStreamRequest(3L, enable = mutable, disable = emptyList())
        mutable.clear()
        assertEquals(listOf(2L, 2L, 1L), filter.enable)
        assertEquals(emptyList<Long>(), filter.disable)
        assertEquals(
            listOf("subscriptionId", "enable", "disable"),
            HtspRequestCodecs.encode(filter).keys.toList(),
        )

        val update = UpdateDvrEntryRequest(entryId = 7L, playPosition = 11L)
        assertEquals(7L, update.entryId)
        assertEquals(
            linkedMapOf("playposition" to 11L, "id" to 7L),
            HtspRequestCodecs.encode(update),
        )
        assertEquals(8L, StopDvrEntryRequest(entryId = 8L).entryId)
        assertEquals(9L, CancelDvrEntryRequest(entryId = 9L).entryId)
        assertEquals(10L, DeleteDvrEntryRequest(entryId = 10L).entryId)
        assertEquals(11L, GetDvrCutpointsRequest(entryId = 11L).entryId)
        assertEquals("profile", HtspProfile(profileUuid = "profile", name = "", comment = "").profileUuid)
        assertEquals(
            "dvr-config",
            HtspDvrConfig(dvrConfigUuid = "dvr-config", name = "", comment = "").dvrConfigUuid,
        )

        assertIllegalArgument { GetChannelRequest(-1L) }
        assertIllegalArgument { GetChannelRequest(0x1_0000_0000L) }

        val transport = FakeProtocolTransport(version = 5)
        assertSame(
            HtspResult.NotSupported,
            HtspConnection.create(transport).call(GetEventRequest(eventId = 1L, language = "")),
        )
        assertEquals(0, transport.dispatches)
        transport.reply = HtspWireReply(linkedMapOf("success" to 1L))
        val result = HtspConnection.create(transport).call(
            AddDvrEntryRequest(
                selector = AddDvrEntrySelector.ExplicitChannelTime(1L, 2L, 3L),
            ),
        )
        assertEquals(HtspResult.Ok(AddDvrEntryResponse(1L, null)), result)
        assertEquals(1, transport.dispatches)
        assertEquals("addDvrEntry", transport.lastMethod)
        assertEquals(linkedMapOf("channelId" to 1L, "start" to 2L, "stop" to 3L), transport.lastFields)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private class FakeProtocolTransport(
        var version: Int?,
    ) : HtspRequestTransport {
        private var current = capturedGeneration(version)
        var dispatches: Int = 0
        var replaceAfterDispatch: Boolean = false
        var replaceBeforeFailure: Boolean = false
        var captureAvailable: Boolean = true
        var failure: Exception? = null
        var lastMethod: String? = null
        var lastFields: LinkedHashMap<String, Any?>? = null
        var reply: HtspWireReply = HtspWireReply(
            linkedMapOf("profiles" to emptyList<Any?>()),
        )

        override fun captureGeneration(): HtspCapturedGeneration? =
            if (captureAvailable) current.copy(protocolVersion = version) else null

        override suspend fun dispatch(
            generation: HtspCapturedGeneration,
            method: String,
            fields: LinkedHashMap<String, Any?>,
        ): HtspWireReply {
            dispatches += 1
            lastMethod = method
            lastFields = LinkedHashMap(fields)
            failure?.let {
                if (replaceBeforeFailure) current = capturedGeneration(version)
                throw it
            }
            if (replaceAfterDispatch) current = capturedGeneration(version)
            return reply
        }

        override fun isCurrent(generation: HtspCapturedGeneration): Boolean =
            generation.token === current.token

        fun replace() {
            current = capturedGeneration(version)
        }

        private fun capturedGeneration(version: Int?): HtspCapturedGeneration =
            HtspCapturedGeneration(
                token = HtspConnectionGeneration.create(),
                protocolVersion = version,
                transportKey = Any(),
            )
    }
}
