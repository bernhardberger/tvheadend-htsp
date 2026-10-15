package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.HtspAttemptMonitor
import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionState
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspService

internal fun HtspConnection.attemptLockForTest(): Any {
    val state = HtspService::class.java.getDeclaredField("attemptMonitor")
        .apply { isAccessible = true }.get(this)
    return HtspAttemptMonitor::class.java.getDeclaredField("connectionAttemptLock")
        .apply { isAccessible = true }.get(state)
}

internal val HtspConnection.currentLiveConnection: HtspLiveConnection?
    get() = (connectionState.value as? HtspConnectionState.Connected)?.connection
