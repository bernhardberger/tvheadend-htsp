package at.bernhardberger.tvheadend.htsp

/** Typed outcome for one HTSP request. Cancellation is never represented here. */
public sealed interface HtspResult<out R> {
    /** The request completed successfully with [value]. */
    public data class Ok<out R>(public val value: R) : HtspResult<R>

    /** The server rejected the request or supplied a malformed reply. */
    public data class ServerError(public val message: String? = null) : HtspResult<Nothing>

    /** The server explicitly denied access to the request. */
    public data object AccessDenied : HtspResult<Nothing>

    /** The request exceeded its configured response timeout. */
    public data object Timeout : HtspResult<Nothing>

    /** No usable transport was available for the request. */
    public data object TransportUnavailable : HtspResult<Nothing>

    /** The negotiated protocol does not support the request. */
    public data object NotSupported : HtspResult<Nothing>
}
