package io.github.evilgenius79.shh.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.DisconnectListener
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Output of a command run with [SshConnection.execute]. */
class CommandResult(val exitStatus: Int?, val stdout: String, val stderr: String) {
    val isSuccess: Boolean get() = exitStatus == 0
}

/** An authenticated SSH connection. Thread safe. */
class SshConnection internal constructor(
    /** The chain of clients: jump hosts first, the actual target last. */
    private val clients: List<SSHClient>,
    val target: SshTarget,
    /** Text the server sent before login, if any. */
    val banner: String?,
) : Closeable {
    private val client: SSHClient get() = clients.last()
    private val closed = AtomicBoolean(false)
    private val disconnectNotified = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()
    private val forwards = CopyOnWriteArrayList<PortForward>()
    private val workers: ExecutorService = Executors.newCachedThreadPool(DaemonThreads("shh-tunnel"))

    init {
        client.transport.disconnectListener = DisconnectListener { _, message -> notifyDisconnected(message) }
    }

    // The disconnect flag is consulted as well because sshj invokes its disconnect listener
    // slightly before it marks the transport as stopped.
    val isConnected: Boolean get() = !closed.get() && !disconnectNotified.get() && client.isConnected

    /** For tests: runs right before [execute] closes the command's standard input. */
    @Volatile
    internal var beforeClosingStdin: (() -> Unit)? = null

    /** Software version the server announced, for example `OpenSSH_9.6p1 Ubuntu-3ubuntu13`. */
    val serverVersion: String get() = client.transport.serverVersion.orEmpty()

    /** Port forwards that are currently active on this connection. */
    val activeForwards: List<PortForward> get() = forwards.filter { it.isOpen }

    /**
     * Registers a callback for the moment the connection ends, whether through [close], the
     * server or a network failure. The argument is the reason if one is known. If the connection
     * is already gone the callback runs immediately.
     */
    fun onDisconnect(listener: (reason: String?) -> Unit) {
        listeners.add(listener)
        if (disconnectNotified.get()) listener(null)
    }

    private fun notifyDisconnected(reason: String?) {
        if (disconnectNotified.compareAndSet(false, true)) {
            for (listener in listeners) runCatching { listener(reason) }
        }
    }

    /**
     * Starts an interactive login shell on a pseudo terminal.
     *
     * @param term value for the remote `TERM` variable.
     * @param environment extra variables; servers only accept the ones their `AcceptEnv` allows
     * (typically `LANG` and `LC_*`), others are silently ignored.
     */
    @Throws(IOException::class)
    fun openShell(
        term: String = "xterm-256color",
        columns: Int,
        rows: Int,
        widthPx: Int = 0,
        heightPx: Int = 0,
        environment: Map<String, String> = emptyMap(),
    ): SshShell {
        val channel = ShellChannel(client.connection, client.remoteCharset)
        channel.open()
        try {
            for ((name, value) in environment) {
                try {
                    channel.setEnvVar(name, value)
                } catch (_: IOException) {
                    // Refused by the server's AcceptEnv; not worth failing the session over.
                }
            }
            channel.allocateTerminal(term, columns, rows, widthPx, heightPx)
            channel.startShell()
        } catch (e: IOException) {
            runCatching { channel.close() }
            throw e
        }
        return SshShell(channel)
    }

    /** Runs [command] without a terminal and collects its output. */
    @Throws(IOException::class)
    fun execute(command: String, timeoutSeconds: Long = 30, stdin: ByteArray? = null): CommandResult {
        client.startSession().use { session ->
            val running = session.exec(command)
            val err = ByteArrayOutputStream()
            val errReader = workers.submit { running.errorStream.copyTo(err) }
            beforeClosingStdin?.invoke()
            try {
                if (stdin != null) running.outputStream.write(stdin)
                running.outputStream.flush()
                running.outputStream.close()
            } catch (_: IOException) {
                // The command already finished and closed the channel, so nobody is left to read
                // its input. Its output and exit status are still collected below.
            }
            val out = ByteArrayOutputStream()
            running.inputStream.copyTo(out)
            running.join(timeoutSeconds, TimeUnit.SECONDS)
            runCatching { errReader.get(5, TimeUnit.SECONDS) }
            val charset: Charset = Charsets.UTF_8
            return CommandResult(running.exitStatus, String(out.toByteArray(), charset), String(err.toByteArray(), charset))
        }
    }

    /** Opens an SFTP session for browsing and transferring files. */
    @Throws(IOException::class)
    fun openSftp(): SftpSession = SftpSession(client.newSFTPClient())

    /**
     * Listens on [bindAddress]:[bindPort] on this device and forwards every connection to
     * [remoteHost]:[remotePort] as seen from the server (`ssh -L`). Pass port 0 to let the system
     * pick a free one; the result reports the port actually used.
     */
    @Throws(IOException::class)
    fun forwardLocal(bindAddress: String, bindPort: Int, remoteHost: String, remotePort: Int): PortForward {
        val server = listen(bindAddress, bindPort)
        val tunnel = TunnelListener(server, workers) { socket ->
            val channel = client.newDirectConnection(remoteHost, remotePort)
            Tunnels.bridge(socket, socket.getInputStream(), channel, workers)
        }
        tunnel.start()
        return register(PortForward(PortForward.Kind.LOCAL, bindAddress, server.localPort, "$remoteHost:$remotePort") { tunnel.close() })
    }

    /**
     * Runs a SOCKS proxy on [bindAddress]:[bindPort] on this device whose connections leave from
     * the server (`ssh -D`). Supports SOCKS5 and SOCKS4/4a without authentication.
     */
    @Throws(IOException::class)
    fun forwardDynamic(bindAddress: String, bindPort: Int): PortForward {
        val server = listen(bindAddress, bindPort)
        val tunnel = TunnelListener(server, workers) { socket ->
            SocksHandshake.serve(socket, workers) { host, port -> client.newDirectConnection(host, port) }
        }
        tunnel.start()
        return register(PortForward(PortForward.Kind.DYNAMIC, bindAddress, server.localPort, "SOCKS") { tunnel.close() })
    }

    /**
     * Asks the server to listen on [remoteBindAddress]:[remotePort] and forwards every connection
     * it gets to [localHost]:[localPort] as seen from this device (`ssh -R`). With port 0 the
     * server picks a port, which the result reports.
     */
    @Throws(IOException::class)
    fun forwardRemote(remoteBindAddress: String, remotePort: Int, localHost: String, localPort: Int): PortForward {
        val forwarder = client.remotePortForwarder
        val listener = SocketForwardingConnectListener(InetSocketAddress(localHost, localPort))
        val bound = forwarder.bind(RemotePortForwarder.Forward(remoteBindAddress, remotePort), listener)
        return register(PortForward(PortForward.Kind.REMOTE, remoteBindAddress, bound.port, "$localHost:$localPort") {
            runCatching { forwarder.cancel(bound) }
        })
    }

    private fun listen(bindAddress: String, bindPort: Int): ServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(InetAddress.getByName(bindAddress), bindPort))
        } catch (e: IOException) {
            runCatching { server.close() }
            throw e
        }
        return server
    }

    private fun register(forward: PortForward): PortForward {
        forwards.add(forward)
        return forward
    }

    /** Closes the connection together with its shells, file sessions and port forwards. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (forward in forwards) runCatching { forward.close() }
        for (c in clients.asReversed()) runCatching { c.disconnect() }
        workers.shutdownNow()
        notifyDisconnected(null)
    }
}

/** An interactive shell running on the server. */
class SshShell internal constructor(private val channel: ShellChannel) : Closeable {
    /**
     * Everything the remote side prints. Reading returns -1 once the shell has ended and throws
     * an [IOException] when the connection is lost; a reader should treat both as the end.
     */
    val output: InputStream get() = channel.inputStream

