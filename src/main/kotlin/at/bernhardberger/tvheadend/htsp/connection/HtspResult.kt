package at.bernhardberger.tvheadend.htsp.connection

/** Typed outcome for one HTSP request. Cancellation is never represented here. */
public sealed interface HtspResult<out R> {
    /** The request completed successfully with [value]. */
    public data class Ok<out R>(public val value: R) : HtspResult<R>

    /**
     * The server rejected the request.
     *
     * [serverMessage] is the reply's `error` string whenever present, and `null` otherwise,
     * including for an unsuccessful DVR acknowledgement without error text.
     * TVHeadend sends fixed
     * messages translated into the connection's language. Treat the text as untrusted display
     * text, not as a stable code. [toString] never renders it.
     */
    @ConsistentCopyVisibility
    public data class ServerError private constructor(
        public val serverMessage: String?,
        private val evolution: Unit,
    ) : HtspFailure {
        public constructor(serverMessage: String? = null) : this(serverMessage, Unit)

        /** Copies this failure, optionally replacing the server's error text. */
        public fun copy(serverMessage: String? = this.serverMessage): ServerError = ServerError(serverMessage)

        override fun toString(): String =
            "ServerError(serverMessage=${if (serverMessage == null) "null" else "<redacted>"})"
    }

    /**
     * A reply arrived but could not be decoded: an envelope field was malformed or the typed
     * decoder could not map the reply to the protocol model. Carries no text or throwable.
     *
     * The server may have performed the request. A subscribe may have created a server-side
     * subscription; its stream stays open, so send unsubscribe if it is not wanted. Do not
     * blindly retry a non-idempotent request such as addDvrEntry.
     */
    public data object MalformedReply : HtspFailure

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

/**
 * Failure returned by a typed HTSP request. Only [HtspResult.ServerError] carries a value: the
 * server's own error text.
 */
public sealed interface HtspFailure : HtspResult<Nothing>

/** Returns the success value, or `null` for any failure. */
public fun <R> HtspResult<R>.getOrNull(): R? = when (this) {
    is HtspResult.Ok -> value
    is HtspFailure -> null
}

/** Returns the success value, or the value produced for the exact failure. */
public inline fun <R> HtspResult<R>.getOrElse(onFailure: (HtspFailure) -> R): R = when (this) {
    is HtspResult.Ok -> value
    is HtspFailure -> onFailure(this)
}

/** Invokes [action] for success and returns this exact result. */
public inline fun <R> HtspResult<R>.onOk(action: (R) -> Unit): HtspResult<R> {
    if (this is HtspResult.Ok) action(value)
    return this
}

/** Invokes [action] for failure and returns this exact result. */
public inline fun <R> HtspResult<R>.onFailure(action: (HtspFailure) -> Unit): HtspResult<R> {
    if (this is HtspFailure) action(this)
    return this
}

/** Transforms success while preserving failures. Transform failures propagate unchanged. */
public inline fun <R, T> HtspResult<R>.map(transform: (R) -> T): HtspResult<T> = when (this) {
    is HtspResult.Ok -> HtspResult.Ok(transform(value))
    is HtspFailure -> this
}

/** Reduces this result by invoking exactly one branch. */
public inline fun <R, T> HtspResult<R>.fold(
    onOk: (R) -> T,
    onFailure: (HtspFailure) -> T,
): T = when (this) {
    is HtspResult.Ok -> onOk(value)
    is HtspFailure -> onFailure(this)
}
