package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

/** Subscription reply observations: optional 90 kHz and normalized-timestamp flags, weight, and timeshift period. */
public data class SubscribeResponse(
    public val ninetyKhz: Boolean?,
    public val normalizedTimestamps: Boolean?,
    public val weight: Long?,
    public val timeshiftPeriodSeconds: Long?,
)
/** Closed exactly-one subscription channel selector by unsigned ID or exact name. */
public sealed interface SubscribeChannel {
    /** Selects a subscription channel by complete unsigned [channelId]. */
    public data class Id(public val channelId: Long) : SubscribeChannel {
        init {
            requireU32("channelId", channelId)
        }
    }

    /** Selects a subscription channel by exact [channelName]. */
    public data class Name(public val channelName: String) : SubscribeChannel
}

/**
 * Requests [subscriptionId] for exactly one [channel]; any nonzero [ninetyKhz]
 * selects the 90 kHz packet clock. Collection of
 * `HtspConnection.subscriptionEvents(subscriptionId)` must already be active.
 * Missing collection or id reuse in one connection generation throws
 * [IllegalStateException] before another request reaches the server.
 */
public data class SubscribeRequest(
    public val subscriptionId: Long,
    public val channel: SubscribeChannel,
    public val profile: String? = null,
    public val weight: Long? = null,
    public val ninetyKhz: Long? = null,
    public val timeshiftPeriodSeconds: Long? = null,
    public val queueDepth: Long? = null,
) : HtspRequest<SubscribeResponse>(
    method = "subscribe",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = maxVersion(
            null,
            16.takeIf { profile != null },
            7.takeIf { ninetyKhz != null || queueDepth != null },
            9.takeIf { timeshiftPeriodSeconds != null },
        ),
) {
    init {
        requireU32("subscriptionId", subscriptionId)
        weight?.let { requireU32("weight", it) }
        ninetyKhz?.let { requireU32("90khz", it) }
        timeshiftPeriodSeconds?.let { requireU32("timeshiftPeriod", it) }
        queueDepth?.let { requireU32("queueDepth", it) }
    }
}

/** Selects one subscription by complete unsigned [subscriptionId] for termination. */
public data class UnsubscribeRequest(
    public val subscriptionId: Long,
) : HtspRequest<HtspEmptyResponse>(
    method = "unsubscribe",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = null,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
    }
}

/** Selects one subscription and optionally supplies its new unsigned scheduling [weight]. */
public data class SubscriptionChangeWeightRequest(
    public val subscriptionId: Long,
    public val weight: Long? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionChangeWeight",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 5,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
        weight?.let { requireU32("weight", it) }
    }
}

/** Closed signed subscription coordinate: media time or byte size. */
public sealed interface SubscriptionSeekPosition {
    /** Carries a signed media [time] coordinate for seek or skip. */
    public data class Time(public val time: Long) : SubscriptionSeekPosition
    /** Carries a signed byte [size] coordinate for seek or skip. */
    public data class Size(public val size: Long) : SubscriptionSeekPosition
}

/** Selects a subscription, one signed [position], and an optional unsigned [absolute] flag for seeking. */
public data class SubscriptionSeekRequest(
    public val subscriptionId: Long,
    public val position: SubscriptionSeekPosition,
    public val absolute: Long? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionSeek",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 9,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
        absolute?.let { requireU32("absolute", it) }
    }
}

/** Selects a subscription, one signed [position], and an optional unsigned [absolute] flag for skipping. */
public data class SubscriptionSkipRequest(
    public val subscriptionId: Long,
    public val position: SubscriptionSeekPosition,
    public val absolute: Long? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionSkip",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 9,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
        absolute?.let { requireU32("absolute", it) }
    }
}

/** Selects a subscription and carries the requested signed playback [speed]. */
public data class SubscriptionSpeedRequest(
    public val subscriptionId: Long,
    public val speed: Int,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionSpeed",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 9,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
    }
}

/** Selects one subscription by complete unsigned [subscriptionId] and requests live mode. */
public data class SubscriptionLiveRequest(
    public val subscriptionId: Long,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionLive",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 9,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
    }
}

/** Selects one subscription and immutable optional stream-index lists to enable and disable. */
public class SubscriptionFilterStreamRequest(
    public val subscriptionId: Long,
    enable: List<Long>? = null,
    disable: List<Long>? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionFilterStream",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 12,
) {
    public val enable: List<Long>? = enable?.immutableSnapshot()
    public val disable: List<Long>? = disable?.immutableSnapshot()

    init {
        requireU32("subscriptionId", subscriptionId)
        this.enable?.forEach { requireU32("enable", it) }
        this.disable?.forEach { requireU32("disable", it) }
    }
}

