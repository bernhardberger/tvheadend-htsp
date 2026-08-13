package at.bernhardberger.tvheadend.htsp

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
        val reader = BoundedReader(input, len, FramePosition(4))

        val fields = LinkedHashMap<String, Any?>()
        decodeMap(reader, fields, depth = 0)

        // Always drain any leftover bytes so next message stays aligned
        reader.drain(what = "message tail")

        val method = fields["method"] as? String
        val seq = (fields["seq"] as? Number)?.toInt()
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

    fun isMuxPkt(msg: HtspWireMessage): Boolean = msg.method == "muxpkt"

    fun tsPayload(msg: HtspWireMessage): ByteArray? =
        msg.rawPayload ?: (msg.fields["payload"] as? ByteArray)

    // ----------------------------
    // DECODING
    // ----------------------------

    private fun decodeMap(r: BoundedReader, out: MutableMap<String, Any?>, depth: Int) {
        if (depth > MAX_NESTING_DEPTH) {
            throw HtspFramingException("nesting exceeds limit", r.byteOffset)
        }
        while (r.remaining > 0) {
            val (name, value) = decodeField(r, depth)
            if (name != null) out[name] = value
        }
    }

    private fun decodeList(r: BoundedReader, out: MutableList<Any?>, depth: Int) {
        if (depth > MAX_NESTING_DEPTH) {
            throw HtspFramingException("nesting exceeds limit", r.byteOffset)
        }
        while (r.remaining > 0) {
            val (_, value) = decodeField(r, depth)
            out.add(value)
        }
    }

    private fun decodeField(r: BoundedReader, depth: Int): Pair<String?, Any?> {
        val type = r.readU8()
        val nameLen = r.readU8()
        if (nameLen > MAX_FIELD_NAME) {
            throw HtspFramingException("field name exceeds limit", r.byteOffset)
        }

        val dataLenU = r.readU32BE()
        if (nameLen.toLong() + dataLenU > r.remaining.toLong()) {
            throw HtspFramingException(
                "field name and data exceed enclosing frame",
                r.byteOffset,
            )
        }
        val dataLen = dataLenU.toInt()

        val name = if (nameLen > 0) {
            val nb = r.readExactly(nameLen, what = "field name")
            String(nb, StandardCharsets.UTF_8)
        } else null

        val data = r.slice(dataLen)

        val value: Any? = when (type) {
            TYPE_MAP -> LinkedHashMap<String, Any?>().also { decodeMap(data, it, depth + 1) }
            TYPE_LIST -> ArrayList<Any?>().also { decodeList(data, it, depth + 1) }
            TYPE_S64 -> readS64VarLenLE(data)
            TYPE_STR -> String(data.readExactly(dataLen, what = "string"), StandardCharsets.UTF_8)
            TYPE_BIN -> data.readExactly(dataLen, what = "binary")
            TYPE_DBL -> readDoubleLE(data, dataLen)
            TYPE_BOOL -> readBool(data, dataLen)
            TYPE_UUID -> HtspWireUuid(data.readExactly(dataLen, what = "uuid"))
            else -> data.readExactly(dataLen, what = "unknown field")
        }

        // ensure slice fully consumed (keeps parent aligned)
        data.drain(what = "field tail")

        return name to value
    }

    private fun readS64VarLenLE(r: BoundedReader): Long {
        val len = r.remaining
        if (len == 0) return 0L
        val n = min(len, 8)
        var v = 0L
        for (i in 0 until n) {
            v = v or ((r.readU8().toLong() and 0xFFL) shl (8 * i))
        }
        // consume any leftover bytes in this slice (if any)
        r.drain(what = "signed integer tail")
        return v
    }

    private fun readDoubleLE(r: BoundedReader, len: Int): Double {
        if (len != 8) {
            r.drain(what = "double length mismatch")
            return 0.0
        }
        var bits = 0L
        for (i in 0 until 8) {
            bits = bits or ((r.readU8().toLong() and 0xFFL) shl (8 * i))
        }
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun readBool(r: BoundedReader, len: Int): Boolean {
        if (len <= 0) return false
        val v = r.readU8() != 0
        r.drain(what = "boolean tail")
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
    ) {
        var readTotal = 0
        while (readTotal < len) {
            val count = input.read(buf, off + readTotal, len - readTotal)
            if (count < 0) {
                throw EOFException("EOF while reading $what ($len bytes, read=$readTotal)")
            }
            if (count == 0) {
                val value = input.read()
                if (value < 0) {
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
    // BoundedReader (always consumes exact bytes)
    // ----------------------------

    private class BoundedReader(
        private val input: InputStream,
        initialLimit: Int,
        private val position: FramePosition,
        private val parent: BoundedReader? = null,
    ) {
        var remaining: Int = initialLimit
            private set

        val byteOffset: Int
            get() = position.byteOffset

        fun readU8(): Int {
            requireWithinBound(1, "field byte")
            val value = input.read()
            if (value < 0) throw EOFException("EOF while reading bounded HTSP frame")
            consume(1)
            return value and 0xFF
        }

        fun readU32BE(): Long {
            val b0 = readU8().toLong()
            val b1 = readU8().toLong()
            val b2 = readU8().toLong()
            val b3 = readU8().toLong()
            return (((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3) and 0xFFFF_FFFFL)
        }

        fun readExactly(n: Int, what: String): ByteArray {
            requireWithinBound(n, what)
            val buf = ByteArray(n)
            readFully(input, buf, len = n, what = what)
            consume(n)
            return buf
        }

        fun slice(n: Int): BoundedReader {
            requireWithinBound(n, "field data")
            return BoundedReader(input, n, position = position, parent = this)
        }

        fun drain(what: String) {
            if (remaining <= 0) return
            val tmp = ByteArray(8192)
            while (remaining > 0) {
                val toRead = min(remaining, tmp.size)
                val count = input.read(tmp, 0, toRead)
                if (count < 0) throw EOFException("EOF while draining $what")
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) throw EOFException("EOF while draining $what")
                    consume(1)
                } else {
                    consume(count)
                }
            }
        }

        private fun requireWithinBound(n: Int, what: String) {
            if (n < 0 || n > remaining) {
                throw HtspFramingException("$what exceeds enclosing frame", byteOffset)
            }
        }

        private fun consume(n: Int) {
            remaining -= n
            position.byteOffset += n
            parent?.consumeFromChild(n)
        }

        private fun consumeFromChild(n: Int) {
            remaining -= n
            if (remaining < 0) {
                throw HtspFramingException("child over-consumed enclosing frame", byteOffset)
            }
            parent?.consumeFromChild(n)
        }
    }

    private class FramePosition(var byteOffset: Int)

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
