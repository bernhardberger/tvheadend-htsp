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
        public val Default: HtspClientIdentity = HtspClientIdentity(
            clientName = "Kotlin HTSP client",
            clientVersion = "unknown",
        )
    }
}

/** Finite severity vocabulary accepted by [HtspLogger]. */
public enum class HtspLogLevel {
    WARNING,
    ERROR,
}

/** Caller-supplied sink for bounded protocol transport diagnostics. */
public fun interface HtspLogger {
    /** Records one bounded diagnostic at [level], with an optional implementation cause. */
    public fun log(level: HtspLogLevel, message: String, cause: Throwable?)

    public companion object {
        public val None: HtspLogger = HtspLogger { _, _, _ -> }
    }
}
