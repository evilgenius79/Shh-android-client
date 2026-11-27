package io.github.evilgenius79.shh.mosh

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What a [MoshClient] needs from its owner. Everything is called on the client's own thread, one
 * call at a time and never before [MoshClient.start].
 */
public interface MoshListener {
    /**
     * Terminal output to feed, in order, into a terminal emulator that started blank with the
     * size given to the [MoshClient].
     *
     * Mosh does not transport the remote program's output. The server runs a terminal emulator
     * of its own and sends what it takes to turn the screen the client last had into the screen
     * as it is now, skipping whatever happened in between. So this is always complete, valid
     * terminal output, but only the visible screen survives: of a long listing the emulator
     * sees the lines that scrolled past between two updates only if the server happened to send
     * them, and its scrollback fills accordingly.
     *
     * Because every update is relative to the previous one, **nothing else may write to that
     * emulator** (no local echo, no banners), and its size has to be the server's; see [onResize].
     */
    public fun onOutput(data: ByteArray)

    /**
     * The server's screen now has this size. The call comes immediately before the [onOutput]
     * that clears the screen and repaints it at the new size.
     *
     * It normally follows a [MoshClient.resize], one round trip later. The dependable way to
     * keep the emulator in step with the server is to resize it *here* rather than when the
     * view changes: output that arrives in between was drawn for the old size, and an emulator
     * that re-wraps its contents on its own cannot be relied on to end up with the screen the
     * server's next update builds on. An owner that resizes its emulator early instead gets a
     * correct screen as soon as the repaint arrives, with two exceptions it has to accept: output
     * for the old size in the meantime, and no repaint at all if the size changed there and back
     * again before the server noticed.
     *
     * A size nobody asked for can show up briefly (one row off); that is the client forcing a
     * repaint to recover from a rare loss of synchronisation.
     */
    public fun onResize(columns: Int, rows: Int) {}

    /**
     * The server has not been heard from for [silentForMs] milliseconds; called about once a
     * second while that is more than [MoshClient.CONTACT_WARNING_MS], and once with 0 when
     * contact is back. Meant for a "last contact 12 s ago" banner. Silence is never an error to
     * Mosh: the session resumes whenever datagrams get through again, also from another network,
     * and it is for the user to give up on it.
     */
    public fun onContact(silentForMs: Long)

    /**
     * The session is over and the client's thread is about to end: the remote program exited or
     * [MoshClient.close] was called ([error] is null), or something went wrong that waiting will
     * not cure ([error] says what, in a sentence fit to show to the user). Called exactly once.
     */
    public fun onClosed(error: String?)
}

/**
 * A Mosh client: a terminal session with a remote `mosh-server` over UDP that survives lost
 * datagrams, long silences and changes of the local network address.
 *
 * Starting a session takes two steps. First something else (an SSH connection) runs
 * [MoshBootstrap.serverCommand] on the remote host and passes what it prints to
 * [MoshBootstrap.parseConnect], which yields the UDP port the new server listens on and the
 * session key. Then the SSH connection can go and a client created with that port and key takes
 * over. The key is all the authentication there is: every datagram is encrypted and
 * authenticated with it (AES-128 in OCB mode), and the server talks to whoever sent the newest
 * valid datagram, which is how roaming works.
 *
 * Feed [MoshListener.onOutput] to a terminal emulator and send that emulator's keystrokes to
 * [send]. All methods return immediately and may be called from any thread.
 *
 * ### Compared with the reference client
 * - **No local echo prediction.** Keystrokes show when the server's answer arrives.
 * - **One screen state.** The reference client keeps a terminal of its own for every state the
 *   server may still refer to. This one passes output on, so it can only use an update that
 *   builds on the screen it showed last; any other is skipped and the server is told again what
 *   is shown here. Nothing is ever applied to the wrong screen and nothing is lost for good, but
 *   it costs time where the round trip is noticeable. The server makes two updates per round
 *   trip, and the second is often made before it can know that the first has arrived, from a
 *   screen this client no longer has. Measured against mosh-server 1.4 with a 100 ms round
 *   trip: a key typed on its own is echoed after 110 ms, keys typed in quick succession after
 *   160 ms (median); steadily scrolling output arrives as about ten screens a second with an
 *   occasional pause of 300 ms, where the reference client would show twenty. After a lost
 *   datagram the wait is one retransmission timeout of the server, as it is for the reference
 *   client. With a round trip below 20 ms there is no difference.
 * - Pasted input is sent in instalments of at most 2 KiB, a few at a time, rather than at once.
 *
 * @param host the server's address, ideally the IP address the SSH connection was made to
 *   rather than a name that could resolve to another machine. Resolved on the client's thread.
 * @param port the UDP port from `MOSH CONNECT`
 * @param key the 22-character key from `MOSH CONNECT`
 * @param columns initial width of the terminal
 * @param rows initial height of the terminal
 * @param socketFactory makes the UDP sockets, one at a time; the place to bind to a particular
 *   network. A socket must not be connected. Called on the client's thread.
 * @throws IllegalArgumentException if [key] is not a Mosh key or a number is out of range
 */
