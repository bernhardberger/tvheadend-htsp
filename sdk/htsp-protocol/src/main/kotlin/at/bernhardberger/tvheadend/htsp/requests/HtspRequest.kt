package at.bernhardberger.tvheadend.htsp.requests

/** Pinned server-dispatch access metadata. The server remains authoritative. */
public enum class HtspAccess {
    ACCESS_HTSP_STREAMING,
    ACCESS_HTSP_RECORDER,
    ACCESS_ANONYMOUS,
}

/** A typed request whose raw HTSP envelope and reply mapping remain ABI-hidden. */
public abstract class HtspRequest<R> internal constructor(
    public val method: String,
    public val access: HtspAccess,
    public val minimumProtocolVersion: Int?,
    @Suppress("UNUSED_PARAMETER")
    constructorMarker: HtspRequestConstructorMarker = HtspRequestConstructorMarker,
)

internal object `HtspRequestConstructorMarker-internal`

internal typealias HtspRequestConstructorMarker =
    `HtspRequestConstructorMarker-internal`
