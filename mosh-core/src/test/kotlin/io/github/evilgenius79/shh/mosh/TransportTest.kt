package io.github.evilgenius79.shh.mosh

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The protocol engine on its own, with a scripted peer and a clock the test moves by hand. This
 * is where the cases live that a real server only produces when the network misbehaves in just
 * the right way.
 */
class TransportTest {
    private val key = ByteArray(16) { (it * 3).toByte() }

    /** A [Transport] together with the test's view of what it sent and delivered. */
    private inner class Rig(columns: Int = 80, rows: Int = 24, maxDatagram: Int = 1252) : Transport.Sink {
        var now = 5_000_000L
        val start = now
        private val serverCodec = PacketCodec(key)
        private val assembler = FragmentAssembler()
        private var serverSeq = 0L
        private var serverInstructionId = 1L

        /** Every datagram the client sent, decrypted. */
        val packets = ArrayList<Packet>()

        /** Every instruction the client sent, with the time it was sent. */
        val instructions = ArrayList<Pair<Long, Instruction>>()
        val output = StringBuilder()
        val resizes = ArrayList<Pair<Int, Int>>()

        /** Called for every instruction the client sends, for tests that play the server. */
        var onInstruction: (Instruction) -> Unit = {}

        /** Return true to lose a datagram on its way to the server. */
        var loseOutgoing: () -> Boolean = { false }

        val transport = Transport(key, columns, rows, maxDatagram, now, this, Random(42))

        override fun sendDatagram(datagram: ByteArray) {
            val packet = assertNotNull(serverCodec.open(datagram), "the client sent something that does not decrypt")
            packets.add(packet)
            if (loseOutgoing()) return
            val fragment = assertNotNull(Fragment.decode(packet.payload))
            val whole = assembler.add(fragment) ?: return
            val instruction = Instruction.decode(Zlib.inflate(whole))
            instructions.add(now to instruction)
            onInstruction(instruction)
        }

        override fun output(data: ByteArray) {
            output.append(String(data, Charsets.ISO_8859_1))
        }

        override fun resized(columns: Int, rows: Int) {
            resizes.add(columns to rows)
        }

        /** Lets [ms] milliseconds pass, running the engine whenever it says it has something to do. */
        fun advance(ms: Long) {
            val until = now + ms
            var steps = 0
            while (!transport.finished) {
                val wake = transport.nextWake(now)
                if (wake > until) break
                now = maxOf(now, wake)
                transport.tick(now)
                if (++steps > 20_000) fail("The engine keeps asking to run without making progress")
            }
            now = until
            transport.tick(now)
        }

        /** Delivers an instruction from the server. */
        fun fromServer(
            oldNum: Long,
            newNum: Long,
            ackNum: Long,
            throwawayNum: Long = oldNum,
            events: List<HostEvent> = emptyList(),
            version: Int = MOSH_PROTOCOL_VERSION,
            timestamp: Int = Packet.NO_TIMESTAMP,
            timestampReply: Int = Packet.NO_TIMESTAMP,
        ) {
            val instruction = Instruction(version, oldNum, newNum, ackNum, throwawayNum, HostMessage.encode(events), ByteArray(3))
            val payload = Zlib.deflate(instruction.encode())
            for (fragment in Fragment.split(serverInstructionId++, payload, 1200)) {
                val datagram = serverCodec.seal(Packet(serverSeq++, true, timestamp, timestampReply, fragment.encode()))
                transport.receive(datagram, datagram.size, now)
            }
        }

        fun bytes(text: String) = listOf<HostEvent>(HostEvent.Bytes(text.toByteArray()))

        /** Number of the newest input state the client has sent. */
        val newestSent: Long get() = instructions.last().second.newNum

        fun userEvents(instruction: Instruction): String = UserMessage.decode(instruction.diff).joinToString(" ") {
            when (it) {
                is UserEvent.Keys -> String(it.bytes, Charsets.ISO_8859_1)
                is UserEvent.Resize -> "${it.columns}x${it.rows}"
            }
        }
    }

    // ---- Connecting -----------------------------------------------------------------------

