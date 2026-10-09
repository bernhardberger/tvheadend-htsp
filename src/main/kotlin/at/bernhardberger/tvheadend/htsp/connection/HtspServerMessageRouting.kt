package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.wire.*

internal data class RoutedSubscriptionEvent(
    val subscriptionId: Long,
    val event: HtspSubscriptionEvent,
)

internal sealed interface MalformedSubscriptionMessage {
    data class Packet(val subscriptionId: Long) : MalformedSubscriptionMessage
    data object ControlOrEnvelope : MalformedSubscriptionMessage
}

internal val SUBSCRIPTION_SERVER_METHODS: Set<String> = setOf(
    "muxpkt",
    "queueStatus",
    "subscriptionStart",
    "subscriptionStop",
    "subscriptionGrace",
    "subscriptionStatus",
    "signalStatus",
    "descrambleInfo",
    "subscriptionSpeed",
    "timeshiftStatus",
    "subscriptionSkip",
)

internal val METADATA_SERVER_METHODS: Set<String> = setOf(
    "channelAdd",
    "channelUpdate",
    "channelDelete",
    "tagAdd",
    "tagUpdate",
    "tagDelete",
    "dvrEntryAdd",
    "dvrEntryUpdate",
    "dvrEntryDelete",
    "autorecEntryAdd",
    "autorecEntryUpdate",
    "autorecEntryDelete",
    "timerecEntryAdd",
    "timerecEntryUpdate",
    "timerecEntryDelete",
    "eventAdd",
    "eventUpdate",
    "eventDelete",
    "initialSyncCompleted",
)

internal val ASYNCHRONOUS_SERVER_METHODS: Set<String> =
    METADATA_SERVER_METHODS + SUBSCRIPTION_SERVER_METHODS

internal fun Map<String, Any?>.malformedSubscriptionMessage(): MalformedSubscriptionMessage? {
    val method = this["method"] as? String ?: return null
    if (method !in SUBSCRIPTION_SERVER_METHODS) return null
    if (method != "muxpkt") return MalformedSubscriptionMessage.ControlOrEnvelope
    val subscriptionId = this["subscriptionId"] as? Long
        ?: return MalformedSubscriptionMessage.ControlOrEnvelope
    return if (subscriptionId in 0L..HTSP_U32_MAX) {
        MalformedSubscriptionMessage.Packet(subscriptionId)
    } else {
        MalformedSubscriptionMessage.ControlOrEnvelope
    }
}

internal fun HtspServerMessage.toRoutedSubscriptionEvent(): RoutedSubscriptionEvent? = when (this) {
    is HtspSubscriptionStartMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Started(this))
    is HtspMuxPacketMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Packet(this))
    is HtspSubscriptionSkipMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Skipped(this))
    is HtspSubscriptionStopMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Stopped(this))
    is HtspSubscriptionStatusMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Status(this))
    is HtspSubscriptionGraceMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Grace(this))
    is HtspSubscriptionSpeedMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Speed(this))
    is HtspTimeshiftStatusMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Timeshift(this))
    is HtspQueueStatusMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Queue(this))
    is HtspSignalStatusMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Signal(this))
    is HtspDescrambleInfoMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Descramble(this))
    else -> null
}
