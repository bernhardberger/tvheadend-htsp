package at.bernhardberger.tvheadend.client

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class `HtspEventStream-internal`(extraBufferCapacity: Int = 256) {
    private val mutableEvents = MutableSharedFlow<HtspEvent>(
        extraBufferCapacity = extraBufferCapacity
    )

    val events: SharedFlow<HtspEvent> = mutableEvents.asSharedFlow()

    suspend fun emit(event: HtspEvent) {
        mutableEvents.emit(event)
    }
}

internal typealias HtspEventStream = `HtspEventStream-internal`
