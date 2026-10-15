package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspLogger
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import kotlinx.coroutines.CoroutineDispatcher

// region logger
fun connectionWithLogging(ioDispatcher: CoroutineDispatcher): HtspConnection =
    createHtspConnection(
        ioDispatcher = ioDispatcher,
        logger = HtspLogger { level, message, _ -> System.err.println("htsp $level: $message") },
    )
// endregion