    @Test
    fun `the first datagram announces the terminal size as input state one`() {
        val rig = Rig(columns = 132, rows = 43)
        rig.advance(0)

        val packet = rig.packets.single()
        assertEquals(0L, packet.seq)
        assertFalse(packet.toClient)
        assertEquals((rig.start and 0xFFFF).toInt(), packet.timestamp)
        assertEquals(Packet.NO_TIMESTAMP, packet.timestampReply)

        val instruction = rig.instructions.single().second
        assertEquals(2, instruction.protocolVersion)
        assertEquals(0L, instruction.oldNum)
        assertEquals(1L, instruction.newNum)
        assertEquals(0L, instruction.ackNum)
        assertEquals(0L, instruction.throwawayNum)
        assertEquals("132x43", rig.userEvents(instruction))
        assertTrue(instruction.chaff.size <= Transport.MAX_CHAFF)
    }

    @Test
    fun `the first datagram is repeated until the server answers`() {
        val rig = Rig()
        rig.advance(4_000)
        // Nothing is known about the path yet, so the timeout is the longest there is: a second,
        // plus the time the server may sit on its acknowledgement.
        val times = rig.instructions.map { it.first - rig.start }
        assertEquals(listOf(0L, 1100L, 2200L, 3300L), times)
        assertTrue(rig.instructions.all { it.second.oldNum == 0L && it.second.newNum == 1L })
        // Every datagram has a sequence number of its own, and every instruction its own id.
        assertEquals(listOf(0L, 1L, 2L, 3L), rig.packets.map { it.seq })
        assertEquals(4, rig.packets.map { Fragment.decode(it.payload)!!.id }.toSet().size)

        rig.fromServer(oldNum = 0, newNum = 1, ackNum = 1, events = rig.bytes("$ "))
        val sentBefore = rig.instructions.size
        rig.advance(2_500)
        // Acknowledged: nothing left to repeat, only the screen update to acknowledge.
        assertEquals(sentBefore + 1, rig.instructions.size)
        assertEquals(1L, rig.instructions.last().second.ackNum)
        assertEquals(1L, rig.instructions.last().second.throwawayNum)
        assertEquals("", rig.userEvents(rig.instructions.last().second))
    }

    @Test
    fun `a server that never answers is given up on after it has given up itself`() {
        val rig = Rig()
        rig.advance(64_000)
        assertFalse(rig.transport.finished)
        rig.advance(2_000)
        assertTrue(rig.transport.finished)
        assertNotNull(rig.transport.failure)
        // Before that the client kept trying, but not frantically.
        assertTrue(rig.instructions.size in 20..40, "${rig.instructions.size} attempts")
    }

    @Test
    fun `datagrams that are not from the server are ignored`() {
        val rig = Rig()
        rig.advance(0)
        val stranger = PacketCodec(ByteArray(16) { 9 })
        val forged = stranger.seal(Packet(0, true, 1, 2, ByteArray(20)))
        rig.transport.receive(forged, forged.size, rig.now)
        rig.transport.receive(ByteArray(5), 5, rig.now)
        // The client's own datagram, reflected.
        val own = PacketCodec(key).seal(rig.packets.single())
        rig.transport.receive(own, own.size, rig.now)

        assertEquals(Transport.NONE, rig.transport.lastHeard)
        assertEquals(0, rig.transport.applied)
        assertFalse(rig.transport.finished)
    }

    @Test
    fun `a server that speaks another protocol version ends the session`() {
        val rig = Rig()
        rig.advance(0)
        rig.fromServer(oldNum = 0, newNum = 1, ackNum = 1, version = 3)
        assertTrue(rig.transport.finished)
        assertTrue(rig.transport.failure!!.contains("version 3"))
    }

    // ---- Input ----------------------------------------------------------------------------

    /**
     * Brings [rig] to an established session: the server has answered the first datagram after
     * a round trip of 40 ms and put a prompt on the screen, and all is acknowledged.
     */
    private fun connected(rig: Rig) {
        rig.advance(0)
        val sentAt = rig.packets.single().timestamp
        rig.advance(40)
        rig.fromServer(oldNum = 0, newNum = 1, ackNum = 1, events = rig.bytes("$ "), timestamp = 100, timestampReply = sentAt)
        rig.advance(50)
    }

