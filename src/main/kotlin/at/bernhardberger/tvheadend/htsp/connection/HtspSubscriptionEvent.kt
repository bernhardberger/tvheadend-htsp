package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.messages.*

/** Ordered control and packet events for one registered HTSP subscription. */
public sealed interface HtspSubscriptionEvent {
    /** Reports initial or replacement stream metadata for the same subscription. */
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

    /**
     * Reports a stream interruption, not subscription retirement. A later [Started] may
     * reconfigure the same subscription; keep collecting unless the consumer chooses to cancel.
     */
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

    /** Final event on transport or local retirement, including after a server [Stopped]. */
    public data class Terminated(
        public val reason: HtspSubscriptionTermination,
    ) : HtspSubscriptionEvent
}

/** Payload-free reason why a subscription stream ended through transport or local retirement. */
public enum class HtspSubscriptionTermination {
    /** A newer connection generation replaced the stream's generation. */
    GENERATION_LOST,

    /** The remote peer ended the transport between HTSP frames. */
    REMOTE_EOF,

    /** The transport failed while reading an HTSP frame. */
    IO_FAILURE,

    /** The remote byte stream violated HTSP framing. */
    FRAMING_FAILURE,

    /** A recognized server message had an incompatible envelope or payload. */
    MALFORMED_MESSAGE,

    /** A transport-level timeout retired the stream. */
    TIMEOUT,

    /** A local disconnect, close, or captured-generation retirement ended the stream. */
    LOCAL_RETIREMENT,

    /** The transport's typed-event publication boundary failed. */
    PUBLICATION_FAILURE,

    /** An otherwise unclassified internal reader failure ended the stream. */
    INTERNAL_FAILURE,
}
