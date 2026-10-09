package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** One stream profile with its stable UUID, display [name], and server [comment]. */
public data class HtspProfile(
    public val profileUuid: String,
    public val name: String,
    public val comment: String,
)

/** Explicit successful acknowledgement for an RPC with no method-specific reply fields. */
public data object HtspEmptyResponse

/** Handshake observations: negotiated version, optional server labels, copied challenge, web root, language, capabilities, and API version. */
public class HelloResponse(
    /** HTSP `htspversion`, unitless server protocol version (htsp_server.c:1487). */
    public val protocolVersion: Long,
    public val serverName: String?,
    public val serverVersion: String?,
    public val challenge: HtspBinary,
    public val webRoot: String?,
    public val language: String?,
    serverCapabilities: List<String>?,
    public val apiVersion: Long?,
) {
    public val serverCapabilities: List<String>? = serverCapabilities?.immutableSnapshot()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is HelloResponse &&
            protocolVersion == other.protocolVersion &&
            serverName == other.serverName &&
            serverVersion == other.serverVersion &&
            challenge == other.challenge &&
            webRoot == other.webRoot &&
            language == other.language &&
            serverCapabilities == other.serverCapabilities &&
            apiVersion == other.apiVersion

    override fun hashCode(): Int {
        var result = protocolVersion.hashCode()
        result = 31 * result + (serverName?.hashCode() ?: 0)
        result = 31 * result + (serverVersion?.hashCode() ?: 0)
        result = 31 * result + challenge.hashCode()
        result = 31 * result + (webRoot?.hashCode() ?: 0)
        result = 31 * result + (language?.hashCode() ?: 0)
        result = 31 * result + (serverCapabilities?.hashCode() ?: 0)
        result = 31 * result + (apiVersion?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "HelloResponse(protocolVersion=$protocolVersion, serverName=$serverName, " +
            "serverVersion=$serverVersion, challenge=$challenge, webRoot=$webRoot, " +
            "language=$language, serverCapabilities=$serverCapabilities, apiVersion=$apiVersion)"
}

/** Authentication access observations and limits; each nullable property was absent or malformed when null. */
public data class AuthenticateResponse(
    public val noAccess: Boolean?,
    public val admin: Boolean?,
    public val streaming: Boolean?,
    public val dvr: Boolean?,
    public val failedDvr: Boolean?,
    public val anonymous: Boolean?,
    public val limitAll: Long?,
    public val limitDvr: Long?,
    public val limitStreaming: Long?,
    public val uiLevel: Long?,
    public val uiLanguage: String?,
)

/** Contains the optional ordered stream-profile list returned by `getProfiles`. */
public data class GetProfilesResponse(public val profiles: List<HtspProfile>?)

/** Contains free and total recording bytes plus the optional used-byte counter. */
public data class GetDiskSpaceResponse(
    public val freeBytes: Long,
    public val usedBytes: Long?,
    public val totalBytes: Long,
)

/** Contains Unix time, the legacy hours-west timezone value, and an optional GMT offset in minutes. */
public data class GetSysTimeResponse(
    /** HTSP `time`, epoch seconds from `timeval.tv_sec` (htsp_server.c:1636). */
    public val timeEpochSeconds: Long,
    public val legacyTimezoneHoursWestOfGmt: Int,
    public val gmtOffsetMinutes: Int?,
)

/** Carries the requested unsigned HTSP version and exact client name for the `hello` exchange. */
public data class HelloRequest(
    /** HTSP `htspversion`, unitless requested protocol version; at least [MINIMUM_HTSP_PROTOCOL_VERSION] (htsp_server.c:1474). */
    public val protocolVersion: Long,
    public val clientName: String,
) : HtspRequest<HelloResponse>(
    method = "hello",
    access = HtspAccess.ACCESS_ANONYMOUS,
    minimumProtocolVersion = null,
) {
    init {
        requireU32("protocolVersion", protocolVersion)
        require(protocolVersion >= MINIMUM_HTSP_PROTOCOL_VERSION) {
            "protocolVersion must be at least $MINIMUM_HTSP_PROTOCOL_VERSION"
        }
    }
}

/** Bare authentication request; credentials belong to the connection envelope rather than constructor properties. */
public class AuthenticateRequest : HtspRequest<AuthenticateResponse>(
    method = "authenticate",
    access = HtspAccess.ACCESS_ANONYMOUS,
    minimumProtocolVersion = null,
)

/** Requests the stream-profile list and carries no method-specific parameters. */
public class GetProfilesRequest : HtspRequest<GetProfilesResponse>(
    method = "getProfiles",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 16,
)

/** Requests recording-storage counters and carries no method-specific parameters. */
public class GetDiskSpaceRequest : HtspRequest<GetDiskSpaceResponse>(
    method = "getDiskSpace",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 3,
)

/** Requests the server clock and timezone observations without method-specific parameters. */
public class GetSysTimeRequest : HtspRequest<GetSysTimeResponse>(
    method = "getSysTime",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 3,
)

/** Selects asynchronous metadata options: EPG inclusion, update frontier, EPG maximum time, and language; null omits each field. */
public data class EnableAsyncMetadataRequest(
    /** HTSP `epg`: 0/1 metadata flag; null preserves server state (pinned htsp_server.c:1660-1661). */
    public val epg: Boolean? = null,
    /**
     * HTSP `lastUpdate`, epoch seconds; 0 means never synchronized, so the server sends all eligible metadata;
     * null omits it (htsp_server.c:1662–1665,1700). Access, EPG enablement and window limits still apply;
     * an existing async session resends eligible EPG events starting after this frontier (4084–4100).
     */
    public val lastUpdateEpochSeconds: Long? = null,
    /** HTSP `epgMaxTime`, epoch seconds; zero means unlimited window, null omits it (htsp_server.c:1666). */
    public val epgMaxTimeEpochSeconds: Long? = null,
    public val language: String? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "enableAsyncMetadata",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 6.takeIf {
            epg != null || lastUpdateEpochSeconds != null || epgMaxTimeEpochSeconds != null || language != null
        },
)

/** Fetches the server's stream-profile metadata through typed connection execution and returns its transport or reply failure as [HtspResult]. */
public suspend fun HtspConnection.getProfiles(
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetProfilesResponse> =
    execute(
        request = GetProfilesRequest(),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Reads free, used, and total recording-storage counters through the typed request boundary. */
public suspend fun HtspConnection.getDiskSpace(
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetDiskSpaceResponse> =
    execute(
        request = GetDiskSpaceRequest(),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Reads the server clock and timezone observations through typed connection execution. */
public suspend fun HtspConnection.getSysTime(
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetSysTimeResponse> =
    execute(
        request = GetSysTimeRequest(),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/**
 * Requests asynchronous metadata delivery and decodes the typed acknowledgement.
 * @param lastUpdateEpochSeconds HTSP `lastUpdate`, epoch seconds; 0 means never synchronized, so the server sends all eligible metadata; null omits it.
 * @param epgMaxTimeEpochSeconds HTSP `epgMaxTime`, epoch seconds; zero means unlimited, null omits it.
 * See [EnableAsyncMetadataRequest] for pinned source evidence.
 */
public suspend fun HtspConnection.enableAsyncMetadata(
    epg: Boolean? = null,
    lastUpdateEpochSeconds: Long? = null,
    epgMaxTimeEpochSeconds: Long? = null,
    language: String? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> =
    execute(
        request = EnableAsyncMetadataRequest(
            epg = epg,
            lastUpdateEpochSeconds = lastUpdateEpochSeconds,
            epgMaxTimeEpochSeconds = epgMaxTimeEpochSeconds,
            language = language,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/**
 * Enables asynchronous metadata after installing an initial-sync observer for the
 * current generation. Callers must serialize this unsequenced orchestration per generation.
 * [timeoutMs] is one deadline covering both the acknowledgement and sync marker.
 * @param lastUpdateEpochSeconds HTSP `lastUpdate`, epoch seconds; 0 means never synchronized, so the server sends all eligible metadata; null omits it.
 * @param epgMaxTimeEpochSeconds HTSP `epgMaxTime`, epoch seconds; zero means unlimited, null omits it.
 * See [EnableAsyncMetadataRequest] for pinned source evidence.
 */
public suspend fun HtspConnection.enableAsyncMetadataAwaitingInitialSync(
    epg: Boolean? = null,
    lastUpdateEpochSeconds: Long? = null,
    epgMaxTimeEpochSeconds: Long? = null,
    language: String? = null,
    timeoutMs: Long = 30_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<Unit> {
    require(timeoutMs > 0L) { "timeoutMs must be positive" }
    currentCoroutineContext().ensureActive()
    val generation = liveConnection.value?.generation
        ?: return HtspResult.TransportUnavailable
    if (expectedGeneration != null && expectedGeneration !== generation) {
        throw CancellationException("Stale HTSP connection generation")
    }

    return withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val marker = CompletableDeferred<Unit>()
            val terminal = CompletableDeferred<HtspResult<Unit>>()
            val eventObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                events.collect { event ->
                    when (event) {
                        is HtspTransportEvent.ServerMessage -> {
                            if (
                                event.generation === generation &&
                                event.message === HtspInitialSyncCompletedMessage
                            ) {
                                marker.complete(Unit)
                            }
                        }
                        is HtspTransportEvent.ConnectionFailure -> {
                            if (event.generation === generation) {
                                terminal.complete(HtspResult.TransportUnavailable)
                            }
                        }
                    }
                }
            }
            val generationObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                liveConnection.first { connection -> connection?.generation !== generation }
                if (isCurrent(generation)) {
                    terminal.complete(HtspResult.TransportUnavailable)
                } else {
                    terminal.completeExceptionally(
                        CancellationException("Stale HTSP connection generation"),
                    )
                }
            }
            val acknowledgement = async(start = CoroutineStart.UNDISPATCHED) {
                enableAsyncMetadata(
                    epg = epg,
                    lastUpdateEpochSeconds = lastUpdateEpochSeconds,
                    epgMaxTimeEpochSeconds = epgMaxTimeEpochSeconds,
                    language = language,
                    timeoutMs = timeoutMs,
                    expectedGeneration = generation,
                )
            }

            try {
                val terminalBeforeAcknowledgement = select<Boolean> {
                    terminal.onAwait { true }
                    acknowledgement.onAwait { false }
                }
                if (terminalBeforeAcknowledgement) {
                    terminal.await()
                } else {
                    when (val result = acknowledgement.await()) {
                        is HtspResult.Ok -> {
                            val terminalBeforeMarker = select<Boolean> {
                                terminal.onAwait { true }
                                marker.onAwait { false }
                            }
                            if (terminalBeforeMarker) {
                                terminal.await()
                            } else if (liveConnection.value?.generation !== generation) {
                                if (isCurrent(generation)) {
                                    HtspResult.TransportUnavailable
                                } else {
                                    throw CancellationException(
                                        "Stale HTSP connection generation",
                                    )
                                }
                            } else if (terminal.isCompleted) {
                                terminal.await()
                            } else {
                                HtspResult.Ok(Unit)
                            }
                        }
                        is HtspFailure -> result
                    }
                }
            } finally {
                acknowledgement.cancel()
                eventObserver.cancel()
                generationObserver.cancel()
            }
        }
    } ?: HtspResult.Timeout
}

/**
 * Negotiates the requested HTSP version and client name through the typed handshake request boundary.
 * @param protocolVersion HTSP `htspversion`, unitless requested version; see [HelloRequest].
 */
public suspend fun HtspConnection.hello(
    protocolVersion: Long,
    clientName: String,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HelloResponse> =
    execute(
        request = HelloRequest(
            protocolVersion = protocolVersion,
            clientName = clientName,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests the current connection's authentication and access observations through typed execution; credentials stay in the envelope. */
public suspend fun HtspConnection.authenticate(
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<AuthenticateResponse> =
    execute(
        request = AuthenticateRequest(),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
