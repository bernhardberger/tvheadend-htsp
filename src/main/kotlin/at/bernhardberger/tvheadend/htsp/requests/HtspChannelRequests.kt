package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.model.HtspChannel
import at.bernhardberger.tvheadend.htsp.wire.*

/**
 * Contains the complete channel selected by `getChannel`.
 *
 * @property channel channel for the requested identifier
 */
public data class GetChannelResponse(public val channel: HtspChannel)
/**
 * Selects one channel by complete unsigned [channelId].
 *
 * @property channelId channel to fetch (`channelId`), 0 to 4294967295
 */
public data class GetChannelRequest(public val channelId: Long) : HtspRequest<GetChannelResponse>(
    method = "getChannel",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 14,
) {
    init {
        requireU32("channelId", channelId)
    }
}

/** Fetches one channel by unsigned identifier and decodes the reply through the typed connection boundary. */
public suspend fun HtspConnection.getChannel(
    channelId: Long,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetChannelResponse> =
    execute(
        request = GetChannelRequest(
            channelId = channelId,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
