package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import kotlinx.coroutines.channels.Channel

/**
 * Unbudgeted latches, written under the attempt lock and observed after deferred wakeup.
 * Progress is a timestamp and a count only: metadata frames never wake the waiter, which re-arms
 * from the timestamp only when the count moved during its wait.
 */
internal class HtspInitialSyncObserver(
    val generation: HtspConnectionGeneration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    val changed = Channel<Unit>(Channel.CONFLATED)
    @Volatile var completed = false
        private set
    @Volatile var failed = false
        private set
    @Volatile private var lastProgressNanos = nanoTime()

    /** Progress records so far; written and read under the attempt lock, independent of the clock. */
    var progressCount = 0L
        private set

    fun offer(event: HtspTransportEvent): Boolean {
        if (event.generation !== generation) return false
        if (event is HtspTransportEvent.ServerMessage || event is HtspTransportEvent.MalformedMetadata) {
            recordProgress()
        }
        return when {
            event is HtspTransportEvent.ServerMessage && event.message === HtspInitialSyncCompletedMessage && !completed -> {
                completed = true
                true
            }
            event is HtspTransportEvent.ConnectionFailure && !failed -> {
                failed = true
                true
            }
            else -> false
        }
    }

    /** Records progress that did not arrive as a metadata event; called under the attempt lock. */
    fun recordProgress() {
        progressCount++
        lastProgressNanos = nanoTime()
    }

    /** Milliseconds, rounded up, until [idleTimeoutMs] passes without progress; zero or less once it has. */
    fun remainingIdleMs(idleTimeoutMs: Long): Long {
        val idleNanos = if (idleTimeoutMs > Long.MAX_VALUE / NANOS_PER_MILLI) Long.MAX_VALUE else idleTimeoutMs * NANOS_PER_MILLI
        val remainingNanos = idleNanos - (nanoTime() - lastProgressNanos).coerceAtLeast(0L)
        return remainingNanos / NANOS_PER_MILLI + if (remainingNanos > 0L && remainingNanos % NANOS_PER_MILLI != 0L) 1L else 0L
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
