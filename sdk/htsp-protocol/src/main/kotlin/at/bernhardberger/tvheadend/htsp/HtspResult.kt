package at.bernhardberger.tvheadend.htsp

import kotlinx.coroutines.CancellationException

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

/** Transforms success while preserving failures and reducing ordinary runtime mapping errors. */
public inline fun <R, T> HtspResult<R>.map(transform: (R) -> T): HtspResult<T> = when (this) {
    is HtspResult.Ok -> try {
        HtspResult.Ok(transform(value))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: RuntimeException) {
        HtspResult.ServerError
    }
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
