package at.bernhardberger.tvheadend.htsp.connection

/**
 * Describes how one generation's transport ended.
 *
 * Library-created connections latch exactly one result, available through
 * [HtspConnection.awaitTermination] before or after the end. That call checks token ownership
 * before suspending. A generation that never reached [HtspConnectionState.Connected] ends as
 * [Disconnected]. The out-of-band result may precede delivery of earlier metadata;
 * [HtspTransportEvent.ConnectionFailure] in [HtspConnection.events] remains the in-order signal.
 * Custom connections inherit a best-effort state-based default without latching, ownership
 * checks, or precise error attribution. Minor releases may add subtypes; keep an `else` branch.
 */
public sealed interface HtspGenerationTermination {
    /**
     * Reports the same unsolicited failure as this generation's [HtspTransportEvent.ConnectionFailure].
     * @property failure the transport failure
     */
    public data class Failed(public val failure: HtspTransportFailure) : HtspGenerationTermination

    /** Ends through owner disconnect, close, replacement, or another teardown without a failure event. */
    public data object Disconnected : HtspGenerationTermination
}

/** Connection lifecycle state exposed as finite typed snapshots. */
public sealed class HtspConnectionState {
    /** No transport is currently connected or connecting. */
    public data object Disconnected : HtspConnectionState()
    /** Terminal owner close; this client cannot reconnect. */
    public data object Closed : HtspConnectionState()
    /**
     * A connection attempt is in progress for the recorded host and port.
     *
     * @property host host name or address being connected to
     * @property port TCP port being connected to
     */
    public data class Connecting(val host: String, val port: Int) : HtspConnectionState() {
        override fun toString(): String = "Connecting(<redacted>)"
    }
    /**
     * One atomic snapshot of the endpoint and its live generation, including handshake facts.
     * @property host host name or address of the connected endpoint
     * @property port TCP port of the connected endpoint
     * @property connection The live connection belonging to this state snapshot.
     */
    public data class Connected(
        val host: String,
        val port: Int,
        val connection: HtspLiveConnection,
    ) : HtspConnectionState() {
        override fun toString(): String = "Connected(<redacted>)"
    }
    /**
     * A connection attempt or active transport failed.
     *
     * @property failure why the attempt or transport failed
     */
    public data class Error(val failure: HtspTransportFailure) : HtspConnectionState()
}
