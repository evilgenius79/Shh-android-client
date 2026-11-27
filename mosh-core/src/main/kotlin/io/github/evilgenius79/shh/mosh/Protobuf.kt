package io.github.evilgenius79.shh.mosh

import java.io.ByteArrayOutputStream
import java.io.IOException

/** The peer sent something that is authentic (it decrypted) but that this client cannot make sense of. */
internal class MoshProtocolException(message: String) : IOException(message)

/**
 * Just enough of the Protocol Buffers wire format for Mosh's three small message types: varint
 * fields (wire type 0) and length-delimited fields (wire type 2).
 */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varintField(field: Int, value: Long) {
        writeVarint((field.toLong() shl 3) or WIRE_VARINT.toLong())
        writeVarint(value)
    }

    fun bytesField(field: Int, value: ByteArray, offset: Int = 0, length: Int = value.size - offset) {
        writeVarint((field.toLong() shl 3) or WIRE_BYTES.toLong())
        writeVarint(length.toLong())
        out.write(value, offset, length)
    }

    /** A varint is seven bits per byte, least significant group first; the value is unsigned. */
    private fun writeVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Walks the fields of one encoded message. Unknown fields can be passed over with [skip]. */
internal class ProtoReader(
    private val data: ByteArray,
    private var pos: Int = 0,
    private val end: Int = data.size,
) {
    /** Number of the field [next] stopped at. */
    var field: Int = 0
        private set

    /** Wire type of the field [next] stopped at. */
    var wireType: Int = 0
        private set

    /** Moves to the next field; false at the end of the message. */
    fun next(): Boolean {
        if (pos >= end) return false
        val tag = readVarint()
        field = (tag ushr 3).toInt()
        wireType = (tag and 7).toInt()
        if (field == 0) throw MoshProtocolException("Field number 0 in a message")
        return true
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw MoshProtocolException("Message ends inside a number")
            val b = data[pos++].toInt()
            // The tenth byte can only contribute one bit; more than ten bytes is not a varint.
            if (shift >= 64) throw MoshProtocolException("Number is longer than ten bytes")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    /** The payload of a length-delimited field as a new array. */
    fun readBytes(): ByteArray {
        val length = readLength()
        val result = data.copyOfRange(pos, pos + length)
        pos += length
        return result
    }

    /** A reader over the payload of a length-delimited field, for nested messages. */
    fun readMessage(): ProtoReader {
        val length = readLength()
        val nested = ProtoReader(data, pos, pos + length)
        pos += length
        return nested
    }

    /** Passes over the current field's value, whatever its type. */
    fun skip() {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> advance(8)
            WIRE_BYTES -> advance(readLength())
            WIRE_FIXED32 -> advance(4)
            else -> throw MoshProtocolException("Unsupported wire type $wireType")
        }
    }

    private fun readLength(): Int {
        val length = readVarint()
        if (length < 0 || length > end - pos) throw MoshProtocolException("Field runs past the end of its message")
        return length.toInt()
    }

    private fun advance(count: Int) {
        if (count > end - pos) throw MoshProtocolException("Field runs past the end of its message")
        pos += count
    }
}

internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_BYTES = 2
internal const val WIRE_FIXED32 = 5
