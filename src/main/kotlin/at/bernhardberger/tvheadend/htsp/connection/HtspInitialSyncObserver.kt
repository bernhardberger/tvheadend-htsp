package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import kotlinx.coroutines.channels.Channel

/** Unbudgeted latches, written under the attempt lock and observed after deferred wakeup. */
internal class HtspInitialSyncObserver(val generation: HtspConnectionGeneration) {
    val changed = Channel<Unit>(Channel.CONFLATED)
    @Volatile var completed = false
        private set
    @Volatile var failed = false
        private set

    fun offer(event: HtspTransportEvent): Boolean {
        if (event.generation !== generation) return false
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
}
