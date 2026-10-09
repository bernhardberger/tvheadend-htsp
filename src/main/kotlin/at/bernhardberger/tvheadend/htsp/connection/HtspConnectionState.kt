package at.bernhardberger.tvheadend.htsp.connection

/** Connection lifecycle state exposed as finite typed snapshots. */
public sealed class HtspConnectionState {
    /** No transport is currently connected or connecting. */
    public data object Disconnected : HtspConnectionState()
    /** A connection attempt is in progress for the recorded host and port. */
    public data class Connecting(val host: String, val port: Int) : HtspConnectionState()
    /**
     * @param protocolVersion Negotiated HTSP `htspversion`, a unitless version number;
     * null means unknown (pinned htsp_server.c:1474–1487).
     * @param dvrAccess HTSP `ACCESS_HTSP_RECORDER` from authenticate (version ≥ 26).
     * null when unauthenticated or the field was not returned.
     */
    public data class Connected(
        val host: String,
        val port: Int,
        val protocolVersion: Int?,
        val dvrAccess: Boolean? = null,
    ) : HtspConnectionState()
    /** A connection attempt or active transport failed. */
    public data class Error(val failure: HtspTransportFailure) : HtspConnectionState()
}
