package at.bernhardberger.tvheadend.htsp.connection

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

public enum class HtspLogLevel {
    WARNING,
    ERROR,
}

public fun interface HtspLogger {
    public fun log(level: HtspLogLevel, message: String, cause: Throwable?)

    public companion object {
        public val None: HtspLogger = HtspLogger { _, _, _ -> }
    }
}
