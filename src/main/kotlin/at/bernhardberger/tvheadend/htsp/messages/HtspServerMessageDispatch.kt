package at.bernhardberger.tvheadend.htsp.messages

/** Closed result family for decoding one candidate asynchronous HTSP message. */
public sealed interface HtspServerMessageDecodeResult
/** Contains the recognized and fully decoded asynchronous [message]. */
public data class HtspServerMessageDecoded(
    public val message: HtspServerMessage,
) : HtspServerMessageDecodeResult
/** Marks an RPC envelope or message whose method is absent, malformed, or outside the finite dispatch catalog. */
public data object HtspServerMessageUnknownMethod : HtspServerMessageDecodeResult
/** Marks a recognized asynchronous method whose fields failed its typed decoder. */
public data object HtspServerMessageMalformedKnownMessage : HtspServerMessageDecodeResult

/** Classifies one raw field map as a decoded server message, an unknown method, or malformed known input without throwing decoder failures. */
public fun decodeHtspServerMessage(fields: Map<String, Any?>): HtspServerMessageDecodeResult {
    if (fields.containsKey("seq")) return HtspServerMessageUnknownMethod
    val method = fields["method"] as? String
        ?: return HtspServerMessageUnknownMethod
    return when (method) {
        "channelAdd" -> decodeKnownServerMessage { decodeChannelAdd(fields) }
        "channelUpdate" -> decodeKnownServerMessage { decodeChannelUpdate(fields) }
        "channelDelete" -> decodeKnownServerMessage { decodeChannelDelete(fields) }
        "tagAdd" -> decodeKnownServerMessage { decodeTagAdd(fields) }
        "tagUpdate" -> decodeKnownServerMessage { decodeTagUpdate(fields) }
        "tagDelete" -> decodeKnownServerMessage { decodeTagDelete(fields) }
        "dvrEntryAdd" -> decodeKnownServerMessage { decodeDvrEntryAdd(fields) }
        "dvrEntryUpdate" -> decodeKnownServerMessage { decodeDvrEntryUpdate(fields) }
        "dvrEntryDelete" -> decodeKnownServerMessage { decodeDvrEntryDelete(fields) }
        "autorecEntryAdd" -> decodeKnownServerMessage { decodeAutorecEntryAdd(fields) }
        "autorecEntryUpdate" -> decodeKnownServerMessage { decodeAutorecEntryUpdate(fields) }
        "autorecEntryDelete" -> decodeKnownServerMessage { decodeAutorecEntryDelete(fields) }
        "timerecEntryAdd" -> decodeKnownServerMessage { decodeTimerecEntryAdd(fields) }
        "timerecEntryUpdate" -> decodeKnownServerMessage { decodeTimerecEntryUpdate(fields) }
        "timerecEntryDelete" -> decodeKnownServerMessage { decodeTimerecEntryDelete(fields) }
        "eventAdd" -> decodeKnownServerMessage { decodeEventAdd(fields) }
        "eventUpdate" -> decodeKnownServerMessage { decodeEventUpdate(fields) }
        "eventDelete" -> decodeKnownServerMessage { decodeEventDelete(fields) }
        "initialSyncCompleted" -> decodeKnownServerMessage { decodeInitialSyncCompleted(fields) }
        "muxpkt" -> decodeKnownServerMessage { decodeMuxPacket(fields) }
        "queueStatus" -> decodeKnownServerMessage { decodeQueueStatus(fields) }
        "subscriptionStart" -> decodeKnownServerMessage { decodeSubscriptionStart(fields) }
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
