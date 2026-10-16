package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One stream profile with its stable UUID, display [name], and server [comment].
 *
 * @property profileUuid profile identifier (`uuid`)
 * @property name profile display name (`name`)
 * @property comment profile description (`comment`)
 */
public data class HtspProfile(
    public val profileUuid: String,
    public val name: String,
    public val comment: String,
)

/** Explicit successful acknowledgement for an RPC with no method-specific reply fields. */
public data object HtspEmptyResponse

/**
 * Handshake observations: the server's own protocol version, optional server labels, copied challenge, web root, language, capabilities, and API version.
 *
 * @property serverName server program name (`servername`); `null` when absent
 * @property serverVersion server software version string (`serverversion`); `null` when absent
 * @property challenge 32-byte server challenge (`challenge`) the client uses to answer `authenticate`
 * @property webRoot server web root path (`webroot`); `null` when the server has none
 * @property language server language (`language`); `null` when the server has none
 * @property apiVersion JSON API version (`api_version`); `null` when absent
 */
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
    /** Immutable capability names (`servercapability`); `null` when absent. */
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

/**
 * Authentication access observations and limits; each nullable property was absent or malformed when null.
 *
 * @property noAccess whether the account has no HTSP access (`noaccess`); when true the server sends no other field
 * @property admin whether the account has administrator access (`admin`)
 * @property streaming whether the account has streaming access (`streaming`)
 * @property dvr whether the account has DVR access (`dvr`)
 * @property failedDvr whether the account can access failed recordings (`faileddvr`)
 * @property anonymous whether the server anonymizes this account's HTSP access (`anonymous`)
 * @property limitAll maximum connections for the account (`limitall`); zero means no limit
 * @property limitDvr maximum recordings for the account (`limitdvr`); zero means no limit
 * @property limitStreaming maximum streaming connections for the account (`limitstreaming`); zero means no limit
 * @property uiLevel web interface level (`uilevel`): 0 basic, 1 advanced, 2 expert
 * @property uiLanguage web interface language (`uilanguage`); empty when the server has none configured
 */
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

/**
 * Contains the optional ordered stream-profile list returned by `getProfiles`.
 *
 * @property profiles profiles available to the account, in server order (`profiles`); `null` when absent
 */
public data class GetProfilesResponse(public val profiles: List<HtspProfile>?)

/**
 * Contains free and total recording bytes plus the optional used-byte counter.
 *
 * @property freeBytes free space on the recording storage, in bytes (`freediskspace`)
 * @property usedBytes used space on the recording storage, in bytes (`useddiskspace`); `null` when absent
 * @property totalBytes total size of the recording storage, in bytes (`totaldiskspace`)
 */
public data class GetDiskSpaceResponse(
    public val freeBytes: Long,
    public val usedBytes: Long?,
    public val totalBytes: Long,
)

/**
 * Contains Unix time, the legacy hours-west timezone value, and an optional GMT offset in minutes.
 *
 * @property legacyTimezoneHoursWestOfGmt server timezone in whole hours west of GMT (`timezone`); positive west, negative east
 * @property gmtOffsetMinutes server offset from GMT in minutes (`gmtoffset`); positive east, `null` when absent
 */
public data class GetSysTimeResponse(
    /** HTSP `time`, epoch seconds from `timeval.tv_sec` (htsp_server.c:1636). */
    public val timeEpochSeconds: Long,
    public val legacyTimezoneHoursWestOfGmt: Int,
    public val gmtOffsetMinutes: Int?,
)

/**
 * Carries the requested unsigned HTSP version and exact client name for the `hello` exchange.
 *
 * @property clientName name the server logs and records for this client (`clientname`)
 */
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

/**
 * Selects asynchronous metadata options: EPG inclusion, update frontier, EPG maximum time, and language; null omits each field.
 *
 * @property language language for localized text (`language`); `null` omits it and keeps the connection's current language, an empty string clears it
 */