/** Requests a subscription for exactly one channel selector with profile, weight, timestamp, timeshift, and queue options. */
public suspend fun HtspConnection.subscribe(
    subscriptionId: Long,
    channel: SubscribeChannel,
    profile: String? = null,
    weight: Long? = null,
    ninetyKhz: Long? = null,
    timeshiftPeriodSeconds: Long? = null,
    queueDepth: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<SubscribeResponse> =
    execute(
        request = SubscribeRequest(
            subscriptionId = subscriptionId,
            channel = channel,
            profile = profile,
            weight = weight,
            ninetyKhz = ninetyKhz,
            timeshiftPeriodSeconds = timeshiftPeriodSeconds,
            queueDepth = queueDepth,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Wraps [channelId] as [SubscribeChannel.Id] before sending the typed subscription request. */
public suspend fun HtspConnection.subscribe(
    subscriptionId: Long,
    channelId: Long,
    profile: String? = null,
    weight: Long? = null,
    ninetyKhz: Long? = null,
    timeshiftPeriodSeconds: Long? = null,
    queueDepth: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<SubscribeResponse> =
    execute(
        request = SubscribeRequest(
            subscriptionId = subscriptionId,
            channel = SubscribeChannel.Id(channelId),
            profile = profile,
            weight = weight,
            ninetyKhz = ninetyKhz,
            timeshiftPeriodSeconds = timeshiftPeriodSeconds,
            queueDepth = queueDepth,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Wraps [channelName] as [SubscribeChannel.Name] before sending the typed subscription request. */
public suspend fun HtspConnection.subscribe(
    subscriptionId: Long,
    channelName: String,
    profile: String? = null,
    weight: Long? = null,
    ninetyKhz: Long? = null,
    timeshiftPeriodSeconds: Long? = null,
    queueDepth: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<SubscribeResponse> =
    execute(
        request = SubscribeRequest(
            subscriptionId = subscriptionId,
            channel = SubscribeChannel.Name(channelName),
            profile = profile,
            weight = weight,
            ninetyKhz = ninetyKhz,
            timeshiftPeriodSeconds = timeshiftPeriodSeconds,
            queueDepth = queueDepth,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests termination of the selected subscription and decodes the typed acknowledgement. */
public suspend fun HtspConnection.unsubscribe(
    subscriptionId: Long,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> =
    execute(
        request = UnsubscribeRequest(
            subscriptionId = subscriptionId,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests a scheduling-weight change for one subscription and decodes its typed acknowledgement; the reply does not establish that the weight was applied. */
public suspend fun HtspConnection.subscriptionChangeWeight(
    subscriptionId: Long,
    weight: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> =
    execute(
        request = SubscriptionChangeWeightRequest(
            subscriptionId = subscriptionId,
            weight = weight,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests a subscription seek by exactly one signed time or byte coordinate and decodes the typed acknowledgement. */
public suspend fun HtspConnection.subscriptionSeek(
    subscriptionId: Long,
    position: SubscriptionSeekPosition,
    absolute: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> =
    execute(
        request = SubscriptionSeekRequest(
            subscriptionId = subscriptionId,
            position = position,
            absolute = absolute,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests a subscription skip using exactly one signed time or byte coordinate and decodes the typed acknowledgement. */
public suspend fun HtspConnection.subscriptionSkip(
    subscriptionId: Long,
    position: SubscriptionSeekPosition,
    absolute: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> =
    execute(
        request = SubscriptionSkipRequest(
            subscriptionId = subscriptionId,
            position = position,
            absolute = absolute,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests the signed playback speed for one subscription and decodes the typed acknowledgement. */
public suspend fun HtspConnection.subscriptionSpeed(
    subscriptionId: Long,
    speed: Int,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> =
    execute(
        request = SubscriptionSpeedRequest(
            subscriptionId = subscriptionId,
            speed = speed,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests live mode for one subscription and decodes the typed acknowledgement; asynchronous subscription status remains authoritative for the resulting position. */
public suspend fun HtspConnection.subscriptionLive(
    subscriptionId: Long,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> =
    execute(
        request = SubscriptionLiveRequest(
            subscriptionId = subscriptionId,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests the supplied immutable enable and disable stream-index filters and decodes the typed acknowledgement. */
public suspend fun HtspConnection.subscriptionFilterStream(
    subscriptionId: Long,
    enable: List<Long>? = null,
    disable: List<Long>? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> =
    execute(
        request = SubscriptionFilterStreamRequest(
            subscriptionId = subscriptionId,
            enable = enable,
            disable = disable,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
