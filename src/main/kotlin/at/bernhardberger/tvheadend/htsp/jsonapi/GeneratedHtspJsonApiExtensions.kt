// Originally generated from a reviewed catalog of TVHeadend master @ 27295c5a.
// HTSP v44 coverage ceiling; wire default is v43. Now maintained by hand:
// edit directly and cover protocol changes with focused tests.
@file:JvmName("GeneratedHtspExtensionsKt")

package at.bernhardberger.tvheadend.htsp.jsonapi

import at.bernhardberger.tvheadend.htsp.connection.*

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
