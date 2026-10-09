package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelAddMessage
import at.bernhardberger.tvheadend.htsp.requests.enableAsyncMetadataAwaitingInitialSync
import at.bernhardberger.tvheadend.htsp.requests.getDiskSpace
import at.bernhardberger.tvheadend.htsp.requests.getProfiles
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import at.bernhardberger.tvheadend.htsp.requests.subscribe
import at.bernhardberger.tvheadend.htsp.requests.unsubscribe
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Opt-in, read-only smoke test against a real TVHeadend server; skipped unless
 * `HTSP_LIVE_HOST` is set. Optional: `HTSP_LIVE_PORT` (9982), `HTSP_LIVE_USERNAME`,
 * `HTSP_LIVE_PASSWORD`. Run with
 * `./gradlew test --tests '*HtspLiveServerSmokeTest' --rerun`.
 *
 * It connects, syncs metadata without EPG, issues read-only requests, and
 * subscribes to the first channel until the first `Started` event or a
 * server-reported subscription error. It never prints the endpoint or credentials.
 */
@EnabledIfEnvironmentVariable(named = "HTSP_LIVE_HOST", matches = ".+")
class HtspLiveServerSmokeTest {
    @Test
    fun readOnlySessionMetadataAndShortSubscription() = runBlocking {
        val environment = System.getenv()
        val endpoint = HtspEndpoint(
            host = environment.getValue("HTSP_LIVE_HOST"),
            port = environment["HTSP_LIVE_PORT"]?.toInt() ?: 9_982,
            username = environment["HTSP_LIVE_USERNAME"].orEmpty(),
            password = environment["HTSP_LIVE_PASSWORD"].orEmpty(),
        )
        val connection = createHtspConnection(ioDispatcher = Dispatchers.IO)
        val channelIds = ConcurrentLinkedQueue<Long>()
        coroutineScope {
            val metadata = launch(start = CoroutineStart.UNDISPATCHED) {
                connection.events
                    .filterIsInstance<HtspTransportEvent.ServerMessage>()
                    .collect { event -> (event.message as? HtspChannelAddMessage)?.let { channelIds += it.channelId } }
            }
            try {
                val outcome = withTimeout(30_000L) { connection.connect(endpoint) }
                assertTrue(outcome is HtspConnectOutcome.Connected, "connect: $outcome")
                val live = (outcome as HtspConnectOutcome.Connected).connection
                val protocolVersion = live.protocolVersion
                assertTrue(protocolVersion != null && protocolVersion in 1..44, "protocol version: $protocolVersion")

                val sync = connection.enableAsyncMetadataAwaitingInitialSync(timeoutMs = 60_000L)
                assertTrue(sync is HtspResult.Ok, "initial sync: $sync")
                val sysTime = connection.getSysTime()
                assertTrue(sysTime is HtspResult.Ok, "getSysTime: $sysTime")
                val profiles = connection.getProfiles()
                assertTrue(profiles is HtspResult.Ok, "getProfiles: $profiles")
                val diskSpace = connection.getDiskSpace()
                assertTrue(diskSpace is HtspResult.Ok || diskSpace === HtspResult.AccessDenied, "getDiskSpace: $diskSpace")

                val subscription = channelIds.firstOrNull()?.let { channelId -> subscribeBriefly(connection, channelId) }
                println(
                    "HTSP live smoke: server=${live.serverFacts.serverName} ${live.serverFacts.serverVersion}, " +
                        "htsp=$protocolVersion, channels=${channelIds.size}, diskSpace=${diskSpace::class.simpleName}, " +
                        "subscription=${subscription ?: "skipped (no channels)"}",
                )
            } finally {
                withContext(NonCancellable) {
                    metadata.cancelAndJoin()
                    connection.disconnect()
                    connection.close()
                }
            }
        }
    }

    private suspend fun subscribeBriefly(connection: HtspConnection, channelId: Long): String = coroutineScope {
        val subscriptionId = 1L
        val events = Channel<HtspSubscriptionEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            connection.subscriptionEvents(subscriptionId).collect { events.send(it) }
            events.close()
        }
        val subscribed = connection.subscribe(subscriptionId = subscriptionId, channelId = channelId)
        if (subscribed !is HtspResult.Ok) {
            collector.cancelAndJoin()
            return@coroutineScope "subscribe=$subscribed"
        }
        val first = withTimeout(20_000L) {
            var result: String? = null
            while (result == null) {
                result = when (val event = events.receive()) {
                    is HtspSubscriptionEvent.Started -> "started streams=${event.message.streams?.size}"
                    is HtspSubscriptionEvent.Status -> event.message.subscriptionError?.let { "error=$it" }
                    is HtspSubscriptionEvent.Stopped, is HtspSubscriptionEvent.Terminated -> "ended before start: $event"
                    else -> null
                }
            }
            result
        }
        val unsubscribed = connection.unsubscribe(subscriptionId)
        assertTrue(unsubscribed is HtspResult.Ok, "unsubscribe: $unsubscribed")
        withTimeout(10_000L) { collector.join() }
        first
    }
}
