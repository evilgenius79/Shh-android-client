package io.github.evilgenius79.shh.mosh

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The only protocol version there is; a peer speaking another one cannot be talked to. */
internal const val MOSH_PROTOCOL_VERSION = 2

/**
 * State number that means "the session is over". On the wire it is the largest unsigned 64-bit
 * value, so it compares as newer than every other state.
 */
internal const val SHUTDOWN_STATE = -1L

/**
 * The transport-level message of the State Synchronization Protocol
 * (`TransportBuffers.Instruction` in Mosh's `transportinstruction.proto`).
 *
 * It says: "if you hold state [oldNum], applying [diff] to it gives state [newNum]". It also
 * acknowledges the newest state received from the other side ([ackNum]) and tells the other side
 * which of its states it no longer has to keep ([throwawayNum]). State numbers are unsigned.
 */
internal class Instruction(
    val protocolVersion: Int,
    val oldNum: Long,
    val newNum: Long,
    val ackNum: Long,
    val throwawayNum: Long,
    val diff: ByteArray,
    /** Random padding that hides the exact length of [diff] from an observer. */
    val chaff: ByteArray,
) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.varintField(1, protocolVersion.toLong())
        w.varintField(2, oldNum)
        w.varintField(3, newNum)
        w.varintField(4, ackNum)
        w.varintField(5, throwawayNum)
        w.bytesField(6, diff)
        w.bytesField(7, chaff)
        return w.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): Instruction {
            var version = 0
            var oldNum = 0L
            var newNum = 0L
            var ackNum = 0L
            var throwawayNum = 0L
            var diff = EMPTY
            var chaff = EMPTY
            val r = ProtoReader(bytes)
            while (r.next()) {
                when {
                    r.field == 1 && r.wireType == WIRE_VARINT -> version = r.readVarint().toInt()
                    r.field == 2 && r.wireType == WIRE_VARINT -> oldNum = r.readVarint()
                    r.field == 3 && r.wireType == WIRE_VARINT -> newNum = r.readVarint()
                    r.field == 4 && r.wireType == WIRE_VARINT -> ackNum = r.readVarint()
                    r.field == 5 && r.wireType == WIRE_VARINT -> throwawayNum = r.readVarint()
                    r.field == 6 && r.wireType == WIRE_BYTES -> diff = r.readBytes()
                    r.field == 7 && r.wireType == WIRE_BYTES -> chaff = r.readBytes()
                    else -> r.skip()
                }
            }
            return Instruction(version, oldNum, newNum, ackNum, throwawayNum, diff, chaff)
        }
    }
}

/** One thing the user did: typed some bytes, or changed the size of the terminal. */
internal sealed class UserEvent {
    class Keys(val bytes: ByteArray) : UserEvent()
    class Resize(val columns: Int, val rows: Int) : UserEvent()
}

/**
 * The diff between two states of the client's input stream (`ClientBuffers.UserMessage` in
 * `userinput.proto`): the events that were appended, in order.
 *
 * Both sub-messages are protobuf extensions of an otherwise empty `Instruction`, which on the
 * wire simply makes them fields 2 (keystroke) and 3 (resize) of it.
 */
internal object UserMessage {
    fun encode(events: List<UserEvent>): ByteArray {
        val message = ProtoWriter()
        for (event in events) {
            val instruction = ProtoWriter()
            when (event) {
                is UserEvent.Keys -> {
                    val keystroke = ProtoWriter()
                    keystroke.bytesField(4, event.bytes)
                    instruction.bytesField(2, keystroke.toByteArray())
                }
                is UserEvent.Resize -> {
                    val resize = ProtoWriter()
                    resize.varintField(5, event.columns.toLong())
                    resize.varintField(6, event.rows.toLong())
                    instruction.bytesField(3, resize.toByteArray())
                }
            }
            message.bytesField(1, instruction.toByteArray())
        }
        return message.toByteArray()
    }

    fun decode(bytes: ByteArray): List<UserEvent> {
        val events = ArrayList<UserEvent>()
        val message = ProtoReader(bytes)
        while (message.next()) {
            if (message.field != 1 || message.wireType != WIRE_BYTES) {
                message.skip()
                continue
            }
            val instruction = message.readMessage()
            while (instruction.next()) {
                when {
                    instruction.field == 2 && instruction.wireType == WIRE_BYTES -> {
                        var keys = EMPTY
                        val keystroke = instruction.readMessage()
                        while (keystroke.next()) {
                            if (keystroke.field == 4 && keystroke.wireType == WIRE_BYTES) keys = keystroke.readBytes()
                            else keystroke.skip()
                        }
                        events.add(UserEvent.Keys(keys))
                    }
                    instruction.field == 3 && instruction.wireType == WIRE_BYTES -> {
                        val size = decodeResize(instruction.readMessage())
                        events.add(UserEvent.Resize(size.first, size.second))
                    }
                    else -> instruction.skip()
                }
            }
        }
        return events
    }
}

