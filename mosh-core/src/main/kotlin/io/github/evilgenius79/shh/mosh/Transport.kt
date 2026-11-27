package io.github.evilgenius79.shh.mosh

import java.security.SecureRandom
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The client's half of Mosh's State Synchronization Protocol, without sockets, threads or a clock
 * of its own: the owner passes in the current time (monotonic milliseconds) and incoming
 * datagrams, and gets outgoing datagrams and terminal output back through [Sink]. That keeps the
 * protocol logic deterministic and testable; [MoshClient] supplies the real world.
 *
 * ## The protocol in brief
 *
 * Each side owns an object and keeps the other side's copy of it up to date. The client owns the
 * stream of user input, the server owns the terminal screen. A side numbers the successive states
 * of its object and sends [Instruction]s that say "if you hold state *a*, this diff gives you
 * state *b*". The receiver applies a diff only to exactly the state it names, and reports the
 * newest state it has, so instructions are idempotent and may be lost, duplicated or reordered.
 * The sender diffs from the newest state it believes the receiver has, and falls back to the
 * newest *acknowledged* state when no acknowledgement arrives in time.
 *
 * ## Sending input
 *
 * A state of the input stream is a position in [UserStream]. Unlike the reference sender, this one
 * never puts more than [MAX_INPUT_PER_INSTRUCTION] new bytes into one instruction: a large paste
 * becomes a chain of states one or two datagrams apart rather than one instruction of a hundred
 * fragments that a single lost datagram would void. It also bounds what mosh-server is asked to
 * decompress into its fixed 4 MiB buffer.
 *
 * ## Receiving the screen
 *
 * The reference client keeps a complete terminal for every state it has received, so it can apply
 * a diff against any of them. This client keeps none: output goes straight to the owner's
 * emulator, so it holds exactly one screen, the newest. A diff is therefore applied only when it
 * starts from that screen (or from a state known to show the same screen, see [held]); any other
 * is ignored and the held state is acknowledged again. The server then diffs from it as soon as
 * it learns of it, at the latest one retransmission timeout later.
 *
 * There is one trap in that. mosh-server remembers at most 32 states it has sent and forgets
 * those in the middle first; an acknowledgement of a forgotten state is ignored. The reference
 * client does not care, it still holds whatever older state the server falls back to. This client
 * would be stuck for good. It notices that case exactly (the server has provably seen the
 * acknowledgement, yet names an older state as the one it knows the client has) and gets out of
 * it with the one kind of diff that does not depend on its base: when the size of the screen
 * changes, the server clears and repaints everything. So the client asks for a size one row off,
 * applies the repaint that follows, and asks for the real size again. See [beginRecovery].
 */
