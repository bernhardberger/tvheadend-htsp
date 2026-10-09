package at.bernhardberger.tvheadend.htsp.connection

import java.io.InputStream
import java.net.SocketTimeoutException

internal class HtspTransportInputStream(
    private val delegate: InputStream,
    private val logger: HtspLogger,
    private val timeoutGraceMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) : InputStream() {
    private var currentFrameBytesRead = 0
    private var currentFrameTimeouts = 0
    private var timeoutStreakStartedAtNanos: Long? = null

    fun beginFrame() {
        currentFrameBytesRead = 0
        currentFrameTimeouts = 0
        timeoutStreakStartedAtNanos = null
    }

    fun frameBytesRead(): Int = currentFrameBytesRead

    override fun read(): Int = retryMidFrameTimeout {
        delegate.read().also { value ->
            if (value >= 0) {
                currentFrameBytesRead++
                timeoutStreakStartedAtNanos = null
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = retryMidFrameTimeout {
        delegate.read(buffer, offset, length).also { count ->
            if (count > 0) {
                currentFrameBytesRead += count
                timeoutStreakStartedAtNanos = null
            }
        }
    }

    override fun close() {
        delegate.close()
    }

    private inline fun retryMidFrameTimeout(read: () -> Int): Int {
        while (true) {
            try {
                return read()
            } catch (timeout: SocketTimeoutException) {
                if (currentFrameBytesRead == 0) throw timeout
                val now = nanoTime()
                val started = timeoutStreakStartedAtNanos
                if (started != null && (now - started) / 1_000_000L >= timeoutGraceMs) throw timeout
                // Allow a transient timeout without sampling the clock on every decoded byte.
                if (started == null) timeoutStreakStartedAtNanos = now
                currentFrameTimeouts++
                if (currentFrameTimeouts == 1 || currentFrameTimeouts % 50 == 0) {
                    logger.log(
                        HtspLogLevel.WARNING,
                        "SO_TIMEOUT after partial current HTSP frame; continuing exact read",
                        timeout,
                    )
                }
            }
        }
    }
}