public class MoshClient(
    private val host: String,
    private val port: Int,
    key: String,
    private val columns: Int,
    private val rows: Int,
    private val listener: MoshListener,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : AutoCloseable {

    private sealed class Event {
        class Input(val data: ByteArray) : Event()
        class Resize(val columns: Int, val rows: Int) : Event()
        object Rebind : Event()
        object Close : Event()
        class Datagram(val data: ByteArray) : Event()
        class SocketFailed(val socketId: Int) : Event()
    }

    private val key: ByteArray = PacketCodec.decodeKey(key)
    private val clockOrigin = System.nanoTime()
    private val events = LinkedBlockingQueue<Event>()
    private val lock = Any()
    private var thread: Thread? = null
    private var closed = false

    /** Set when the client's thread is done; later calls have nobody to talk to. */
    @Volatile
    private var ended = false

    // Everything below belongs to the client's thread.
    private lateinit var address: InetAddress
    private lateinit var transport: Transport
    private var socket: DatagramSocket? = null
    private var socketId = 0
    private var socketOpenedAt = 0L
    private var socketBroken = false
    private var startedAt = 0L
    private var silenceReported = false
    private var nextSilenceReport = 0L

    /** Test hook: the protocol engine, for its counters. Only meaningful to look at, not to call. */
    @Volatile
    internal var engine: Transport? = null
        private set

    init {
        require(port in 1..65535) { "Port out of range: $port" }
        require(columns > 0 && rows > 0) { "Terminal size must be positive" }
    }

    /**
     * Starts the session on a new thread and returns at once. Does nothing when called again or
     * after [close].
     */
    public fun start() {
        synchronized(lock) {
            if (thread != null || closed) return
            thread = Thread({ run() }, "mosh-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Sends user input: the bytes a terminal would send for the keys pressed. */
    public fun send(data: ByteArray) {
        if (data.isEmpty() || ended) return
        events.add(Event.Input(data.copyOf()))
    }

    /**
     * Tells the server the terminal has a new size. The emulator should follow when
     * [MoshListener.onResize] reports that the server's screen has it.
     */
    public fun resize(columns: Int, rows: Int) {
        require(columns > 0 && rows > 0) { "Terminal size must be positive" }
        if (ended) return
        events.add(Event.Resize(columns, rows))
    }

    /**
     * Continues the session from a new local socket. Call this when the device's network changes;
     * the server follows as soon as a datagram from the new address reaches it. The client also
     * does this by itself when sending fails and when nothing has come back for ten seconds.
     */
    public fun rebind() {
        if (ended) return
        events.add(Event.Rebind)
    }

    /**
     * Ends the session: tells the server to shut down, which ends the remote program too, and
     * stops. Returns immediately; [MoshListener.onClosed] follows when the server has confirmed,
     * or after a few seconds if it cannot be reached (it is then left to itself). Input not yet
     * sent is dropped. Before [start] this merely prevents the session from starting.
     */
    override fun close() {
        synchronized(lock) {
            closed = true
            if (thread == null) return
        }
        if (ended) return
        events.add(Event.Close)
    }

    // ---- The client's thread --------------------------------------------------------------

    private val sink = object : Transport.Sink {
        override fun sendDatagram(datagram: ByteArray) {
            val s = socket ?: return
            try {
                s.send(DatagramPacket(datagram, datagram.size, address, port))
            } catch (e: IOException) {
                val reason = e.message.orEmpty()
                if (datagram.size > FALLBACK_DATAGRAM && (reason.contains("EMSGSIZE") || reason.contains("too long", ignoreCase = true))) {
                    // The path cannot carry datagrams of the usual size. Use small ones from now on.
                    transport.maxDatagram = FALLBACK_DATAGRAM
                } else {
                    // Most likely the network this socket was made on is gone.
                    socketBroken = true
                }
            }
        }

        override fun output(data: ByteArray) = listener.onOutput(data)

        override fun resized(columns: Int, rows: Int) = listener.onResize(columns, rows)
    }

    private fun run() {
        var error: String? = null
        try {
            address = InetAddress.getByName(host)
            startedAt = clock()
            transport = Transport(key, columns, rows, datagramSizeFor(address), startedAt, sink)
            engine = transport
            openSocket(startedAt)

            while (!transport.finished) {
                var now = clock()
                var wake = transport.nextWake(now)
                wake = minOf(wake, nextSilenceCheck(now))
                if (socketBroken) wake = minOf(wake, socketOpenedAt + SOCKET_RETRY_MS)
                wake = minOf(wake, maxOf(socketOpenedAt, transport.lastRoundTrip) + PORT_HOP_INTERVAL_MS + 1)

                var event = if (wake > now) events.poll(wake - now, TimeUnit.MILLISECONDS) else events.poll()
                while (event != null) {
                    handle(event, clock())
                    event = events.poll()
                }

                now = clock()
                maintainSocket(now)
                transport.tick(now)
                reportSilence(now)
            }
            error = transport.failure
        } catch (e: UnknownHostException) {
            error = "Cannot find the address of $host."
        } catch (e: InterruptedException) {
            error = "The Mosh client was interrupted."
        } catch (e: RuntimeException) {
            error = "The Mosh client failed: $e"
        } finally {
            closeSocket()
            ended = true
            events.clear()
            listener.onClosed(error)
        }
    }

    private fun handle(event: Event, now: Long) {
        when (event) {
            is Event.Input -> transport.userInput(event.data)
            is Event.Resize -> transport.resize(event.columns, event.rows)
            is Event.Rebind -> openSocket(now)
            is Event.Close -> transport.close(now)
            is Event.Datagram -> transport.receive(event.data, event.data.size, now)
            is Event.SocketFailed -> if (event.socketId == socketId) socketBroken = true
        }
    }

    /** Replaces a socket that has stopped working, or that nothing has come back on for a while. */
    private fun maintainSocket(now: Long) {
        if (socketBroken) {
            if (now - socketOpenedAt >= SOCKET_RETRY_MS) openSocket(now)
            return
        }
        // A NAT on the way may have dropped the mapping for the old port, or the address may
        // have changed without anyone saying so. A new socket gets a new port and a new mapping.
        if (now - socketOpenedAt > PORT_HOP_INTERVAL_MS && now - transport.lastRoundTrip > PORT_HOP_INTERVAL_MS) {
            openSocket(now)
        }
    }

    private fun openSocket(now: Long) {
        closeSocket()
        socketOpenedAt = now
        try {
            val created = socketFactory()
            socket = created
            socketId++
            socketBroken = false
            Receiver(created, socketId).start()
            // Let the server know the new address without waiting for the next heartbeat.
            transport.sendSoon(now)
        } catch (e: IOException) {
            socketBroken = true
        } catch (e: RuntimeException) {
            socketBroken = true
        }
    }

    private fun closeSocket() {
        // Closing the socket also ends its receiver thread.
        socket?.close()
        socket = null
    }

    /** Blocks in `receive` on one socket and hands what arrives to the client's thread. */
    private inner class Receiver(private val source: DatagramSocket, private val id: Int) : Thread("mosh-receive-$id") {
        init {
            isDaemon = true
        }

        override fun run() {
            val buffer = ByteArray(MAX_UDP_PAYLOAD)
            val packet = DatagramPacket(buffer, buffer.size)
            while (true) {
                try {
                    packet.setData(buffer, 0, buffer.size)
                    source.receive(packet)
                    events.add(Event.Datagram(buffer.copyOfRange(packet.offset, packet.offset + packet.length)))
                } catch (e: IOException) {
                    if (!source.isClosed) events.add(Event.SocketFailed(id))
                    return
                }
            }
        }
    }

    private fun silentFor(now: Long): Long {
        val since = if (transport.lastHeard != Transport.NONE) transport.lastHeard else startedAt
        return now - since
    }

    private fun nextSilenceCheck(now: Long): Long {
        val silent = silentFor(now)
        return if (silent < CONTACT_WARNING_MS) now + (CONTACT_WARNING_MS - silent) else maxOf(now, nextSilenceReport)
    }

    private fun reportSilence(now: Long) {
        if (transport.finished) return
        val silent = silentFor(now)
        if (silent >= CONTACT_WARNING_MS) {
            if (now >= nextSilenceReport) {
                silenceReported = true
                nextSilenceReport = now + CONTACT_REPORT_INTERVAL_MS
                listener.onContact(silent)
            }
        } else if (silenceReported) {
            silenceReported = false
            nextSilenceReport = 0
            listener.onContact(0)
        }
    }

    /** Milliseconds since this client was created: monotonic, and never negative. */
    private fun clock(): Long = (System.nanoTime() - clockOrigin) / 1_000_000

    public companion object {
        /**
         * Silence from the server shorter than this is not reported through
         * [MoshListener.onContact]. An idle server sends something every three seconds.
         */
        public const val CONTACT_WARNING_MS: Long = 5_000

        private const val CONTACT_REPORT_INTERVAL_MS = 1_000L
        private const val SOCKET_RETRY_MS = 1_000L

        /** As in the reference client: try a new local port after this long without a round trip. */
        private const val PORT_HOP_INTERVAL_MS = 10_000L

        private const val MAX_UDP_PAYLOAD = 65_535

        /** Datagram size to fall back to when the usual one turns out not to fit the path. */
        private const val FALLBACK_DATAGRAM = 500

        /**
         * The largest UDP payload to send, as the reference implementation chooses it: a 1280
         * byte IP packet, which every IPv6 path and practically every IPv4 path carries whole,
         * less the IP and UDP headers (with room for two IPv6 extension headers).
         */
        private fun datagramSizeFor(address: InetAddress): Int =
            if (address is Inet6Address) 1280 - 40 - 16 - 8 else 1280 - 20 - 8
    }
}
