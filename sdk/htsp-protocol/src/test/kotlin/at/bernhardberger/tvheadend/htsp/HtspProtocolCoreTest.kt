package at.bernhardberger.tvheadend.htsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Modifier

class HtspProtocolCoreTest {
    @Test
    fun boundedFileOperationsPreserveFiniteWireAndAttemptContracts() = runTest {
        val disconnected = createHtspConnection(Dispatchers.Unconfined)
        try {
            assertSame(HtspResult.TransportUnavailable, disconnected.fileOpen(file = ""))
            assertSame(HtspResult.TransportUnavailable, disconnected.fileRead(id = 0L, size = 0L))
            assertSame(HtspResult.TransportUnavailable, disconnected.fileClose(id = 0L))
            assertSame(HtspResult.TransportUnavailable, disconnected.fileSeek(id = 0L, offset = 0L))
        } finally {
            disconnected.close()
        }

        val pathBearingOpen = FileOpenRequest("//private/recording.ts")
        assertEquals("FileOpenRequest(file=<redacted>)", pathBearingOpen.toString())
        assertEquals(
            linkedMapOf("file" to "/imagecache/73"),
            HtspRequestCodecs.encode(FileOpenRequest("/imagecache/73")),
        )
        assertEquals(
            linkedMapOf("id" to 0xffff_ffffL, "size" to 0L),
            HtspRequestCodecs.encode(FileReadRequest(0xffff_ffffL, size = 0L)),
        )
        assertEquals(
            linkedMapOf("id" to 0L, "size" to 16_777_216L, "offset" to Long.MIN_VALUE),
            HtspRequestCodecs.encode(FileReadRequest(0L, size = 16_777_216L, offset = Long.MIN_VALUE)),
        )
        assertEquals(
            linkedMapOf("id" to 0xffff_ffffL),
            HtspRequestCodecs.encode(FileCloseRequest(0xffff_ffffL)),
        )
        assertEquals(
            linkedMapOf("id" to 0L, "offset" to Long.MIN_VALUE),
            HtspRequestCodecs.encode(FileSeekRequest(0L, offset = Long.MIN_VALUE)),
        )
        assertEquals(
            listOf("SEEK_SET", "SEEK_CUR", "SEEK_END"),
            FileSeekWhence.entries.map { whence ->
                HtspRequestCodecs.encode(FileSeekRequest(1L, 2L, whence))["whence"]
            },
        )
        listOf<() -> Unit>(
            { FileReadRequest(-1L, 0L) },
            { FileReadRequest(0x1_0000_0000L, 0L) },
            { FileReadRequest(0L, -1L) },
            { FileReadRequest(0L, 16_777_217L) },
            { FileCloseRequest(-1L) },
            { FileSeekRequest(0x1_0000_0000L, 0L) },
        ).forEach(::assertIllegalArgument)

        val transport = FakeProtocolTransport(version = 7)
        val connection = HtspTypedRequestCaller(transport)
        val requests: List<HtspRequest<*>> = listOf(
            FileOpenRequest(""),
            FileReadRequest(0L, 0L),
            FileCloseRequest(0L),
            FileSeekRequest(0L, Long.MAX_VALUE),
        )
        requests.forEach { request -> assertSame(HtspResult.NotSupported, connection.call(request)) }
        assertEquals(0, transport.dispatches)

        transport.version = 8
        transport.reply = HtspWireReply(
            linkedMapOf("id" to 0xffff_ffffL, "size" to 123L, "mtime" to Long.MIN_VALUE),
        )
        assertEquals(
            HtspResult.Ok(
                FileOpenResponse(
                    id = 0xffff_ffffL,
                    sizeBytes = 123L,
                    modifiedAtUnixSeconds = Long.MIN_VALUE,
                ),
            ),
            connection.call(FileOpenRequest("//dvrfile/1")),
        )
        assertEquals("fileOpen", transport.lastMethod)
        assertEquals(linkedMapOf("file" to "//dvrfile/1"), transport.lastFields)

        transport.reply = HtspWireReply(linkedMapOf("id" to 0L))
        assertEquals(
            HtspResult.Ok(FileOpenResponse(id = 0L, sizeBytes = null, modifiedAtUnixSeconds = null)),
            connection.call(FileOpenRequest("")),
        )

        listOf(
            linkedMapOf<String, Any?>(),
            linkedMapOf<String, Any?>("id" to 1L, "size" to 1L),
            linkedMapOf<String, Any?>("id" to 1L, "mtime" to 1L),
            linkedMapOf<String, Any?>("id" to -1L),
            linkedMapOf<String, Any?>("id" to 1, "size" to 1L, "mtime" to 1L),
            linkedMapOf<String, Any?>("id" to 1L, "size" to -1L, "mtime" to 1L),
        ).forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, connection.call(FileOpenRequest("")))
        }

        val mutableData = byteArrayOf(1, 2, 3)
        transport.reply = HtspWireReply(linkedMapOf("data" to mutableData))
        val read = connection.call(FileReadRequest(0L, 3L, offset = 0L))
        assertEquals(HtspResult.Ok(FileReadResponse(HtspBinary(byteArrayOf(1, 2, 3)))), read)
        mutableData[0] = 9
        val readData = ((read as HtspResult.Ok).value.data).toByteArray()
        readData[1] = 9
        assertEquals(HtspBinary(byteArrayOf(1, 2, 3)), read.value.data)
        assertEquals(linkedMapOf("id" to 0L, "size" to 3L, "offset" to 0L), transport.lastFields)

        transport.reply = HtspWireReply(linkedMapOf("data" to ByteArray(0)))
        assertEquals(
            HtspResult.Ok(FileReadResponse(HtspBinary(ByteArray(0)))),
            connection.call(FileReadRequest(0L, 0L)),
        )
        listOf(
            linkedMapOf<String, Any?>(),
            linkedMapOf<String, Any?>("data" to emptyList<Any?>()),
        ).forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, connection.call(FileReadRequest(0L, 1L)))
        }

        transport.reply = HtspWireReply(linkedMapOf("seq" to 7L))
        assertEquals(HtspResult.Ok(FileCloseResponse), connection.call(FileCloseRequest(0L)))
        transport.reply = HtspWireReply(linkedMapOf("unexpected" to 1L))
        assertSame(HtspResult.ServerError, connection.call(FileCloseRequest(0L)))

        transport.reply = HtspWireReply(linkedMapOf("offset" to Long.MAX_VALUE))
        assertEquals(
            HtspResult.Ok(FileSeekResponse(offset = Long.MAX_VALUE)),
            connection.call(FileSeekRequest(0L, Long.MIN_VALUE, FileSeekWhence.END)),
        )
        assertEquals(
            linkedMapOf("id" to 0L, "offset" to Long.MIN_VALUE, "whence" to "SEEK_END"),
            transport.lastFields,
        )
        listOf(
            linkedMapOf<String, Any?>(),
            linkedMapOf<String, Any?>("offset" to -1L),
            linkedMapOf<String, Any?>("offset" to 0),
        ).forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, connection.call(FileSeekRequest(0L, 0L)))
        }

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1L))
        assertSame(HtspResult.AccessDenied, connection.call(FileCloseRequest(0L)))
        transport.reply = HtspWireReply(linkedMapOf("error" to "synthetic rejection"))
        assertSame(HtspResult.ServerError, connection.call(FileReadRequest(0L, 1L)))

        val staleGeneration = connection.generation
        transport.replace()
        val staleFailure = runCatching {
            connection.call(FileOpenRequest(""), expectedGeneration = staleGeneration)
        }.exceptionOrNull()
        assertTrue(staleFailure is CancellationException)

        val cancellation = CancellationException("synthetic file operation cancellation")
        transport.failure = cancellation
        val cancellationFailure = runCatching { connection.call(FileSeekRequest(0L, 0L)) }.exceptionOrNull()
        assertSame(cancellation, cancellationFailure)
    }

    @Test
    fun subscriptionSkipPreservesSharedHandlerWireAndAttemptContracts() = runTest {
        val disconnected = createHtspConnection(Dispatchers.Unconfined)
        try {
            val canonicalPosition: SubscriptionSeekPosition = SubscriptionSeekPosition.Time(0L)
            assertSame(
                HtspResult.TransportUnavailable,
                disconnected.subscriptionSkip(
                    subscriptionId = 0L,
                    position = canonicalPosition,
                ),
            )
            assertSame(
                HtspResult.TransportUnavailable,
                disconnected.subscriptionSkip(
                    subscriptionId = 0L,
                    position = SubscriptionSeekPosition.Time(0L),
                ),
            )
            assertSame(
                HtspResult.TransportUnavailable,
                disconnected.subscriptionSkip(
                    subscriptionId = 0L,
                    position = SubscriptionSeekPosition.Size(0L),
                ),
            )
        } finally {
            disconnected.close()
        }

        assertEquals(
            linkedMapOf("subscriptionId" to 0L, "time" to Long.MIN_VALUE),
            HtspRequestCodecs.encode(
                SubscriptionSkipRequest(0L, SubscriptionSeekPosition.Time(Long.MIN_VALUE)),
            ),
        )
        assertEquals(
            linkedMapOf("subscriptionId" to 0xffff_ffffL, "size" to Long.MAX_VALUE),
            HtspRequestCodecs.encode(
                SubscriptionSkipRequest(0xffff_ffffL, SubscriptionSeekPosition.Size(Long.MAX_VALUE)),
            ),
        )
        assertEquals(
            linkedMapOf(
                "subscriptionId" to 1L,
                "time" to 2L,
                "absolute" to 0L,
            ),
            HtspRequestCodecs.encode(
                SubscriptionSkipRequest(1L, SubscriptionSeekPosition.Time(2L), absolute = 0L),
            ),
        )
        assertEquals(
            linkedMapOf(
                "subscriptionId" to 1L,
                "size" to -3L,
                "absolute" to 0xffff_ffffL,
            ),
            HtspRequestCodecs.encode(
                SubscriptionSkipRequest(
                    1L,
                    SubscriptionSeekPosition.Size(-3L),
                    absolute = 0xffff_ffffL,
                ),
            ),
        )
        listOf<() -> Unit>(
            { SubscriptionSkipRequest(-1L, SubscriptionSeekPosition.Time(0L)) },
            { SubscriptionSkipRequest(0x1_0000_0000L, SubscriptionSeekPosition.Size(0L)) },
            { SubscriptionSkipRequest(0L, SubscriptionSeekPosition.Time(0L), absolute = -1L) },
            {
                SubscriptionSkipRequest(
                    0L,
                    SubscriptionSeekPosition.Size(0L),
                    absolute = 0x1_0000_0000L,
                )
            },
        ).forEach(::assertIllegalArgument)

        val transport = FakeProtocolTransport(version = 8)
        val connection = HtspTypedRequestCaller(transport)
        val request = SubscriptionSkipRequest(0L, SubscriptionSeekPosition.Time(0L))
        assertSame(HtspResult.NotSupported, connection.call(request))
        assertEquals(0, transport.dispatches)

        transport.version = 9
        transport.reply = HtspWireReply(linkedMapOf("seq" to 7L))
        assertEquals(HtspResult.Ok(HtspEmptyResponse), connection.call(request))
        assertEquals("subscriptionSkip", transport.lastMethod)
        assertEquals(linkedMapOf("subscriptionId" to 0L, "time" to 0L), transport.lastFields)

        transport.reply = HtspWireReply(linkedMapOf())
        assertEquals(
            HtspResult.Ok(HtspEmptyResponse),
            connection.call(
                SubscriptionSkipRequest(2L, SubscriptionSeekPosition.Size(Long.MIN_VALUE)),
            ),
        )
        assertEquals(
            linkedMapOf("subscriptionId" to 2L, "size" to Long.MIN_VALUE),
            transport.lastFields,
        )

        transport.reply = HtspWireReply(linkedMapOf("unexpected" to 1L))
        assertSame(HtspResult.ServerError, connection.call(request))

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1L))
        assertSame(HtspResult.AccessDenied, connection.call(request))
        transport.reply = HtspWireReply(linkedMapOf("error" to "synthetic rejection"))
        assertSame(HtspResult.ServerError, connection.call(request))

        val staleGeneration = connection.generation
        transport.replace()
        val dispatchesBeforeStaleCall = transport.dispatches
        val staleFailure = runCatching {
            connection.call(request, expectedGeneration = staleGeneration)
        }.exceptionOrNull()
        assertTrue(staleFailure is CancellationException)
        assertEquals(dispatchesBeforeStaleCall, transport.dispatches)

        val cancellation = CancellationException("synthetic subscriptionSkip cancellation")
        transport.failure = cancellation
        val cancellationFailure = runCatching { connection.call(request) }.exceptionOrNull()
        assertSame(cancellation, cancellationFailure)
    }

    @Test
    fun fileStatPreservesFiniteSourceReplyAndAttemptContracts() = runTest {
        val disconnected = createHtspConnection(Dispatchers.Unconfined)
        try {
            assertSame(HtspResult.TransportUnavailable, disconnected.fileStat(id = 0L))
        } finally {
            disconnected.close()
        }

        assertEquals(
            linkedMapOf("id" to 0xffff_ffffL),
            HtspRequestCodecs.encode(FileStatRequest(0xffff_ffffL)),
        )
        assertIllegalArgument { FileStatRequest(-1L) }
        assertIllegalArgument { FileStatRequest(0x1_0000_0000L) }

        val transport = FakeProtocolTransport(version = 7)
        val connection = HtspTypedRequestCaller(transport)
        val request = FileStatRequest(0L)
        assertSame(HtspResult.NotSupported, connection.call(request))
        assertEquals(0, transport.dispatches)

        transport.version = 8
        transport.reply = HtspWireReply(
            linkedMapOf(
                "size" to 123L,
                "mtime" to -456L,
            ),
        )
        assertEquals(
            HtspResult.Ok(FileStatResponse(sizeBytes = 123L, modifiedAtUnixSeconds = -456L)),
            connection.call(request),
        )
        assertEquals("fileStat", transport.lastMethod)
        assertEquals(linkedMapOf("id" to 0L), transport.lastFields)

        transport.reply = HtspWireReply(linkedMapOf())
        assertEquals(
            HtspResult.Ok(FileStatResponse(sizeBytes = null, modifiedAtUnixSeconds = null)),
            connection.call(request),
        )

        listOf(
            linkedMapOf<String, Any?>("size" to 1L),
            linkedMapOf<String, Any?>("mtime" to 2L),
            linkedMapOf<String, Any?>("size" to 1, "mtime" to 2L),
            linkedMapOf<String, Any?>("size" to 1L, "mtime" to 2),
            linkedMapOf<String, Any?>("size" to -1L, "mtime" to Long.MIN_VALUE),
        ).forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, connection.call(request))
        }

        val staleGeneration = connection.generation
        transport.replace()
        val dispatchesBeforeStaleCall = transport.dispatches
        val staleFailure = runCatching {
            connection.call(request, expectedGeneration = staleGeneration)
        }.exceptionOrNull()
        assertTrue(staleFailure is CancellationException)
        assertEquals(dispatchesBeforeStaleCall, transport.dispatches)

        val cancellation = CancellationException("synthetic fileStat cancellation")
        transport.failure = cancellation
        val cancellationFailure = runCatching { connection.call(request) }.exceptionOrNull()
        assertSame(cancellation, cancellationFailure)
    }

    @Test
    fun getTicketUsesOneStrictSelectorAndRedactsCredentialBearingSuccess() = runTest {
        val channelSelector = GetTicketSelector.Channel(0L)
        val dvrSelector = GetTicketSelector.Dvr(0xffff_ffffL)

        assertEquals(
            linkedMapOf("channelId" to 0L),
            HtspRequestCodecs.encode(GetTicketRequest(channelSelector)),
        )
        assertEquals(
            linkedMapOf("dvrId" to 0xffff_ffffL),
            HtspRequestCodecs.encode(GetTicketRequest(dvrSelector)),
        )
        listOf<() -> Unit>(
            { GetTicketSelector.Channel(-1L) },
            { GetTicketSelector.Channel(0x1_0000_0000L) },
            { GetTicketSelector.Dvr(-1L) },
            { GetTicketSelector.Dvr(0x1_0000_0000L) },
        ).forEach(::assertIllegalArgument)

        val transport = FakeProtocolTransport(version = 4)
        val connection = HtspTypedRequestCaller(transport)
        val request = GetTicketRequest(channelSelector)
        assertSame(HtspResult.NotSupported, connection.call(request))
        assertEquals(0, transport.dispatches)

        transport.version = 5
        transport.reply = HtspWireReply(
            linkedMapOf(
                "path" to "wire-path-value",
                "ticket" to "wire-ticket-value",
            ),
        )
        val successful = connection.call(request)
        assertTrue(successful is HtspResult.Ok)
        val response = (successful as HtspResult.Ok).value
        assertEquals("wire-path-value", response.path)
        assertEquals("wire-ticket-value", response.ticket)
        assertNotEquals(response, GetTicketResponse("wire-path-value", "wire-ticket-value"))
        assertTrue(response.toString().contains("redacted", ignoreCase = true))
        assertTrue(!response.toString().contains("wire-path-value"))
        assertTrue(!response.toString().contains("wire-ticket-value"))
        assertTrue(response.javaClass.declaredMethods.none { method ->
            method.name == "copy" || method.name.startsWith("component")
        })

        transport.reply = HtspWireReply(linkedMapOf("path" to "", "ticket" to ""))
        val emptySuccessful = connection.call(GetTicketRequest(dvrSelector))
        assertTrue(emptySuccessful is HtspResult.Ok)
        assertEquals("", (emptySuccessful as HtspResult.Ok).value.path)
        assertEquals("", emptySuccessful.value.ticket)

        listOf(
            linkedMapOf<String, Any?>(),
            linkedMapOf<String, Any?>("path" to "wire-path-value"),
            linkedMapOf<String, Any?>("ticket" to "wire-ticket-value"),
            linkedMapOf<String, Any?>("path" to 1L, "ticket" to "wire-ticket-value"),
            linkedMapOf<String, Any?>("path" to "wire-path-value", "ticket" to 1L),
        ).forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, connection.call(request))
        }

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1L))
        assertSame(HtspResult.AccessDenied, connection.call(request))
        transport.reply = HtspWireReply(linkedMapOf("error" to "synthetic rejection"))
        assertSame(HtspResult.ServerError, connection.call(request))
    }

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
                "enableAsyncMetadata",
                "getChannel",
                "getEvent",
                "getEvents",
                "epgQuery",
                "getEpgObject",
                "getDvrConfigs",
                "addDvrEntry",
                "updateDvrEntry",
                "stopDvrEntry",
                "cancelDvrEntry",
                "deleteDvrEntry",
                "addAutorecEntry",
                "updateAutorecEntry",
                "deleteAutorecEntry",
                "addTimerecEntry",
                "updateTimerecEntry",
                "deleteTimerecEntry",
                "getDvrCutpoints",
                "getTicket",
                "subscribe",
                "unsubscribe",
                "subscriptionChangeWeight",
                "subscriptionSeek",
                "subscriptionSkip",
                "subscriptionSpeed",
                "subscriptionLive",
                "subscriptionFilterStream",
                "fileOpen",
                "fileRead",
                "fileClose",
                "fileStat",
                "fileSeek",
            ),
            typedHtspRequestCatalog.map { it.method },
        )
        assertEquals(
            List(9) { HtspAccess.ACCESS_HTSP_STREAMING } +
                List(13) { HtspAccess.ACCESS_HTSP_RECORDER } +
                List(9) { HtspAccess.ACCESS_HTSP_STREAMING } +
                List(5) { HtspAccess.ACCESS_HTSP_RECORDER },
            typedHtspRequestCatalog.map { it.access },
        )
        val requests: List<HtspRequest<*>> = listOf(
            GetProfilesRequest(),
            GetDiskSpaceRequest(),
            GetSysTimeRequest(),
            EnableAsyncMetadataRequest(),
            GetChannelRequest(0L),
            GetEventRequest(0L),
            GetEventsRequest(),
            EpgQueryRequest(""),
            GetEpgObjectRequest(0L),
            GetDvrConfigsRequest(),
            AddDvrEntryRequest(AddDvrEntrySelector.Event(0L)),
            UpdateDvrEntryRequest(0L),
            StopDvrEntryRequest(0L),
            CancelDvrEntryRequest(0L),
            DeleteDvrEntryRequest(0L),
            AddAutorecEntryRequest(""),
            UpdateAutorecEntryRequest(""),
            DeleteAutorecEntryRequest(""),
            AddTimerecEntryRequest(""),
            UpdateTimerecEntryRequest(""),
            DeleteTimerecEntryRequest(""),
            GetDvrCutpointsRequest(0L),
            GetTicketRequest(GetTicketSelector.Channel(0L)),
            SubscribeRequest(0L, SubscribeChannel.Id(0L)),
            UnsubscribeRequest(0L),
            SubscriptionChangeWeightRequest(0L),
            SubscriptionSeekRequest(0L, SubscriptionSeekPosition.Time(0L)),
            SubscriptionSkipRequest(0L, SubscriptionSeekPosition.Time(0L)),
            SubscriptionSpeedRequest(0L, 0),
            SubscriptionLiveRequest(0L),
            SubscriptionFilterStreamRequest(0L),
            FileOpenRequest(""),
            FileReadRequest(0L, 0L),
            FileCloseRequest(0L),
            FileStatRequest(0L),
            FileSeekRequest(0L, 0L),
        )
        assertEquals(
            typedHtspRequestCatalog.map { Triple(it.method, it.access, it.minimumProtocolVersion) },
            requests.map { Triple(it.method, it.access, it.minimumProtocolVersion) },
        )
    }

    @Test
    fun generatedExtensionsUseOnlyTheFactoryInternalRequestCapability() = runTest {
        val factoryConnection = createHtspConnection(Dispatchers.Unconfined)
        assertTrue(factoryConnection is HtspTypedRequestCapability)

        val unsupportedCustomConnection = object : HtspConnection by factoryConnection {}
        assertSame(HtspResult.TransportUnavailable, unsupportedCustomConnection.getProfiles())

        unsupportedCustomConnection.close()
    }

    @Test
    fun primitivePreflightsVersionAndClassifiesReplies() = runTest {
        val transport = FakeProtocolTransport(version = 13)
        val connection = HtspTypedRequestCaller(transport)

        assertSame(HtspResult.NotSupported, connection.call(GetChannelRequest(channelId = 7L)))
        assertEquals(0, transport.dispatches)

        transport.version = 44
        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1L))
        assertSame(HtspResult.AccessDenied, connection.call(GetChannelRequest(channelId = 7L)))
        assertEquals(1, transport.dispatches)

        transport.reply = HtspWireReply(linkedMapOf("error" to "server detail"))
        assertEquals(
            HtspResult.ServerError,
            connection.call(GetChannelRequest(channelId = 7L)),
        )
        assertEquals(2, transport.dispatches)

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1))
        assertEquals(HtspResult.ServerError, connection.call(GetChannelRequest(channelId = 7L)))
        assertEquals(3, transport.dispatches)

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 0L))
        assertEquals(HtspResult.ServerError, connection.call(GetChannelRequest(channelId = 7L)))
        assertEquals(4, transport.dispatches)
    }

    @Test
    fun globalFailuresClassifyConnectionLimitStrictlyAndRemainPayloadFree() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspTypedRequestCaller(transport)

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1L, "connlimit" to 1L))
        assertSame(HtspResult.ConnectionLimit, connection.call(GetChannelRequest(7L)))

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1L))
        assertSame(HtspResult.AccessDenied, connection.call(GetChannelRequest(7L)))

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to 1L, "connlimit" to "1"))
        assertSame(HtspResult.ServerError, connection.call(GetChannelRequest(7L)))

        transport.reply = HtspWireReply(linkedMapOf("noaccess" to (1 as Any)))
        assertSame(HtspResult.ServerError, connection.call(GetChannelRequest(7L)))

        val failures: List<HtspFailure> = listOf(
            HtspResult.ServerError,
            HtspResult.AccessDenied,
            HtspResult.ConnectionLimit,
            HtspResult.Timeout,
            HtspResult.TransportUnavailable,
            HtspResult.NotSupported,
        )
        failures.forEach { failure ->
            assertTrue(
                "Failure must not retain payload fields: ${failure.javaClass.declaredFields.toList()}",
                failure.javaClass.declaredFields.all { field -> Modifier.isStatic(field.modifiers) },
            )
        }
        assertTrue(HtspResult::class.java.declaredClasses.none { nested -> nested.simpleName == "Conflict" })
    }

    @Test
    fun methodNotFoundIsNotSupportedWhileOrdinaryErrorsRemainServerError() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspTypedRequestCaller(transport)

        transport.reply = HtspWireReply(linkedMapOf("error" to "Unknown method: getChannel"))
        assertSame(HtspResult.NotSupported, connection.call(GetChannelRequest(7L)))

        transport.reply = HtspWireReply(linkedMapOf("error" to "ordinary server detail"))
        assertSame(HtspResult.ServerError, connection.call(GetChannelRequest(7L)))

        transport.reply = HtspWireReply(linkedMapOf("error" to 7L))
        assertSame(HtspResult.ServerError, connection.call(GetChannelRequest(7L)))
    }

    @Test
    fun dvrMutationRepliesPreserveOptionalWireErrorsWithoutInventingSuccess() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspTypedRequestCaller(transport)

        transport.reply = HtspWireReply(linkedMapOf("error" to "add exact detail"))
        assertEquals(
            HtspResult.Ok(AddDvrEntryResponse(success = null, entryId = null, error = "add exact detail")),
            connection.call(AddDvrEntryRequest(AddDvrEntrySelector.Event(1L))),
        )
        transport.reply = HtspWireReply(linkedMapOf("error" to "update exact detail"))
        assertEquals(
            HtspResult.Ok(UpdateDvrEntryResponse(success = null, error = "update exact detail")),
            connection.call(UpdateDvrEntryRequest(1L)),
        )
        transport.reply = HtspWireReply(linkedMapOf("error" to "stop exact detail"))
        assertEquals(
            HtspResult.Ok(StopDvrEntryResponse(success = null, error = "stop exact detail")),
            connection.call(StopDvrEntryRequest(1L)),
        )
        transport.reply = HtspWireReply(linkedMapOf("error" to "cancel exact detail"))
        assertEquals(
            HtspResult.Ok(CancelDvrEntryResponse(success = null, error = "cancel exact detail")),
            connection.call(CancelDvrEntryRequest(1L)),
        )
        transport.reply = HtspWireReply(linkedMapOf("success" to 1L, "error" to "delete exact detail"))
        assertEquals(
            HtspResult.Ok(DeleteDvrEntryResponse(success = 1L, error = "delete exact detail")),
            connection.call(DeleteDvrEntryRequest(1L)),
        )

        transport.reply = HtspWireReply(linkedMapOf())
        assertSame(
            HtspResult.ServerError,
            connection.call(DeleteDvrEntryRequest(1L)),
        )
        transport.reply = HtspWireReply(linkedMapOf("error" to 1L))
        assertSame(
            HtspResult.ServerError,
            connection.call(DeleteDvrEntryRequest(1L)),
        )
    }

    @Test
    fun addDvrEntryReplyUsesStrictIdThenCompatibilityDvrId() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspTypedRequestCaller(transport)
        val request = AddDvrEntryRequest(AddDvrEntrySelector.Event(1L))

        listOf(
            linkedMapOf("success" to 1L, "id" to 11L) to 11L,
            linkedMapOf("success" to 1L, "dvrId" to 12L) to 12L,
            linkedMapOf("success" to 1L, "id" to 13L, "dvrId" to 14L) to 13L,
            linkedMapOf("success" to 1L, "id" to 15L, "dvrId" to "ignored") to 15L,
        ).forEach { (fields, expectedEntryId) ->
            transport.reply = HtspWireReply(fields)
            assertEquals(
                HtspResult.Ok(AddDvrEntryResponse(1L, expectedEntryId, null)),
                connection.call(request),
            )
        }

        listOf(
            linkedMapOf("success" to 1L, "id" to "11", "dvrId" to 12L),
            linkedMapOf("success" to 1L, "dvrId" to -1L),
            linkedMapOf("success" to 1L, "dvrId" to 0x1_0000_0000L),
        ).forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, connection.call(request))
        }
    }

    @Test
    fun recordingRuleRequestsPreserveExactFamilyShapesSelectorsVersionsAndRanges() {
        assertEquals(
            linkedMapOf("title" to ""),
            HtspRequestCodecs.encode(AddAutorecEntryRequest(title = "")),
        )
        assertEquals(
            linkedMapOf("id" to "", "title" to "updated"),
            HtspRequestCodecs.encode(UpdateAutorecEntryRequest(id = "", title = "updated")),
        )
        assertEquals(
            linkedMapOf("id" to ""),
            HtspRequestCodecs.encode(DeleteAutorecEntryRequest(id = "")),
        )
        assertEquals(
            linkedMapOf("title" to ""),
            HtspRequestCodecs.encode(AddTimerecEntryRequest(title = "")),
        )
        assertEquals(
            linkedMapOf("id" to "", "title" to "updated"),
            HtspRequestCodecs.encode(UpdateTimerecEntryRequest(id = "", title = "updated")),
        )
        assertEquals(
            linkedMapOf("id" to ""),
            HtspRequestCodecs.encode(DeleteTimerecEntryRequest(id = "")),
        )

        val autorec = AddAutorecEntryRequest(
            title = "title",
            channel = HtspRecordingRuleChannel.Id(0xffff_ffffL),
            minDurationSeconds = 0L,
            maxDurationSeconds = 0xffff_ffffL,
            fullText = 0xffff_ffffL,
            mergeText = 0L,
            duplicateDetection = 0xffff_ffffL,
            maximumRecordingCount = 0L,
            broadcastType = 0xffff_ffffL,
            startExtraMinutes = Long.MIN_VALUE,
            stopExtraMinutes = Long.MAX_VALUE,
            seriesLinkUri = "",
            approximateStartMinutesSinceMidnight = Int.MIN_VALUE,
            startMinutesSinceMidnight = -1,
            startWindowEndMinutesSinceMidnight = Int.MAX_VALUE,
            enabled = false,
            retentionDays = 0L,
            removalDays = 0xffff_ffffL,
            priority = 0xffff_ffffL,
            name = "",
            comment = "",
            directory = "",
            configName = "",
            daysOfWeekMask = 0xffff_ffffL,
        )
        assertEquals(
            linkedMapOf(
                "title" to "title",
                "channelId" to 0xffff_ffffL,
                "minduration" to 0L,
                "maxduration" to 0xffff_ffffL,
                "fulltext" to 0xffff_ffffL,
                "mergetext" to 0L,
                "dupDetect" to 0xffff_ffffL,
                "maxCount" to 0L,
                "broadcastType" to 0xffff_ffffL,
                "startExtra" to Long.MIN_VALUE,
                "stopExtra" to Long.MAX_VALUE,
                "serieslinkUri" to "",
                "approxTime" to Int.MIN_VALUE,
                "start" to -1,
                "startWindow" to Int.MAX_VALUE,
                "enabled" to 0L,
                "retention" to 0L,
                "removal" to 0xffff_ffffL,
                "priority" to 0xffff_ffffL,
                "name" to "",
                "comment" to "",
                "directory" to "",
                "configName" to "",
                "daysOfWeek" to 0xffff_ffffL,
            ),
            HtspRequestCodecs.encode(autorec),
        )
        assertEquals(42, autorec.minimumProtocolVersion)
        assertEquals(25, AddAutorecEntryRequest("x", HtspRecordingRuleChannel.Any).minimumProtocolVersion)
        assertEquals(13, AddAutorecEntryRequest("x", HtspRecordingRuleChannel.Id(1L)).minimumProtocolVersion)
        assertEquals(18, AddAutorecEntryRequest("x", name = "").minimumProtocolVersion)
        assertEquals(19, AddAutorecEntryRequest("x", enabled = true).minimumProtocolVersion)
        assertEquals(20, AddAutorecEntryRequest("x", fullText = 0L).minimumProtocolVersion)
        assertEquals(39, UpdateAutorecEntryRequest("x", broadcastType = 0L).minimumProtocolVersion)
        assertEquals(42, UpdateAutorecEntryRequest("x", comment = "").minimumProtocolVersion)

        assertEquals(
            linkedMapOf(
                "title" to "timed",
                "channelId" to -1L,
                "start" to 0xffff_ffffL,
                "stop" to 0L,
                "enabled" to 1L,
                "retention" to 0xffff_ffffL,
                "removal" to 0L,
                "priority" to 0xffff_ffffL,
                "name" to "",
                "comment" to "",
                "directory" to "",
                "configName" to "",
                "daysOfWeek" to 0xffff_ffffL,
            ),
            HtspRequestCodecs.encode(
                AddTimerecEntryRequest(
                    title = "timed",
                    channel = HtspRecordingRuleChannel.Any,
                    startMinutesSinceMidnight = 0xffff_ffffL,
                    stopMinutesSinceMidnight = 0L,
                    enabled = true,
                    retentionDays = 0xffff_ffffL,
                    removalDays = 0L,
                    priority = 0xffff_ffffL,
                    name = "",
                    comment = "",
                    directory = "",
                    configName = "",
                    daysOfWeekMask = 0xffff_ffffL,
                ),
            ),
        )
        assertEquals(18, AddTimerecEntryRequest("x").minimumProtocolVersion)
        assertEquals(19, AddTimerecEntryRequest("x", directory = "").minimumProtocolVersion)
        assertEquals(25, UpdateTimerecEntryRequest("x").minimumProtocolVersion)
        assertEquals(42, UpdateTimerecEntryRequest("x", comment = "").minimumProtocolVersion)

        listOf<() -> Unit>(
            { HtspRecordingRuleChannel.Id(-1L) },
            { HtspRecordingRuleChannel.Id(0x1_0000_0000L) },
            { AddAutorecEntryRequest("x", minDurationSeconds = -1L) },
            { AddAutorecEntryRequest("x", maxDurationSeconds = 0x1_0000_0000L) },
            { AddAutorecEntryRequest("x", fullText = -1L) },
            { AddAutorecEntryRequest("x", mergeText = 0x1_0000_0000L) },
            { AddAutorecEntryRequest("x", duplicateDetection = -1L) },
            { AddAutorecEntryRequest("x", maximumRecordingCount = 0x1_0000_0000L) },
            { AddAutorecEntryRequest("x", broadcastType = -1L) },
            { AddAutorecEntryRequest("x", retentionDays = 0x1_0000_0000L) },
            { AddAutorecEntryRequest("x", removalDays = -1L) },
            { AddAutorecEntryRequest("x", priority = 0x1_0000_0000L) },
            { AddAutorecEntryRequest("x", daysOfWeekMask = -1L) },
            { AddTimerecEntryRequest("x", startMinutesSinceMidnight = -1L) },
            { AddTimerecEntryRequest("x", stopMinutesSinceMidnight = 0x1_0000_0000L) },
        ).forEach(::assertIllegalArgument)
    }

    @Test
    fun recordingRuleRepliesAreStrictFinitePayloadFreeOutcomes() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspTypedRequestCaller(transport)

        transport.reply = HtspWireReply(linkedMapOf("success" to 1L, "id" to "autorec-id"))
        assertEquals(
            HtspResult.Ok(AddAutorecEntryResponse("autorec-id")),
            connection.call(AddAutorecEntryRequest("")),
        )
        transport.reply = HtspWireReply(linkedMapOf("success" to 1L, "id" to "timerec-id"))
        assertEquals(
            HtspResult.Ok(AddTimerecEntryResponse("timerec-id")),
            connection.call(AddTimerecEntryRequest("")),
        )
        val acknowledgements = listOf(
            UpdateAutorecEntryRequest("") to UpdateAutorecEntryResponse,
            DeleteAutorecEntryRequest("") to DeleteAutorecEntryResponse,
            UpdateTimerecEntryRequest("") to UpdateTimerecEntryResponse,
            DeleteTimerecEntryRequest("") to DeleteTimerecEntryResponse,
        )
        acknowledgements.forEach { (request, response) ->
            transport.reply = HtspWireReply(linkedMapOf("success" to 1L))
            assertEquals(HtspResult.Ok(response), connection.call(request))
        }

        val malformedAdds = listOf(
            linkedMapOf<String, Any?>(),
            linkedMapOf("success" to 0L, "id" to "id"),
            linkedMapOf("success" to 2L, "id" to "id"),
            linkedMapOf("success" to 1, "id" to "id"),
            linkedMapOf<String, Any?>("success" to 1L),
            linkedMapOf("success" to 1L, "id" to 1L),
        )
        malformedAdds.forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, connection.call(AddAutorecEntryRequest("x")))
        }
        listOf<Any?>(null, 0L, 2L, 1).forEach { success ->
            transport.reply = if (success == null) {
                HtspWireReply(linkedMapOf())
            } else {
                HtspWireReply(linkedMapOf("success" to success))
            }
            assertSame(HtspResult.ServerError, connection.call(DeleteTimerecEntryRequest("x")))
        }
        transport.reply = HtspWireReply(linkedMapOf("error" to "private server detail", "success" to 0L))
        assertSame(HtspResult.ServerError, connection.call(AddTimerecEntryRequest("x")))

        transport.version = 24
        val before = transport.dispatches
        assertSame(HtspResult.NotSupported, connection.call(UpdateAutorecEntryRequest("x")))
        assertEquals(before, transport.dispatches)
    }

    @Test
    fun enableAsyncMetadataRequestPreservesFieldsAndOnlySelectedFieldsRequireVersionSix() = runTest {
        assertEquals(linkedMapOf<String, Any?>(), HtspRequestCodecs.encode(EnableAsyncMetadataRequest()))
        assertEquals(null, EnableAsyncMetadataRequest().minimumProtocolVersion)
        assertEquals(
            linkedMapOf(
                "epg" to 0xffff_ffffL,
                "lastUpdate" to Long.MIN_VALUE,
                "epgMaxTime" to Long.MAX_VALUE,
                "language" to "",
            ),
            HtspRequestCodecs.encode(
                EnableAsyncMetadataRequest(
                    epg = 0xffff_ffffL,
                    lastUpdate = Long.MIN_VALUE,
                    epgMaxTime = Long.MAX_VALUE,
                    language = "",
                ),
            ),
        )
        assertEquals(6, EnableAsyncMetadataRequest(epg = 0L).minimumProtocolVersion)

        val transport = FakeProtocolTransport(version = 5).apply {
            reply = HtspWireReply(linkedMapOf())
        }
        val caller = HtspTypedRequestCaller(transport)
        assertEquals(HtspResult.Ok(HtspEmptyResponse), caller.call(EnableAsyncMetadataRequest()))
        assertSame(HtspResult.NotSupported, caller.call(EnableAsyncMetadataRequest(language = "")))
        assertEquals(1, transport.dispatches)
    }

    @Test
    fun epgQueryPreservesWireShapeVersionsAndStrictSelectedReplyAlternative() = runTest {
        assertEquals(
            linkedMapOf("query" to ""),
            HtspRequestCodecs.encode(EpgQueryRequest(query = "")),
        )
        assertEquals(4, EpgQueryRequest(query = "").minimumProtocolVersion)
        assertEquals(
            linkedMapOf(
                "query" to "needle",
                "channelId" to 0xffff_ffffL,
                "tagId" to 0L,
                "contentType" to 1L,
                "language" to "",
                "fulltext" to false,
                "mergetext" to true,
                "full" to 2L,
                "minduration" to 0L,
                "maxduration" to 0xffff_ffffL,
            ),
            HtspRequestCodecs.encode(
                EpgQueryRequest(
                    query = "needle",
                    channelId = 0xffff_ffffL,
                    tagId = 0L,
                    contentType = 1L,
                    language = "",
                    fullText = false,
                    mergeText = true,
                    full = 2L,
                    minDurationSeconds = 0L,
                    maxDurationSeconds = 0xffff_ffffL,
                ),
            ),
        )
        assertEquals(4, EpgQueryRequest("q", fullText = false, mergeText = true, full = 1L).minimumProtocolVersion)
        assertEquals(6, EpgQueryRequest("q", language = "").minimumProtocolVersion)
        assertEquals(13, EpgQueryRequest("q", minDurationSeconds = 0L).minimumProtocolVersion)
        assertEquals(13, EpgQueryRequest("q", maxDurationSeconds = 0L, language = "").minimumProtocolVersion)

        val invalidU32Requests = listOf<() -> Unit>(
            { EpgQueryRequest("q", channelId = -1L) },
            { EpgQueryRequest("q", tagId = 0x1_0000_0000L) },
            { EpgQueryRequest("q", contentType = -1L) },
            { EpgQueryRequest("q", full = 0x1_0000_0000L) },
            { EpgQueryRequest("q", minDurationSeconds = -1L) },
            { EpgQueryRequest("q", maxDurationSeconds = 0x1_0000_0000L) },
        )
        invalidU32Requests.forEach(::assertIllegalArgument)

        val transport = FakeProtocolTransport(version = 44)
        val caller = HtspTypedRequestCaller(transport)
        transport.reply = HtspWireReply(linkedMapOf("eventIds" to listOf(0L, 0xffff_ffffL)))
        assertEquals(
            HtspResult.Ok(EpgQueryResponse.EventIds(listOf(0L, 0xffff_ffffL))),
            caller.call(EpgQueryRequest("q")),
        )
        transport.reply = HtspWireReply(
            linkedMapOf(
                "events" to listOf(
                    linkedMapOf(
                        "eventId" to 7L,
                        "start" to Long.MIN_VALUE,
                        "stop" to Long.MAX_VALUE,
                    ),
                ),
                "ignored" to "envelope",
            ),
        )
        val fullResult = caller.call(EpgQueryRequest("q", full = 2L))
        assertTrue(fullResult is HtspResult.Ok)
        val fullResponse = (fullResult as HtspResult.Ok).value
        assertTrue(fullResponse is EpgQueryResponse.Events)
        assertEquals(7L, (fullResponse as EpgQueryResponse.Events).events.single().eventId)
        assertEquals(Long.MIN_VALUE, fullResponse.events.single().start)
        assertEquals(Long.MAX_VALUE, fullResponse.events.single().stop)

        transport.reply = HtspWireReply(linkedMapOf())
        assertEquals(
            HtspResult.Ok(EpgQueryResponse.EventIds(emptyList())),
            caller.call(EpgQueryRequest("q", full = 0L)),
        )
        assertEquals(
            HtspResult.Ok(EpgQueryResponse.Events(emptyList())),
            caller.call(EpgQueryRequest("q", full = 1L)),
        )

        val malformedIdReplies = listOf(
            linkedMapOf<String, Any?>("events" to emptyList<Any?>()),
            linkedMapOf("eventIds" to emptyList<Any?>(), "events" to emptyList<Any?>()),
            linkedMapOf<String, Any?>("eventIds" to "not-a-list"),
            linkedMapOf<String, Any?>("eventIds" to listOf(1)),
            linkedMapOf<String, Any?>("eventIds" to listOf(-1L)),
            linkedMapOf<String, Any?>("eventIds" to listOf(0x1_0000_0000L)),
        )
        malformedIdReplies.forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, caller.call(EpgQueryRequest("q")))
        }
        val malformedEventReplies = listOf(
            linkedMapOf<String, Any?>("eventIds" to emptyList<Any?>()),
            linkedMapOf("eventIds" to emptyList<Any?>(), "events" to emptyList<Any?>()),
            linkedMapOf<String, Any?>("events" to "not-a-list"),
            linkedMapOf<String, Any?>("events" to listOf(1L)),
            linkedMapOf<String, Any?>(
                "events" to listOf(
                    linkedMapOf<String, Any?>("eventId" to 1L, "start" to 2, "stop" to 3L),
                ),
            ),
            linkedMapOf<String, Any?>("events" to listOf(linkedMapOf("eventId" to -1L, "start" to 2L, "stop" to 3L))),
        )
        malformedEventReplies.forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, caller.call(EpgQueryRequest("q", full = 1L)))
        }
    }

    @Test
    fun getEpgObjectPreservesTypedSelectorDefaultOmissionAndU32Validation() {
        assertEquals(
            linkedMapOf("id" to 0xffff_ffffL, "type" to 1L),
            HtspRequestCodecs.encode(GetEpgObjectRequest(id = 0xffff_ffffL)),
        )
        assertEquals(
            linkedMapOf("id" to 0L),
            HtspRequestCodecs.encode(GetEpgObjectRequest(id = 0L, objectType = null)),
        )
        assertEquals(HtspEpgObjectType.BROADCAST, GetEpgObjectRequest(1L).objectType)
        assertEquals(null, GetEpgObjectRequest(1L).minimumProtocolVersion)

        assertIllegalArgument { GetEpgObjectRequest(-1L) }
        assertIllegalArgument { GetEpgObjectRequest(0x1_0000_0000L) }
    }

    @Test
    fun getEpgObjectDecodesTheCompleteFiniteBroadcastAndIgnoresOpaqueCredits() = runTest {
        val transport = FakeProtocolTransport(version = 1)
        val connection = HtspTypedRequestCaller(transport)
        transport.reply = HtspWireReply(
            linkedMapOf(
                "id" to 42L,
                "tp" to 1L,
                "up" to Long.MIN_VALUE,
                "start" to -1L,
                "stop" to Long.MAX_VALUE,
                "gr" to "",
                "ch" to "channel-uuid",
                "eid" to 0xffff_ffffL,
                "xeid" to "external-id",
                "is_wd" to 1L,
                "is_hd" to 1L,
                "is_bw" to 1L,
                "is_de" to 1L,
                "is_st" to 1L,
                "is_ad" to 1L,
                "is_n" to 1L,
                "is_r" to 1L,
                "lines" to 0L,
                "aspect" to 0xffff_ffffL,
                "star" to 3L,
                "age" to 12L,
                "ratlab" to "rating-label",
                "img" to "image-ref",
                "tit" to linkedMapOf("eng" to "Title", "deu" to "Titel"),
                "sti" to linkedMapOf("eng" to "Subtitle"),
                "sum" to linkedMapOf("eng" to "Summary"),
                "des" to linkedMapOf("eng" to "Description"),
                "epn" to linkedMapOf(
                    "enum" to 1L,
                    "ecnt" to 2L,
                    "snum" to 3L,
                    "scnt" to 4L,
                    "pnum" to 5L,
                    "pcnt" to 6L,
                    "text" to "S03E01",
                ),
                "genre" to listOf(0L, 0xffff_ffffL, 0L),
                "cyear" to 0xffff_ffffL,
                "fair" to Long.MIN_VALUE,
                "cat" to listOf("documentary", "news"),
                "key" to emptyList<String>(),
                "slink" to "series-link",
                "elink" to "episode-link",
                "cred" to linkedMapOf("unbounded" to listOf(1L, "opaque")),
            ),
        )

        assertEquals(
            HtspResult.Ok(
                GetEpgObjectResponse(
                    broadcast = HtspEpgBroadcastObject(
                        id = 42L,
                        updatedUnixSeconds = Long.MIN_VALUE,
                        startUnixSeconds = -1L,
                        stopUnixSeconds = Long.MAX_VALUE,
                        grabber = "",
                        channelUuid = "channel-uuid",
                        eventId = 0xffff_ffffL,
                        externalEventId = "external-id",
                        widescreen = true,
                        highDefinition = true,
                        blackAndWhite = true,
                        deafSigned = true,
                        subtitled = true,
                        audioDescribed = true,
                        isNew = true,
                        isRepeat = true,
                        lines = 0L,
                        aspectRatio = 0xffff_ffffL,
                        starRating = 3L,
                        ageRating = 12L,
                        ratingLabel = "rating-label",
                        image = "image-ref",
                        titles = mapOf("eng" to "Title", "deu" to "Titel"),
                        subtitles = mapOf("eng" to "Subtitle"),
                        summaries = mapOf("eng" to "Summary"),
                        descriptions = mapOf("eng" to "Description"),
                        episodeNumber = HtspEpgEpisodeNumber(
                            episodeNumber = 1L,
                            episodeCount = 2L,
                            seasonNumber = 3L,
                            seasonCount = 4L,
                            partNumber = 5L,
                            partCount = 6L,
                            text = "S03E01",
                        ),
                        genres = listOf(0L, 0xffff_ffffL, 0L),
                        copyrightYear = 0xffff_ffffL,
                        firstAiredUnixSeconds = Long.MIN_VALUE,
                        categories = listOf("documentary", "news"),
                        keywords = emptyList(),
                        seriesLinkUri = "series-link",
                        episodeLinkUri = "episode-link",
                    ),
                ),
            ),
            connection.call(GetEpgObjectRequest(id = 42L)),
        )
        assertEquals("getEpgObject", transport.lastMethod)
        assertEquals(linkedMapOf("id" to 42L, "type" to 1L), transport.lastFields)

        transport.reply = HtspWireReply(
            validGetEpgObjectReply().apply { put("cred", listOf(null, 1L, "still opaque")) },
        )
        assertTrue(connection.call(GetEpgObjectRequest(1L)) is HtspResult.Ok)
    }

    @Test
    fun getEpgObjectRejectsEveryMalformedBoundedReplyShapeAsPayloadFreeServerError() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspTypedRequestCaller(transport)
        val malformed = listOf(
            validGetEpgObjectReply().apply { remove("id") },
            validGetEpgObjectReply().apply { put("id", 1) },
            validGetEpgObjectReply().apply { put("id", -1L) },
            validGetEpgObjectReply().apply { remove("tp") },
            validGetEpgObjectReply().apply { put("tp", 0L) },
            validGetEpgObjectReply().apply { put("tp", 0x1_0000_0000L) },
            validGetEpgObjectReply().apply { remove("up") },
            validGetEpgObjectReply().apply { put("up", 1) },
            validGetEpgObjectReply().apply { remove("start") },
            validGetEpgObjectReply().apply { remove("stop") },
            validGetEpgObjectReply().apply { put("gr", 1L) },
            validGetEpgObjectReply().apply { put("eid", -1L) },
            validGetEpgObjectReply().apply { put("is_hd", 0L) },
            validGetEpgObjectReply().apply { put("is_hd", 2L) },
            validGetEpgObjectReply().apply { put("lines", 0x1_0000_0000L) },
            validGetEpgObjectReply().apply { put("tit", listOf("not-a-map")) },
            validGetEpgObjectReply().apply { put("tit", linkedMapOf(1L to "Title")) },
            validGetEpgObjectReply().apply { put("tit", linkedMapOf("eng" to 1L)) },
            validGetEpgObjectReply().apply { put("epn", "not-an-object") },
            validGetEpgObjectReply().apply { put("epn", emptyMap<String, Any?>()) },
            validGetEpgObjectReply().apply { put("epn", linkedMapOf("enum" to -1L)) },
            validGetEpgObjectReply().apply { put("epn", linkedMapOf("text" to 1L)) },
            validGetEpgObjectReply().apply { put("genre", "not-a-list") },
            validGetEpgObjectReply().apply { put("genre", listOf(1)) },
            validGetEpgObjectReply().apply { put("genre", listOf(0x1_0000_0000L)) },
            validGetEpgObjectReply().apply { put("cat", listOf(1L)) },
            validGetEpgObjectReply().apply { put("cat", listOf("news", "news")) },
            validGetEpgObjectReply().apply { put("cat", listOf("news", "documentary")) },
            validGetEpgObjectReply().apply { put("key", "not-a-list") },
            validGetEpgObjectReply().apply { put("fair", 1) },
        )

        malformed.forEach { fields ->
            transport.reply = HtspWireReply(fields)
            assertSame(HtspResult.ServerError, connection.call(GetEpgObjectRequest(1L)))
        }
    }

    @Test
    fun getEpgObjectOrdersCategoriesAndKeywordsByUnsignedUtf8Bytes() = runTest {
        val utf8First = "\uE000"
        val utf8Second = "\uD800\uDC00"
        assertTrue(utf8First.compareTo(utf8Second) > 0)

        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspTypedRequestCaller(transport)
        for (field in listOf("cat", "key")) {
            transport.reply = HtspWireReply(
                validGetEpgObjectReply().apply { put(field, listOf(utf8First, utf8Second)) },
            )
            val accepted = connection.call(GetEpgObjectRequest(1L))
            assertTrue(accepted is HtspResult.Ok)
            val broadcast = (accepted as HtspResult.Ok).value.broadcast
            assertEquals(
                listOf(utf8First, utf8Second),
                if (field == "cat") broadcast.categories else broadcast.keywords,
            )

            transport.reply = HtspWireReply(
                validGetEpgObjectReply().apply { put(field, listOf(utf8Second, utf8First)) },
            )
            assertSame(HtspResult.ServerError, connection.call(GetEpgObjectRequest(1L)))

            transport.reply = HtspWireReply(
                validGetEpgObjectReply().apply { put(field, listOf(utf8First, utf8First)) },
            )
            assertSame(HtspResult.ServerError, connection.call(GetEpgObjectRequest(1L)))
        }
    }

    @Test
    fun serverErrorIsPayloadFreeAndDoesNotRetainReplyText() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspTypedRequestCaller(transport)

        transport.reply = HtspWireReply(linkedMapOf("error" to "first server detail"))
        val first = connection.call(GetChannelRequest(channelId = 7L))
        assertTrue(first is HtspResult.ServerError)

        val instanceFields = first.javaClass.declaredFields.filterNot { field ->
            Modifier.isStatic(field.modifiers)
        }
        assertTrue(
            "ServerError must not retain instance payload fields: $instanceFields",
            instanceFields.isEmpty(),
        )

        val payloadAccessors = first.javaClass.declaredMethods.filter { method ->
            method.name in setOf("getMessage", "component1", "copy", "copy\$default")
        }
        assertTrue(
            "ServerError must not expose payload accessors: $payloadAccessors",
            payloadAccessors.isEmpty(),
        )

        transport.reply = HtspWireReply(linkedMapOf("error" to "second server detail"))
        val second = connection.call(GetChannelRequest(channelId = 7L))
        assertTrue(second is HtspResult.ServerError)
        assertSame(first, second)
    }

    @Test
    fun primitiveMapsSuccessTimeoutTransportAndAbsentGeneration() = runTest {
        val transport = FakeProtocolTransport(version = null)
        val connection = HtspTypedRequestCaller(transport)
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
        val connection = HtspTypedRequestCaller(transport)
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
    fun generationConstructorIsPrivateAndProtocolFactoryRetainsOpaqueIdentity() {
        val constructors = HtspConnectionGeneration::class.java.declaredConstructors
        assertTrue(constructors.any { constructor ->
            constructor.parameterCount == 0 && Modifier.isPrivate(constructor.modifiers)
        })
        assertTrue(constructors.none { constructor ->
            Modifier.isPublic(constructor.modifiers) && !constructor.isSynthetic
        })
        assertNotSame(HtspConnectionGeneration.create(), HtspConnectionGeneration.create())
    }

    @Test
    fun endpointHasIdentitySemanticsAndNoCredentialBearingDataClassHelpers() {
        val first = HtspEndpoint("example.invalid", 9982, "viewer", "credential-value")
        val second = HtspEndpoint("example.invalid", 9982, "viewer", "credential-value")

        assertNotEquals(first, second)
        assertTrue(first.javaClass.declaredMethods.none { method ->
            method.name == "copy" || method.name.startsWith("component")
        })
        assertTrue(!first.toString().contains("credential-value"))
        assertTrue(first.toString().contains("<redacted>"))
    }

    @Test
    fun primitiveRejectsAReplacedGenerationAsCancellation() = runTest {
        val transport = FakeProtocolTransport(version = 44)
        val connection = HtspTypedRequestCaller(transport)
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
            HtspTypedRequestCaller(transport).call(GetProfilesRequest())
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
            HtspTypedRequestCaller(transport).call(GetProfilesRequest())
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
            EnableAsyncMetadataRequest::class.java,
            GetChannelRequest::class.java,
            GetEventRequest::class.java,
            GetEventsRequest::class.java,
            EpgQueryRequest::class.java,
            GetEpgObjectRequest::class.java,
            GetDvrConfigsRequest::class.java,
            AddDvrEntryRequest::class.java,
            UpdateDvrEntryRequest::class.java,
            StopDvrEntryRequest::class.java,
            CancelDvrEntryRequest::class.java,
            DeleteDvrEntryRequest::class.java,
            AddAutorecEntryRequest::class.java,
            UpdateAutorecEntryRequest::class.java,
            DeleteAutorecEntryRequest::class.java,
            AddTimerecEntryRequest::class.java,
            UpdateTimerecEntryRequest::class.java,
            DeleteTimerecEntryRequest::class.java,
            GetDvrCutpointsRequest::class.java,
            SubscribeRequest::class.java,
            UnsubscribeRequest::class.java,
            SubscriptionChangeWeightRequest::class.java,
            SubscriptionSeekRequest::class.java,
            SubscriptionSkipRequest::class.java,
            SubscriptionSpeedRequest::class.java,
            SubscriptionLiveRequest::class.java,
            SubscriptionFilterStreamRequest::class.java,
            FileOpenRequest::class.java,
            FileReadRequest::class.java,
            FileCloseRequest::class.java,
            FileStatRequest::class.java,
            FileSeekRequest::class.java,
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
        assertEquals(
            linkedMapOf("subscriptionId" to 3L, "time" to Long.MAX_VALUE, "absolute" to 1L),
            HtspRequestCodecs.encode(
                SubscriptionSkipRequest(
                    3L,
                    SubscriptionSeekPosition.Time(Long.MAX_VALUE),
                    absolute = 1L,
                ),
            ),
        )
        assertEquals(
            linkedMapOf("subscriptionId" to 3L, "size" to Long.MIN_VALUE),
            HtspRequestCodecs.encode(
                SubscriptionSkipRequest(3L, SubscriptionSeekPosition.Size(Long.MIN_VALUE)),
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
            HtspTypedRequestCaller(transport).call(GetEventRequest(eventId = 1L, language = "")),
        )
        assertEquals(0, transport.dispatches)
        transport.reply = HtspWireReply(linkedMapOf("success" to 1L))
        val result = HtspTypedRequestCaller(transport).call(
            AddDvrEntryRequest(
                selector = AddDvrEntrySelector.ExplicitChannelTime(1L, 2L, 3L),
            ),
        )
        assertEquals(HtspResult.Ok(AddDvrEntryResponse(1L, null, null)), result)
        assertEquals(1, transport.dispatches)
        assertEquals("addDvrEntry", transport.lastMethod)
        assertEquals(linkedMapOf("channelId" to 1L, "start" to 2L, "stop" to 3L), transport.lastFields)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun validGetEpgObjectReply(): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to 1L,
        "tp" to 1L,
        "up" to 2L,
        "start" to 3L,
        "stop" to 4L,
    )

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
            timeoutMs: Long,
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
