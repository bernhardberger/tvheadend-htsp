package at.bernhardberger.tvheadend.htsp.connection

/**
 * The client identity used by the connection. [clientName] is sent in `hello`;
 * [clientVersion] is retained for identity and diagnostics and is not transmitted by the
 * current [at.bernhardberger.tvheadend.htsp.requests.HelloRequest].
 */
public data class HtspClientIdentity(
    val clientName: String,
    val clientVersion: String,
) {
    public companion object {
        /** Identity used when none is supplied: client name `Kotlin HTSP client`, version `unknown`. */
        public val Default: HtspClientIdentity = HtspClientIdentity(
            clientName = "Kotlin HTSP client",
            clientVersion = "unknown",
        )
    }
}

/**
 * Severity vocabulary accepted by [HtspLogger].
 *
 * Minor releases may add entries; keep an `else` branch when matching.
 */
public enum class HtspLogLevel {
    /** A recoverable or degraded condition. */
    WARNING,

    /** A failed operation. */
    ERROR,
}

/** Caller-supplied sink for bounded protocol transport diagnostics. */
public fun interface HtspLogger {
    /** Records one bounded diagnostic at [level], with an optional implementation cause. */
    public fun log(level: HtspLogLevel, message: String, cause: Throwable?)

    public companion object {
        /** Logger that discards every diagnostic. */
        public val None: HtspLogger = HtspLogger { _, _, _ -> }
    }
}
