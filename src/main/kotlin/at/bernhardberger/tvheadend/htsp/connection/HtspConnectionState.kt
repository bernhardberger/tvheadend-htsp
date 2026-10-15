package at.bernhardberger.tvheadend.htsp.connection

/** Connection lifecycle state exposed as finite typed snapshots. */
public sealed class HtspConnectionState {
    /** No transport is currently connected or connecting. */
    public data object Disconnected : HtspConnectionState()
    /** Terminal owner close; this client cannot reconnect. */
    public data object Closed : HtspConnectionState()
    /** A connection attempt is in progress for the recorded host and port. */
    public data class Connecting(val host: String, val port: Int) : HtspConnectionState()
    /**
     * One atomic snapshot of the endpoint and its live generation, including handshake facts.
     * @param connection The live connection belonging to this state snapshot.
     */
    public data class Connected(
        val host: String,
        val port: Int,
        val connection: HtspLiveConnection,
    ) : HtspConnectionState()
    /** A connection attempt or active transport failed. */
    public data class Error(val failure: HtspTransportFailure) : HtspConnectionState()
}
