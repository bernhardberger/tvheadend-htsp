package at.bernhardberger.tvheadend.htsp.wire

import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.min

internal class HtspFramingException(
    val failure: String,
    val byteOffset: Int,
) : IOException("HTSP framing failure: $failure at byte offset $byteOffset")

internal object `HtspCodec-internal` {

    private const val TYPE_MAP: Int = 1
    private const val TYPE_S64: Int = 2
    private const val TYPE_STR: Int = 3
    private const val TYPE_BIN: Int = 4
    private const val TYPE_LIST: Int = 5
    private const val TYPE_DBL: Int = 6
    private const val TYPE_BOOL: Int = 7
    private const val TYPE_UUID: Int = 8

    private const val MAX_MESSAGE_SIZE = 32 * 1024 * 1024
    private const val MAX_FIELD_NAME = 255
    private const val MAX_NESTING_DEPTH = 32

    fun readMessage(input: InputStream): HtspWireMessage {
        // ---- Root 4B length ----
        val hdr = ByteArray(4)
        readFully(input, hdr, len = 4, what = "root length")

        val declaredLen =
            (((hdr[0].toLong() and 0xFF) shl 24) or
                    ((hdr[1].toLong() and 0xFF) shl 16) or
                    ((hdr[2].toLong() and 0xFF) shl 8)  or
                    ( hdr[3].toLong() and 0xFF)) and 0xFFFF_FFFFL

        if (declaredLen <= 0L || declaredLen > MAX_MESSAGE_SIZE.toLong()) {
            throw HtspFramingException("invalid root length", byteOffset = 0)
        }

        val len = declaredLen.toInt()
        val reader = FrameReader(input)

        val fields = LinkedHashMap<String, Any?>()
        decodeMap(reader, fields, end = 4 + len, depth = 0)

        val method = fields["method"] as? String
        val seq = (fields["seq"] as? Long)?.takeIf { it in 0L..0xFFFF_FFFFL }?.toInt()
        val rawPayload = if (method == "muxpkt") fields["payload"] as? ByteArray else null

        return HtspWireMessage(
            method = method,
            seq = seq,
            fields = fields,
            rawPayload = rawPayload,
        )
    }

    fun writeMessage(output: OutputStream, method: String, fields: Map<String, Any?>) {
        val root = LinkedHashMap<String, Any?>()
        root["method"] = method
        for ((k, v) in fields) root[k] = v

        val body = encodeMapBody(root)
        writeU32BE(output, body.size)
        output.write(body)
    }

    // ----------------------------
    // DECODING
    // ----------------------------

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
        if (depth == 0 && name == "seq" && type == TYPE_S64 && dataLen > 8) {
            throw HtspFramingException("sequence integer exceeds 64 bits", r.byteOffset)
        }

