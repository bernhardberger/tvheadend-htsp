package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

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
    public val htspVersion: Long,
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
            htspVersion == other.htspVersion &&
            serverName == other.serverName &&
            serverVersion == other.serverVersion &&
            challenge == other.challenge &&
            webRoot == other.webRoot &&
            language == other.language &&
            serverCapabilities == other.serverCapabilities &&
            apiVersion == other.apiVersion

    override fun hashCode(): Int {
        var result = htspVersion.hashCode()
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
        "HelloResponse(htspVersion=$htspVersion, serverName=$serverName, " +
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
    public val unixTimeSeconds: Long,
    public val legacyTimezoneHoursWestOfGmt: Int,
    public val gmtOffsetMinutes: Int?,
)

/** Carries the requested unsigned HTSP version and exact client name for the `hello` exchange. */
public data class HelloRequest(
    public val htspVersion: Long,
    public val clientName: String,
) : HtspRequest<HelloResponse>(
    method = "hello",
    access = HtspAccess.ACCESS_ANONYMOUS,
    minimumProtocolVersion = null,
) {
    init {
        requireU32("htspVersion", htspVersion)
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
    public val epg: Long? = null,
    public val lastUpdate: Long? = null,
    public val epgMaxTime: Long? = null,
    public val language: String? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "enableAsyncMetadata",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 6.takeIf {
            epg != null || lastUpdate != null || epgMaxTime != null || language != null
        },
) {
    init {
        epg?.let { requireU32("epg", it) }
    }
}

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

/** Requests asynchronous metadata delivery with the selected EPG window and language options and decodes the typed acknowledgement. */
public suspend fun HtspConnection.enableAsyncMetadata(
    epg: Long? = null,
    lastUpdate: Long? = null,
    epgMaxTime: Long? = null,
    language: String? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> =
    execute(
        request = EnableAsyncMetadataRequest(
            epg = epg,
            lastUpdate = lastUpdate,
            epgMaxTime = epgMaxTime,
            language = language,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
/** Negotiates the requested HTSP version and client name through the typed handshake request boundary. */
public suspend fun HtspConnection.hello(
    htspVersion: Long,
    clientName: String,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HelloResponse> =
    execute(
        request = HelloRequest(
            htspVersion = htspVersion,
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
