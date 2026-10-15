package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionTermination
import at.bernhardberger.tvheadend.htsp.messages.HtspFrameType
import at.bernhardberger.tvheadend.htsp.messages.HtspMuxPacketMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionStartMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimeshiftStatusMessage
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionTimestampClock
import at.bernhardberger.tvheadend.htsp.requests.subscribe
import at.bernhardberger.tvheadend.htsp.requests.subscriptionSkipNearLive
import at.bernhardberger.tvheadend.htsp.requests.unsubscribe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// region stream-handler
class StreamHandler(
    private val onStarted: (HtspSubscriptionStartMessage) -> Unit,
    private val onPacket: (HtspMuxPacketMessage) -> Unit,
    /** Gets the `Terminated` reason, or null when the flow completed without one. */
    private val onEnded: (HtspSubscriptionTermination?) -> Unit,
) {
    /** The latest timeshift status, which `subscriptionSkipNearLive` needs. */
    @Volatile
    var timeshift: HtspTimeshiftStatusMessage? = null
        private set

    /** Streams that have had a keyframe since the last start or gap. */
    private val synced = mutableSetOf<Long>()

    private var termination: HtspSubscriptionTermination? = null

    /** Call once the flow has completed, after the last `handle`. */
    fun flowCompleted() = onEnded(termination)

    fun handle(event: HtspSubscriptionEvent) {
        when (event) {
            is HtspSubscriptionEvent.Started -> {
                synced.clear()
                onStarted(event.message)
            }
            is HtspSubscriptionEvent.Packet -> {
                val packet = event.packet
                if (packet.frameType == HtspFrameType.I) synced += packet.streamIndex
                val predicted = packet.frameType == HtspFrameType.P || packet.frameType == HtspFrameType.B
                if (!predicted || packet.streamIndex in synced) onPacket(packet)
            }
            is HtspSubscriptionEvent.Dropped -> synced.clear()
            is HtspSubscriptionEvent.Timeshift -> timeshift = event.message
            is HtspSubscriptionEvent.Terminated -> termination = event.reason
            else -> Unit
        }
    }
}
// endregion

// region subscribe
suspend fun startStream(
    scope: CoroutineScope,
    connection: HtspConnection,
    live: HtspLiveConnection,
    subscriptionId: Long,
    channelId: Long,
    handler: StreamHandler,
): Job? {
    val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        connection.subscriptionEvents(subscriptionId, live.generation).collect(handler::handle)
        handler.flowCompleted()
    }
    var result: HtspResult<*>? = null
    try {
        result = connection.subscribe(
            subscriptionId = subscriptionId,
            channelId = channelId,
            timeshiftPeriodSeconds = 3_600L,
            expectedGeneration = live.generation,
        )
        return if (result is HtspResult.Ok) collector else null
    } finally {
        if (result !is HtspResult.Ok) {
            collector.cancel()
            // null: cancelled while the request may already have reached the server.
            if (result == null || result is HtspResult.Timeout || result is HtspResult.MalformedReply) {
                withContext(NonCancellable) {
                    connection.unsubscribe(subscriptionId, expectedGeneration = live.generation)
                }
            }
        }
    }
}
// endregion

// region stop
suspend fun stopStream(connection: HtspConnection, live: HtspLiveConnection, subscriptionId: Long) {
    connection.unsubscribe(subscriptionId, expectedGeneration = live.generation)
}
// endregion

// region near-live
suspend fun jumpNearLive(
    connection: HtspConnection,
    live: HtspLiveConnection,
    handler: StreamHandler,
    ninetyKhz: Boolean,
): HtspResult<*>? {
    val status = handler.timeshift ?: return null
    if (status.start == null || status.end == null) return null
    val clock = if (ninetyKhz) SubscriptionTimestampClock.NINETY_KHZ else SubscriptionTimestampClock.MICROSECONDS
    return connection.subscriptionSkipNearLive(
        status = status,
        clock = clock,
        marginSeconds = 5L,
        expectedGeneration = live.generation,
    )
}
// endregion
