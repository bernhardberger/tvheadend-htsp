package at.bernhardberger.tvheadend.htsp.connection

import java.io.IOException

internal class `HtspRequestTimeoutException-internal`(
    val requestMethod: String,
    val timeoutMs: Long,
    cause: Throwable? = null,
) : IOException("HTSP request timed out", cause)

internal typealias HtspRequestTimeoutException = `HtspRequestTimeoutException-internal`
