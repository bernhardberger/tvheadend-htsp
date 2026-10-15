package at.bernhardberger.tvheadend.protocolconsumer

// region quick-start
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.connection.getOrNull
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import kotlinx.coroutines.Dispatchers

suspend fun main() {
    val connection = createHtspConnection(ioDispatcher = Dispatchers.IO)
    try {
        val endpoint = HtspEndpoint("tvh.example.com", 9982, username = "user", password = "secret")
        when (val outcome = connection.connect(endpoint)) {
            is HtspConnectOutcome.Failed ->
                println("Could not connect: ${outcome.failure.kind}")
            is HtspConnectOutcome.Connected -> {
                val serverTime = connection.getSysTime().getOrNull()
                println("Server time: ${serverTime?.timeEpochSeconds}")
            }
        }
    } finally {
        connection.close()
    }
}
// endregion
