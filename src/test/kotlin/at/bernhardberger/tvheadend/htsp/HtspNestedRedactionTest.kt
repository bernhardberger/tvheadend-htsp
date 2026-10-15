package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.model.*
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class HtspNestedRedactionTest {
    private val subscriptionId = 4294967294L
    private val secret = "private-server-value"
    private val payload = HtspBinary(secret.toByteArray())

    @Test
    fun subscriptionMessagesAndTheirEventsRedactSensitiveLeaves() {
        val messagesAndEvents = listOf(
            HtspSubscriptionStartMessage(subscriptionId, status = secret, subscriptionError = secret, codecMetadata = payload)
                .let { it to HtspSubscriptionEvent.Started(it) },
            HtspMuxPacketMessage(subscriptionId, HtspFrameType.I, 0L, null, null, 1L, payload)
                .let { it to HtspSubscriptionEvent.Packet(it) },
            HtspSubscriptionSkipMessage(subscriptionId, null, null, null, null)
                .let { it to HtspSubscriptionEvent.Skipped(it) },
            HtspSubscriptionStopMessage(subscriptionId, secret, secret)
                .let { it to HtspSubscriptionEvent.Stopped(it) },
            HtspSubscriptionStatusMessage(subscriptionId, secret, secret)
                .let { it to HtspSubscriptionEvent.Status(it) },
            HtspSubscriptionGraceMessage(subscriptionId, 1L)
                .let { it to HtspSubscriptionEvent.Grace(it) },
            HtspSubscriptionSpeedMessage(subscriptionId, 100)
                .let { it to HtspSubscriptionEvent.Speed(it) },
            HtspTimeshiftStatusMessage(subscriptionId, false, 0L, null, null)
                .let { it to HtspSubscriptionEvent.Timeshift(it) },
            HtspQueueStatusMessage(subscriptionId, 1L, 1L, null, 0L, 0L, 0L)
                .let { it to HtspSubscriptionEvent.Queue(it) },
            HtspSignalStatusMessage(subscriptionId, secret, null, null, null, null, null, null)
                .let { it to HtspSubscriptionEvent.Signal(it) },
            HtspDescrambleInfoMessage(subscriptionId, 1L, 1L, 1L, 1L, 1L, secret, secret, secret, secret)
                .let { it to HtspSubscriptionEvent.Descramble(it) },
        )
        for ((message, event) in messagesAndEvents) {
            assertRedacted(message, secret, subscriptionId.toString())
            assertRedacted(event, secret, subscriptionId.toString())
        }
    }

    @Test
    fun subscriptionDescriptorsRedactSourceAndCodecMetadata() {
        val source = HtspSubscriptionSourceInfo(secret, secret, secret, secret, secret, secret, secret, secret, secret, secret)
        val stream = HtspSubscriptionStream(
            0L, secret, secret, null, null, null, null, null, null, null, null, null, null, null, null, payload,
        )
        val start = HtspSubscriptionStartMessage(subscriptionId, listOf(stream), source)
        for (value in listOf(source, stream, start, HtspSubscriptionEvent.Started(start))) {
            assertRedacted(value, secret, subscriptionId.toString())
        }
    }

    @Test
    fun modelLeavesRedactRecordingPathsAndMetadata() {
        val stream = HtspDvrRecordingStream(type = secret, language = secret)
        val info = HtspDvrRecordingInfo(listOf(stream))
        val file = HtspDvrRecordingFile(1L, "/recordings/$secret.ts", null, null, null, info)
        val title = HtspHbbtvTitle(secret, secret)
        val application = HtspHbbtvApplication(listOf(title), "https://$secret/app", secret)
        val applications = HtspHbbtvApplications(mapOf(secret to listOf(application)))
        val service = HtspChannelService(secret, secret, 1L, null, secret, secret, applications)
        for (value in listOf(stream, info, file, title, application, applications, service)) {
            assertRedacted(value, secret)
            assertRedacted(HtspResult.Ok(value), secret)
        }
        val entry = HtspDvrEntry(
            entryId = 1L, enabled = true, startEpochSeconds = 0L, stopEpochSeconds = 1L,
            startExtraMinutes = 0L, stopExtraMinutes = 0L, retentionDays = 0L, removalDays = 0L,
            priority = 0L, contentType = 0L, ageRating = 0L, state = secret, files = listOf(file),
        )
        val channel = HtspChannel(1L, null, 1L, null, secret, null, 0L, 0L, listOf(service), emptyList())
        for (message in listOf(
            HtspDvrEntryAddMessage(entry), HtspDvrEntryUpdateMessage(entry),
            HtspChannelAddMessage(channel), HtspChannelUpdateMessage(channel),
        )) {
            assertRedacted(message, secret)
            assertRedacted(HtspTransportEvent.ServerMessage(message, HtspConnectionGeneration(), 1L), secret)
        }
    }

    @Test
    fun endpointsAndNestedHandshakeFactsDoNotRenderAddressesOrPaths() {
        val port = 54321
        val endpoint = HtspEndpoint(secret, port, "$secret-user", "$secret-password")
        val facts = HtspServerFacts(webRoot = "/$secret", serverName = secret)
        val live = HtspLiveConnection(HtspConnectionGeneration(), 44L, true, facts)
        for (value in listOf(
            endpoint, facts, live, HtspConnectOutcome.Connected(live),
            HtspConnectionState.Connecting(secret, port), HtspConnectionState.Connected(secret, port, live),
        )) {
            assertRedacted(value, secret, port.toString())
        }
    }

    private fun assertRedacted(value: Any, vararg sensitive: String) {
        val rendered = value.toString()
        assertTrue("<redacted>" in rendered, value.javaClass.simpleName)
        for (text in sensitive) assertFalse(text in rendered, value.javaClass.simpleName)
    }
}
