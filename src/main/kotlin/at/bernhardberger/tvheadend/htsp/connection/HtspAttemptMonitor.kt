package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.wire.HtspWireMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the attempt monitor and publishes its deferred effects only after the outermost exit. */
internal class HtspAttemptMonitor(
    private val beforeStatePublication: (HtspConnectionState) -> Unit,
) {
    private val _state = MutableStateFlow<HtspConnectionState>(HtspConnectionState.Disconnected)
    val connectionState: StateFlow<HtspConnectionState> = _state.asStateFlow()

    private val _liveConnection = MutableStateFlow<HtspLiveConnection?>(null)
    val liveConnection: StateFlow<HtspLiveConnection?> = _liveConnection.asStateFlow()

    private val connectionAttemptLock = Any()
    private val pendingWakeups = linkedSetOf<Channel<Unit>>()
    private val pendingReplies = mutableListOf<Pair<CompletableDeferred<HtspWireMessage>, HtspWireMessage>>()
    internal data class StateSnapshot(val version: Long, val state: HtspConnectionState, val live: HtspLiveConnection?)
    private var internalState = StateSnapshot(0L, HtspConnectionState.Disconnected, null)
    private var pendingState: StateSnapshot? = null
    private val statePublicationMonitor = Any()
    private var publishedStateVersion = 0L

    fun stateLocked(): StateSnapshot {
        check(Thread.holdsLock(connectionAttemptLock))
        return internalState
    }

    fun recordStateLocked(state: HtspConnectionState, live: HtspLiveConnection? = null) {
        val previous = stateLocked()
        check((state is HtspConnectionState.Connected) == (live != null))
        if (previous.state == state && previous.live == live) return
        internalState = StateSnapshot(previous.version + 1L, state, live)
        pendingState = internalState
    }

    private fun applyState(snapshot: StateSnapshot) {
        check(!Thread.holdsLock(connectionAttemptLock))
        beforeStatePublication(snapshot.state)
        kotlin.synchronized(statePublicationMonitor) {
            if (snapshot.version <= publishedStateVersion) return
            publishedStateVersion = snapshot.version
            // Native StateFlows cannot be updated atomically as a pair. This ordering
            // never exposes Connected with no live connection. A reentrant callback may
            // publish a newer snapshot in either setter: never apply the older tail then.
            if (snapshot.live == null) {
                _state.value = snapshot.state
                if (publishedStateVersion == snapshot.version) _liveConnection.value = null
            } else {
                _liveConnection.value = snapshot.live
                if (publishedStateVersion == snapshot.version) _state.value = snapshot.state
            }
        }
    }

    fun queueWakeup(channel: Channel<Unit>) {
        check(Thread.holdsLock(connectionAttemptLock))
        pendingWakeups.add(channel)
    }

    fun queueReply(reply: CompletableDeferred<HtspWireMessage>, message: HtspWireMessage) {
        check(Thread.holdsLock(connectionAttemptLock))
        pendingReplies.add(reply to message)
    }

    /** Snapshot every deferred effect at the outermost exit, including exceptional exits. */
    inline fun <T> withAttemptLock(block: () -> T): T {
        val outermost = !Thread.holdsLock(connectionAttemptLock)
        var wakeups: List<Channel<Unit>> = emptyList()
        var replies: List<Pair<CompletableDeferred<HtspWireMessage>, HtspWireMessage>> = emptyList()
        var state: StateSnapshot? = null
        try {
            return kotlin.synchronized(connectionAttemptLock) {
                try { block() } finally {
                    if (outermost) {
                        wakeups = pendingWakeups.toList()
                        pendingWakeups.clear()
                        replies = pendingReplies.toList()
                        pendingReplies.clear()
                        state = pendingState
                        pendingState = null
                    }
                }
            }
        } finally {
            // State first: a section that recorded state also holds back its replies and wakeups
            // while publication waits for the publication monitor. Only failure and lifecycle
            // sections record state, and their pending replies are failing anyway.
            try {
                state?.let { applyState(it) }
            } finally {
                try {
                    replies.forEach { (reply, message) -> reply.complete(message) }
                } finally {
                    wakeups.forEach { it.trySend(Unit) }
                }
            }
        }
    }
}
