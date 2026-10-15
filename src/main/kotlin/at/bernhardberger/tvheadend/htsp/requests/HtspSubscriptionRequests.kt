package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspTimeshiftStatusMessage
import at.bernhardberger.tvheadend.htsp.wire.*

/**
 * Subscription reply observations: optional 90 kHz and normalized-timestamp flags, weight, and timeshift period.
 *
 * @property ninetyKhz whether the server confirmed the 90 kHz clock (`90khz`); `null` when absent
 * @property normalizedTimestamps whether the server normalizes timestamp origins (`normts`); `null` when absent
 * @property weight scheduling weight the server applied (`weight`); `null` when absent
 * @property timeshiftPeriodSeconds timeshift period the server granted, in seconds (`timeshiftPeriod`); `null` when absent
 */
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
 * Requests [subscriptionId] for exactly one [channel]; true [ninetyKhz]
 * selects the 90 kHz packet clock. Collection of
 * `HtspConnection.subscriptionEvents(subscriptionId)` must already be active.
 * Missing collection or id reuse in one connection generation throws
 * [IllegalStateException] before another request reaches the server.
 *
 * @property subscriptionId client-chosen subscription identifier (`subscriptionId`), 0 to 4294967295; tags this subscription's events
 * @property channel channel to subscribe to, by id (`channelId`) or exact name (`channelName`)
 * @property profile stream profile name (`profile`); `null` omits it and the server picks the profile
 * @property weight scheduling weight (`weight`), 0 to 4294967295; `null` omits it and the server uses 0
 */
public data class SubscribeRequest(
    public val subscriptionId: Long,
    public val channel: SubscribeChannel,
    public val profile: String? = null,
    public val weight: Long? = null,
    /** HTSP `90khz`: 0/1 clock flag; null selects microseconds (pinned htsp_server.c:2658). */
    public val ninetyKhz: Boolean? = null,
    /**
     * HTSP `timeshiftPeriod`, seconds, 0 to 4294967295; `null` omits it and the server uses none.
     * The server applies it only when timeshift is enabled and caps it at its configured maximum
     * unless that is unlimited (htsp_server.c:2663–2667).
     */
    public val timeshiftPeriodSeconds: Long? = null,
    /** HTSP `queueDepth`, payload bytes before frame dropping; null uses the server default (htsp_server.c:2676–2677,4191–4197). */
    public val queueDepthBytes: Long? = null,
) : HtspRequest<SubscribeResponse>(
    method = "subscribe",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = maxVersion(
            null,
            16.takeIf { profile != null },
            7.takeIf { ninetyKhz != null || queueDepthBytes != null },
            9.takeIf { timeshiftPeriodSeconds != null },
        ),
) {
    init {
        requireU32("subscriptionId", subscriptionId)
        weight?.let { requireU32("weight", it) }
        timeshiftPeriodSeconds?.let { requireU32("timeshiftPeriod", it) }
        queueDepthBytes?.let { requireU32("queueDepthBytes", it) }
    }
}

/**
 * Selects one subscription by complete unsigned [subscriptionId] for termination.
 *
 * @property subscriptionId subscription to end (`subscriptionId`), 0 to 4294967295; the server acknowledges an unknown id
 */
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

/**
 * Selects one subscription and optionally supplies its new unsigned scheduling [weight].
 *
 * @property subscriptionId subscription to change (`subscriptionId`), 0 to 4294967295
 * @property weight new scheduling weight (`weight`), 0 to 4294967295; `null` omits it and the server uses 0
 */
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
    /**
     * Media time position.
     *
     * @property time HTSP `time`, signed ticks in the subscription's negotiated 90 kHz or microsecond clock (htsp_server.c:2827–2831)
     */
    public data class Time(public val time: Long) : SubscriptionSeekPosition

    /**
     * Byte position.
     *
     * @property sizeBytes HTSP `size`, signed byte coordinate for seek or skip (htsp_server.c:2832–2836)
     */
    public data class Size(public val sizeBytes: Long) : SubscriptionSeekPosition
}

/** Timestamp clock selected by the matching subscribe request for timeshift-status coordinates. */
public enum class SubscriptionTimestampClock {
    /** The default 1 MHz clock, used when the subscribe request did not select 90 kHz. */
    MICROSECONDS,

    /** The 90 kHz clock, selected by a subscribe request with `90khz` set. */
    NINETY_KHZ,
}

/**
 * Selects a subscription, one signed [position], and an optional [absolute] flag for seeking.
 *
 * @property subscriptionId subscription to seek (`subscriptionId`), 0 to 4294967295
 * @property position media time (`time`) or byte size (`size`) to seek to
 */
public data class SubscriptionSeekRequest(
    public val subscriptionId: Long,
    public val position: SubscriptionSeekPosition,
    /** HTSP `absolute`: 0/1 seek flag; null means relative (pinned htsp_server.c:2824-2834). */
    public val absolute: Boolean? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionSeek",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 9,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
    }
}

/**
 * Selects a subscription, one signed [position], and an optional [absolute] flag for skipping.
 *
 * @property subscriptionId subscription to skip (`subscriptionId`), 0 to 4294967295
 * @property position media time (`time`) or byte size (`size`) to skip by or to
 */
public data class SubscriptionSkipRequest(
    public val subscriptionId: Long,
    public val position: SubscriptionSeekPosition,
    /** HTSP `absolute`: 0/1 skip flag; null means relative (pinned htsp_server.c:2824-2834). */
    public val absolute: Boolean? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionSkip",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 9,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
    }
}

