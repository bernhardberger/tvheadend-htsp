package at.bernhardberger.tvheadend.client

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class `HtspConnectionProbe-internal`(
    private val ioDispatcher: CoroutineDispatcher,
    private val clientIdentity: HtspClientIdentity = HtspClientIdentity.Default,
    private val logger: HtspLogger = HtspLogger.None,
) {
    suspend fun test(
        host: String,
        port: Int,
        username: String,
        password: String,
    ): HtspProbeResult {
        val service = HtspService(
            ioDispatcher = ioDispatcher,
            clientIdentity = clientIdentity,
            logger = logger,
        )
        return runHtspConnectionProbe(
            session = HtspServiceProbeSession(
                service = service,
                host = host,
                port = port,
                username = username,
                password = password,
                ioDispatcher = ioDispatcher,
            ),
        )
    }

    private class HtspServiceProbeSession(
        private val service: HtspService,
        private val host: String,
        private val port: Int,
        private val username: String,
        private val password: String,
        private val ioDispatcher: CoroutineDispatcher,
    ) : HtspProbeSession {
        override suspend fun connect(): Int {
            service.connect(
                host = host,
                port = port,
                username = username,
                password = password,
                forceReconnect = true,
                connectTimeoutMs = 10_000,
                responseTimeoutMs = 5_000,
            )
            return (service.state.value as? ConnectionState.Connected)?.htspVersion ?: 0
        }

        override suspend fun syncChannelMetadata(): Int = coroutineScope {
            val channelIds = ConcurrentHashMap.newKeySet<Int>()
            val completed = CompletableDeferred<Int>()
            val collector = launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
                service.controlEvents.collect { event ->
                    val message = (event as? HtspEvent.ServerMessage)?.msg ?: return@collect
                    when (message.method) {
                        "channelAdd", "channelUpdate" ->
                            message.int("channelId")?.let(channelIds::add)
                        "channelDelete" -> message.int("channelId")?.let(channelIds::remove)
                        "initialSyncCompleted" -> completed.complete(channelIds.size)
                    }
                }
            }

            try {
                service.enableAsyncMetadataAndWaitInitialSync()
                withTimeout(30_000) { completed.await() }
            } finally {
                collector.cancelAndJoin()
            }
        }

        override suspend fun close() {
            service.close()
        }
    }
}

internal typealias HtspConnectionProbe = `HtspConnectionProbe-internal`

internal sealed interface `HtspProbeResult-internal` {
    data class Success(
        val serverVersion: Int,
        val channelCount: Int,
    ) : `HtspProbeResult-internal`

    data class Failure(val error: Throwable) : `HtspProbeResult-internal`
}

internal typealias HtspProbeResult = `HtspProbeResult-internal`
internal typealias HtspProbeSuccess = `HtspProbeResult-internal`.Success
internal typealias HtspProbeFailure = `HtspProbeResult-internal`.Failure

internal interface `HtspProbeSession-internal` {
    suspend fun connect(): Int
    suspend fun syncChannelMetadata(): Int
    suspend fun close()
}

internal typealias HtspProbeSession = `HtspProbeSession-internal`

internal class `HtspIncompatibleServerVersionException-internal`(val serverVersion: Int) :
    IllegalStateException("Incompatible HTSP server version")

internal typealias HtspIncompatibleServerVersionException =
    `HtspIncompatibleServerVersionException-internal`

internal class `HtspZeroChannelsException-internal` :
    IllegalStateException("HTSP initial sync contained zero channels")

internal typealias HtspZeroChannelsException = `HtspZeroChannelsException-internal`

private const val MINIMUM_HTSP_VERSION = 19

@JvmSynthetic
internal suspend fun runHtspConnectionProbe(session: HtspProbeSession): HtspProbeResult = try {
    val serverVersion = session.connect()
    if (serverVersion < MINIMUM_HTSP_VERSION) {
        throw HtspIncompatibleServerVersionException(serverVersion)
    }

    val channelCount = session.syncChannelMetadata()
    if (channelCount == 0) throw HtspZeroChannelsException()

    HtspProbeSuccess(
        serverVersion = serverVersion,
        channelCount = channelCount,
    )
} catch (error: Throwable) {
    if (error is CancellationException) throw error
    HtspProbeFailure(error)
} finally {
    runCatching { session.close() }.onFailure { error ->
        if (error is CancellationException) throw error
    }
}