    @Test
    fun `keystrokes typed together travel together a millisecond later`() {
        val rig = Rig()
        connected(rig)
        val before = rig.instructions.size

        rig.transport.userInput("l".toByteArray())
        rig.transport.userInput("s".toByteArray())
        rig.advance(0)
        assertEquals(before, rig.instructions.size)
        rig.advance(1)
        assertEquals(before + 1, rig.instructions.size)
        assertEquals("ls", rig.userEvents(rig.instructions.last().second))
    }

    @Test
    fun `unacknowledged keystrokes are sent again from the acknowledged state`() {
        val rig = Rig()
        connected(rig)
        val acked = rig.newestSent
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = acked)
        val before = rig.instructions.size

        rig.transport.userInput("a".toByteArray())
        rig.advance(30)
        rig.transport.userInput("b".toByteArray())
        rig.advance(30)
        val sent = rig.instructions.drop(before).map { it.second }
        assertEquals(2, sent.size)
        assertEquals("a", rig.userEvents(sent[0]))
        // The second instruction does not rely on the first having arrived.
        assertEquals(acked, sent[1].oldNum)
        assertEquals("ab", rig.userEvents(sent[1]))

        // No acknowledgement: after the timeout the lot is repeated, under the same state number.
        rig.advance(300)
        val repeated = rig.instructions.last().second
        assertEquals(sent[1].newNum, repeated.newNum)
        assertEquals(acked, repeated.oldNum)
        assertEquals("ab", rig.userEvents(repeated))

