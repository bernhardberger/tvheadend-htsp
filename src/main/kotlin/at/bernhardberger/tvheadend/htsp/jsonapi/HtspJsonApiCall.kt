package at.bernhardberger.tvheadend.htsp.jsonapi

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.HtspAccess
import at.bernhardberger.tvheadend.htsp.requests.HtspRequest

/** Carries an exact JSON API [path] and optional finite [args] object without rewriting either value. */
@HtspJsonApi
public data class ApiRequest(
    public val path: String,
    public val args: HtspApiObject? = null,
) : HtspRequest<ApiResponse>(
    method = "api",
    access = HtspAccess.ACCESS_ANONYMOUS,
    minimumProtocolVersion = 24,
)

/** Finite successful JSON API reply: either a typed container payload or an explicit absence of payload. */
@HtspJsonApi
public sealed interface ApiResponse {
    /** Contains the recursively typed map or list returned by a successful JSON API call. */
    @HtspJsonApi
    public data class Payload(public val value: HtspApiContainer) : ApiResponse

    /** Marks a successful JSON API callback that supplied no response payload. */
    @HtspJsonApi
    public data object NoPayload : ApiResponse
}

/** Calls one JSON API path with an optional object argument through typed connection execution; failures remain [HtspResult] values. */
@HtspJsonApi
public suspend fun HtspConnection.api(
    path: String,
    args: HtspApiObject? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<ApiResponse> =
    execute(
        request = ApiRequest(
            path = path,
            args = args,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
