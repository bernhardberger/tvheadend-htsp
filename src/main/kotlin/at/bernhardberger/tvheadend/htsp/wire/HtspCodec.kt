package at.bernhardberger.tvheadend.htsp.wire

import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.min

internal class HtspFramingException(
    val failure: String,
    val byteOffset: Int,
) : IOException("HTSP framing failure: $failure at byte offset $byteOffset")

internal object `HtspCodec-internal` {
    // HTSP field tags, as recorded in docs/htsp-protocol/WIRE_FORMAT.md.
    private object FieldType {
        const val MAP = 1
        const val S64 = 2
        const val STR = 3
        const val BIN = 4
        const val LIST = 5
        const val DBL = 6
        const val BOOL = 7
        const val UUID = 8
    }

    private const val MAX_MESSAGE_SIZE = 32 * 1024 * 1024
    private const val MAX_FIELD_NAME = 255
    private const val MAX_NESTING_DEPTH = 32
    private val unnamed = ByteArray(0)

    fun readMessage(input: InputStream): HtspWireMessage {
        val reader = FrameReader(input)
        val end = reader.readRootEnd()
        val fields = LinkedHashMap<String, Any?>()
        decodeMap(reader, fields, end = end, depth = 0)
        return HtspWireMessage(fields)
    }

    fun encode(method: String, fields: Map<String, Any?>): ByteArray {
        // A caller-supplied method replaces the default without changing its position.
        val envelope = linkedMapOf<String, Any?>("method" to method).apply { putAll(fields) }
        val plan = planMap(envelope)
        val bodySize = containerSize(plan)
        val frame = ByteBuffer.allocate(Math.addExact(Int.SIZE_BYTES, bodySize))
        frame.putInt(bodySize)
        plan.forEach { it.writeTo(frame) }
        return frame.array()
    }

    private fun decodeMap(r: FrameReader, out: MutableMap<String, Any?>, end: Int, depth: Int) {
        if (depth > MAX_NESTING_DEPTH) {
            throw HtspFramingException("nesting exceeds limit", r.byteOffset)
        }
        while (r.byteOffset < end) {
            val name = r.readFieldHeader(end)
            val value = decodeValue(r, depth, name)
            if (name != null) out[name] = value
        }
    }

    private fun decodeList(r: FrameReader, out: MutableList<Any?>, end: Int, depth: Int) {
        if (depth > MAX_NESTING_DEPTH) {
            throw HtspFramingException("nesting exceeds limit", r.byteOffset)
        }
        while (r.byteOffset < end) {
            val name = r.readFieldHeader(end)
            out.add(decodeValue(r, depth, name))
        }
    }

    private fun decodeValue(r: FrameReader, depth: Int, name: String?): Any {
        val type = r.fieldType
        val dataLen = r.fieldLength
        if (depth == 0 && name == "seq" && type == FieldType.S64 && dataLen > 8) {
            throw HtspFramingException("sequence integer exceeds 64 bits", r.byteOffset)
        }

        return when (type) {
            FieldType.MAP -> LinkedHashMap<String, Any?>().also { decodeMap(r, it, r.byteOffset + dataLen, depth + 1) }
            FieldType.LIST -> ArrayList<Any?>().also { decodeList(r, it, r.byteOffset + dataLen, depth + 1) }
            FieldType.S64 -> r.readS64(dataLen)
            FieldType.STR -> r.readString(dataLen, what = "string")
            FieldType.BIN -> r.readExactly(dataLen, what = "binary")
            FieldType.DBL -> readDoubleLE(r, dataLen)
            FieldType.BOOL -> readBool(r, dataLen)
            FieldType.UUID -> HtspWireUuid(r.readExactly(dataLen, what = "uuid"))
            else -> r.readExactly(dataLen, what = "unknown field")
        }
    }