        // Acknowledged: quiet, until the next heartbeat three seconds after the last instruction.
        rig.fromServer(oldNum = 2, newNum = 3, ackNum = repeated.newNum)
        val count = rig.instructions.size
        val lastSent = rig.instructions.last().first
        rig.advance(2_700)
        assertEquals(count, rig.instructions.size)
        rig.advance(400)
        assertEquals(count + 1, rig.instructions.size)
        assertEquals(lastSent + Transport.ACK_INTERVAL, rig.instructions.last().first)
        val heartbeat = rig.instructions.last().second
        assertEquals("", rig.userEvents(heartbeat))
        // A heartbeat is a state of its own, so the server can tell it from a repeat.
        assertEquals(repeated.newNum + 1, heartbeat.newNum)
        assertEquals(repeated.newNum, heartbeat.throwawayNum)
    }

    /**
     * The server's side of the input stream as the reference implementation keeps it: every
     * state received, a diff applied only to the state it names, old states dropped when told.
     */
    private class InputReceiver {
        val states = HashMap<Long, ByteArray>().apply { put(0L, ByteArray(0)) }
        var newest = 0L
        var wasted = 0

        fun receive(instruction: Instruction) {
            states.keys.removeAll { java.lang.Long.compareUnsigned(it, instruction.throwawayNum) < 0 }
            if (states.containsKey(instruction.newNum)) return
            val base = states[instruction.oldNum]
            if (base == null) {
                wasted++
                return
            }
            val content = ByteArrayOutputStream()
            content.write(base)
            for (event in UserMessage.decode(instruction.diff)) {
                when (event) {
                    is UserEvent.Keys -> content.write(event.bytes)
                    is UserEvent.Resize -> content.write("<${event.columns}x${event.rows}>".toByteArray())
                }
            }
            states[instruction.newNum] = content.toByteArray()
            if (java.lang.Long.compareUnsigned(instruction.newNum, newest) > 0) {
                // A newer state must contain everything the previous newest one did.
                val previous = states[newest]
                if (previous != null) assertContentEquals(previous, content.toByteArray().copyOf(previous.size), "input went backwards")
                newest = instruction.newNum
            }
        }
    }

    @Test
    fun `a large paste is sent in instalments and arrives complete`() {
        val rig = Rig()
        val server = InputReceiver()
        var serverState = 0L
        rig.onInstruction = { instruction ->
            assertTrue(UserMessage.decode(instruction.diff).sumOf { if (it is UserEvent.Keys) it.bytes.size else 1 } <= Transport.MAX_INPUT_PER_INSTRUCTION)
            server.receive(instruction)
            // Acknowledge like a server with nothing to say.
            rig.fromServer(oldNum = serverState, newNum = serverState + 1, ackNum = server.newest, throwawayNum = serverState)
            serverState++
        }
        rig.advance(0)
        val paste = ByteArray(50_000) { ('a' + (it * 7 + it / 13) % 26).code.toByte() }
        rig.transport.userInput(paste)
        rig.advance(10_000)

        assertContentEquals("<80x24>".toByteArray() + paste, server.states[server.newest])
        assertEquals(0, server.wasted, "instructions the server could not use")
        // With nothing lost, nothing is sent twice: 25 instalments plus heartbeats.
        assertTrue(rig.instructions.count { it.second.diff.isNotEmpty() } in 25..27, "${rig.instructions.size} instructions")
    }

    @Test
    fun `a large paste gets through heavy loss in both directions`() {
        for (seed in 1..5) {
            val rig = Rig()
            val server = InputReceiver()
            val luck = Random(seed.toLong())
            var serverState = 0L
            rig.loseOutgoing = { luck.nextInt(100) < 40 }
            rig.onInstruction = { instruction ->
                server.receive(instruction)
                if (luck.nextInt(100) >= 40) {
                    rig.fromServer(oldNum = serverState, newNum = serverState + 1, ackNum = server.newest, throwawayNum = serverState)
                    serverState++
                }
            }
            rig.advance(0)
            val paste = ByteArray(30_000) { (luck.nextInt(95) + 32).toByte() }
            rig.transport.userInput(paste.copyOfRange(0, 20_000))
            rig.advance(3_000)
            rig.transport.resize(100, 40)
            rig.transport.userInput(paste.copyOfRange(20_000, 30_000))
            rig.advance(120_000)

            val expected = "<80x24>".toByteArray() + paste.copyOfRange(0, 20_000) + "<100x40>".toByteArray() + paste.copyOfRange(20_000, 30_000)
            assertContentEquals(expected, server.states[server.newest], "seed $seed")
        }
    }

    @Test
    fun `input small enough for one datagram is not fragmented and large input is`() {
        val rig = Rig(maxDatagram = 500)
        connected(rig)
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = rig.newestSent)
        val before = rig.packets.size
        val random = Random(3)
        rig.transport.userInput(ByteArray(2000) { random.nextInt(256).toByte() })
        rig.advance(5)

        val sent = rig.packets.drop(before).map { Fragment.decode(it.payload)!! }
        assertTrue(sent.size >= 4, "${sent.size} fragments")
        assertEquals(1, sent.map { it.id }.toSet().size)
        assertEquals(sent.indices.toList(), sent.map { it.index })
        assertEquals(sent.indices.map { it == sent.lastIndex }, sent.map { it.final })
        // Each datagram stays within the limit: fragment, plus nonce, timestamps and tag.
        assertTrue(rig.packets.drop(before).all { it.payload.size + Packet.OVERHEAD <= 500 })
        assertEquals(2000, UserMessage.decode(rig.instructions.last().second.diff).sumOf { (it as UserEvent.Keys).bytes.size })
    }

    // ---- The screen -----------------------------------------------------------------------

    @Test
    fun `a screen update is applied once and only to the screen it was made for`() {
        val rig = Rig()
        connected(rig)
        assertEquals("$ ", rig.output.toString())

        rig.fromServer(oldNum = 1, newNum = 2, ackNum = 1, events = rig.bytes("a"))
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = 1, events = rig.bytes("a")) // duplicate
        rig.fromServer(oldNum = 1, newNum = 3, ackNum = 1, events = rig.bytes("ab")) // from a screen no longer shown
        rig.fromServer(oldNum = 7, newNum = 8, ackNum = 1, events = rig.bytes("zzz")) // from one never seen
        assertEquals("$ a", rig.output.toString())

        rig.fromServer(oldNum = 2, newNum = 3, ackNum = 1, throwawayNum = 2, events = rig.bytes("b"))
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = 1, events = rig.bytes("a")) // late, older than what is held
        assertEquals("$ ab", rig.output.toString())
        assertEquals(3, rig.transport.applied)
    }

    @Test
    fun `a changed screen is acknowledged at once and an unusable update makes the client say what it holds`() {
        val rig = Rig()
        connected(rig)
        val before = rig.instructions.size

        rig.fromServer(oldNum = 1, newNum = 2, ackNum = 1, events = rig.bytes("a"))
        rig.advance(0)
        assertEquals(before + 1, rig.instructions.size)
        assertEquals(2L, rig.instructions.last().second.ackNum)

        // The server has not seen that acknowledgement (it still names state 1 as known) and
        // keeps diffing from state 1. Remind it, but only once per retransmission timeout.
        rig.advance(5)
        rig.fromServer(oldNum = 1, newNum = 3, ackNum = 1, throwawayNum = 1, events = rig.bytes("ab"))
        rig.advance(5)
        rig.fromServer(oldNum = 1, newNum = 4, ackNum = 1, throwawayNum = 1, events = rig.bytes("abc"))
        rig.advance(5)
        assertEquals(before + 1, rig.instructions.size)
        rig.advance(60)
        rig.fromServer(oldNum = 1, newNum = 5, ackNum = 1, throwawayNum = 1, events = rig.bytes("abcd"))
        rig.advance(0)
        assertEquals(before + 2, rig.instructions.size)
        assertEquals(2L, rig.instructions.last().second.ackNum)
        assertEquals("$ a", rig.output.toString())
    }

    @Test
    fun `states that changed nothing visible stand for the same screen`() {
        val rig = Rig()
        connected(rig)
        // A heartbeat and an echo acknowledgement: new state numbers, same screen.
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = 1)
        rig.fromServer(oldNum = 2, newNum = 3, ackNum = 1, events = listOf(HostEvent.EchoAck(4)))
        // The server has not heard that the client has state 3 and diffs from state 1 or 2.
        rig.fromServer(oldNum = 2, newNum = 4, ackNum = 1, throwawayNum = 1, events = rig.bytes("x"))
        assertEquals("$ x", rig.output.toString())

        // Once the screen has changed the old numbers no longer stand for it.
        rig.fromServer(oldNum = 1, newNum = 5, ackNum = 1, throwawayNum = 1, events = rig.bytes("y"))
        assertEquals("$ x", rig.output.toString())
        rig.fromServer(oldNum = 4, newNum = 5, ackNum = 1, throwawayNum = 4, events = rig.bytes("y"))
        assertEquals("$ xy", rig.output.toString())
    }

    @Test
    fun `the round trip time measured from echoed timestamps sets the pace`() {
        val rig = Rig()
        rig.advance(0)
        // The server answers 40 ms after the first datagram and echoes its timestamp.
        val sentAt = rig.packets.single().timestamp
        rig.advance(40)
        rig.fromServer(oldNum = 0, newNum = 1, ackNum = 1, events = rig.bytes("$ "), timestamp = 7_000, timestampReply = sentAt)
        rig.advance(4)
        // The acknowledgement echoes the server's timestamp, advanced by the time it was held.
        val reply = rig.packets.last()
        assertEquals(7_000 + (rig.instructions.last().first - (rig.start + 40)).toInt(), reply.timestampReply)
        // A timestamp is echoed once.
        rig.advance(3_100)
        assertEquals(Packet.NO_TIMESTAMP, rig.packets.last().timestampReply)

        // With a 40 ms round trip, input goes out every 20 ms and is repeated after
        // 40 + 4 * 20 = 120 ms plus the 100 ms the server may delay its acknowledgement.
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = rig.newestSent)
        val before = rig.instructions.size
        rig.transport.userInput("a".toByteArray())
        rig.advance(400)
        val times = rig.instructions.drop(before).map { it.first }
        assertEquals(listOf(220L), times.zipWithNext { a, b -> b - a }.distinct())
    }

    // ---- Losing step with the server and finding it again ---------------------------------

    @Test
    fun `when the server has forgotten the state held here a repaint is forced`() {
        val rig = Rig()
        connected(rig)
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = rig.newestSent, events = rig.bytes("a"))
        rig.advance(20)
        // State 2 is on the screen and has been acknowledged. The server acknowledges the very
        // instruction that carried that acknowledgement, and still names state 1 as the newest
        // one it knows the client to have: it no longer has state 2 and will never diff from it.
        val carrier = rig.newestSent
        assertEquals(2L, rig.instructions.last().second.ackNum)
        rig.fromServer(oldNum = 1, newNum = 9, ackNum = carrier, throwawayNum = 1, events = rig.bytes("abc"))
        assertEquals(1, rig.transport.recoveries)
        rig.advance(20)
        assertEquals("80x23", rig.userEvents(rig.instructions.last().second))

        // More of the same changes nothing ...
        rig.fromServer(oldNum = 1, newNum = 10, ackNum = carrier, throwawayNum = 1, events = rig.bytes("abcd"))
        assertEquals("$ a", rig.output.toString())
        assertEquals(1, rig.transport.recoveries)

        // ... until the server has resized its screen. That update repaints everything, so it
        // fits although it starts from state 1.
        val resizeState = rig.newestSent
        rig.fromServer(
            oldNum = 1, newNum = 11, ackNum = resizeState, throwawayNum = 1,
            events = listOf(HostEvent.Resize(80, 23), HostEvent.Bytes("[repaint]".toByteArray())),
        )
        assertEquals("$ a[repaint]", rig.output.toString())
        assertEquals(listOf(80 to 23), rig.resizes)

        // Back in step: ask for the real size again, and carry on as usual from state 11.
        rig.advance(20)
        assertTrue(rig.instructions.any { rig.userEvents(it.second) == "80x24" })
        assertEquals(11L, rig.instructions.last().second.ackNum)
        rig.fromServer(oldNum = 11, newNum = 12, ackNum = rig.newestSent, throwawayNum = 11, events = listOf(HostEvent.Resize(80, 24), HostEvent.Bytes("!".toByteArray())))
        assertEquals("$ a[repaint]!", rig.output.toString())
        assertEquals(1, rig.transport.recoveries)
    }

    @Test
    fun `a forced repaint that does not come is asked for again with another size`() {
        val rig = Rig()
        connected(rig)
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = rig.newestSent, events = rig.bytes("a"))
        rig.advance(20)
        rig.fromServer(oldNum = 1, newNum = 9, ackNum = rig.newestSent, throwawayNum = 1, events = rig.bytes("abc"))
        rig.advance(20)
        assertEquals("80x23", rig.userEvents(rig.instructions.last().second))

        // The server has the resize, but the state it diffs from happened to be 23 rows high
        // already, so what it sends is an ordinary update that does not fit.
        var serverState = 20L
        repeat(30) {
            rig.fromServer(oldNum = 1, newNum = serverState++, ackNum = rig.newestSent, throwawayNum = 1, events = rig.bytes("abc$it"))
            rig.advance(100)
        }
        assertEquals(2, rig.transport.recoveries)
        assertTrue(rig.instructions.any { rig.userEvents(it.second) == "80x22" })
        assertEquals("$ a", rig.output.toString())

        // A size the user asks for in the meantime is kept for later rather than mixed in.
        rig.transport.resize(100, 30)
        rig.advance(20)
        assertFalse(rig.instructions.any { rig.userEvents(it.second).contains("100x30") })

        rig.fromServer(
            oldNum = 1, newNum = 90, ackNum = rig.newestSent, throwawayNum = 1,
            events = listOf(HostEvent.Resize(80, 22), HostEvent.Bytes("[repaint]".toByteArray())),
        )
        rig.advance(20)
        assertEquals("$ a[repaint]", rig.output.toString())
        assertTrue(rig.instructions.any { rig.userEvents(it.second).contains("100x30") })
    }

    @Test
    fun `an update with a resize is not special while all is well`() {
        val rig = Rig()
        connected(rig)
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = rig.newestSent, events = rig.bytes("a"))
        // From a state not held: skipped like any other, the server will send one that fits.
        rig.fromServer(oldNum = 1, newNum = 3, ackNum = 1, throwawayNum = 1, events = listOf(HostEvent.Resize(90, 20), HostEvent.Bytes("R".toByteArray())))
        assertEquals("$ a", rig.output.toString())
        assertEquals(0, rig.transport.recoveries)
    }

    // ---- Ending ---------------------------------------------------------------------------

    @Test
    fun `closing offers the final state until the server acknowledges it`() {
        val rig = Rig()
        connected(rig)
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = rig.newestSent)
        val acked = rig.newestSent
        rig.transport.userInput("x".toByteArray())
        rig.advance(5)
        val before = rig.instructions.size

        rig.transport.close(rig.now)
        rig.transport.userInput("ignored".toByteArray())
        rig.advance(60)
        val closing = rig.instructions.drop(before).map { it.second }
        assertTrue(closing.size >= 2, "${closing.size} attempts")
        assertTrue(closing.all { it.newNum == SHUTDOWN_STATE })
        // Always from the acknowledged state, so that any one of them is enough.
        assertTrue(closing.all { it.oldNum == acked && rig.userEvents(it) == "x" })
        assertFalse(rig.transport.finished)

        rig.fromServer(oldNum = 2, newNum = 3, ackNum = SHUTDOWN_STATE)
        assertTrue(rig.transport.finished)
        assertNull(rig.transport.failure)
        assertFalse(rig.transport.closedByServer)
    }

    @Test
    fun `closing before anything was sent still tells the server to leave`() {
        val rig = Rig()
        rig.transport.close(rig.now)
        rig.advance(0)
        val first = rig.instructions.single().second
        assertEquals(0L, first.oldNum)
        assertEquals(SHUTDOWN_STATE, first.newNum)
        // The terminal size that would have been the first thing sent is of no interest now.
        assertEquals("", rig.userEvents(first))

        rig.fromServer(oldNum = 0, newNum = 1, ackNum = SHUTDOWN_STATE)
        assertTrue(rig.transport.finished)
        assertNull(rig.transport.failure)
    }

    @Test
    fun `closing gives up after sixteen attempts`() {
        val rig = Rig()
        connected(rig)
        val before = rig.instructions.size
        rig.transport.close(rig.now)
        rig.advance(20_000)
        assertTrue(rig.transport.finished)
        assertNull(rig.transport.failure)
        assertEquals(Transport.SHUTDOWN_RETRIES, rig.instructions.size - before)
    }

    @Test
    fun `the end of the remote program is acknowledged and reported`() {
        val rig = Rig()
        connected(rig)
        val before = rig.instructions.size
        rig.fromServer(oldNum = 1, newNum = SHUTDOWN_STATE, ackNum = rig.newestSent, events = rig.bytes("logout\r\n"))
        rig.advance(30)

        assertEquals("$ logout\r\n", rig.output.toString())
        assertTrue(rig.transport.finished)
        assertTrue(rig.transport.closedByServer)
        assertNull(rig.transport.failure)
        assertEquals(before + 1, rig.instructions.size)
        assertEquals(SHUTDOWN_STATE, rig.instructions.last().second.ackNum)
    }

    @Test
    fun `the end of the remote program is believed even if its last update never fits`() {
        val rig = Rig()
        connected(rig)
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = rig.newestSent, events = rig.bytes("a"))
        rig.advance(20)
        // The server's attempts all start from states that are not held here.
        repeat(5) {
            rig.fromServer(oldNum = 1, newNum = SHUTDOWN_STATE, ackNum = 1, throwawayNum = 1, events = rig.bytes("abye"))
            rig.advance(20)
        }
        assertFalse(rig.transport.finished)
        // Meanwhile the client keeps saying what it does hold.
        assertEquals(2L, rig.instructions.last().second.ackNum)

        rig.advance(500)
        assertTrue(rig.transport.finished)
        assertTrue(rig.transport.closedByServer)
        assertEquals(SHUTDOWN_STATE, rig.instructions.last().second.ackNum)
        assertEquals("$ a", rig.output.toString())
    }

    @Test
    fun `a final update that fits after all is used`() {
        val rig = Rig()
        connected(rig)
        rig.fromServer(oldNum = 1, newNum = 2, ackNum = rig.newestSent, events = rig.bytes("a"))
        rig.advance(20)
        rig.fromServer(oldNum = 1, newNum = SHUTDOWN_STATE, ackNum = 1, throwawayNum = 1, events = rig.bytes("abye"))
        rig.advance(100)
        rig.fromServer(oldNum = 2, newNum = SHUTDOWN_STATE, ackNum = rig.newestSent, throwawayNum = 2, events = rig.bytes("bye"))
        rig.advance(30)
        assertEquals("$ abye", rig.output.toString())
        assertTrue(rig.transport.finished)
        assertTrue(rig.transport.closedByServer)
    }
}