        return when (type) {
            TYPE_MAP -> LinkedHashMap<String, Any?>().also { decodeMap(r, it, r.byteOffset + dataLen, depth + 1) }
            TYPE_LIST -> ArrayList<Any?>().also { decodeList(r, it, r.byteOffset + dataLen, depth + 1) }
            TYPE_S64 -> r.readS64(dataLen)
            TYPE_STR -> r.readString(dataLen, what = "string")
            TYPE_BIN -> r.readExactly(dataLen, what = "binary")
            TYPE_DBL -> readDoubleLE(r, dataLen)
            TYPE_BOOL -> readBool(r, dataLen)
            TYPE_UUID -> HtspWireUuid(r.readExactly(dataLen, what = "uuid"))
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

    // ----------------------------
    // Root prefix write
    // ----------------------------

    private fun writeU32BE(output: OutputStream, v: Int) {
        output.write((v ushr 24) and 0xFF)
        output.write((v ushr 16) and 0xFF)
        output.write((v ushr 8) and 0xFF)
        output.write(v and 0xFF)
    }

    // ----------------------------
    // Exact read helpers
    // ----------------------------

    private fun readFully(
        input: InputStream,
        buf: ByteArray,
        off: Int = 0,
        len: Int = buf.size,
        what: String,
        bounded: Boolean = false,
    ) {
        var readTotal = 0
        while (readTotal < len) {
            val count = input.read(buf, off + readTotal, len - readTotal)
            if (count < 0) {
                if (bounded) throw EOFException("EOF while reading bounded HTSP frame")
                throw EOFException("EOF while reading $what ($len bytes, read=$readTotal)")
            }
            if (count == 0) {
                val value = input.read()
                if (value < 0) {
                    if (bounded) throw EOFException("EOF while reading bounded HTSP frame")
                    throw EOFException("EOF while reading $what ($len bytes, read=$readTotal)")
                }
                buf[off + readTotal] = value.toByte()
                readTotal++
            } else {
                readTotal += count
            }
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
            readFully(input, buf, len = n, what = what)
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
            readFully(input, scratch, len = n, what = what, bounded = bounded)
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

    // ----------------------------
    // Encoding (unchanged)
    // ----------------------------

    private fun encodeMapBody(map: Map<String, Any?>): ByteArray {
        val out = ByteArrayBuilder()
        for ((name, value) in map) {
            if (value == null) continue
            out.append(encodeField(name, value))
        }
        return out.toByteArray()
    }

    private fun encodeListBody(list: List<Any?>): ByteArray {
        val out = ByteArrayBuilder()
        for (value in list) {
            if (value == null) continue
            out.append(encodeField(null, value))
        }
        return out.toByteArray()
    }

    private fun encodeField(name: String?, value: Any): ByteArray {
        val nameBytes = name?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val nameLen = nameBytes.size
        if (nameLen > MAX_FIELD_NAME) {
            throw HtspFramingException("encoded field name exceeds one-byte bound", byteOffset = 0)
        }

        val (typeId, dataBytes) = encodeValue(value)

        val out = ByteArrayBuilder()
        out.appendByte(typeId)
        out.appendByte(nameLen)
        out.appendU32BE(dataBytes.size)
        out.append(nameBytes)
        out.append(dataBytes)
        return out.toByteArray()
    }

    private fun encodeValue(value: Any): Pair<Int, ByteArray> {
        return when (value) {
            is String -> TYPE_STR to value.toByteArray(StandardCharsets.UTF_8)
            is ByteArray -> TYPE_BIN to value
            is HtspWireUuid -> TYPE_UUID to value.bytes()
            is Boolean -> TYPE_BOOL to byteArrayOf(if (value) 1 else 0)
            is Double -> TYPE_DBL to writeDoubleLE(value)
            is Float -> TYPE_DBL to writeDoubleLE(value.toDouble())
            is Int -> TYPE_S64 to writeS64VarLen(value.toLong())
            is Long -> TYPE_S64 to writeS64VarLen(value)
            is Short -> TYPE_S64 to writeS64VarLen(value.toLong())
            is Byte -> TYPE_S64 to writeS64VarLen(value.toLong())
            is Number -> TYPE_S64 to writeS64VarLen(value.toLong())
            is Map<*, *> -> {
                val m = value.entries.associate { (k, v) ->
                    (k?.toString() ?: error("Map key is null")) to v
                }
                TYPE_MAP to encodeMapBody(m)
            }
            is List<*> -> TYPE_LIST to encodeListBody(value)
            else -> error("Unsupported HTSP field type: ${value::class.java.name}")
        }
    }

    private fun writeS64VarLen(v: Long): ByteArray {
        if (v < 0) return writeLongLE(v)
        if (v == 0L) return ByteArray(0)

        var tmp = v
        val bytes = ByteArray(8)
        var len = 0
        while (tmp != 0L) {
            bytes[len] = (tmp and 0xFF).toByte()
            tmp = tmp ushr 8
            len++
        }
        return bytes.copyOf(len)
    }

    private fun writeLongLE(v: Long): ByteArray {
        val b = ByteArray(8)
        var x = v
        for (i in 0 until 8) {
            b[i] = (x and 0xFF).toByte()
            x = x shr 8
        }
        return b
    }

    private fun writeDoubleLE(v: Double): ByteArray {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        return writeLongLE(bits)
    }

    private class ByteArrayBuilder(initial: Int = 256) {
        private var a = ByteArray(initial)
        private var n = 0

        fun append(bytes: ByteArray) {
            ensure(n + bytes.size)
            System.arraycopy(bytes, 0, a, n, bytes.size)
            n += bytes.size
        }

        fun appendByte(v: Int) {
            ensure(n + 1)
            a[n++] = (v and 0xFF).toByte()
        }

        fun appendU32BE(v: Int) {
            ensure(n + 4)
            a[n++] = ((v ushr 24) and 0xFF).toByte()
            a[n++] = ((v ushr 16) and 0xFF).toByte()
            a[n++] = ((v ushr 8) and 0xFF).toByte()
            a[n++] = (v and 0xFF).toByte()
        }

        fun toByteArray(): ByteArray = a.copyOf(n)

        private fun ensure(cap: Int) {
            if (cap <= a.size) return
            var newSize = a.size
            while (newSize < cap) newSize *= 2
            a = a.copyOf(newSize)
        }
    }
}

internal typealias HtspCodec = `HtspCodec-internal`