internal class Transport(
    key: ByteArray,
    columns: Int,
    rows: Int,
    /** Largest UDP payload to produce; instructions that need more are fragmented. */
    var maxDatagram: Int,
    private val startTime: Long,
    private val sink: Sink,
    private val random: Random = SecureRandom(),
) {
    interface Sink {
        /** An encrypted datagram to send to the server. */
        fun sendDatagram(datagram: ByteArray)

        /** Terminal output that turns the previous screen into the next one. */
        fun output(data: ByteArray)

        /** The server's screen has this size from now on; a full repaint follows through [output]. */
        fun resized(columns: Int, rows: Int)
    }

    /** One state of the input stream that has been offered to the server. */
    private class SentState(val num: Long, val end: Long, var sentAt: Long)

    private val codec = PacketCodec(key)
    private val assembler = FragmentAssembler()
    private val stream = UserStream()

    // ---- Datagram layer -------------------------------------------------------------------

    private var nextSeq = 0L
    private var expectedSeq = 0L
    private var savedTimestamp = Packet.NO_TIMESTAMP
    private var savedTimestampAt = 0L
    private var rttMeasured = false
    private var srtt = 1000.0
    private var rttvar = 500.0
    private var nextInstructionId = 1L

    /** When the newest in-order datagram from the server arrived, or [NONE]. */
    var lastHeard: Long = NONE
        private set

    // ---- Sender: the input stream ---------------------------------------------------------

    /**
     * States offered to the server, oldest first. The first is the newest one the server has
     * acknowledged; nothing before it will be needed again. Positions never decrease along the
     * list and neighbours are at most [MAX_INPUT_PER_INSTRUCTION] apart, so from any state the
     * next one can be reached with a single bounded instruction.
     */
    private val sent = ArrayList<SentState>().apply { add(SentState(0, 0, startTime)) }

    private var nextAckTime = startTime
    private var nextSendTime = startTime
    private var lastSendTime = startTime - 60_000

    /** When input was last sent, as opposed to a bare acknowledgement; this is what the frame rate limits. */
    private var lastInputSendTime = startTime - 60_000
    private var mindelayClock = NONE
    private var pendingAck = false

    /** When the newest acknowledged state was sent: the last time a round trip is known to have worked. */
    var lastRoundTrip: Long = startTime
        private set

    // ---- Receiver: the screen -------------------------------------------------------------

    /**
     * Numbers of the server states that all show what has been delivered to [Sink.output], oldest
     * first. More than one when the server sent states that changed nothing visible (its
     * heartbeats and echo acknowledgements); a diff from any of them fits. The last is the one
     * acknowledged to the server.
     */
    private val held = ArrayList<Long>().apply { add(0L) }

    /**
     * Number of the first input state created after the newest entry of [held] was adopted. Once
     * the server acknowledges this state or a later one, it has seen an acknowledgement of that
     * entry.
     */
    private var ackCarrier = 1L

    private var wantedColumns = columns
    private var wantedRows = rows
    private var requestedColumns = columns
    private var requestedRows = rows

    /** Number of repaints asked for since the receiver got stuck; 0 while all is well. */
    private var recoveryAttempt = 0
    private var recoveryResizeEnd = 0L
    private var recoveryResizeAckedAt = NONE

    // Counters for tests and diagnostics; the only state that other threads may look at.

    /** How often [beginRecovery] ran. */
    @Volatile
    var recoveries = 0
        private set

    /** Server states taken over. */
    @Volatile
    var applied = 0
        private set

    /** Instructions from the server that could not be used: duplicates, and diffs from a state not held. */
    @Volatile
    var unusable = 0
        private set

    /** Fragments received that were part of an instruction too large for one datagram. */
    @Volatile
    var fragmentsOfSplitInstructions = 0
        private set

    /** Instructions sent. */
    @Volatile
    var instructionsSent = 0
        private set

    // ---- Session end ----------------------------------------------------------------------

    private var closing = false
    private var closingSince = 0L
    private var shutdownTries = 0
    private var remoteShutdown = false

    /** When the server first announced the end with a final update that could not be used, or [NONE]. */
    private var unusableShutdownSince = NONE

    /** True once the session is over, for whatever reason; nothing more will be sent. */
    var finished = false
        private set

    /** Why the session ended, if it ended because something went wrong. */
    var failure: String? = null
        private set

    /** True if the server ended the session (the remote program exited). */
    val closedByServer: Boolean get() = remoteShutdown

    init {
        require(columns > 0 && rows > 0) { "Terminal size must be positive" }
        // The first thing the server learns is the size of the terminal. It does not start the
        // remote program before it has received a first state from the client.
        stream.addResize(columns, rows)
    }

    // ---- What the owner does --------------------------------------------------------------

    /** Appends keystrokes to the input stream. */
    fun userInput(bytes: ByteArray) {
        if (closing || finished || remoteShutdown) return
        stream.addKeys(bytes)
    }

    /** Tells the server the terminal has a new size. */
    fun resize(columns: Int, rows: Int) {
        require(columns > 0 && rows > 0) { "Terminal size must be positive" }
        if (closing || finished || remoteShutdown) return
        wantedColumns = columns
        wantedRows = rows
        // During a recovery the size on the wire is deliberately not the wanted one.
        if (recoveryAttempt == 0) requestWantedSize()
    }

    /** Makes the next [tick] send something, for instance right after changing the local address. */
    fun sendSoon(now: Long) {
        nextAckTime = minOf(nextAckTime, now)
    }

    /**
     * Begins the shutdown handshake: the final state, numbered [SHUTDOWN_STATE], is offered until
     * the server acknowledges it or the attempts run out. Input not yet sent is dropped. This is
     * worth doing even before anything else was sent: a server that has been started and never
     * hears from a client lingers for a minute.
     */
    fun close(now: Long) {
        if (closing || finished) return
        if (remoteShutdown) {
            finished = true
            return
        }
        closing = true
        closingSince = now
        val last = sent.last()
        stream.truncate(last.end)
        sent.add(SentState(SHUTDOWN_STATE, last.end, NEVER))
        nextAckTime = minOf(nextAckTime, now)
    }

    /** The time at which [tick] next has something to do. */
    fun nextWake(now: Long): Long {
        if (finished) return now
        updateTimers(now, assumedIndex(now))
        var wake = minOf(nextAckTime, nextSendTime)
        if (lastHeard == NONE) wake = minOf(wake, startTime + CONNECT_TIMEOUT)
        if (closing) wake = minOf(wake, closingSince + ACTIVE_RETRY_TIMEOUT)
        if (unusableShutdownSince != NONE && !remoteShutdown) wake = minOf(wake, unusableShutdownSince + finalUpdatePatience())
        return wake
    }

    /** Sends whatever is due at [now]. */
    fun tick(now: Long) {
        if (finished) return
        if (lastHeard == NONE && now - startTime >= CONNECT_TIMEOUT) {
            fail("No answer from the Mosh server. It gives up waiting for a client after a minute, so UDP is probably blocked on the way.")
            return
        }
        if (closing && (shutdownTries >= SHUTDOWN_RETRIES || now - closingSince >= ACTIVE_RETRY_TIMEOUT)) {
            finished = true
            return
        }
        if (unusableShutdownSince != NONE && !remoteShutdown && now - unusableShutdownSince >= finalUpdatePatience()) {
            // The server is leaving and a usable version of its last update is not coming.
            adopt(SHUTDOWN_STATE, sameScreen = false)
            remoteShutdown = true
            nextAckTime = minOf(nextAckTime, now)
        }

        trimSentStates()
        val assumed = assumedIndex(now)
        updateTimers(now, assumed)
        if (now < nextAckTime && now < nextSendTime) return

        if (remoteShutdown) {
            // Acknowledge the server's final state once; like the reference client, do not wait
            // to see whether that arrives. The server leaves on its own if it does not. (If both
            // sides are shutting down at once there is nothing left to say.)
            if (!closing) sendEmptyAck(assumed, now)
            finished = true
            return
        }

        var base = assumed
        if (closing) {
            // The final state is only worth something to a server that holds its base.
            base = 0
        } else if (assumed != 0) {
            // While little is unacknowledged, diff from the acknowledged state rather than the
            // assumed one: a few bytes more, and the instruction works even if the ones before
            // it were lost.
            val fromFront = targetEnd(0)
            if (fromFront == targetEnd(assumed) &&
                fromFront - sent[0].end < RESEND_LIMIT &&
                sent[assumed].end - sent[0].end < RESEND_EXTRA
            ) {
                base = 0
            }
        }

        val target = stateEndingAt(targetEnd(base))
        if (sent[target].end == sent[base].end && !closing) {
            check(sent[base].end == sent.last().end) { "Input states are too far apart" }
            if (now >= nextAckTime) sendEmptyAck(base, now)
            if (now >= nextSendTime) nextSendTime = NEVER_DUE
        } else {
            transmit(base, target, now)
            // A paste too large for one instruction: send a few more instalments right away,
            // each building on the one before, rather than one per frame interval.
            var newest = target
            var instalments = 1
            while (instalments < MAX_INSTALMENTS_PER_TICK && newest == sent.lastIndex && canCreateState() && stream.end > sent.last().end) {
                val next = stateEndingAt(targetEnd(newest))
                transmit(newest, next, now)
                newest = next
                instalments++
            }
        }
        mindelayClock = NONE
    }

    /** Takes in one datagram from the network. */
    fun receive(datagram: ByteArray, length: Int, now: Long) {
        if (finished) return
        val packet = codec.open(datagram, length) ?: return
        // Someone reflecting the client's own datagrams back at it gets nowhere.
        if (!packet.toClient) return

        // Only the newest datagram seen so far says anything about timing; an old (or replayed)
        // one is still good for its payload, the layer above ignores what it already has.
        if (packet.seq >= expectedSeq) {
            expectedSeq = packet.seq + 1
            lastHeard = now
            if (packet.timestamp != Packet.NO_TIMESTAMP) {
                savedTimestamp = packet.timestamp
                savedTimestampAt = now
            }
            if (packet.timestampReply != Packet.NO_TIMESTAMP) {
                val roundTrip = (timestamp16(now) - packet.timestampReply) and 0xFFFF
                if (roundTrip < RTT_SANITY_LIMIT) noteRoundTrip(roundTrip.toDouble())
            }
        }

        val fragment = Fragment.decode(packet.payload) ?: return
        if (!fragment.final || fragment.index > 0) fragmentsOfSplitInstructions++
        val whole = assembler.add(fragment) ?: return
        try {
            handle(Instruction.decode(Zlib.inflate(whole)), now)
        } catch (e: MoshProtocolException) {
            fail("The Mosh server sent something this client does not understand: ${e.message}")
        }
    }

    // ---- Receiving ------------------------------------------------------------------------

    private fun handle(instruction: Instruction, now: Long) {
        if (instruction.protocolVersion != MOSH_PROTOCOL_VERSION) {
            fail("The Mosh server speaks protocol version ${instruction.protocolVersion}, this client version $MOSH_PROTOCOL_VERSION.")
            return
        }
        acknowledged(instruction.ackNum, now)
        if (finished) return
        if (remoteShutdown) return
        checkStillInStep(instruction, now)

        val newest = held.last()
        when {
            held.contains(instruction.newNum) -> noteUnusable(instruction, now)
            isOlder(instruction.newNum, newest) -> Unit
            held.contains(instruction.oldNum) -> apply(instruction, HostMessage.decode(instruction.diff), now)
            instruction.newNum == SHUTDOWN_STATE -> {
                // The remote program has exited, but the last change to the screen starts from a
                // state not held here. Say again what is held and wait a little for a version
                // that fits; [tick] goes without it when the wait is over. The server repeats
                // itself only sixteen times, and at first optimistically, from the final state
                // itself.
                if (unusableShutdownSince == NONE) unusableShutdownSince = now
                unusable++
                pendingAck = true
            }
            recoveryAttempt > 0 -> {
                val events = HostMessage.decode(instruction.diff)
                if (events.any { it is HostEvent.Resize }) apply(instruction, events, now) else noteUnusable(instruction, now)
            }
            else -> noteUnusable(instruction, now)
        }

        // States older than the one the server will diff from at the earliest are of no use.
        while (held.size > 1 && isOlder(held[0], instruction.throwawayNum)) held.removeAt(0)
        while (held.size > MAX_HELD) held.removeAt(0)
    }

    private fun apply(instruction: Instruction, events: List<HostEvent>, now: Long) {
        applied++
        var changesScreen = false
        for (event in events) {
            when (event) {
                is HostEvent.Bytes -> if (event.data.isNotEmpty()) {
                    changesScreen = true
                    sink.output(event.data)
                }
                is HostEvent.Resize -> {
                    changesScreen = true
                    sink.resized(event.columns, event.rows)
                }
                // Only local echo prediction needs to know how far the server has echoed.
                is HostEvent.EchoAck -> Unit
            }
        }
        adopt(instruction.newNum, sameScreen = !changesScreen)
        if (changesScreen) {
            // The sooner the server knows which screen is held here, the sooner it diffs from it.
            pendingAck = true
        } else if (instruction.diff.isNotEmpty()) {
            // Nothing to see (an echo acknowledgement), but the server repeats what is not
            // acknowledged. No hurry: there may be a keystroke to send it along with.
            nextAckTime = minOf(nextAckTime, now + ACK_DELAY)
        }

        if (instruction.newNum == SHUTDOWN_STATE) {
            remoteShutdown = true
            nextAckTime = minOf(nextAckTime, now)
        }
        if (recoveryAttempt > 0) {
            // Back in step with the server. Undo the size change that got us here.
            recoveryAttempt = 0
            recoveryResizeAckedAt = NONE
            if (!closing && !remoteShutdown) requestWantedSize()
        }
    }

    private fun adopt(num: Long, sameScreen: Boolean) {
        if (!sameScreen) held.clear()
        held.add(num)
        ackCarrier = sent.last().num + 1
    }

    /** The server sent something that cannot be used; make sure it knows what is held here. */
    private fun noteUnusable(instruction: Instruction, now: Long) {
        unusable++
        // If the state it diffs from in the worst case is ours it knows already and is merely
        // being optimistic. Otherwise an acknowledgement is on its way or was lost; it is lost
        // if the last one had a round trip's time to show an effect.
        if (instruction.throwawayNum != held.last() && now - lastSendTime >= ceil(srtt + rttvar).toLong()) pendingAck = true
    }

    /**
     * Detects the receiver being stuck for good; see the class description. The server's
     * [Instruction.throwawayNum] is the newest state it knows the client to have. If the server
     * has processed an acknowledgement of the newest state held here and still names a state that
     * is not held, it has forgotten ours, and nothing it sends will ever fit.
     */
    private fun checkStillInStep(instruction: Instruction, now: Long) {
        if (closing || instruction.ackNum == SHUTDOWN_STATE) return
        if (isOlder(instruction.ackNum, ackCarrier)) return
        if (held.contains(instruction.throwawayNum)) return
        val firstTime = recoveryAttempt == 0
        val lastAttemptFailed = recoveryResizeAckedAt != NONE && now - recoveryResizeAckedAt >= RECOVERY_RETRY
        if (firstTime || lastAttemptFailed) beginRecovery()
    }

    /**
     * Asks for a slightly different terminal size. The diff to a screen of another size is a
     * clear followed by a complete repaint, whatever state it starts from, so it can be applied
     * to the screen held here even though that is not its base. (What such a diff does not carry
     * is anything that is the same in both of the server's states apart from the cells: a window
     * title set in between can be missed.) Each attempt uses another size in case the state the
     * server diffs from happens to have had the previous one.
     */
    private fun beginRecovery() {
        recoveryAttempt++
        recoveries++
        val rows = if (wantedRows - recoveryAttempt >= 1) wantedRows - recoveryAttempt else wantedRows + recoveryAttempt
        stream.addResize(wantedColumns, rows)
        requestedColumns = wantedColumns
        requestedRows = rows
        recoveryResizeEnd = stream.end
        recoveryResizeAckedAt = NONE
    }

    private fun requestWantedSize() {
        if (requestedColumns == wantedColumns && requestedRows == wantedRows) return
        stream.addResize(wantedColumns, wantedRows)
        requestedColumns = wantedColumns
        requestedRows = wantedRows
    }

    // ---- Sending --------------------------------------------------------------------------

    /** The server has every input state up to [ackNum]. */
    private fun acknowledged(ackNum: Long, now: Long) {
        var index = -1
        for (i in sent.indices) {
            if (sent[i].num == ackNum) {
                index = i
                break
            }
        }
        // Not found: an old acknowledgement, of a state already left behind.
        if (index > 0) {
            lastRoundTrip = maxOf(lastRoundTrip, sent[index].sentAt)
            sent.subList(0, index).clear()
            stream.forgetBefore(sent[0].end)
            if (recoveryAttempt > 0 && recoveryResizeAckedAt == NONE && sent[0].end >= recoveryResizeEnd) {
                recoveryResizeAckedAt = now
            }
        }
        if (closing && sent[0].num == SHUTDOWN_STATE) finished = true
    }

    /**
     * Index of the newest state the server probably has: the acknowledged one, or a later one
     * if everything up to it was sent so recently that its acknowledgement can still be on its way.
     */
    private fun assumedIndex(now: Long): Int {
        val patience = rto() + ACK_DELAY
        var index = 0
        for (i in 1 until sent.size) {
            if (now - sent[i].sentAt < patience) index = i else break
        }
        return index
    }

    private fun updateTimers(now: Long, assumed: Int) {
        val last = sent.last()
        if (pendingAck) nextAckTime = minOf(nextAckTime, now)

        val contact = if (lastHeard != NONE) lastHeard else startTime
        val activeRetry = now - contact < ACTIVE_RETRY_TIMEOUT

        nextSendTime = when {
            // New input that can be sent right now: after a moment in which more may arrive,
            // and no more often than the frame rate.
            canCreateState() && stream.end > last.end && last.end - sent[assumed].end < MAX_INPUT_PER_INSTRUCTION -> {
                if (mindelayClock == NONE) mindelayClock = now
                maxOf(mindelayClock + SEND_MINDELAY, lastInputSendTime + sendInterval())
            }
            // The server is presumed to be missing something already sent: repeat at frame rate.
            sent[assumed].end != last.end && activeRetry -> lastInputSendTime + sendInterval()
            // Everything sent is presumed to have arrived but is not acknowledged: look again
            // when the oldest of it has waited long enough.
            sent[0].end != last.end && activeRetry && assumed >= 1 -> {
                var oldest = sent[1].sentAt
                for (i in 2..assumed) oldest = minOf(oldest, sent[i].sentAt)
                oldest + rto() + ACK_DELAY
            }
            else -> NEVER_DUE
        }

        if (closing || remoteShutdown) nextAckTime = minOf(nextAckTime, lastSendTime + sendInterval())
    }

    private fun canCreateState(): Boolean = !closing && sent.size < MAX_SENT_STATES

    /** Stream position of the newest state one instruction can take the server to from [base]. */
    private fun targetEnd(base: Int): Long {
        val reach = sent[base].end + MAX_INPUT_PER_INSTRUCTION
        val last = sent.last()
        if (last.end <= reach) {
            return if (canCreateState()) maxOf(last.end, minOf(stream.end, reach)) else last.end
        }
        var i = sent.lastIndex
        while (sent[i].end > reach) i--
        return sent[i].end
    }

    /** Index of the newest state that ends at [end], creating a new last state if there is none yet. */
    private fun stateEndingAt(end: Long): Int {
        val last = sent.last()
        if (end > last.end) {
            sent.add(SentState(last.num + 1, end, NEVER))
            return sent.lastIndex
        }
        var i = sent.lastIndex
        while (sent[i].end != end) i--
        return i
    }

    private fun transmit(base: Int, target: Int, now: Long) {
        val diff = UserMessage.encode(stream.slice(sent[base].end, sent[target].end))
        // Whatever lies between the two is on its way to the server as well.
        for (i in base + 1..target) sent[i].sentAt = now
        if (sent[target].num == SHUTDOWN_STATE) shutdownTries++
        emit(sent[base].num, sent[target].num, diff, now)
    }

    /**
     * Sends an instruction that changes nothing, to acknowledge and to show the client is alive.
     * It still gets a state number of its own: the server only counts new states as a sign of life.
     */
    private fun sendEmptyAck(base: Int, now: Long) {
        val last = sent.last()
        val num = last.num + 1
        sent.add(SentState(num, last.end, now))
        emit(sent[base].num, num, EMPTY, now)
    }

    private fun emit(oldNum: Long, newNum: Long, diff: ByteArray, now: Long) {
        instructionsSent++
        val chaff = ByteArray(random.nextInt(MAX_CHAFF + 1)).also { random.nextBytes(it) }
        val instruction = Instruction(MOSH_PROTOCOL_VERSION, oldNum, newNum, held.last(), sent[0].num, diff, chaff)
        val payload = Zlib.deflate(instruction.encode())
        val room = maxDatagram - Packet.OVERHEAD - Fragment.HEADER_LENGTH
        // Every instruction gets a new id, also a repeated one: its acknowledgement field and
        // padding differ, and the receiver insists that fragments with one id belong together.
        for (fragment in Fragment.split(nextInstructionId++, payload, room)) {
            sink.sendDatagram(codec.seal(Packet(nextSeq++, false, timestamp16(now), takeTimestampReply(now), fragment.encode())))
        }
        lastSendTime = now
        if (diff.isNotEmpty()) lastInputSendTime = now
        nextAckTime = now + ACK_INTERVAL
        nextSendTime = NEVER_DUE
        pendingAck = false
    }

    /**
     * Keeps [sent] from growing without bound while nothing is acknowledged, by forgetting states
     * in the middle. A forgotten state can no longer be recognised when it is acknowledged, which
     * only delays things; but its neighbours must remain within one instruction of each other.
     */
    private fun trimSentStates() {
        while (sent.size > MAX_SENT_STATES) {
            val middle = sent.size / 2
            var victim = -1
            var distance = 0
            while (victim == -1 && distance < sent.size) {
                for (i in intArrayOf(middle - distance, middle + distance)) {
                    if (i >= 1 && i <= sent.size - 2 && sent[i + 1].end - sent[i - 1].end <= MAX_INPUT_PER_INSTRUCTION) {
                        victim = i
                        break
                    }
                }
                distance++
            }
            if (victim == -1) return
            sent.removeAt(victim)
        }
    }

    // ---- Timing ---------------------------------------------------------------------------

    /**
     * The 16-bit clock put into every datagram. The receiver echoes it, corrected by how long it
     * held on to it, which gives the sender a round trip time without either side keeping
     * per-datagram state.
     */
    private fun timestamp16(now: Long): Int {
        val t = (now and 0xFFFF).toInt()
        return if (t == Packet.NO_TIMESTAMP) 0 else t
    }

    private fun takeTimestampReply(now: Long): Int {
        if (savedTimestamp == Packet.NO_TIMESTAMP || now - savedTimestampAt >= TIMESTAMP_HOLD_LIMIT) return Packet.NO_TIMESTAMP
        val reply = (savedTimestamp + (now - savedTimestampAt).toInt()) and 0xFFFF
        savedTimestamp = Packet.NO_TIMESTAMP
        return reply
    }

    /** Smoothed round trip time and its variation, as TCP computes them (RFC 6298). */
    private fun noteRoundTrip(sample: Double) {
        if (!rttMeasured) {
            srtt = sample
            rttvar = sample / 2
            rttMeasured = true
        } else {
            rttvar = 0.75 * rttvar + 0.25 * abs(srtt - sample)
            srtt = 0.875 * srtt + 0.125 * sample
        }
    }

    /** How long to wait for an acknowledgement before assuming a datagram was lost. */
    private fun rto(): Long = ceil(srtt + 4 * rttvar).toLong().coerceIn(MIN_RTO, MAX_RTO)

    /**
     * How long to wait, once the server has announced the end, for a final update that starts
     * from a state held here: long enough for the server to notice it was too optimistic about
     * what the client has (its retransmission timeout, estimated with this side's numbers).
     */
    private fun finalUpdatePatience(): Long = maxOf(MIN_FINAL_UPDATE_PATIENCE, 2 * (rto() + ACK_DELAY))

    /** Time between two instructions with new input: about two per round trip. */
    private fun sendInterval(): Long = ceil(srtt / 2).toLong().coerceIn(SEND_INTERVAL_MIN, SEND_INTERVAL_MAX)

    private fun fail(message: String) {
        if (finished) return
        failure = message
        finished = true
    }

    /** State numbers are unsigned 64-bit values; [SHUTDOWN_STATE] is the largest. */
    private fun isOlder(a: Long, b: Long): Boolean = java.lang.Long.compareUnsigned(a, b) < 0

    companion object {
        const val NONE = -1L

        /** A send time for states that were never sent: longer ago than any timeout. */
        private const val NEVER = Long.MIN_VALUE / 4

        /** A deadline that does not come. */
        private const val NEVER_DUE = Long.MAX_VALUE / 4

        // Timing constants of the reference implementation (transportsender.h, network.h). The
        // two sides do not have to agree on them, but each side's timeouts allow for the other's
        // delays, so there is no good reason to differ.
        const val SEND_INTERVAL_MIN = 20L
        const val SEND_INTERVAL_MAX = 250L
        /** An idle side sends an empty instruction this often to show it is alive. */
        const val ACK_INTERVAL = 3000L
        /** How long the peer may sit on an acknowledgement, hoping to send it along with data. */
        const val ACK_DELAY = 100L
        /** Wait this long for more keystrokes before sending the first. */
        const val SEND_MINDELAY = 1L
        /** Once the peer has been silent this long, stop repeating at frame rate. */
        const val ACTIVE_RETRY_TIMEOUT = 10_000L
        const val SHUTDOWN_RETRIES = 16
        const val MIN_RTO = 50L
        const val MAX_RTO = 1000L
        /** A timestamp is echoed only if it was received less than this long ago. */
        private const val TIMESTAMP_HOLD_LIMIT = 1000L
        /** Round trip samples beyond this are not believed (the peer was probably suspended). */
        private const val RTT_SANITY_LIMIT = 5000
        const val MAX_CHAFF = 16
        /** Diff from the acknowledged state if that makes the instruction shorter than this ... */
        private const val RESEND_LIMIT = 1000L
        /** ... and no more than this much longer than it would otherwise be. */
        private const val RESEND_EXTRA = 100L

        // This client's own choices.

        /** Most input bytes one instruction introduces: at most two datagrams, usually one. */
        const val MAX_INPUT_PER_INSTRUCTION = 2048L
        /** Most input states kept while waiting for acknowledgements (the reference keeps 32 too). */
        const val MAX_SENT_STATES = 32
        /** Most instructions with new input sent in one go when there is a backlog. */
        private const val MAX_INSTALMENTS_PER_TICK = 4
        private const val MAX_HELD = 256
        private const val MIN_FINAL_UPDATE_PATIENCE = 500L
        /** Ask for another repaint if one has not arrived this long after the server saw the request. */
        private const val RECOVERY_RETRY = 2000L
        /**
         * mosh-server exits if no client has reached it 60 seconds after it started; a client
         * that has heard nothing by then (plus a little, the server started first) never will.
         */
        const val CONNECT_TIMEOUT = 65_000L
    }
}
