package at.bernhardberger.tvheadend.htsp.benchmarks

import at.bernhardberger.tvheadend.htsp.messages.HtspServerMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspServerMessageDecoded
import at.bernhardberger.tvheadend.htsp.messages.HtspTimestampClock
import at.bernhardberger.tvheadend.htsp.messages.decodeHtspServerMessage
import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun frame(method: String, fields: Map<String, Any?>): ByteArray =
    ByteArrayOutputStream().also { HtspCodec.writeMessage(it, method, fields) }.toByteArray()

internal fun muxFrame(size: Int): ByteArray = frame("muxpkt", mapOf(
    "subscriptionId" to 1L, "stream" to 0L, "frametype" to 73L,
    "dts" to 1_000_000L, "pts" to 1_040_000L, "duration" to 40_000L,
    "payload" to ByteArray(size) { (it * 31).toByte() },
))

internal fun typedFrame(bytes: ByteArray): HtspServerMessage = typedFrame(bytes.inputStream())

internal fun typedFrame(input: InputStream): HtspServerMessage {
    val wire = HtspCodec.readMessage(input)
    return (decodeHtspServerMessage(wire) { HtspTimestampClock.MICROSECONDS } as HtspServerMessageDecoded).message
}

internal fun metadataFrame(method: String): ByteArray = frame(method, when (method) {
    "channelAdd" -> mapOf(
        "channelId" to 42L, "channelName" to "Benchmark HD", "channelNumber" to 7L,
        "eventId" to 100L, "nextEventId" to 101L, "tags" to listOf(1L, 2L),
        "services" to listOf(mapOf("name" to "Benchmark HD", "type" to "HDTV", "content" to 2L)),
    )
    "dvrEntryAdd" -> mapOf(
        "id" to 5L, "enabled" to 1L, "channelId" to 42L, "channelName" to "Benchmark HD",
        "eventId" to 100L, "start" to 1_800_000_000L, "stop" to 1_800_003_600L,
        "startExtra" to 2L, "stopExtra" to 5L, "retention" to 30L, "removal" to 90L,
        "priority" to 2L, "contentType" to 16L, "ageRating" to 12L,
        "playcount" to 1L, "playposition" to 300L, "seasonNumber" to 2L, "episodeNumber" to 3L,
        "title" to "An example programme", "subtitle" to "Episode three",
        "description" to "A representative programme description. ".repeat(12),
        "summary" to "A short summary", "state" to "completed", "dataSize" to 2_000_000_000L,
        "streamErrors" to 0L, "dataErrors" to 0L,
    )
    "eventAdd" -> mapOf(
        "eventId" to 100L, "channelId" to 42L, "start" to 1_800_000_000L, "stop" to 1_800_003_600L,
        "title" to "An example programme", "subtitle" to "Episode three",
        "description" to "A representative programme description. ".repeat(12),
        "summary" to "A short summary", "contentType" to 16L, "ageRating" to 12L,
        "seasonNumber" to 2L, "episodeNumber" to 3L, "nextEventId" to 101L,
    )
    else -> error("Unknown benchmark fixture")
})
