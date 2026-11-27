package io.github.evilgenius79.shh.mosh

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The byte layouts, checked against encodings made by independent implementations: the
 * protobuf messages were serialised with Google's protobuf library from Mosh's own `.proto`
 * files, the datagrams encrypted with OpenSSL's AES-OCB.
 */
class WireFormatTest {
    private fun hex(text: String): ByteArray {
        val clean = text.replace(" ", "")
        return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val key = ByteArray(16) { it.toByte() }

    // ---- Protocol buffers -----------------------------------------------------------------

    @Test
    fun `an instruction is encoded as protobuf does`() {
        val diff = UserMessage.encode(listOf(UserEvent.Resize(80, 24)))
        assertEquals("0a061a0428503018", diff.hex())
        val instruction = Instruction(2, oldNum = 0, newNum = 1, ackNum = 0, throwawayNum = 0, diff = diff, chaff = ByteArray(0))
        assertEquals("0802100018012000280032080a061a04285030183a00", instruction.encode().hex())
    }

    @Test
    fun `state numbers are unsigned 64 bit values`() {
        val encoded = hex("080210ac0218ffffffffffffffffff0120808080808080808080012807320201023a03aabbcc")
        val instruction = Instruction.decode(encoded)
        assertEquals(2, instruction.protocolVersion)
        assertEquals(300L, instruction.oldNum)
        assertEquals(SHUTDOWN_STATE, instruction.newNum)
        assertEquals(Long.MIN_VALUE, instruction.ackNum) // 2^63
        assertEquals(7L, instruction.throwawayNum)
        assertContentEquals(byteArrayOf(1, 2), instruction.diff)
        assertContentEquals(hex("aabbcc"), instruction.chaff)

        assertEquals(encoded.hex(), instruction.encode().hex())
    }

    @Test
    fun `fields that are absent have their defaults and unknown fields are skipped`() {
        val empty = Instruction.decode(ByteArray(0))
        assertEquals(0, empty.protocolVersion)
        assertEquals(0L, empty.newNum)
        assertEquals(0, empty.diff.size)

        // Field 9 as a varint, field 10 as bytes, field 11 as fixed32 and field 12 as fixed64
        // surround the one field that is known (new_num = 5).
        val withUnknown = hex("4801" + "52021122" + "5d01020304" + "1805" + "610102030405060708")
        assertEquals(5L, Instruction.decode(withUnknown).newNum)
    }

    @Test
    fun `user input is encoded as keystroke and resize extensions`() {
        val events = listOf(
            UserEvent.Keys("ls\n".toByteArray()),
            UserEvent.Resize(132, 43),
            UserEvent.Keys(byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())),
        )
        val encoded = UserMessage.encode(events)
        assertEquals("0a07120522036c730a0a071a05288401302b0a07120522031b5b41", encoded.hex())
        assertEquals(0, UserMessage.encode(emptyList()).size)

        val decoded = UserMessage.decode(encoded)
        assertEquals(3, decoded.size)
        assertContentEquals("ls\n".toByteArray(), (decoded[0] as UserEvent.Keys).bytes)
        assertEquals(132 to 43, (decoded[1] as UserEvent.Resize).let { it.columns to it.rows })
    }