/**
 * Selects a subscription and carries the requested signed playback [speed].
 *
 * @property subscriptionId subscription to change (`subscriptionId`), 0 to 4294967295
 * @property speed requested playback speed in percent of normal speed (`speed`); 100 is normal playback, 0 pauses, and negative values rewind
 */
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

/**
 * Selects one subscription by complete unsigned [subscriptionId] and requests live mode.
 *
 * @property subscriptionId subscription to return to live (`subscriptionId`), 0 to 4294967295
 */
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

/**
 * Selects one subscription and immutable optional stream-index lists to enable and disable.
 *
 * @property subscriptionId subscription to filter (`subscriptionId`), 0 to 4294967295
 */
public class SubscriptionFilterStreamRequest(
    public val subscriptionId: Long,
    enable: List<Long>? = null,
    disable: List<Long>? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionFilterStream",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 12,
) {
    /** Immutable stream indexes to enable (`enable`), each 0 to 4294967295; `null` omits the list. */
    public val enable: List<Long>? = enable?.immutableSnapshot()

    /** Immutable stream indexes to disable (`disable`), each 0 to 4294967295; `null` omits the list. */
    public val disable: List<Long>? = disable?.immutableSnapshot()

    init {
        requireU32("subscriptionId", subscriptionId)
        this.enable?.forEach { requireU32("enable", it) }
        this.disable?.forEach { requireU32("disable", it) }
    }
}

/**
 * Requests a subscription for exactly one channel selector with profile, weight, timestamp, timeshift, and queue options.
 * @param queueDepthBytes HTSP `queueDepth`, payload bytes before dropping; null uses the server default.
 * See [SubscribeRequest] for pinned source evidence.
 */
public suspend fun HtspConnection.subscribe(
    subscriptionId: Long,
    channel: SubscribeChannel,
    profile: String? = null,
    weight: Long? = null,
    ninetyKhz: Boolean? = null,
    timeshiftPeriodSeconds: Long? = null,
    queueDepthBytes: Long? = null,
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
            queueDepthBytes = queueDepthBytes,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/**
 * Wraps [channelId] as [SubscribeChannel.Id] before sending the typed subscription request.
 * @param queueDepthBytes HTSP `queueDepth`, payload bytes before dropping; null uses the server default.
 * See [SubscribeRequest] for pinned source evidence.
 */
public suspend fun HtspConnection.subscribe(
    subscriptionId: Long,
    channelId: Long,
    profile: String? = null,
    weight: Long? = null,
    ninetyKhz: Boolean? = null,
    timeshiftPeriodSeconds: Long? = null,
    queueDepthBytes: Long? = null,
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
            queueDepthBytes = queueDepthBytes,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/**
 * Wraps [channelName] as [SubscribeChannel.Name] before sending the typed subscription request.
 * @param queueDepthBytes HTSP `queueDepth`, payload bytes before dropping; null uses the server default.
 * See [SubscribeRequest] for pinned source evidence.
 */
public suspend fun HtspConnection.subscribe(
    subscriptionId: Long,
    channelName: String,
    profile: String? = null,
    weight: Long? = null,
    ninetyKhz: Boolean? = null,
    timeshiftPeriodSeconds: Long? = null,
    queueDepthBytes: Long? = null,
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
            queueDepthBytes = queueDepthBytes,
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
    absolute: Boolean? = null,
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
    absolute: Boolean? = null,
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

/**
 * Requests one normal absolute skip to [marginSeconds] before the observed timeshift [status]
 * end. This is a near-live operation, not a guarantee that TVHeadend entered exact live mode.
 *
 * [status] and [clock] must describe the same subscription and connection generation selected by
 * the caller. A successful result acknowledges only the request reply; the matching ordered
 * `Timeshift` and `Skipped` subscription events remain authoritative. This operation never falls
 * back to `subscriptionLive`.
 *
 * Invalid or missing bounds, unsafe arithmetic, and coordinates that would overflow TVHeadend's
 * selected-clock conversion are rejected before dispatch.
 */
public suspend fun HtspConnection.subscriptionSkipNearLive(
    status: HtspTimeshiftStatusMessage,
    clock: SubscriptionTimestampClock,
    marginSeconds: Long,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<HtspEmptyResponse> {
    val start = requireNotNull(status.start) { "status.start must be present" }
    val end = requireNotNull(status.end) { "status.end must be present" }
    require(marginSeconds > 0L) { "marginSeconds must be positive" }

    val (unitsPerSecond, serverConversionMultiplier) = when (clock) {
        SubscriptionTimestampClock.MICROSECONDS ->
            MICROSECONDS_PER_SECOND to NINETY_KHZ_UNITS_PER_SECOND
        SubscriptionTimestampClock.NINETY_KHZ ->
            NINETY_KHZ_UNITS_PER_SECOND to MICROSECONDS_PER_SECOND
    }
    require(marginSeconds <= Long.MAX_VALUE / unitsPerSecond) {
        "marginSeconds exceeds the selected timestamp clock"
    }
    val margin = marginSeconds * unitsPerSecond
    require(end >= Long.MIN_VALUE + margin) { "near-live target underflows" }
    val target = end - margin
    require(target >= start) { "near-live target precedes status.start" }

    val safeCoordinate = Long.MAX_VALUE / serverConversionMultiplier
    require(target in -safeCoordinate..safeCoordinate) {
        "near-live target exceeds TVHeadend's safe conversion range"
    }

    return subscriptionSkip(
        subscriptionId = status.subscriptionId,
        position = SubscriptionSeekPosition.Time(target),
        absolute = true,
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
}

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

private const val MICROSECONDS_PER_SECOND = 1_000_000L
private const val NINETY_KHZ_UNITS_PER_SECOND = 90_000L
