package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

/** One channel service with name, type, content code, optional conditional-access data, and optional provider name. */
public data class HtspChannelService(
    public val name: String,
    public val type: String,
    public val content: Long,
    public val conditionalAccessId: Long?,
    public val conditionalAccessName: String?,
    public val providerName: String?,
)

/** A complete channel reply with identity, numbering, display data, current and next event IDs, services, and tag IDs. */
public data class HtspChannel(
    public val channelId: Long,
    public val channelUuid: String?,
    public val channelNumber: Long,
    public val channelNumberMinor: Long?,
    public val channelName: String,
    public val channelIcon: String?,
    public val currentEventId: Long,
    public val nextEventId: Long,
    public val services: List<HtspChannelService>,
    public val tagIds: List<Long>,
)
/** Contains the complete channel selected by `getChannel`. */
public data class GetChannelResponse(public val channel: HtspChannel)
/** Selects one channel by complete unsigned [channelId]. */
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