public data class EnableAsyncMetadataRequest(
    /** HTSP `epg`: 0/1 metadata flag; null preserves server state (pinned htsp_server.c:1660-1661). */
    public val epg: Boolean? = null,
    /**
     * HTSP `lastUpdate`, epoch seconds; 0 means never synchronized, so the server sends all eligible metadata;
     * null omits it (htsp_server.c:1662–1665,1700). Access, EPG enablement and window limits still apply;
     * an existing async session resends eligible EPG events starting after this frontier (4084–4100).
     */
    public val lastUpdateEpochSeconds: Long? = null,
    /**
     * HTSP `epgMaxTime`, epoch seconds; the server pushes EPG events up to this far ahead of its current time, as a
     * window of at least 600 seconds that moves forward as time passes; when EPG is not on yet, zero or a time not in
     * the future means unlimited window; on a session that already pushes EPG it can only extend an existing limit;
     * null omits it (htsp_server.c:1666–1677, 4084–4107).
     */
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
 * @param epgMaxTimeEpochSeconds HTSP `epgMaxTime`, epoch seconds; sets a rolling EPG window, see
 * [EnableAsyncMetadataRequest.epgMaxTimeEpochSeconds]; null omits it.
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
 * current generation, then waits for that generation's sync marker. Callers must serialize this
 * unsequenced orchestration per generation.
 *
 * The wait is bounded by progress, not by total duration: it returns [HtspResult.Timeout] once
 * [idleTimeoutMs] passes without progress, counted from the call. Progress is the Ok
 * acknowledgement and each metadata event of this generation that the connection queues for
 * [HtspConnection.events] collectors ([HtspTransportEvent.ServerMessage] and
 * [HtspTransportEvent.MalformedMetadata]); subscription packets and other generations' events do
 * not count. The same idle limit bounds the acknowledgement. [timeoutMs], when set, also caps the
 * whole operation however steady the progress.
 *
 * On library-created connections the helper watches for the marker internally, without a
 * second [HtspConnection.events] collector, so it cannot overflow. Custom connections use an events
 * collector; overflow of that observer's generation returns [HtspResult.TransportUnavailable].
 * Skipped malformed metadata does not fail this wait: an Ok acknowledgement and this
 * generation's sync marker return Ok. In the caller's already-active [HtspConnection.events]
 * collector, [HtspTransportEvent.MalformedMetadata] (or [HtspTransportEvent.MetadataOverflow]
 * if its queue fills) precedes any later admitted sync marker. Stateful consumers must
 * observe those events there and treat that generation's state as incomplete.
 * @param lastUpdateEpochSeconds HTSP `lastUpdate`, epoch seconds; 0 means never synchronized, so the server sends all eligible metadata; null omits it.
 * @param epgMaxTimeEpochSeconds HTSP `epgMaxTime`, epoch seconds; sets a rolling EPG window, see
 * [EnableAsyncMetadataRequest.epgMaxTimeEpochSeconds]; null omits it.
 * @param timeoutMs optional overall limit in milliseconds for the acknowledgement and marker together;
 * must be positive; null, the default, sets no overall limit.
 * @param idleTimeoutMs longest wait in milliseconds without progress, 30 s by default; must be positive.
 * See [EnableAsyncMetadataRequest] for pinned source evidence.
 */
public suspend fun HtspConnection.enableAsyncMetadataAwaitingInitialSync(
    epg: Boolean? = null,
    lastUpdateEpochSeconds: Long? = null,
    epgMaxTimeEpochSeconds: Long? = null,
    language: String? = null,
    timeoutMs: Long? = null,
    expectedGeneration: HtspConnectionGeneration? = null,
    idleTimeoutMs: Long = 30_000L,
): HtspResult<Unit> {
    require(timeoutMs == null || timeoutMs > 0L) { "timeoutMs must be positive or null" }
    require(idleTimeoutMs > 0L) { "idleTimeoutMs must be positive" }
    currentCoroutineContext().ensureActive()
    val generation = (connectionState.value as? HtspConnectionState.Connected)?.connection?.generation
        ?: return HtspResult.TransportUnavailable
    if (expectedGeneration != null && expectedGeneration !== generation) {
        return HtspResult.TransportUnavailable
    }
    val request = EnableAsyncMetadataRequest(
        epg = epg,
        lastUpdateEpochSeconds = lastUpdateEpochSeconds,
        epgMaxTimeEpochSeconds = epgMaxTimeEpochSeconds,
        language = language,
    )
    if (timeoutMs == null) return awaitInitialSync(request, generation, idleTimeoutMs, idleTimeoutMs)
    return withTimeoutOrNull(timeoutMs) {
        awaitInitialSync(request, generation, idleTimeoutMs, minOf(idleTimeoutMs, timeoutMs))
    } ?: HtspResult.Timeout
}

private enum class InitialSyncWake { TERMINAL, ACKNOWLEDGEMENT, MARKER, PROGRESS }

private suspend fun HtspConnection.awaitInitialSync(
    request: EnableAsyncMetadataRequest,
    generation: HtspConnectionGeneration,
    idleTimeoutMs: Long,
    acknowledgementTimeoutMs: Long,
): HtspResult<Unit> {
    val service = this as? HtspService
    val internalObserver = service?.registerInitialSyncObserver(generation)
    try {
        return coroutineScope {
            val marker = CompletableDeferred<Unit>()
            val terminal = CompletableDeferred<HtspResult<Unit>>()
            // Custom connections only: the internal observer stamps progress without waking anyone.
            val progress = if (internalObserver == null) Channel<Unit>(Channel.CONFLATED) else null
            val eventObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                if (internalObserver != null) {
                    while (true) {
                        internalObserver.changed.receive()
                        if (internalObserver.failed) terminal.complete(HtspResult.TransportUnavailable)
                        if (internalObserver.completed) marker.complete(Unit)
                    }
                } else {
                    events.collect { event ->
                        when (event) {
                            is HtspTransportEvent.ServerMessage -> {
                                if (event.generation === generation) {
                                    if (event.message === HtspInitialSyncCompletedMessage) marker.complete(Unit)
                                    progress?.trySend(Unit)
                                }
                            }
                            is HtspTransportEvent.MalformedMetadata -> {
                                if (event.generation === generation) progress?.trySend(Unit)
                            }
                            is HtspTransportEvent.ConnectionFailure -> {
                                if (event.generation === generation) {
                                    terminal.complete(HtspResult.TransportUnavailable)
                                }
                            }
                            is HtspTransportEvent.MetadataOverflow -> {
                                if (event.generation === generation) terminal.complete(HtspResult.TransportUnavailable)
                            }
                        }
                    }
                }
            }
            val generationObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                connectionState.first { state ->
                    (state as? HtspConnectionState.Connected)?.connection?.generation !== generation
                }
                terminal.complete(HtspResult.TransportUnavailable)
            }
            val acknowledgement = async(start = CoroutineStart.UNDISPATCHED) {
                execute(request = request, timeoutMs = acknowledgementTimeoutMs, expectedGeneration = generation)
            }

            try {
                var acknowledged = false
                var outcome: HtspResult<Unit>? = null
                while (outcome == null) {
                    // The internal deadline is the last progress stamp plus the idle limit; a custom
                    // connection restarts a full idle wait after each progress signal instead.
                    val progressBefore = if (internalObserver != null) service.initialSyncProgressCount(internalObserver) else null
                    val waitMs = internalObserver?.remainingIdleMs(idleTimeoutMs) ?: idleTimeoutMs
                    val wake = if (waitMs <= 0L) null else withTimeoutOrNull(waitMs) {
                        select<InitialSyncWake> {
                            terminal.onAwait { InitialSyncWake.TERMINAL }
                            if (acknowledged) {
                                marker.onAwait { InitialSyncWake.MARKER }
                            } else {
                                acknowledgement.onAwait { InitialSyncWake.ACKNOWLEDGEMENT }
                            }
                            if (progress != null) progress.onReceive { InitialSyncWake.PROGRESS }
                        }
                    }
                    outcome = when (wake) {
                        // Re-arm only when frames recorded progress during the wait; otherwise the
                        // limit passed, whatever the progress clock says.
                        null -> if (
                            internalObserver != null && waitMs > 0L &&
                            service.initialSyncProgressCount(internalObserver) != progressBefore
                        ) {
                            null
                        } else {
                            HtspResult.Timeout
                        }
                        InitialSyncWake.TERMINAL -> terminal.await()
                        InitialSyncWake.ACKNOWLEDGEMENT -> when (val result = acknowledgement.await()) {
                            is HtspResult.Ok -> {
                                acknowledged = true
                                if (internalObserver != null) service.recordInitialSyncProgress(internalObserver)
                                null
                            }
                            is HtspFailure -> result
                        }
                        InitialSyncWake.MARKER -> when {
                            (connectionState.value as? HtspConnectionState.Connected)?.connection?.generation !== generation ->
                                HtspResult.TransportUnavailable
                            internalObserver?.failed == true -> HtspResult.TransportUnavailable
                            terminal.isCompleted -> terminal.await()
                            else -> HtspResult.Ok(Unit)
                        }
                        InitialSyncWake.PROGRESS -> null
                    }
                }
                outcome
            } finally {
                acknowledgement.cancel()
                eventObserver.cancel()
                generationObserver.cancel()
            }
        }
    } finally {
        if (internalObserver != null) service.unregisterInitialSyncObserver(internalObserver)
    }
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
