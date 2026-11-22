package com.universalremote.tv.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal protobuf writer.
 *
 * The Android TV Remote protocol uses plain protocol buffers (proto3 for the remote channel,
 * proto2 for pairing). Only the handful of message types in [RemoteMessage] and
 * [PairingMessage] are needed, so a small hand-written codec keeps the app free of a
 * protobuf runtime/codegen step. Field numbers come from the published schema
 * (`remotemessage.proto` and `polo.proto`), see docs/PROTOCOL.md.
 */
class ProtoWriter(initialCapacity: Int = 64) {

    private val out = ByteArrayOutputStream(initialCapacity)

    fun varint(value: Long): ProtoWriter {
        var v = value
        do {
            var b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
        } while (v != 0L)
        return this
    }

    /** Writes a field tag for the given wire type. */
    fun tag(field: Int, wireType: Int): ProtoWriter = varint(((field shl 3) or wireType).toLong())

    /**
     * Varint field. proto3 omits zero values on the wire, which is fine for everything the TV
     * parses, but callers can force the write when a zero is meaningful.
     */
    fun int(field: Int, value: Int, writeDefault: Boolean = false): ProtoWriter {
        if (value == 0 && !writeDefault) return this
        tag(field, WIRE_VARINT)
        if (value < 0) varint(value.toLong()) else varint(value.toLong() and 0xFFFFFFFFL)
        return this
    }

    fun bool(field: Int, value: Boolean, writeDefault: Boolean = false): ProtoWriter {
        if (!value && !writeDefault) return this
        tag(field, WIRE_VARINT)
        varint(if (value) 1 else 0)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter {
        if (value.isEmpty()) return this
        bytes(field, value.toByteArray(StandardCharsets.UTF_8))
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LENGTH_DELIMITED)
        varint(value.size.toLong())
        out.write(value, 0, value.size)
        return this
    }

    /** Writes an embedded message built by [body]. Always emitted so the receiver sees the field. */
    fun message(field: Int, body: ProtoWriter.() -> Unit): ProtoWriter {
        val nested = ProtoWriter().apply(body).toByteArray()
        return bytes(field, nested)
    }

    fun rawMessage(field: Int, encoded: ByteArray): ProtoWriter = bytes(field, encoded)

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_START_GROUP = 3
        const val WIRE_END_GROUP = 4
        const val WIRE_FIXED32 = 5
    }
}

/** Raised when a payload is not valid protobuf, so the caller can drop it and continue. */
class ProtoDecodeException(message: String) : Exception(message)

/**
 * Minimal protobuf reader. Unknown fields are skipped using the wire type, which is what keeps
 * the app working when a TV sends a field this version does not know about.
 */
class ProtoReader(private val data: ByteArray) {

    private var pos = 0

    val isAtEnd: Boolean get() = pos >= data.size

    class Field(val number: Int, val wireType: Int)

    fun nextField(): Field? {
        if (pos >= data.size) return null
        val key = readVarint()
        val number = (key ushr 3).toInt()
        val wireType = (key and 0x7).toInt()
        if (number == 0) throw ProtoDecodeException("invalid field number 0")
        return Field(number, wireType)
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= data.size) throw ProtoDecodeException("truncated varint")
            val b = data[pos++].toInt()
            result = result or ((b.toLong() and 0x7f) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw ProtoDecodeException("varint too long")
    }

    fun readInt(): Int = readVarint().toInt()

    fun readBool(): Boolean = readVarint() != 0L

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > data.size) throw ProtoDecodeException("bad length $len")
        val slice = data.copyOfRange(pos, pos + len)
        pos += len
        return slice
    }

    fun readString(): String = String(readBytes(), StandardCharsets.UTF_8)

    /** Reads an embedded message. Returns a reader positioned on that sub-message. */
    fun readMessage(): ProtoReader = ProtoReader(readBytes())

    fun skip(wireType: Int) {
        when (wireType) {
            ProtoWriter.WIRE_VARINT -> readVarint()
            ProtoWriter.WIRE_FIXED64 -> skipBytes(8)
            ProtoWriter.WIRE_LENGTH_DELIMITED -> skipBytes(readVarint().toInt())
            ProtoWriter.WIRE_FIXED32 -> skipBytes(4)
            else -> throw ProtoDecodeException("unsupported wire type $wireType")
        }
    }

    private fun skipBytes(count: Int) {
        if (count < 0 || pos + count > data.size) throw ProtoDecodeException("truncated value")
        pos += count
    }
}

/**
 * Reads the varint length prefix used by both pairing and remote channels.
 *
 * @return the frame length, or null when the stream ended cleanly before a frame started.
 */
internal fun readFrameLength(input: InputStream): Int? {
    var result = 0
    var shift = 0
    var readAny = false
    while (shift < 35) {
        val b = input.read()
        if (b == -1) {
            if (!readAny) return null
            throw EOFException("stream ended inside a frame length")
        }
        readAny = true
        result = result or ((b and 0x7f) shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
    }
    throw ProtoDecodeException("frame length varint too long")
}

/** Writes a length-delimited frame. */
fun writeFrame(out: OutputStream, payload: ByteArray) {
    val header = ByteArrayOutputStream(5)
    var v = payload.size.toLong()
    do {
        var b = (v and 0x7f).toInt()
        v = v ushr 7
        if (v != 0L) b = b or 0x80
        header.write(b)
    } while (v != 0L)
    out.write(header.toByteArray())
    out.write(payload)
    out.flush()
}

/** Reads one complete frame from [input], or null at a clean end of stream. */
fun readFrame(input: InputStream): ByteArray? {
    val length = readFrameLength(input) ?: return null
    if (length < 0) throw ProtoDecodeException("negative frame length $length")
    if (length == 0) return ByteArray(0)
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val n = input.read(buffer, offset, length - offset)
        if (n < 0) throw EOFException("stream ended inside a frame")
        offset += n
    }
    return buffer
}
