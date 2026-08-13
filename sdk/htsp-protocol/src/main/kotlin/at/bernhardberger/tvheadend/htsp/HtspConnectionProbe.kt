package at.bernhardberger.tvheadend.htsp

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

public class HtspConnectionProbe(
    private val ioDispatcher: CoroutineDispatcher,
    private val clientIdentity: HtspClientIdentity = HtspClientIdentity.Default,
    private val logger: HtspLogger = HtspLogger.None,
) {
    public suspend fun test(
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
            val liveGeneration = service.liveConnection.value?.generation
            val channelIds = ConcurrentHashMap.newKeySet<Long>()
            val completed = CompletableDeferred<Int>()
            val collector = launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
                service.events.collect { event ->
                    val server = event as? HtspTransportEvent.ServerMessage ?: return@collect
                    if (liveGeneration == null || server.generation !== liveGeneration) {
                        return@collect
                    }
                    when (val message = server.message) {
                        is HtspChannelAddMessage -> channelIds.add(message.channelId)
                        is HtspChannelUpdateMessage -> channelIds.add(message.channelId)
                        is HtspChannelDeleteMessage -> channelIds.remove(message.channelId)
                        HtspInitialSyncCompletedMessage -> completed.complete(channelIds.size)
                        else -> Unit
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

public sealed interface HtspProbeResult

public data class HtspProbeSuccess(
    public val serverVersion: Int,
    public val channelCount: Int,
) : HtspProbeResult

public data class HtspProbeFailure(
    public val failure: HtspTransportFailure,
) : HtspProbeResult

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
    HtspProbeFailure(error.toProbeTransportFailure())
} finally {
    runCatching { session.close() }.onFailure { error ->
        if (error is CancellationException) throw error
    }
}

private fun Throwable.toProbeTransportFailure(): HtspTransportFailure = when (this) {
    is HtspIncompatibleServerVersionException ->
        HtspTransportFailure(HtspTransportFailureKind.INCOMPATIBLE_SERVER)
    is HtspZeroChannelsException -> HtspTransportFailure(HtspTransportFailureKind.ZERO_CHANNELS)
    else -> typedTransportFailure(this)
}
