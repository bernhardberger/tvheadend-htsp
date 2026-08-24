package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

internal class HtspNearLiveIntegrationTest {
    @Test
    @Timeout(value = 2L, unit = TimeUnit.MINUTES)
    fun nearLiveSkipPreservesPinnedServerTransportAndAudioVideoDelivery() = runBlocking {
        val endpoint = liveEndpoint()
        val connection = createHtspConnection(Dispatchers.IO)
        var generation: HtspConnectionGeneration? = null
        var metadataJob: Job? = null
        var subscriptionJob: Job? = null
        var subscribed = false

        try {
            val outcome = connection.connect(endpoint)
            assertTrue(outcome is HtspConnectOutcome.Connected, "Pinned live server must connect")
            val live = (outcome as HtspConnectOutcome.Connected).connection
            generation = live.generation
            assertEquals(
                PINNED_SERVER_VERSION,
                live.serverFacts.serverVersion,
                "Live regression must run against the pinned affected server",
            )

            val metadata = LiveMetadataObservation(live.generation)
            metadataJob = launch(start = CoroutineStart.UNDISPATCHED) {
                connection.events.collect(metadata::accept)
            }
            assertTrue(
                connection.enableAsyncMetadataAwaitingInitialSync(
                    timeoutMs = METADATA_TIMEOUT_MS,
                    expectedGeneration = live.generation,
                ) is HtspResult.Ok,
                "Pinned live server metadata must synchronize",
            )
            metadata.awaitInitialSync()
            val channelId = metadata.dvrChannelId()
                ?: throw AssertionError("A DVR-associated live channel is required")

            val observation = LiveSubscriptionObservation()
            subscriptionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                connection.subscriptionEvents(SUBSCRIPTION_ID, live.generation)
                    .collect(observation::accept)
            }
            val subscribe = connection.subscribe(
                subscriptionId = SUBSCRIPTION_ID,
                channelId = channelId,
                timeshiftPeriodSeconds = TIMESHIFT_PERIOD_SECONDS,
                expectedGeneration = live.generation,
            )
            assertTrue(subscribe is HtspResult.Ok, "Live timeshift subscription must be accepted")
            assertTrue(
                (subscribe as HtspResult.Ok).value.timeshiftPeriodSeconds != null,
                "Pinned server must grant a timeshift period",
            )
            subscribed = true

            observation.awaitSnapshot("Initial audio/video delivery was not observed") { snapshot ->
                snapshot.startedCount == 1 && snapshot.videoPackets > 0L && snapshot.audioPackets > 0L
            }
            delay(TIMESHIFT_FILL_MS)
            observation.awaitSnapshot("Timeshift bounds were not observed") { snapshot ->
                snapshot.latestTimeshiftSequence > 0L && observation.latestTimeshiftStatusHasBounds()
            }

            val beforeRewind = observation.snapshot()
            assertTrue(
                connection.subscriptionSkip(
                    subscriptionId = SUBSCRIPTION_ID,
                    position = SubscriptionSeekPosition.Time(-REWIND_MICROSECONDS),
                    absolute = 0L,
                    expectedGeneration = live.generation,
                ) is HtspResult.Ok,
                "Timeshift rewind request must be acknowledged",
            )
            val rewound = observation.awaitSnapshot("Timeshift rewind was not accepted") { snapshot ->
                snapshot.successfulSkips > beforeRewind.successfulSkips &&
                    snapshot.latestTimeshiftSequence > beforeRewind.eventSequence
            }
            assertTrue(
                rewound.latestTimeshiftSequence < rewound.latestSuccessfulSkipSequence,
                "Rewind status must precede its ordered skip result",
            )
            val rewindPacketBaseline = observation.snapshot()
            observation.awaitSnapshot("Audio/video did not resume after rewind") { snapshot ->
                snapshot.videoPackets > rewindPacketBaseline.videoPackets &&
                    snapshot.audioPackets > rewindPacketBaseline.audioPackets
            }

            val nearLiveStatus = observation.latestTimeshiftStatus()
                ?: throw AssertionError("A current timeshift status is required")
            val beforeNearLive = observation.snapshot()
            assertTrue(
                connection.subscriptionSkipNearLive(
                    status = nearLiveStatus,
                    clock = SubscriptionTimestampClock.MICROSECONDS,
                    marginSeconds = NEAR_LIVE_MARGIN_SECONDS,
                    expectedGeneration = live.generation,
                ) is HtspResult.Ok,
                "Near-live skip request must be acknowledged",
            )
            val nearLive = observation.awaitSnapshot("Near-live skip was not accepted") { snapshot ->
                snapshot.successfulSkips > beforeNearLive.successfulSkips &&
                    snapshot.latestTimeshiftSequence > beforeNearLive.eventSequence
            }
            assertTrue(
                nearLive.latestTimeshiftSequence < nearLive.latestSuccessfulSkipSequence,
                "Near-live status must precede its ordered skip result",
            )
            val nearLivePacketBaseline = observation.snapshot()
            observation.awaitSnapshot("Audio/video did not resume after the near-live skip") { snapshot ->
                snapshot.videoPackets > nearLivePacketBaseline.videoPackets &&
                    snapshot.audioPackets > nearLivePacketBaseline.audioPackets
            }
            delay(STABILITY_OBSERVATION_MS)

            val final = observation.snapshot()
            assertEquals(1, final.startedCount, "Near-live skip must not restart the subscription")
            assertEquals(0, final.stoppedCount, "Near-live skip must not stop the subscription")
            assertEquals(0, final.terminatedCount, "Near-live skip must preserve the transport")
            assertEquals(
                beforeNearLive.droppedPackets,
                final.droppedPackets,
                "Near-live skip must not add client-side packet drops",
            )
            assertEquals(
                beforeNearLive.serverFrameDrops,
                final.serverFrameDrops,
                "Near-live skip must not add server-side frame drops",
            )
            assertEquals(
                beforeNearLive.failedSkips,
                final.failedSkips,
                "Near-live skip must not produce an asynchronous rejection",
            )
            assertEquals(0, metadata.connectionFailures(), "Near-live skip must not fail the transport")
            assertSame(
                live.generation,
                connection.liveConnection.value?.generation,
                "Near-live skip must preserve the live generation",
            )
            assertTrue(connection.isCurrent(live.generation), "Near-live generation must remain current")
        } finally {
            withContext(NonCancellable) {
                val currentGeneration = generation
                var cleanupAcknowledged = !subscribed
                var streamDrained = !subscribed || subscriptionJob == null
                try {
                    if (
                        subscribed &&
                        currentGeneration != null &&
                        connection.liveConnection.value?.generation === currentGeneration
                    ) {
                        cleanupAcknowledged = connection.unsubscribe(
                            subscriptionId = SUBSCRIPTION_ID,
                            expectedGeneration = currentGeneration,
                        ) is HtspResult.Ok
                    }
                    val collector = subscriptionJob
                    if (subscribed && collector != null) {
                        streamDrained = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                            collector.join()
                            true
                        } == true
                        if (!streamDrained) collector.cancelAndJoin()
                    }
                } finally {
                    subscriptionJob?.cancelAndJoin()
                    metadataJob?.cancelAndJoin()
                    connection.close()
                }
                assertTrue(cleanupAcknowledged, "Live subscription cleanup must be acknowledged")
                assertTrue(streamDrained, "Live subscription stream must drain during cleanup")
            }
        }
    }

    private fun liveEndpoint(): HtspEndpoint {
        assumeTrue(
            System.getenv(ENABLE_ENVIRONMENT_VARIABLE) == "true",
            "Pinned-server near-live verification is opt-in",
        )
        val host = requiredEnvironmentVariable(HOST_ENVIRONMENT_VARIABLE)
        val port = requiredEnvironmentVariable(PORT_ENVIRONMENT_VARIABLE)
            .toIntOrNull()
            ?.takeIf { value -> value in 1..65_535 }
            ?: throw AssertionError("Pinned-server HTSP port is invalid")
        return HtspEndpoint(
            host = host,
            port = port,
            username = requiredEnvironmentVariable(USERNAME_ENVIRONMENT_VARIABLE),
            password = requiredEnvironmentVariable(PASSWORD_ENVIRONMENT_VARIABLE),
        )
    }

    private fun requiredEnvironmentVariable(name: String): String =
        System.getenv(name)?.takeIf(String::isNotEmpty)
            ?: throw AssertionError("Pinned-server live verification is incompletely provisioned")

    private companion object {
        const val ENABLE_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_NEAR_LIVE_TEST"
        const val HOST_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_LIVE_HOST"
        const val PORT_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_LIVE_PORT"
        const val USERNAME_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_LIVE_USERNAME"
        const val PASSWORD_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_LIVE_PASSWORD"
        const val PINNED_SERVER_VERSION = "4.3-2735~gfcd987f0b"
        const val SUBSCRIPTION_ID = 1L
        const val TIMESHIFT_PERIOD_SECONDS = 300L
        const val TIMESHIFT_FILL_MS = 8_000L
        const val REWIND_MICROSECONDS = 5_000_000L
        const val NEAR_LIVE_MARGIN_SECONDS = 3L
        const val STABILITY_OBSERVATION_MS = 2_000L
        const val METADATA_TIMEOUT_MS = 30_000L
        const val EVENT_TIMEOUT_MS = 20_000L
        const val CLEANUP_TIMEOUT_MS = 5_000L
    }

    private class LiveMetadataObservation(
        private val generation: HtspConnectionGeneration,
    ) {
        private val lock = Any()
        private val changed = Channel<Unit>(Channel.CONFLATED)
        private val channels = linkedSetOf<Long>()
        private val completedDvrChannels = mutableMapOf<Long, Long>()
        private var initialSync = false
        private var failures = 0

        fun accept(event: HtspTransportEvent) {
            when (event) {
                is HtspTransportEvent.ServerMessage -> {
                    if (event.generation !== generation) return
                    synchronized(lock) {
                        when (val message = event.message) {
                            is HtspChannelAddMessage -> channels += message.channelId
                            is HtspChannelUpdateMessage -> channels += message.channelId
                            is HtspChannelDeleteMessage -> channels -= message.channelId
                            is HtspDvrEntryAddMessage -> recordCompletedDvrChannel(
                                message.channelId,
                                message.state,
                                message.stop,
                                message.files,
                            )
                            is HtspDvrEntryUpdateMessage -> recordCompletedDvrChannel(
                                message.channelId,
                                message.state,
                                message.stop,
                                message.files,
                            )
                            HtspInitialSyncCompletedMessage -> initialSync = true
                            else -> Unit
                        }
                    }
                    changed.trySend(Unit)
                }
                is HtspTransportEvent.ConnectionFailure -> {
                    if (event.generation === generation) {
                        synchronized(lock) { failures += 1 }
                        changed.trySend(Unit)
                    }
                }
            }
        }

        suspend fun awaitInitialSync() {
            val observed = withTimeoutOrNull(METADATA_TIMEOUT_MS) {
                while (!synchronized(lock) { initialSync }) changed.receive()
                true
            } == true
            assertTrue(observed, "Metadata collector must observe initial sync")
        }

        fun dvrChannelId(): Long? = synchronized(lock) {
            completedDvrChannels
                .filterKeys(channels::contains)
                .maxByOrNull { entry -> entry.value }
                ?.key
        }

        fun connectionFailures(): Int = synchronized(lock) { failures }

        private fun recordCompletedDvrChannel(
            channelId: Long?,
            state: String?,
            stop: Long?,
            files: List<HtspDvrRecordingFile>?,
        ) {
            if (
                channelId != null &&
                state == "completed" &&
                stop != null &&
                files.orEmpty().any { file -> file.sizeBytes?.let { size -> size > 0L } == true }
            ) {
                completedDvrChannels.merge(channelId, stop, ::maxOf)
            }
        }
    }

    private class LiveSubscriptionObservation {
        private val lock = Any()
        private val changed = Channel<Unit>(Channel.CONFLATED)
        private val videoStreams = mutableSetOf<Long>()
        private val audioStreams = mutableSetOf<Long>()
        private var eventSequence = 0L
        private var startedCount = 0
        private var stoppedCount = 0
        private var terminatedCount = 0
        private var videoPackets = 0L
        private var audioPackets = 0L
        private var droppedPackets = 0L
        private var serverFrameDrops = 0L
        private var successfulSkips = 0
        private var failedSkips = 0
        private var latestTimeshiftSequence = 0L
        private var latestSuccessfulSkipSequence = 0L
        private var latestTimeshiftStatus: HtspTimeshiftStatusMessage? = null

        fun accept(event: HtspSubscriptionEvent) {
            synchronized(lock) {
                eventSequence += 1L
                when (event) {
                    is HtspSubscriptionEvent.Started -> {
                        startedCount += 1
                        event.message.streams.orEmpty().forEach { stream ->
                            if (stream.width != null || stream.height != null) {
                                videoStreams += stream.streamIndex
                            }
                            if (stream.sampleRate != null || stream.channelCount != null) {
                                audioStreams += stream.streamIndex
                            }
                        }
                    }
                    is HtspSubscriptionEvent.Packet -> {
                        if (event.packet.streamIndex in videoStreams) videoPackets += 1L
                        if (event.packet.streamIndex in audioStreams) audioPackets += 1L
                    }
                    is HtspSubscriptionEvent.Timeshift -> {
                        latestTimeshiftSequence = eventSequence
                        latestTimeshiftStatus = event.message
                    }
                    is HtspSubscriptionEvent.Skipped -> {
                        if (event.message.error == null) {
                            successfulSkips += 1
                            latestSuccessfulSkipSequence = eventSequence
                        } else {
                            failedSkips += 1
                        }
                    }
                    is HtspSubscriptionEvent.Queue -> {
                        val drops = event.message.bFrameDropCount +
                            event.message.pFrameDropCount +
                            event.message.iFrameDropCount
                        serverFrameDrops = maxOf(serverFrameDrops, drops)
                    }
                    is HtspSubscriptionEvent.Dropped -> droppedPackets += event.count
                    is HtspSubscriptionEvent.Stopped -> stoppedCount += 1
                    is HtspSubscriptionEvent.Terminated -> terminatedCount += 1
                    else -> Unit
                }
            }
            changed.trySend(Unit)
        }

        fun snapshot(): SubscriptionSnapshot = synchronized(lock) {
            SubscriptionSnapshot(
                eventSequence = eventSequence,
                startedCount = startedCount,
                stoppedCount = stoppedCount,
                terminatedCount = terminatedCount,
                videoPackets = videoPackets,
                audioPackets = audioPackets,
                droppedPackets = droppedPackets,
                serverFrameDrops = serverFrameDrops,
                successfulSkips = successfulSkips,
                failedSkips = failedSkips,
                latestTimeshiftSequence = latestTimeshiftSequence,
                latestSuccessfulSkipSequence = latestSuccessfulSkipSequence,
            )
        }

        fun latestTimeshiftStatus(): HtspTimeshiftStatusMessage? = synchronized(lock) {
            latestTimeshiftStatus
        }

        fun latestTimeshiftStatusHasBounds(): Boolean = synchronized(lock) {
            latestTimeshiftStatus?.let { status -> status.start != null && status.end != null } == true
        }

        suspend fun awaitSnapshot(
            failureMessage: String,
            predicate: (SubscriptionSnapshot) -> Boolean,
        ): SubscriptionSnapshot {
            val observed = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                var matching = snapshot().takeIf(predicate)
                while (matching == null) {
                    changed.receive()
                    matching = snapshot().takeIf(predicate)
                }
                matching
            }
            return observed ?: throw AssertionError(failureMessage)
        }
    }

    private data class SubscriptionSnapshot(
        val eventSequence: Long,
        val startedCount: Int,
        val stoppedCount: Int,
        val terminatedCount: Int,
        val videoPackets: Long,
        val audioPackets: Long,
        val droppedPackets: Long,
        val serverFrameDrops: Long,
        val successfulSkips: Int,
        val failedSkips: Int,
        val latestTimeshiftSequence: Long,
        val latestSuccessfulSkipSequence: Long,
    )
}
