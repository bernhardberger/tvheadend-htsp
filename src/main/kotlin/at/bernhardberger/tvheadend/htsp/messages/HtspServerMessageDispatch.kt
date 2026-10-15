package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.wire.HtspWireMessage

/** Closed result family for decoding one candidate asynchronous HTSP message. */
internal sealed interface HtspServerMessageDecodeResult
/** Contains the recognized and fully decoded asynchronous [message]. */
internal data class HtspServerMessageDecoded(
    val message: HtspServerMessage,
) : HtspServerMessageDecodeResult
/** Marks an RPC envelope or message whose method is absent, malformed, or outside the finite dispatch catalog. */
internal data object HtspServerMessageUnknownMethod : HtspServerMessageDecodeResult
/** Marks a recognized asynchronous method whose fields failed its typed decoder. */
internal data object HtspServerMessageMalformedKnownMessage : HtspServerMessageDecodeResult

/**
 * Classifies one raw field map as a decoded server message, unknown method, or
 * malformed known input. Standalone mux packets are interpreted as native
 * microseconds because no subscribe request supplies a negotiated clock.
 */
internal fun decodeHtspServerMessage(fields: Map<String, Any?>, protocolVersion: Int = 44): HtspServerMessageDecodeResult =
    decodeHtspServerMessage(fields, { HtspTimestampClock.MICROSECONDS }, null, protocolVersion)

@JvmSynthetic
internal fun decodeHtspServerMessage(
    fields: Map<String, Any?>,
    timestampClockForSubscription: (Long) -> HtspTimestampClock,
): HtspServerMessageDecodeResult =
    decodeHtspServerMessage(fields, timestampClockForSubscription, ownedMuxPayload = null, protocolVersion = 44)

@JvmSynthetic
internal fun decodeHtspServerMessage(
    message: HtspWireMessage,
    protocolVersion: Int = 44,
    timestampClockForSubscription: (Long) -> HtspTimestampClock,
): HtspServerMessageDecodeResult =
    decodeHtspServerMessage(
        fields = message.fields,
        protocolVersion = protocolVersion,
        timestampClockForSubscription = timestampClockForSubscription,
        ownedMuxPayload = message.rawPayload?.takeIf { payload ->
            payload === message.fields["payload"]
        },
    )

private fun decodeHtspServerMessage(
    fields: Map<String, Any?>,
    timestampClockForSubscription: (Long) -> HtspTimestampClock,
    ownedMuxPayload: ByteArray?,
    protocolVersion: Int,
): HtspServerMessageDecodeResult {
    if (fields.containsKey("seq")) return HtspServerMessageUnknownMethod
    val method = fields["method"] as? String
        ?: return HtspServerMessageUnknownMethod
    return when (method) {
        "channelAdd" -> decodeKnownServerMessage { decodeChannelAdd(fields, protocolVersion) }
        "channelUpdate" -> decodeKnownServerMessage { decodeChannelUpdate(fields, protocolVersion) }
        "channelDelete" -> decodeKnownServerMessage { decodeChannelDelete(fields) }
        "tagAdd" -> decodeKnownServerMessage { decodeTagAdd(fields, protocolVersion) }
        "tagUpdate" -> decodeKnownServerMessage { decodeTagUpdate(fields, protocolVersion) }
        "tagDelete" -> decodeKnownServerMessage { decodeTagDelete(fields) }
        "dvrEntryAdd" -> decodeKnownServerMessage { decodeDvrEntryAdd(fields, protocolVersion) }
        "dvrEntryUpdate" -> decodeKnownServerMessage { decodeDvrEntryUpdate(fields, protocolVersion) }
        "dvrEntryDelete" -> decodeKnownServerMessage { decodeDvrEntryDelete(fields) }
        "autorecEntryAdd" -> decodeKnownServerMessage { decodeAutorecEntryAdd(fields, protocolVersion) }
        "autorecEntryUpdate" -> decodeKnownServerMessage { decodeAutorecEntryUpdate(fields, protocolVersion) }
        "autorecEntryDelete" -> decodeKnownServerMessage { decodeAutorecEntryDelete(fields) }
        "timerecEntryAdd" -> decodeKnownServerMessage { decodeTimerecEntryAdd(fields, protocolVersion) }
        "timerecEntryUpdate" -> decodeKnownServerMessage { decodeTimerecEntryUpdate(fields, protocolVersion) }
        "timerecEntryDelete" -> decodeKnownServerMessage { decodeTimerecEntryDelete(fields) }
        "eventAdd" -> decodeKnownServerMessage { decodeEventAdd(fields, protocolVersion) }
        "eventUpdate" -> decodeKnownServerMessage { decodeEventUpdate(fields, protocolVersion) }
        "eventDelete" -> decodeKnownServerMessage { decodeEventDelete(fields) }
        "initialSyncCompleted" -> decodeKnownServerMessage { decodeInitialSyncCompleted(fields) }
        "muxpkt" -> decodeKnownServerMessage {
            decodeMuxPacket(fields, timestampClockForSubscription, ownedMuxPayload)
        }
        "queueStatus" -> decodeKnownServerMessage { decodeQueueStatus(fields, timestampClockForSubscription) }
        "subscriptionStart" -> decodeKnownServerMessage { decodeSubscriptionStart(fields, timestampClockForSubscription) }
        "subscriptionStop" -> decodeKnownServerMessage { decodeSubscriptionStop(fields) }
        "subscriptionGrace" -> decodeKnownServerMessage { decodeSubscriptionGrace(fields) }
        "subscriptionStatus" -> decodeKnownServerMessage { decodeSubscriptionStatus(fields) }
        "signalStatus" -> decodeKnownServerMessage { decodeSignalStatus(fields) }
        "descrambleInfo" -> decodeKnownServerMessage { decodeDescrambleInfo(fields) }
        "subscriptionSpeed" -> decodeKnownServerMessage { decodeSubscriptionSpeed(fields) }
        "timeshiftStatus" -> decodeKnownServerMessage { decodeTimeshiftStatus(fields) }
        "subscriptionSkip" -> decodeKnownServerMessage { decodeSubscriptionSkip(fields) }
        else -> HtspServerMessageUnknownMethod
    }
}

private fun decodeKnownServerMessage(block: () -> HtspServerMessage): HtspServerMessageDecodeResult =
    try {
        HtspServerMessageDecoded(block())
    } catch (_: IllegalArgumentException) {
        HtspServerMessageMalformedKnownMessage
    }
