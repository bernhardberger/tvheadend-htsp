package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.messages.*

/** Ordered control and packet events for one registered HTSP subscription. */
public sealed interface HtspSubscriptionEvent {
    /** Reports server acceptance and stream metadata for the subscription. */
    public data class Started(
        public val message: HtspSubscriptionStartMessage,
    ) : HtspSubscriptionEvent

    /** Carries one mux packet in committed server order. */
    public data class Packet(
        public val packet: HtspMuxPacketMessage,
    ) : HtspSubscriptionEvent

    /** Reports the result of a server-side subscription skip. */
    public data class Skipped(
        public val message: HtspSubscriptionSkipMessage,
    ) : HtspSubscriptionEvent

    /** Reports the server's terminal subscription stop. */
    public data class Stopped(
        public val message: HtspSubscriptionStopMessage,
    ) : HtspSubscriptionEvent

    /** Reports the current subscription status. */
    public data class Status(
        public val message: HtspSubscriptionStatusMessage,
    ) : HtspSubscriptionEvent

    /** Reports the subscription's grace interval. */
    public data class Grace(
        public val message: HtspSubscriptionGraceMessage,
    ) : HtspSubscriptionEvent

    /** Reports a subscription playback-speed change. */
    public data class Speed(
        public val message: HtspSubscriptionSpeedMessage,
    ) : HtspSubscriptionEvent

    /** Reports the current timeshift state. */
    public data class Timeshift(
        public val message: HtspTimeshiftStatusMessage,
    ) : HtspSubscriptionEvent

    /** Reports subscription queue counters and server-side frame drops. */
    public data class Queue(
        public val message: HtspQueueStatusMessage,
    ) : HtspSubscriptionEvent

    /** Reports tuner signal observations for the subscription. */
    public data class Signal(
        public val message: HtspSignalStatusMessage,
    ) : HtspSubscriptionEvent

    /** Reports descrambling observations for the subscription. */
    public data class Descramble(
        public val message: HtspDescrambleInfoMessage,
    ) : HtspSubscriptionEvent

    /** Reports trusted subscription packets unavailable due to decode rejection or pressure. */
    public data class Dropped(public val count: Long) : HtspSubscriptionEvent {
        init {
            require(count > 0L) { "count must be positive" }
        }
    }

    /** Final event when the stream ends without a server `subscriptionStop`. */
    public data class Terminated(
        public val reason: HtspSubscriptionTermination,
    ) : HtspSubscriptionEvent
}

/** Payload-free reason why a subscription stream ended without a server stop. */
public enum class HtspSubscriptionTermination {
    GENERATION_LOST,
    TRANSPORT_CLOSED,
}
