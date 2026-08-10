package at.bernhardberger.tvheadend.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback-infrastructure SPI for the Media3 runtime.
 *
 * This is intentionally not a frontend service API. It exposes only the attempt-scoped
 * subscription and recording-file operations needed to keep playback atomic across a
 * reconnect; frontend clients should use [TvheadendClient] instead.
 */
@PlaybackIntegrationApi
public interface PlaybackHtspTransport {
    public val state: StateFlow<ConnectionState>
    public val controlEvents: Flow<HtspEvent>
    public val muxEvents: Flow<HtspMuxEvent>

    /** [timeshiftPeriodSec] is the requested timeshift duration in seconds. */
    public suspend fun startSubscription(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        channelId: Int,
        timeshiftPeriodSec: Int,
        profile: String?,
    ): PlaybackSubscriptionStart

    public suspend fun stopSubscription(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
    )

    /** Acknowledges the request only; it does not report settled or applied weight state. */
    public suspend fun setSubscriptionWeight(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        weight: Int,
    )

    /** Acknowledges only the requested stream-filter update; no effective state is retained. */
    public suspend fun updateSubscriptionStreamFilter(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        enabledStreamIndices: List<Int>,
        disabledStreamIndices: List<Int>,
    )

    /**
     * A successful return acknowledges only the empty RPC reply. The separate asynchronous
     * `subscriptionSkip` message remains authoritative for the observed live transition and
     * position; this call makes no settled-live or delivery-order guarantee.
     */
    public suspend fun returnSubscriptionToLive(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
    )

    public suspend fun setSubscriptionSpeed(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        speed: Int,
    )

    /** [timeUs] is an absolute HTSP timeshift-timeline point in microseconds. */
    public suspend fun seekSubscription(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        timeUs: Long,
        absolute: Boolean,
    )

    /** File-operation timeouts are durations in milliseconds. */
    public suspend fun fileOpen(
        path: String,
        timeoutMs: Long = 5_000,
        expectedConnectionAttemptId: Long? = null,
    ): Int

    public suspend fun fileRead(
        id: Int,
        size: Int,
        timeoutMs: Long = 5_000,
        expectedConnectionAttemptId: Long? = null,
    ): ByteArray

    public suspend fun fileSeek(
        id: Int,
        offset: Long,
        whence: String = "SEEK_SET",
        timeoutMs: Long = 5_000,
        expectedConnectionAttemptId: Long? = null,
    ): Long

    public suspend fun fileCloseRecording(
        id: Int,
        htspVersion: Int?,
        timeoutMs: Long = 5_000,
        expectedConnectionAttemptId: Long? = null,
    )

    public fun currentConnectionAttemptId(): Long
    public fun currentMuxSequenceForConnectionAttempt(attemptId: Long): Long?
    public fun isCurrentConnectionAttemptId(attemptId: Long): Boolean
    public fun connectionAttemptStatus(attemptId: Long): HtspConnectionAttemptStatus
    public fun <T> commitIfCurrentConnectionAttempt(attemptId: Long, block: () -> T): T?
    public fun <T> commitIfLiveConnectionAttempt(attemptId: Long, block: () -> T): T?
}

/** Available server timeshift duration in seconds. */
@PlaybackIntegrationApi
public data class PlaybackSubscriptionStart(
    val availableTimeshiftPeriodSec: Int?,
)
