package at.bernhardberger.tvheadend.htsp

internal data class HtspWireMessage(
    val method: String?,
    val seq: Int?,
    val fields: Map<String, Any?>,
    val rawPayload: ByteArray? = null,
) {

    fun int(key: String): Int? = when (val v = fields[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Short -> v.toInt()
        is Byte -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    fun long(key: String): Long? = when (val v = fields[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    fun bool(key: String): Boolean? = when (val v = fields[key]) {
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

    fun str(key: String): String? = when (val v = fields[key]) {
        is String -> v
        else -> null
    }

    fun bin(key: String): ByteArray? = when (val v = fields[key]) {
        is ByteArray -> v
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun map(key: String): Map<String, Any?>? = fields[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(key: String): List<Any?>? = fields[key] as? List<Any?>

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HtspWireMessage) return false

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

internal data class `SubscriptionStatus-internal`(
    val id: Int,
    val state: String? = null,
    val subscriptionError: String? = null,
)

internal typealias SubscriptionStatus = `SubscriptionStatus-internal`

public data class StreamProfile(
    val id: String,
    val name: String,
)
