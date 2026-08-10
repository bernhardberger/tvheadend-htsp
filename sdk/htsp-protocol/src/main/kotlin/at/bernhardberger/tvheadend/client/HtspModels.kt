package at.bernhardberger.tvheadend.client

@PlaybackIntegrationApi
public data class HtspMessage(
    val method: String?,               // null pro reply, pokud to tak máš
    val seq: Int?,                     // seq pro korelaci
    val fields: Map<String, Any?>,     // decoded map
    val rawPayload: ByteArray? = null  // pro muxpkt TS bytes (pokud rovnou vytáhneš)
) {

    public fun int(key: String): Int? = when (val v = fields[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Short -> v.toInt()
        is Byte -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    public fun long(key: String): Long? = when (val v = fields[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    public fun bool(key: String): Boolean? = when (val v = fields[key]) {
        is Boolean -> v
        is Int -> v != 0
        is Long -> v != 0L
        is String -> when (v.lowercase()) {
            "1", "true", "yes", "y" -> true
            "0", "false", "no", "n" -> false
            else -> null
        }

        else -> null
    }

    public fun str(key: String): String? = when (val v = fields[key]) {
        is String -> v
        else -> null
    }

    public fun bin(key: String): ByteArray? = when (val v = fields[key]) {
        is ByteArray -> v
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    public fun map(key: String): Map<String, Any?>? = fields[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    public fun list(key: String): List<Any?>? = fields[key] as? List<Any?>

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HtspMessage) return false

        if (seq != other.seq) return false
        if (method != other.method) return false
        if (fields != other.fields) return false

        val a = rawPayload
        val b = other.rawPayload
        if (a === null && b === null) return true
        if (a === null || b === null) return false
        if (!a.contentEquals(b)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = seq ?: 0
        result = 31 * result + (method?.hashCode() ?: 0)
        result = 31 * result + fields.hashCode()
        result = 31 * result + (rawPayload?.contentHashCode() ?: 0)
        return result
    }
}

@PlaybackIntegrationApi
public sealed interface HtspEvent {
    public val connectionAttemptId: Long

    public data class ServerMessage(
        val msg: HtspMessage,
        override val connectionAttemptId: Long = 0L,
        val messageSequence: Long = 0L,
    ) : HtspEvent

    public data class ConnectionError(
        val error: Throwable,
        override val connectionAttemptId: Long = 0L,
    ) : HtspEvent
}

@PlaybackIntegrationApi
public data class HtspMuxEvent(
    val msg: HtspMessage,
    val connectionAttemptId: Long,
    val messageSequence: Long = 0L,
    val muxSequence: Long = 0L,
)

internal data class `SubscriptionStatus-internal`
    (
    val id: Int,
    val state: String? = null,   // "Running" / "No input" / "Scrambled" / ...
    val subscriptionError: String? = null,
)

internal typealias SubscriptionStatus = `SubscriptionStatus-internal`

public data class StreamProfile(
    val id: String,
    val name: String
)
