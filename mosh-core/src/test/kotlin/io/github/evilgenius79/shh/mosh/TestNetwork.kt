package io.github.evilgenius79.shh.mosh

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketException
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A bad network for tests: makes the sockets a [MoshClient] uses, and loses or delays the
 * datagrams that pass through them as the test says. Everything can be changed while a session
 * is running. The client is none the wiser; it gets these sockets through its ordinary socket
 * factory.
 */
internal class TestNetwork(seed: Long = 1) : AutoCloseable {
    private val random = Random(seed)
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "test-network").apply { isDaemon = true } }
    private val order = AtomicLong()
    private val allowance = AtomicInteger()

    /** Fraction of datagrams lost on the way to the server. */
    @Volatile
    var lossOut = 0.0

    /** Fraction of datagrams lost on the way from the server. */
    @Volatile
    var lossIn = 0.0

    /** Delay in each direction, in milliseconds. */
    @Volatile
    var delayMs = 0L

    /** Random extra delay per datagram, up to this many milliseconds; enough of it reorders. */
    @Volatile
    var jitterMs = 0L

    /** While set, nothing reaches the server except what [letThrough] allows. */
    @Volatile
    var blackoutOut = false

    /** Largest datagram the path carries; sending a larger one fails the way it does on Linux. */
    @Volatile
    var pathLimit = Int.MAX_VALUE

    val lostOut = AtomicInteger()
    val lostIn = AtomicInteger()

    /** Sizes of the datagrams that were sent (or lost on the way), in order. */
    val sentSizes = CopyOnWriteArrayList<Int>()

    /** Every socket the client has asked for, oldest first. */
    val sockets = CopyOnWriteArrayList<DatagramSocket>()

    /** What to give [MoshClient] as its socket factory. */
    val socketFactory: () -> DatagramSocket = { Socket().also { sockets.add(it) } }

    /** Lets the next [count] datagrams through a blackout. */
    fun letThrough(count: Int) = allowance.set(count)

    /**
     * The network the existing sockets were made on goes away: sending on them fails from now
     * on and whoever is receiving on them gets an error. Sockets made afterwards work.
     */
    fun breakExistingSockets() {
        for (socket in sockets) (socket as Socket).fail()
    }

    override fun close() {
        timer.shutdownNow()
    }

    private fun lost(fraction: Double): Boolean = fraction > 0 && random.nextDouble() < fraction

    private fun delay(): Long = delayMs + if (jitterMs > 0) random.nextInt(jitterMs.toInt() + 1) else 0

    private class Arrival(val data: ByteArray?, val from: SocketAddress?, val at: Long, val order: Long) : Delayed {
        override fun getDelay(unit: TimeUnit): Long = unit.convert(at - System.nanoTime(), TimeUnit.NANOSECONDS)

        override fun compareTo(other: Delayed): Int {
            other as Arrival
            return if (at != other.at) at.compareTo(other.at) else order.compareTo(other.order)
        }
    }

    private inner class Socket : DatagramSocket() {
        private val arrivals = DelayQueue<Arrival>()

        @Volatile
        private var broken = false

        fun fail() {
            broken = true
            // Wake up a receiver that is waiting.
            arrivals.add(Arrival(null, null, System.nanoTime(), order.incrementAndGet()))
        }

        init {
            // Reads the real socket as fast as datagrams arrive, so that each one can be held
            // back for its own delay, counted from its own arrival.
            Thread({
                val buffer = ByteArray(65_535)
                val packet = DatagramPacket(buffer, buffer.size)
                while (true) {
                    try {
                        packet.setData(buffer, 0, buffer.size)
                        super@Socket.receive(packet)
                    } catch (e: IOException) {
                        arrivals.add(Arrival(null, null, System.nanoTime(), order.incrementAndGet()))
                        return@Thread
                    }
                    if (lost(lossIn)) {
                        lostIn.incrementAndGet()
                        continue
                    }
                    val data = buffer.copyOfRange(packet.offset, packet.offset + packet.length)
                    arrivals.add(Arrival(data, packet.socketAddress, System.nanoTime() + delay() * 1_000_000, order.incrementAndGet()))
                }
            }, "test-network-receive").apply { isDaemon = true }.start()
        }

        override fun send(p: DatagramPacket) {
            if (broken) throw IOException("Network is unreachable")
            if (p.length > pathLimit) throw IOException("Message too long")
            sentSizes.add(p.length)
            if (blackoutOut && allowance.getAndUpdate { if (it > 0) it - 1 else 0 } <= 0 || lost(lossOut)) {
                lostOut.incrementAndGet()
                return
            }
            val wait = delay()
            if (wait == 0L) {
                super.send(p)
                return
            }
            val copy = DatagramPacket(p.data.copyOfRange(p.offset, p.offset + p.length), p.length, p.socketAddress)
            try {
                timer.schedule({
                    try {
                        super@Socket.send(copy)
                    } catch (e: IOException) {
                        // The socket was closed in the meantime; the datagram is lost, as it would be.
                    }
                }, wait, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // The test is over and the network gone.
            }
        }

        override fun receive(p: DatagramPacket) {
            val arrival = arrivals.take()
            val data = arrival.data
            if (data == null) {
                // Leave the marker for anyone else who might be waiting.
                arrivals.add(arrival)
                if (broken && !isClosed) throw IOException("Network is down")
                throw SocketException("Socket closed")
            }
            System.arraycopy(data, 0, p.data, p.offset, data.size)
            p.length = data.size
            p.socketAddress = arrival.from
        }
    }
}
