package at.bernhardberger.tvheadend.htsp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class `HtspEventStream-internal`(extraBufferCapacity: Int = 256) {
    private val mutableEvents = MutableSharedFlow<HtspControlEvent>(
        extraBufferCapacity = extraBufferCapacity
    )

    val events: SharedFlow<HtspControlEvent> = mutableEvents.asSharedFlow()

    suspend fun emit(event: HtspControlEvent) {
        mutableEvents.emit(event)
    }
}

internal typealias HtspEventStream = `HtspEventStream-internal`
