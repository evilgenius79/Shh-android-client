package io.github.evilgenius79.shh.mosh

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.OCBBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.util.Base64

/**
 * What one UDP datagram carries once it is decrypted.
 *
 * On the wire a datagram is
 *
 * ```
 * 8 bytes   nonce: bit 63 is the direction (1 = server to client), bits 0..62 a sequence
 *           number that the sender never reuses, big endian
 * n bytes   AES-128-OCB ciphertext of:  2 bytes  sender's clock, milliseconds mod 65536
 *                                       2 bytes  echo of the receiver's clock (see below)
 *                                       rest     payload (one fragment)
 * 16 bytes  OCB authentication tag
 * ```
 *
 * The cipher's nonce is twelve bytes: four zero bytes followed by the eight on the wire. There is
 * no associated data. A timestamp field of 0xFFFF means "none".
 */
internal class Packet(
    /** Sequence number, without the direction bit. */
    val seq: Long,
    val toClient: Boolean,
    /** The sender's 16-bit clock, or [NO_TIMESTAMP]. */
    val timestamp: Int,
    /** The newest timestamp the sender has received, advanced by how long it held it, or [NO_TIMESTAMP]. */
    val timestampReply: Int,
    val payload: ByteArray,
) {
    companion object {
        const val NO_TIMESTAMP = 0xFFFF

        /** Bytes a datagram is longer than its payload: nonce, the two timestamps and the tag. */
        const val OVERHEAD = NONCE_WIRE_LENGTH + 4 + TAG_LENGTH
    }
}

private const val NONCE_WIRE_LENGTH = 8
private const val TAG_LENGTH = 16
private const val DIRECTION_BIT = 1L shl 63

/** Encrypts and decrypts datagrams with the session key. Not thread safe. */
internal class PacketCodec(key: ByteArray) {
    private val keyParameter: KeyParameter
    private val encryptor = OCBBlockCipher(AESEngine.newInstance(), AESEngine.newInstance())
    private val decryptor = OCBBlockCipher(AESEngine.newInstance(), AESEngine.newInstance())

    init {
        require(key.size == 16) { "A Mosh key is 16 bytes" }
        keyParameter = KeyParameter(key)
    }

    fun seal(packet: Packet): ByteArray {
        val nonce = ByteArray(12)
        putLong(nonce, 4, (if (packet.toClient) DIRECTION_BIT else 0L) or (packet.seq and DIRECTION_BIT.inv()))

        val plain = ByteArray(4 + packet.payload.size)
        putShort(plain, 0, packet.timestamp)
        putShort(plain, 2, packet.timestampReply)
        System.arraycopy(packet.payload, 0, plain, 4, packet.payload.size)

        encryptor.init(true, AEADParameters(keyParameter, TAG_LENGTH * 8, nonce))
        val datagram = ByteArray(NONCE_WIRE_LENGTH + encryptor.getOutputSize(plain.size))
        System.arraycopy(nonce, 4, datagram, 0, NONCE_WIRE_LENGTH)
        var n = encryptor.processBytes(plain, 0, plain.size, datagram, NONCE_WIRE_LENGTH)
        n += encryptor.doFinal(datagram, NONCE_WIRE_LENGTH + n)
        check(NONCE_WIRE_LENGTH + n == datagram.size)
        return datagram
    }

    /** The packet in [datagram], or null if it was not made with this key (or is damaged). */
    fun open(datagram: ByteArray, length: Int = datagram.size): Packet? {
        if (length < Packet.OVERHEAD) return null
        val nonce = ByteArray(12)
        System.arraycopy(datagram, 0, nonce, 4, NONCE_WIRE_LENGTH)

        decryptor.init(false, AEADParameters(keyParameter, TAG_LENGTH * 8, nonce))
        val body = length - NONCE_WIRE_LENGTH
        val plain = ByteArray(decryptor.getOutputSize(body))
        val n = try {
            val processed = decryptor.processBytes(datagram, NONCE_WIRE_LENGTH, body, plain, 0)
            processed + decryptor.doFinal(plain, processed)
        } catch (e: InvalidCipherTextException) {
            return null
        }
        if (n < 4) return null

        val directionAndSeq = getLong(nonce, 4)
        return Packet(
            seq = directionAndSeq and DIRECTION_BIT.inv(),
            toClient = directionAndSeq and DIRECTION_BIT != 0L,
            timestamp = getShort(plain, 0),
            timestampReply = getShort(plain, 2),
            payload = plain.copyOfRange(4, n),
        )
    }