/** One part of a change to the server's screen. */
internal sealed class HostEvent {
    /** Terminal output that turns the old screen into the new one. */
    class Bytes(val data: ByteArray) : HostEvent()

    /** The server's screen has this size from here on. */
    class Resize(val columns: Int, val rows: Int) : HostEvent()

    /** The server has shown the effect of the client's input up to this input state. */
    class EchoAck(val inputState: Long) : HostEvent()
}

/**
 * The diff between two states of the server's screen (`HostBuffers.HostMessage` in
 * `hostinput.proto`). As in [UserMessage] the parts are extensions: fields 2 (host bytes),
 * 3 (resize) and 7 (echo acknowledgement) of each instruction.
 */
internal object HostMessage {
    fun decode(bytes: ByteArray): List<HostEvent> {
        val events = ArrayList<HostEvent>()
        val message = ProtoReader(bytes)
        while (message.next()) {
            if (message.field != 1 || message.wireType != WIRE_BYTES) {
                message.skip()
                continue
            }
            val instruction = message.readMessage()
            while (instruction.next()) {
                when {
                    instruction.field == 2 && instruction.wireType == WIRE_BYTES -> {
                        var data = EMPTY
                        val hostBytes = instruction.readMessage()
                        while (hostBytes.next()) {
                            if (hostBytes.field == 4 && hostBytes.wireType == WIRE_BYTES) data = hostBytes.readBytes()
                            else hostBytes.skip()
                        }
                        events.add(HostEvent.Bytes(data))
                    }
                    instruction.field == 3 && instruction.wireType == WIRE_BYTES -> {
                        val size = decodeResize(instruction.readMessage())
                        events.add(HostEvent.Resize(size.first, size.second))
                    }
                    instruction.field == 7 && instruction.wireType == WIRE_BYTES -> {
                        var number = 0L
                        val echoAck = instruction.readMessage()
                        while (echoAck.next()) {
                            if (echoAck.field == 8 && echoAck.wireType == WIRE_VARINT) number = echoAck.readVarint()
                            else echoAck.skip()
                        }
                        events.add(HostEvent.EchoAck(number))
                    }
                    else -> instruction.skip()
                }
            }
        }
        return events
    }

    /** The server's side of the codec; the client never sends this, tests do. */
    fun encode(events: List<HostEvent>): ByteArray {
        val message = ProtoWriter()
        for (event in events) {
            val instruction = ProtoWriter()
            when (event) {
                is HostEvent.Bytes -> {
                    val hostBytes = ProtoWriter()
                    hostBytes.bytesField(4, event.data)
                    instruction.bytesField(2, hostBytes.toByteArray())
                }
                is HostEvent.Resize -> {
                    val resize = ProtoWriter()
                    resize.varintField(5, event.columns.toLong())
                    resize.varintField(6, event.rows.toLong())
                    instruction.bytesField(3, resize.toByteArray())
                }
                is HostEvent.EchoAck -> {
                    val echoAck = ProtoWriter()
                    echoAck.varintField(8, event.inputState)
                    instruction.bytesField(7, echoAck.toByteArray())
                }
            }
            message.bytesField(1, instruction.toByteArray())
        }
        return message.toByteArray()
    }
}

/** `ResizeMessage { width = 5; height = 6 }`, the same in both directions. */
private fun decodeResize(r: ProtoReader): Pair<Int, Int> {
    var columns = 0
    var rows = 0
    while (r.next()) {
        when {
            r.field == 5 && r.wireType == WIRE_VARINT -> columns = r.readVarint().toInt()
            r.field == 6 && r.wireType == WIRE_VARINT -> rows = r.readVarint().toInt()
            else -> r.skip()
        }
    }
    return columns to rows
}

/** Instructions travel zlib-compressed (RFC 1950, as produced by zlib's `compress`). */
internal object Zlib {
    /**
     * The most a compressed instruction may expand to. mosh-server decompresses into a fixed
     * buffer of this size, so a larger instruction could not be sent to it either.
     */
    const val MAX_INFLATED = 2048 * 2048

    fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 32)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 3 + 64)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    throw MoshProtocolException("Instruction is not valid zlib data: ${e.message}")
                }
                if (n == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw MoshProtocolException("Instruction is truncated zlib data")
                }
                out.write(buffer, 0, n)
                if (out.size() > MAX_INFLATED) throw MoshProtocolException("Instruction expands to more than $MAX_INFLATED bytes")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

internal val EMPTY = ByteArray(0)
