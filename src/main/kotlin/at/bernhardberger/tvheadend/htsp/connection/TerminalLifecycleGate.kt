package at.bernhardberger.tvheadend.htsp.connection

/** Serializes terminal close with the small state mutation that admits new work. */
internal class `TerminalLifecycleGate-internal`(
    private val closedMessage: String,
) {
    private val lock = Any()
    private var closed = false

    fun <T> admit(block: () -> T): T = synchronized(lock) {
        if (closed) throw TerminalLifecycleClosedException(closedMessage)
        block()
    }

    fun <T : Any> close(block: () -> T): T? = synchronized(lock) {
        if (closed) return@synchronized null
        closed = true
        block()
    }

    fun checkOpen() {
        synchronized(lock) {
            if (closed) throw TerminalLifecycleClosedException(closedMessage)
        }
    }
}

internal typealias TerminalLifecycleGate = `TerminalLifecycleGate-internal`

internal class `TerminalLifecycleClosedException-internal`(message: String) : IllegalStateException(message)

internal typealias TerminalLifecycleClosedException = `TerminalLifecycleClosedException-internal`
