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
) {
    override fun toString(): String = "ApiRequest(<redacted>)"
}

/** Finite successful JSON API reply: either a typed container payload or an explicit absence of payload. */
@HtspJsonApi
public sealed interface ApiResponse {
    /** Contains the recursively typed map or list returned by a successful JSON API call. */
    @HtspJsonApi
    public data class Payload(public val value: HtspApiContainer) : ApiResponse

    /**
     * Marks a reply without a `response` payload.
     *
     * The server sends the same empty reply for a call that produced no payload, an unknown
     * [ApiRequest.path], and an object the endpoint could not find, so this value does not prove
     * that the call did anything.
     */
    @HtspJsonApi
    public data object NoPayload : ApiResponse
}

/**
 * Calls one JSON API path with an optional object argument through typed connection execution; failures remain [HtspResult] values.
 *
 * An unknown path or a missing object is not a failure: the server answers it like a call without
 * payload, so it arrives as `Ok(`[ApiResponse.NoPayload]`)`.
 */
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