    companion object {
        /**
         * The 16 key bytes behind the 22 characters mosh-server prints: unpadded standard base64.
         * @throws IllegalArgumentException if [printable] is not such a key
         */
        fun decodeKey(printable: String): ByteArray {
            require(printable.length == 22) { "A Mosh key is 22 characters long" }
            val bytes = try {
                Base64.getDecoder().decode("$printable==")
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("A Mosh key is base64")
            }
            // 22 characters hold 132 bits; the four spare ones must be zero.
            require(bytes.size == 16 && Base64.getEncoder().withoutPadding().encodeToString(bytes) == printable) {
                "Not a 128-bit Mosh key"
            }
            return bytes
        }
    }
}

/**
 * One piece of a compressed [Instruction]. An instruction that does not fit a datagram is cut
 * into several fragments, each sent in a datagram of its own:
 *
 * ```
 * 8 bytes  instruction id (the same for all pieces of one instruction), big endian
 * 2 bytes  bit 15: this is the last piece; bits 0..14: index of the piece, big endian
 * rest     the piece
 * ```
 */
internal class Fragment(val id: Long, val index: Int, val final: Boolean, val contents: ByteArray) {
    fun encode(): ByteArray {
        val out = ByteArray(HEADER_LENGTH + contents.size)
        putLong(out, 0, id)
        putShort(out, 8, (if (final) 0x8000 else 0) or index)
        System.arraycopy(contents, 0, out, HEADER_LENGTH, contents.size)
        return out
    }

    companion object {
        const val HEADER_LENGTH = 10

        /** Highest index a fragment can have; bit 15 of the field is the "last piece" flag. */
        const val MAX_INDEX = 0x7FFF

        fun decode(bytes: ByteArray): Fragment? {
            if (bytes.size < HEADER_LENGTH) return null
            val field = getShort(bytes, 8)
            return Fragment(
                id = getLong(bytes, 0),
                index = field and MAX_INDEX,
                final = field and 0x8000 != 0,
                contents = bytes.copyOfRange(HEADER_LENGTH, bytes.size),
            )
        }

        /** Cuts [payload] into fragments of at most [maxContents] bytes each. */
        fun split(id: Long, payload: ByteArray, maxContents: Int): List<Fragment> {
            require(maxContents > 0)
            val count = maxOf(1, (payload.size + maxContents - 1) / maxContents)
            require(count - 1 <= MAX_INDEX) { "Instruction needs too many fragments" }
            return List(count) { i ->
                val from = i * maxContents
                val to = minOf(payload.size, from + maxContents)
                Fragment(id, i, i == count - 1, payload.copyOfRange(from, to))
            }
        }
    }
}

/**
 * Puts the fragments of one instruction back together. Only the instruction whose fragment
 * arrived most recently is collected: a fragment with another id starts over, because a sender
 * that has moved on to a new instruction will not finish the old one.
 */
internal class FragmentAssembler {
    private var currentId = 0L
    private var collecting = false
    private val pieces = ArrayList<ByteArray?>()
    private var arrived = 0
    private var total = -1

    /** Adds [fragment]; returns the whole instruction if this was its last missing piece. */
    fun add(fragment: Fragment): ByteArray? {
        if (!collecting || fragment.id != currentId) {
            reset()
            collecting = true
            currentId = fragment.id
        }
        if (fragment.final) {
            if (total != -1 && total != fragment.index + 1 || pieces.size > fragment.index + 1) {
                // Contradicts what was collected so far; believe the newer fragment.
                reset()
                collecting = true
            }
            total = fragment.index + 1
        } else if (total != -1 && fragment.index >= total) {
            return null
        }
        while (pieces.size <= fragment.index) pieces.add(null)
        if (pieces[fragment.index] == null) {
            pieces[fragment.index] = fragment.contents
            arrived++
        }
        if (arrived != total) return null

        var length = 0
        for (piece in pieces) length += piece!!.size
        val whole = ByteArray(length)
        var at = 0
        for (piece in pieces) {
            System.arraycopy(piece!!, 0, whole, at, piece.size)
            at += piece.size
        }
        // Keep the id: a late duplicate then starts a collection of its own instead of being
        // mistaken for part of something else, and at worst delivers the same instruction twice.
        pieces.clear()
        arrived = 0
        total = -1
        return whole
    }

    private fun reset() {
        pieces.clear()
        arrived = 0
        total = -1
    }
}

internal fun putLong(target: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
}

internal fun getLong(source: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
    return value
}

internal fun putShort(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 8).toByte()
    target[offset + 1] = value.toByte()
}

internal fun getShort(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)
