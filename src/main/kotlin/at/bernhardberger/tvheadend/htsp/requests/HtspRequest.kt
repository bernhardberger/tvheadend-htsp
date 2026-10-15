package at.bernhardberger.tvheadend.htsp.requests

/**
 * Pinned server-dispatch access metadata. The server remains authoritative.
 *
 * Minor releases may add entries; keep an `else` branch when matching.
 */
public enum class HtspAccess {
    /** Streaming right; also covers metadata, EPG and subscription methods. */
    ACCESS_HTSP_STREAMING,

    /** Recorder right, which DVR methods require. */
    ACCESS_HTSP_RECORDER,

    /** No access right required, as for `hello` and `authenticate`. */
    ACCESS_ANONYMOUS,
}

/**
 * A typed request whose raw HTSP envelope and reply mapping remain ABI-hidden.
 *
 * @property method HTSP method name sent on the wire (`method`)
 * @property access access right the server's dispatch table requires for [method]
 */
public abstract class HtspRequest<R> internal constructor(
    public val method: String,
    public val access: HtspAccess,
    minimumProtocolVersion: Int?,
    @Suppress("UNUSED_PARAMETER")
    constructorMarker: HtspRequestConstructorMarker = HtspRequestConstructorMarker,
) {
    /** Minimum evidenced HTSP protocol version, or null when no gate is known. */
    public val minimumProtocolVersion: Long? = minimumProtocolVersion?.toLong()
}

internal object `HtspRequestConstructorMarker-internal`

internal typealias HtspRequestConstructorMarker =
    `HtspRequestConstructorMarker-internal`