    private val remoteInput: OutputStream get() = channel.outputStream

    val isOpen: Boolean get() = channel.isOpen

    /** Exit status of the shell once it has ended, if the server reported one. */
    val exitStatus: Int? get() = channel.exitStatus

    /**
     * Waits for the shell to finish and returns its exit status, or null when it did not finish
     * in time or the server reported no status. Call this after [output] reached its end: the
     * status travels in a separate message that may arrive just after the last output.
     */
    fun awaitExit(timeoutMs: Long): Int? {
        try {
            channel.join(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: IOException) {
            // Timed out or the connection went away; report whatever is known.
        }
        return channel.exitStatus
    }

    /** Sends keystrokes or pasted text to the shell. */
    @Throws(IOException::class)
    fun write(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
        val out = remoteInput
        synchronized(out) {
            out.write(data, offset, count)
            out.flush()
        }
    }

    /** Tells the remote side that the terminal has a new size. */
    @Throws(IOException::class)
    fun resize(columns: Int, rows: Int, widthPx: Int = 0, heightPx: Int = 0) {
        channel.changeWindowDimensions(columns, rows, widthPx, heightPx)
    }

    override fun close() {
        runCatching { channel.close() }
    }
}

/**
 * A session channel that can request a terminal with the IUTF8 mode set, which sshj's own
 * `allocatePTY` cannot express. Without it the remote line editor erases only one byte of a
 * multi-byte character on backspace.
 */
internal class ShellChannel(private val connection: Connection, charset: Charset) : SessionChannel(connection, charset) {
    fun allocateTerminal(term: String, columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        val modes = Buffer.PlainBuffer()
            .putByte(TTY_OP_IUTF8)
            .putUInt32(1)
            .putByte(TTY_OP_END)
            .compactData
        sendChannelRequest(
            "pty-req",
            true,
            Buffer.PlainBuffer()
                .putString(term)
                .putUInt32(columns.toLong())
                .putUInt32(rows.toLong())
                .putUInt32(widthPx.toLong())
                .putUInt32(heightPx.toLong())
                .putBytes(modes),
        ).await(connection.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }

    private companion object {
        /** RFC 8160. */
        const val TTY_OP_IUTF8: Byte = 42
        const val TTY_OP_END: Byte = 0
    }
}

/** A port forward that is currently set up. Closing it stops the forwarding. */
class PortForward internal constructor(
    val kind: Kind,
    /** Address the listening side is bound to. */
    val bindAddress: String,
    /** Port the listening side is bound to (resolved when 0 was requested). */
    val boundPort: Int,
    /** Where connections end up: `host:port`, or `SOCKS` for a dynamic forward. */
    val destination: String,
    private val onClose: () -> Unit,
) : Closeable {
    enum class Kind { LOCAL, REMOTE, DYNAMIC }

    private val open = AtomicBoolean(true)

    val isOpen: Boolean get() = open.get()

    override fun close() {
        if (open.compareAndSet(true, false)) onClose()
    }

    override fun toString(): String = "$kind $bindAddress:$boundPort -> $destination"
}

internal class DaemonThreads(private val prefix: String) : java.util.concurrent.ThreadFactory {
    private val counter = AtomicInteger()

    override fun newThread(task: Runnable): Thread = Thread(task, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
}