    private fun readDoubleLE(r: FrameReader, len: Int): Double {
        if (len != 8) {
            r.drain(len, what = "double length mismatch")
            return 0.0
        }
        return java.lang.Double.longBitsToDouble(r.readS64(len))
    }

    private fun readBool(r: FrameReader, len: Int): Boolean {
        if (len <= 0) return false
        val v = r.readS64(1) != 0L
        r.drain(len - 1, what = "boolean tail")
        return v
    }

    /** Fills only the requested slice, including streams that make no bulk-read progress. */
    private fun readInto(
        source: InputStream,
        destination: ByteArray,
        length: Int,
        label: String,
        bounded: Boolean = false,
    ) {
        var remaining = length
        while (remaining != 0) {
            val position = length - remaining
            val received = source.read(destination, position, remaining)
            val progress = when (received) {
                0 -> {
                    val single = source.read()
                    if (single < 0) -1 else {
                        destination[position] = single.toByte()
                        1
                    }
                }
                else -> received
            }
            if (progress < 0) {
                val detail = if (bounded) "bounded HTSP frame" else "$label ($length bytes, read=$position)"
                throw EOFException("EOF while reading $detail")
            }
            remaining -= progress
        }
    }

    // ----------------------------
    // One cursor and reusable scalar/name scratch space per frame. Binary fields
    // still read straight into their owned array: no whole-frame/payload copy or retention.
    // There is no per-field slice: every decodeValue branch must consume exactly the
    // field length, or the rest of the frame desynchronizes.
    // ----------------------------

    private class FrameReader(private val input: InputStream) {
        private val scratch = ByteArray(256)
        var byteOffset: Int = 4
            private set
        var fieldType: Int = 0
            private set
        var fieldLength: Int = 0
            private set

        fun readRootEnd(): Int {
            readInto(input, scratch, Int.SIZE_BYTES, "root length")
            var length = 0L
            repeat(Int.SIZE_BYTES) { index -> length = length * 256 + (scratch[index].toInt() and 0xff) }
            if (length !in 1L..MAX_MESSAGE_SIZE.toLong()) {
                throw HtspFramingException("invalid root length", byteOffset = 0)
            }
            return Int.SIZE_BYTES + length.toInt()
        }

        fun readFieldHeader(end: Int): String? {
            // Consume a short enclosing header before rejecting it, just as single-byte
            // reads did. Physical EOF takes precedence over the enclosing-bound error.
            val size = min(6, end - byteOffset)
            readScratch(size, bounded = true)
            if (size < 6) throw HtspFramingException("field byte exceeds enclosing frame", byteOffset)
            fieldType = scratch[0].toInt() and 0xff
            val nameLen = scratch[1].toInt() and 0xff
            val dataLen = ((scratch[2].toLong() and 0xff) shl 24) or
                ((scratch[3].toLong() and 0xff) shl 16) or
                ((scratch[4].toLong() and 0xff) shl 8) or (scratch[5].toLong() and 0xff)
            if (nameLen.toLong() + dataLen > end - byteOffset) {
                throw HtspFramingException("field name and data exceed enclosing frame", byteOffset)
            }
            fieldLength = dataLen.toInt()
            return if (nameLen == 0) null else readString(nameLen, what = "field name")
        }

        fun readExactly(n: Int, what: String): ByteArray {
            val buf = ByteArray(n)
            readInto(input, buf, n, what)
            byteOffset += n
            return buf
        }

        fun readString(n: Int, what: String): String {
            if (n > scratch.size) return String(readExactly(n, what), StandardCharsets.UTF_8)
            readScratch(n, what = what)
            return String(scratch, 0, n, StandardCharsets.UTF_8)
        }

        fun readS64(len: Int): Long {
            val n = min(len, 8)
            readScratch(n, bounded = true)
            var value = 0L
            for (i in 0 until n) value = value or ((scratch[i].toLong() and 0xff) shl (8 * i))
            drain(len - n, what = "signed integer tail")
            return value
        }

