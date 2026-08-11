package at.bernhardberger.tvheadend.htsp

/** Typed outcome for one HTSP request. Cancellation is never represented here. */
public sealed interface HtspResult<out R> {
    /** The request completed successfully with [value]. */
    public data class Ok<out R>(public val value: R) : HtspResult<R>

    /** The server rejected the request or supplied a malformed reply. */
    public data object ServerError : HtspFailure

    /** The server explicitly denied access to the request. */
    public data object AccessDenied : HtspFailure

    /** The server denied the request because the connection limit was reached. */
    public data object ConnectionLimit : HtspFailure

    /** The request exceeded its configured response timeout. */
    public data object Timeout : HtspFailure

    /** No usable transport was available for the request. */
    public data object TransportUnavailable : HtspFailure

    /** The negotiated protocol does not support the request. */
    public data object NotSupported : HtspFailure
}

/** Payload-free failure returned by a typed HTSP request. */
public sealed interface HtspFailure : HtspResult<Nothing>
