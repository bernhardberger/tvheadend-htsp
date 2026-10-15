package at.bernhardberger.tvheadend.htsp.requests

/**
 * Pinned server-dispatch access metadata. The server remains authoritative.
 *
 * Minor releases may add entries; keep an `else` branch when matching.
 */
public enum class HtspAccess {
    ACCESS_HTSP_STREAMING,
    ACCESS_HTSP_RECORDER,
    ACCESS_ANONYMOUS,
}

/** A typed request whose raw HTSP envelope and reply mapping remain ABI-hidden. */
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
