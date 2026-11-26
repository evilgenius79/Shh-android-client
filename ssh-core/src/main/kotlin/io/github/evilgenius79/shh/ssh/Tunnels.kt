package io.github.evilgenius79.shh.ssh

import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.OpenFailException
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/** Accepts connections on a local socket and hands each one to [handler] on a worker thread. */
internal class TunnelListener(
    private val server: ServerSocket,
    private val workers: ExecutorService,
    private val handler: (Socket) -> Unit,
) : Closeable {
    fun start() {
        val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    workers.execute {
                        try {
                            handler(socket)
                        } catch (_: Exception) {
                            // One failed connection (target refused, peer vanished, ...) must
                            // never take the whole forward down.
                        } finally {
                            Tunnels.closeQuietly(socket)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    Tunnels.closeQuietly(socket)
                    break
                }
            }
        }, "shh-accept-${server.localPort}")
        acceptor.isDaemon = true
        acceptor.start()
    }

    override fun close() {
        Tunnels.closeQuietly(server)
    }
}

internal object Tunnels {
    private const val BUFFER_SIZE = 32 * 1024

    /**
     * Copies data both ways between a local socket and an SSH channel until both directions are
     * finished, then closes both. [socketInput] is passed separately because a SOCKS handshake
     * may already have buffered bytes that belong to the payload.
     */
    fun bridge(socket: Socket, socketInput: InputStream, channel: Channel, workers: ExecutorService) {
        try {
            socket.soTimeout = 0
            val upstream = workers.submit {
                try {
                    copy(socketInput, channel.outputStream)
                    // Closing the channel's output sends EOF, the equivalent of a half close.
                    channel.outputStream.close()
                } catch (_: IOException) {
                    closeQuietly(channel)
                    closeQuietly(socket)
                }
            }
            try {
                copy(channel.inputStream, socket.getOutputStream())
                runCatching { socket.shutdownOutput() }
            } catch (_: IOException) {
                closeQuietly(channel)
                closeQuietly(socket)
            }
            runCatching { upstream.get() }
        } finally {
            closeQuietly(channel)
            closeQuietly(socket)
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = from.read(buffer)
            if (n < 0) break
            to.write(buffer, 0, n)
            to.flush()
        }
    }

    fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }
}

/** The server side of SOCKS4, SOCKS4a and SOCKS5 (CONNECT only, no authentication). */
internal object SocksHandshake {
    private const val HANDSHAKE_TIMEOUT_MS = 30_000

    fun serve(socket: Socket, workers: ExecutorService, connect: (host: String, port: Int) -> Channel) {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val buffered = BufferedInputStream(socket.getInputStream())
        val input = DataInputStream(buffered)
        val output = socket.getOutputStream()
        val channel = when (input.read()) {
            5 -> socks5(input, output, connect)
            4 -> socks4(input, output, connect)
            else -> null
        } ?: return
        Tunnels.bridge(socket, buffered, channel, workers)
    }

    private fun socks5(input: DataInputStream, output: OutputStream, connect: (String, Int) -> Channel): Channel? {
        val methodCount = input.readUnsignedByte()
        val methods = ByteArray(methodCount)
        input.readFully(methods)
        if (!methods.contains(0)) {
            output.write(byteArrayOf(5, 0xFF.toByte()))
            output.flush()
            return null
        }
        output.write(byteArrayOf(5, 0))
        output.flush()

        if (input.readUnsignedByte() != 5) return null
        val command = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
            3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
            else -> {
                reply5(output, 8) // address type not supported
                return null
            }
        }
        val port = input.readUnsignedShort()
        if (command != 1) {
            reply5(output, 7) // command not supported
            return null
        }
        val channel = try {
            connect(host, port)
        } catch (e: OpenFailException) {
            reply5(output, if (e.reason == OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED) 2 else 5)
            return null
        } catch (_: IOException) {
            reply5(output, 1)
            return null
        }
        reply5(output, 0)
        return channel
    }

    private fun reply5(output: OutputStream, status: Int) {
        // VER, REP, RSV, ATYP = IPv4, BND.ADDR = 0.0.0.0, BND.PORT = 0
        output.write(byteArrayOf(5, status.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun socks4(input: DataInputStream, output: OutputStream, connect: (String, Int) -> Channel): Channel? {
        val command = input.readUnsignedByte()
        val port = input.readUnsignedShort()
        val address = ByteArray(4).also { input.readFully(it) }
        readNullTerminated(input) // user id, unused
        // SOCKS4a: an address of the form 0.0.0.x means a host name follows.
        val host = if (address[0].toInt() == 0 && address[1].toInt() == 0 && address[2].toInt() == 0 && address[3].toInt() != 0) {
            readNullTerminated(input)
        } else {
            InetAddress.getByAddress(address).hostAddress
        }
        if (command != 1) {
            reply4(output, false)
            return null
        }
        val channel = try {
            connect(host, port)
        } catch (_: IOException) {
            reply4(output, false)
            return null
        }
        reply4(output, true)
        return channel
    }

    private fun reply4(output: OutputStream, granted: Boolean) {
        output.write(byteArrayOf(0, if (granted) 0x5A else 0x5B, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun readNullTerminated(input: DataInputStream): String {
        val out = StringBuilder()
        while (true) {
            val b = input.readUnsignedByte()
            if (b == 0) break
            if (out.length > 255) throw IOException("SOCKS field too long")
            out.append(b.toChar())
        }
        return out.toString()
    }
}