    @Test
    fun `a screen update is decoded into its parts in order`() {
        val events = HostMessage.decode(hex("0a043a0240030a061a042864301e0a0a120822061b5b324a6869"))
        assertEquals(3, events.size)
        assertEquals(3L, (events[0] as HostEvent.EchoAck).inputState)
        assertEquals(100 to 30, (events[1] as HostEvent.Resize).let { it.columns to it.rows })
        assertEquals("\u001b[2Jhi", String((events[2] as HostEvent.Bytes).data, Charsets.ISO_8859_1))

        assertEquals("0a043a0240030a061a042864301e0a0a120822061b5b324a6869", HostMessage.encode(events).hex())
        assertTrue(HostMessage.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun `damaged messages are rejected rather than misread`() {
        // A length that runs past the end, a number that never ends, a group (wire type 3).
        assertFailsWith<MoshProtocolException> { Instruction.decode(hex("3205aabb")) }
        assertFailsWith<MoshProtocolException> { Instruction.decode(hex("10ffffffffffffffffffffff01")) }
        assertFailsWith<MoshProtocolException> { Instruction.decode(hex("1b")) }
        assertFailsWith<MoshProtocolException> { HostMessage.decode(hex("0a0512ff")) }
    }

    @Test
    fun `instructions survive compression and garbage does not`() {
        val text = ByteArray(5000) { ('a' + it % 7).code.toByte() }
        val packed = Zlib.deflate(text)
        assertTrue(packed.size < text.size / 4)
        // zlib's own framing: deflate with a 32K window, then a checksum.
        assertEquals(0x78, packed[0].toInt() and 0xFF)
        assertContentEquals(text, Zlib.inflate(packed))
        assertContentEquals(ByteArray(0), Zlib.inflate(Zlib.deflate(ByteArray(0))))

        assertFailsWith<MoshProtocolException> { Zlib.inflate(byteArrayOf(1, 2, 3, 4)) }
        assertFailsWith<MoshProtocolException> { Zlib.inflate(packed.copyOf(packed.size - 6)) }
    }

    // ---- Datagrams ------------------------------------------------------------------------

    @Test
    fun `a datagram is the nonce followed by the OCB ciphertext and tag`() {
        val codec = PacketCodec(key)
        val toServer = Packet(0x0102030405060708, toClient = false, timestamp = 0x1234, timestampReply = 0xFFFF, payload = "hello mosh".toByteArray())
        assertEquals("010203040506070880be6a22140fb1bee54bad8461c3f9d4c1506b5d62d25561e732cae4b118", codec.seal(toServer).hex())

        // The direction is the top bit of the nonce.
        val toClient = Packet(5, toClient = true, timestamp = 1, timestampReply = 2, payload = ByteArray(0))
        assertEquals("800000000000000567036a8bfe820ffa44c2990d736c4b032337e7b2", codec.seal(toClient).hex())
    }

    @Test
    fun `a datagram decrypts to what was put in`() {
        val codec = PacketCodec(key)
        val opened = assertNotNull(codec.open(hex("010203040506070880be6a22140fb1bee54bad8461c3f9d4c1506b5d62d25561e732cae4b118")))
        assertEquals(0x0102030405060708, opened.seq)
        assertFalse(opened.toClient)
        assertEquals(0x1234, opened.timestamp)
        assertEquals(Packet.NO_TIMESTAMP, opened.timestampReply)
        assertEquals("hello mosh", String(opened.payload))

        val fromServer = assertNotNull(codec.open(hex("800000000000000567036a8bfe820ffa44c2990d736c4b032337e7b2")))
        assertTrue(fromServer.toClient)
        assertEquals(5L, fromServer.seq)
        assertEquals(0, fromServer.payload.size)
    }

    @Test
    fun `a datagram that was tampered with or made with another key is not opened`() {
        val codec = PacketCodec(key)
        val datagram = codec.seal(Packet(9, false, 1, 2, "payload".toByteArray()))
        for (i in datagram.indices) {
            val damaged = datagram.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull(codec.open(damaged), "byte $i")
        }
        assertNull(codec.open(datagram.copyOf(datagram.size - 1)))
        assertNull(codec.open(ByteArray(10)))
        assertNull(PacketCodec(ByteArray(16) { 7 }).open(datagram))
        assertNotNull(codec.open(datagram))
    }

    @Test
    fun `the printable key is unpadded base64 of sixteen bytes`() {
        assertContentEquals(key, PacketCodec.decodeKey("AAECAwQFBgcICQoLDA0ODw"))
        assertEquals(16, PacketCodec.decodeKey("1RIhw7m9IgrxPMuH2JNNPQ").size)
        assertFailsWith<IllegalArgumentException> { PacketCodec.decodeKey("AAECAwQFBgcICQoLDA0OD") } // too short
        assertFailsWith<IllegalArgumentException> { PacketCodec.decodeKey("AAECAwQFBgcICQoLDA0ODw==") }
        assertFailsWith<IllegalArgumentException> { PacketCodec.decodeKey("AAECAwQFBgcICQoLDA0OD!") }
        // The last character carries four bits that are not part of the key.
        assertFailsWith<IllegalArgumentException> { PacketCodec.decodeKey("AAECAwQFBgcICQoLDA0ODx") }
    }

    // ---- Fragments ------------------------------------------------------------------------

    @Test
    fun `a fragment starts with the instruction id and its index`() {
        val last = Fragment(0x0102030405060708, 3, final = true, contents = byteArrayOf(9, 8))
        assertEquals("0102030405060708" + "8003" + "0908", last.encode().hex())
        val first = Fragment(1, 0, final = false, contents = ByteArray(0))
        assertEquals("0000000000000001" + "0000", first.encode().hex())

        val decoded = assertNotNull(Fragment.decode(last.encode()))
        assertEquals(0x0102030405060708, decoded.id)
        assertEquals(3, decoded.index)
        assertTrue(decoded.final)
        assertContentEquals(byteArrayOf(9, 8), decoded.contents)
        assertNull(Fragment.decode(ByteArray(9)))
    }

    @Test
    fun `an instruction is cut into fragments that fit`() {
        val payload = ByteArray(2500) { it.toByte() }
        val fragments = Fragment.split(7, payload, 1000)
        assertEquals(listOf(1000, 1000, 500), fragments.map { it.contents.size })
        assertEquals(listOf(0, 1, 2), fragments.map { it.index })
        assertEquals(listOf(false, false, true), fragments.map { it.final })
        assertTrue(fragments.all { it.id == 7L })

        assertEquals(1, Fragment.split(7, ByteArray(1000), 1000).size)
        // Even nothing at all is sent as one, final, fragment.
        val empty = Fragment.split(7, ByteArray(0), 1000).single()
        assertTrue(empty.final)
    }

    @Test
    fun `fragments are reassembled in whatever order they arrive`() {
        val payload = ByteArray(2500) { (it * 31).toByte() }
        val fragments = Fragment.split(7, payload, 1000)
        for (order in listOf(listOf(0, 1, 2), listOf(2, 1, 0), listOf(1, 2, 0), listOf(2, 0, 1))) {
            val assembler = FragmentAssembler()
            assertNull(assembler.add(fragments[order[0]]), "$order")
            assertNull(assembler.add(fragments[order[1]]), "$order")
            assertContentEquals(payload, assembler.add(fragments[order[2]]), "$order")
        }
    }

    @Test
    fun `duplicate fragments do not complete an instruction early`() {
        val payload = ByteArray(2500) { (it * 31).toByte() }
        val fragments = Fragment.split(7, payload, 1000)
        val assembler = FragmentAssembler()
        assertNull(assembler.add(fragments[0]))
        assertNull(assembler.add(fragments[0]))
        assertNull(assembler.add(fragments[2]))
        assertNull(assembler.add(fragments[2]))
        assertNull(assembler.add(fragments[0]))
        assertContentEquals(payload, assembler.add(fragments[1]))
    }

    @Test
    fun `a fragment of a newer instruction abandons the one being collected`() {
        val first = Fragment.split(1, ByteArray(2000) { 1 }, 1000)
        val second = Fragment.split(2, ByteArray(2000) { 2 }, 1000)
        val assembler = FragmentAssembler()
        assertNull(assembler.add(first[0]))
        assertNull(assembler.add(second[1]))
        // The rest of the first instruction is no use any more...
        assertNull(assembler.add(first[1]))
        // ... and has in turn displaced the second, which has to arrive again in full.
        assertNull(assembler.add(second[0]))
        assertContentEquals(ByteArray(2000) { 2 }, assembler.add(second[1]))
    }

    @Test
    fun `an instruction that fits one datagram is delivered every time it arrives`() {
        val single = Fragment.split(4, byteArrayOf(1, 2, 3), 1000).single()
        val assembler = FragmentAssembler()
        assertContentEquals(byteArrayOf(1, 2, 3), assembler.add(single))
        // A duplicate datagram: delivered again, the layer above knows it has that state.
        assertContentEquals(byteArrayOf(1, 2, 3), assembler.add(single))
    }

    // ---- The input stream -----------------------------------------------------------------

    @Test
    fun `the input stream is addressed by position`() {
        val stream = UserStream()
        stream.addResize(80, 24)
        stream.addKeys("abc".toByteArray())
        stream.addKeys("de".toByteArray())
        stream.addResize(100, 30)
        stream.addKeys("f".toByteArray())
        assertEquals(8L, stream.end)

        fun render(from: Long, to: Long) = stream.slice(from, to).joinToString(" ") {
            when (it) {
                is UserEvent.Keys -> String(it.bytes)
                is UserEvent.Resize -> "${it.columns}x${it.rows}"
            }
        }
        // Keystrokes typed one after another travel as one event.
        assertEquals("80x24 abcde 100x30 f", render(0, 8))
        assertEquals("bcd", render(2, 5))
        assertEquals("e 100x30", render(5, 7))
        assertEquals("", render(4, 4))

        stream.forgetBefore(3)
        assertEquals("cde 100x30 f", render(3, 8))
        stream.truncate(5)
        assertEquals(5L, stream.end)
        assertEquals("cd", render(3, 5))
        stream.addKeys("z".toByteArray())
        assertEquals("cdz", render(3, 6))
    }
}
