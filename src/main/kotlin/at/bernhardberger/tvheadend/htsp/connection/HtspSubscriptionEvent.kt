package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.messages.*

/**
 * Ordered control and packet events for one registered HTSP subscription.
 *
 * Minor releases may add subtypes; keep an `else` branch when matching.
 */
public sealed interface HtspSubscriptionEvent {
    /**
     * Reports initial or replacement stream metadata for the same subscription.
     *
     * @property message server `subscriptionStart` message describing the streams
     */
    public data class Started(
        public val message: HtspSubscriptionStartMessage,
    ) : HtspSubscriptionEvent

    /**
     * Carries one mux packet in committed server order.
     *
     * @property packet the mux packet
     */
    public data class Packet(
        public val packet: HtspMuxPacketMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports the result of a server-side subscription skip.
     *
     * @property message server `subscriptionSkip` message
     */
    public data class Skipped(
        public val message: HtspSubscriptionSkipMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports a stream interruption, not subscription retirement. A later [Started] may
     * reconfigure the same subscription; keep collecting unless the consumer chooses to cancel.
     *
     * @property message server `subscriptionStop` message
     */
    public data class Stopped(
        public val message: HtspSubscriptionStopMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports the current subscription status.
     *
     * @property message server `subscriptionStatus` message
     */
    public data class Status(
        public val message: HtspSubscriptionStatusMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports the subscription's grace interval.
     *
     * @property message server `subscriptionGrace` message
     */
    public data class Grace(
        public val message: HtspSubscriptionGraceMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports a subscription playback-speed change.
     *
     * @property message server `subscriptionSpeed` message
     */
    public data class Speed(
        public val message: HtspSubscriptionSpeedMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports the current timeshift state.
     *
     * @property message server `timeshiftStatus` message
     */
    public data class Timeshift(
        public val message: HtspTimeshiftStatusMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports subscription queue counters and server-side frame drops.
     *
     * @property message server `queueStatus` message
     */
    public data class Queue(
        public val message: HtspQueueStatusMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports tuner signal observations for the subscription.
     *
     * @property message server `signalStatus` message
     */
    public data class Signal(
        public val message: HtspSignalStatusMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports descrambling observations for the subscription.
     *
     * @property message server `descrambleInfo` message
     */
    public data class Descramble(
        public val message: HtspDescrambleInfoMessage,
    ) : HtspSubscriptionEvent

    /**
     * Reports trusted subscription packets unavailable due to decode rejection or pressure.
     *
     * @property count number of packets lost, where adjacent drops coalesce; always positive
     */
    public data class Dropped(public val count: Long) : HtspSubscriptionEvent {
        init {
            require(count > 0L) { "count must be positive" }
        }
    }

    /**
     * Final event on transport or local retirement or a rejected `subscribe`, including after a server [Stopped].
     *
     * @property reason why the stream ended
     */
    public data class Terminated(
        public val reason: HtspSubscriptionTermination,
    ) : HtspSubscriptionEvent
}

/**
 * Payload-free reason why a subscription stream ended through transport or local retirement.
 *
 * Minor releases may add entries; keep an `else` branch when matching.
 */
public enum class HtspSubscriptionTermination {
    /** The consumer's control queue overflowed; the consumer must send unsubscribe. */
    CONSUMER_OVERFLOW,
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

    /** The server refused the stream's `subscribe` request with an error or access denial. */
    SUBSCRIBE_REJECTED,
}
