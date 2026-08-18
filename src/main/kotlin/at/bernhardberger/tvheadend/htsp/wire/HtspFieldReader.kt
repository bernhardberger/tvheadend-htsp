package at.bernhardberger.tvheadend.htsp.wire

import java.util.Collections

internal class `HtspFieldReader-internal`(
    private val fields: Map<*, *>,
    private val fail: () -> Nothing,
) {
    internal fun contains(name: String): Boolean = fields.containsKey(name)

    internal fun value(name: String): Any? = fields[name]

    internal fun requiredS64(name: String): Long = fields[name] as? Long ?: fail()

    internal fun optionalS64(name: String): Long? =
        if (contains(name)) requiredS64(name) else null

    internal fun requiredS32(name: String): Int {
        val value = requiredS64(name)
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) fail()
        return value.toInt()
    }

    internal fun optionalS32(name: String): Int? =
        if (contains(name)) requiredS32(name) else null

    internal fun requiredBoundedInt(name: String, range: IntRange): Int {
        val value = requiredS64(name)
        if (value !in range.first.toLong()..range.last.toLong()) fail()
        return value.toInt()
    }

    internal fun optionalBoundedInt(name: String, range: IntRange): Int? =
        if (contains(name)) requiredBoundedInt(name, range) else null

    internal fun requiredFlag(name: String): Boolean = when (requiredS64(name)) {
        0L -> false
        1L -> true
        else -> fail()
    }

    internal fun optionalFlag(name: String): Boolean? =
        if (contains(name)) requiredFlag(name) else null

    internal fun requiredU32(name: String): Long {
        val value = requiredS64(name)
        if (value !in 0L..HTSP_U32_MAX) fail()
        return value
    }

    internal fun optionalU32(name: String): Long? =
        if (contains(name)) requiredU32(name) else null

    internal fun requiredString(name: String): String = fields[name] as? String ?: fail()

    internal fun optionalString(name: String): String? =
        if (contains(name)) requiredString(name) else null

    internal fun requiredBinary(name: String): ByteArray = fields[name] as? ByteArray ?: fail()

    internal fun requiredBinaryCopy(name: String): ByteArray = requiredBinary(name).copyOf()

    internal fun optionalBinary(name: String): ByteArray? =
        if (contains(name)) requiredBinaryCopy(name) else null

    internal fun requiredObject(name: String): Map<*, *> {
        val source = fields[name] as? Map<*, *> ?: fail()
        return Collections.unmodifiableMap(LinkedHashMap(source))
    }

    internal fun observedString(name: String): String? = fields[name] as? String

    internal fun observedU32(name: String): Long? =
        (fields[name] as? Long)?.takeIf { it in 0L..HTSP_U32_MAX }

    internal fun observedFlag(name: String): Boolean? = when (fields[name]) {
        0L -> false
        1L -> true
        else -> null
    }

    internal fun observedStringList(name: String): List<String>? {
        val source = fields[name] as? List<*> ?: return null
        if (source.any { it !is String }) return null
        return source.map { it as String }.immutableSnapshot()
    }

    internal fun requiredU32List(name: String): List<Long> {
        val source = fields[name] as? List<*> ?: fail()
        return source.map { value ->
            val decoded = value as? Long ?: fail()
            if (decoded !in 0L..HTSP_U32_MAX) fail()
            decoded
        }.immutableSnapshot()
    }

    internal fun optionalU32List(name: String): List<Long>? =
        if (contains(name)) requiredU32List(name) else null

    internal fun requiredStringList(name: String): List<String> {
        val source = fields[name] as? List<*> ?: fail()
        return source.map { it as? String ?: fail() }.immutableSnapshot()
    }

    internal fun optionalStringList(name: String): List<String>? =
        if (contains(name)) requiredStringList(name) else null

    internal fun <T> requiredObjectList(
        name: String,
        mapper: (Map<*, *>) -> T,
    ): List<T> {
        val source = fields[name] as? List<*> ?: fail()
        return source.map { value ->
            val objectFields = value as? Map<*, *> ?: fail()
            mapper(Collections.unmodifiableMap(LinkedHashMap(objectFields)))
        }.immutableSnapshot()
    }

    internal fun <T> optionalObjectList(
        name: String,
        mapper: (Map<*, *>) -> T,
    ): List<T>? = if (contains(name)) requiredObjectList(name, mapper) else null

    internal fun optionalSortedUniqueStringList(name: String): List<String>? {
        val values = optionalStringList(name) ?: return null
        if (values.zipWithNext().any { (previous, next) -> compareUtf8(previous, next) >= 0 }) fail()
        return values
    }

    private fun compareUtf8(left: String, right: String): Int {
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
            val difference = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return leftBytes.size - rightBytes.size
    }
}

internal typealias HtspFieldReader = `HtspFieldReader-internal`