        private fun readScratch(n: Int, what: String = "field byte", bounded: Boolean = false) {
            readInto(input, scratch, n, what, bounded)
            byteOffset += n
        }

        fun drain(n: Int, what: String) {
            if (n <= 0) return
            var remaining = n
            val tmp = ByteArray(8192)
            while (remaining > 0) {
                val toRead = min(remaining, tmp.size)
                val count = input.read(tmp, 0, toRead)
                if (count < 0) throw EOFException("EOF while draining $what")
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) throw EOFException("EOF while draining $what")
                    remaining--
                    byteOffset++
                } else {
                    remaining -= count
                    byteOffset += count
                }
            }
        }
    }

    // Planning converts names/strings once and measures containers without serializing
    // them. Emission then writes directly into the sole frame buffer, including binaries.
    private class PlannedField(
        val name: ByteArray,
        val type: Int,
        val dataSize: Int,
        val bytes: ByteArray? = null,
        val bits: Long = 0,
        val children: List<PlannedField> = emptyList(),
    ) {
        val size: Int = Math.addExact(6 + name.size, dataSize)

        fun writeTo(frame: ByteBuffer) {
            frame.put(type.toByte()).put(name.size.toByte()).putInt(dataSize).put(name)
            when (type) {
                FieldType.MAP, FieldType.LIST -> children.forEach { it.writeTo(frame) }
                FieldType.S64, FieldType.DBL, FieldType.BOOL ->
                    repeat(dataSize) { byte -> frame.put((bits ushr (byte * 8)).toByte()) }
                FieldType.STR, FieldType.BIN, FieldType.UUID -> frame.put(checkNotNull(bytes))
                else -> error("Unplanned HTSP field type")
            }
        }
    }

    private fun containerSize(fields: List<PlannedField>): Int =
        fields.fold(0) { size, field -> Math.addExact(size, field.size) }

    private fun planMap(values: Map<String, Any?>): List<PlannedField> =
        values.entries.mapNotNull { (name, value) ->
            if (value == null) null else planField(name.toByteArray(StandardCharsets.UTF_8), value)
        }

    private fun planField(name: ByteArray, value: Any): PlannedField {
        if (name.size > MAX_FIELD_NAME) {
            throw HtspFramingException("encoded field name exceeds one-byte bound", byteOffset = 0)
        }
        return when (value) {
            is String -> planBytes(name, FieldType.STR, value.toByteArray(StandardCharsets.UTF_8))
            is ByteArray -> planBytes(name, FieldType.BIN, value)
            is HtspWireUuid -> planBytes(name, FieldType.UUID, value.bytes())
            is Boolean -> PlannedField(name, FieldType.BOOL, 1, bits = if (value) 1L else 0L)
            is Double -> PlannedField(name, FieldType.DBL, 8, bits = value.toRawBits())
            is Float -> PlannedField(name, FieldType.DBL, 8, bits = value.toDouble().toRawBits())
            is Number -> {
                val bits = value.toLong()
                val width = (Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(bits) + 7) / 8
                PlannedField(name, FieldType.S64, width, bits = bits)
            }
            is Map<*, *> -> {
                // Stringification can merge keys; retain the first position and last value.
                val named = LinkedHashMap<String, Any?>()
                value.forEach { (key, item) -> named[checkNotNull(key) { "Map key is null" }.toString()] = item }
                val children = planMap(named)
                PlannedField(name, FieldType.MAP, containerSize(children), children = children)
            }
            is List<*> -> {
                val children = value.mapNotNull { item -> item?.let { planField(unnamed, it) } }
                PlannedField(name, FieldType.LIST, containerSize(children), children = children)
            }
            else -> error("Unsupported HTSP field type: ${value::class.java.name}")
        }
    }

    private fun planBytes(name: ByteArray, type: Int, bytes: ByteArray): PlannedField =
        PlannedField(name, type, bytes.size, bytes = bytes)
}

internal typealias HtspCodec = `HtspCodec-internal`
