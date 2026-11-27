package io.github.evilgenius79.shh

import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.ForwardRule
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.Settings
import io.github.evilgenius79.shh.session.SessionEnvironment
import io.github.evilgenius79.shh.session.SessionPrompt
import io.github.evilgenius79.shh.session.SessionState
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.HostKeyDecision
import io.github.evilgenius79.shh.ssh.InMemoryHostKeyStore
import org.junit.After
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a session does when its connection breaks, against a real OpenSSH server reached through
 * a relay that the test can cut or shut, the way a network fails. Needs the server started by
 * `tools/test-sshd.sh start` (see `SHH_TEST_OPENSSH`); skipped otherwise.
 */
class ReconnectTest {
    private val spec: List<String>? = System.getenv("SHH_TEST_OPENSSH")?.takeIf { it.isNotBlank() }?.split('|')
    private val secrets = TestSecretBox()
    private val hostKeys = InMemoryHostKeyStore()
    private val sessions = ArrayList<TerminalSession>()
    private val relays = ArrayList<Relay>()

    /** Forwards TCP connections to the server until told to break them. */
    private class Relay(private val targetHost: String, private val targetPort: Int) {
        private var listener: ServerSocket? = null
        private val sockets = CopyOnWriteArrayList<Socket>()
        var port = 0
            private set

        /** How many connections were made through the relay so far. */
        val accepted = AtomicInteger()

        fun start(): Relay {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", port))
            port = server.localPort
            listener = server
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    val upstream = try {
                        Socket(targetHost, targetPort)
                    } catch (_: IOException) {
                        client.close()
                        continue
                    }
                    accepted.incrementAndGet()
                    sockets.add(client)
                    sockets.add(upstream)
                    pipe(client, upstream)
                    pipe(upstream, client)
                }
            }
            return this
        }

        private fun pipe(from: Socket, to: Socket) = thread(isDaemon = true) {
            try {
                from.getInputStream().copyTo(to.getOutputStream())
            } catch (_: IOException) {
            } finally {
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }

        /** Breaks every connection. New ones are still accepted: the network blinked. */
        fun cut() {
            for (socket in sockets) runCatching { socket.close() }
            sockets.clear()
        }

        /** Breaks every connection and refuses new ones until [start]: the server is away. */
        fun stop() {
            runCatching { listener?.close() }
            listener = null
            cut()
        }
    }

    @After
    fun tearDown() {
        for (session in sessions) session.close()
        for (relay in relays) relay.stop()
    }

    private fun relay(): Relay {
        assumeTrue("SHH_TEST_OPENSSH is not set; see tools/test-sshd.sh", spec != null)
        return Relay(spec!![0], spec[1].toInt()).start().also { relays.add(it) }
    }

    /** Logs in through [relay] with a stored password and waits until the shell is there. */
    private fun connect(
        relay: Relay,
        delays: List<Long>,
        settings: Settings = Settings(),
        forwards: List<ForwardRule> = emptyList(),
        startup: String = "",
    ): TerminalSession {
        val s = spec!!
        val host = Host(
            id = "live", name = "Test server", host = "127.0.0.1", port = relay.port, username = s[3],
            encryptedPassword = secrets.encryptText(s[4]), forwards = forwards, startupCommand = startup,
        )
        val data = AppData(hosts = listOf(host), settings = settings)
        val session = TerminalSession("s${sessions.size}", host, SessionEnvironment({ data }, secrets, hostKeys, reconnectDelaysMs = delays))
        sessions.add(session)
        session.connect()
        waitFor("the host key prompt") { session.prompt.value is SessionPrompt.HostKey }
        (session.prompt.value as SessionPrompt.HostKey).respond(HostKeyDecision.ACCEPT_AND_REMEMBER)
        waitFor("the first login") { session.isConnected }
        session.sendText("echo ready-\$((20+3))\n")
        waitFor("the shell") { session.history().contains("ready-23") }
        return session
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                "Timed out waiting for: $what\n" + sessions.joinToString("\n---\n") { "${it.state.value}\n${it.history().trimEnd()}" }
            }
            Thread.sleep(20)
        }
    }

    /** Everything the terminal has shown, scrolled away or not. */
    private fun TerminalSession.history(): String = synchronized(emulator) {
        emulator.getText(-emulator.scrollbackSize, 0, emulator.rows - 1, emulator.columns - 1)
    }

    private fun String.count(part: String): Int = windowed(part.length).count { it == part }

    @Test
    fun aConnectionThatDropsComesBackByItself() {
        val relay = relay()
        val forward = ForwardRule("f", ForwardKind.LOCAL, "127.0.0.1", 0, "127.0.0.1", spec!![1].toInt())
        val session = connect(relay, delays = listOf(100, 100, 100), forwards = listOf(forward), startup = "echo started-\$((6*7))")
        waitFor("the startup command") { session.history().contains("started-42") }
        assertEquals(1, session.forwards.value.size)

        relay.cut()
        waitFor("the connection to be back") { session.isConnected && session.history().contains("[Reconnected]") }
        assertTrue(session.history().contains("[Connection lost"), "the terminal says what happened")
        assertEquals(2, relay.accepted.get(), "one new connection was made")
        assertNull(session.prompt.value, "nothing is asked: the host key is remembered and the password stored")

        // It is a new shell that works, the startup command ran again and the forward is back.
        session.sendText("echo again-\$((5+5))\n")
        waitFor("the new shell") { session.history().contains("again-10") }
        waitFor("the startup command to run again") { session.history().count("started-42") >= 2 }
        assertEquals(1, session.forwards.value.size)
    }

    @Test
    fun leavingTheShellIsNotAConnectionToGetBack() {
        val relay = relay()
        val session = connect(relay, delays = listOf(50, 50, 50))
        session.sendText("exit\n")
        waitFor("the session to end") { session.state.value is SessionState.Disconnected }
        assertEquals("Session ended (exit status 0)", (session.state.value as SessionState.Disconnected).message)
        Thread.sleep(600)
        assertIs<SessionState.Disconnected>(session.state.value)
        assertEquals(1, relay.accepted.get(), "no attempt to connect again")
    }

    @Test
    fun itKeepsTryingWhileTheServerIsAwayAndReturnsAsSoonAsToldTheNetworkIsBack() {
        val relay = relay()
        // Three quick attempts, then a wait so long that only being told ends it.
        val session = connect(relay, delays = listOf(50, 50, 50, 600_000))
        relay.stop()
        waitFor("the fourth attempt to be waiting") { (session.state.value as? SessionState.Reconnecting)?.attempt == 4 }
        assertEquals(1, relay.accepted.get())

        relay.start()
        Thread.sleep(400)
        assertIs<SessionState.Reconnecting>(session.state.value, "still waiting: nobody said the network is back")
        session.retryNow()
        waitFor("the connection to be back") { session.isConnected }
        session.sendText("echo back-\$((4*4))\n")
        waitFor("the new shell") { session.history().contains("back-16") }
    }

    @Test
    fun itGivesUpWhenTheServerDoesNotComeBack() {
        val relay = relay()
        val session = connect(relay, delays = listOf(30, 30, 30))
        relay.stop()
        waitFor("it to give up") { session.state.value is SessionState.Disconnected }
        val end = session.state.value as SessionState.Disconnected
        assertTrue(end.message.startsWith("Connection lost"), end.message)
        assertTrue(end.message.contains("Could not reconnect"), end.message)
        assertTrue(session.history().contains("Could not reconnect"), "the terminal says so too")
    }

    @Test
    fun theUserCanStopIt() {
        val relay = relay()
        val session = connect(relay, delays = listOf(600_000))
        relay.cut()
        waitFor("the wait for the first attempt") { session.state.value is SessionState.Reconnecting }
        session.stopReconnecting()
        val end = assertIs<SessionState.Disconnected>(session.state.value)
        assertTrue(end.message.startsWith("Connection lost"), end.message)
        session.retryNow()
        Thread.sleep(400)
        assertIs<SessionState.Disconnected>(session.state.value)
        assertEquals(1, relay.accepted.get(), "nothing connects behind the user's back")

        // Reconnecting by hand still works afterwards.
        session.reconnect()
        waitFor("the manual reconnect") { session.isConnected }
    }

    @Test
    fun switchedOffInTheSettingsADroppedConnectionJustEnds() {
        val relay = relay()
        val session = connect(relay, delays = listOf(30, 30), settings = Settings(autoReconnect = false))
        relay.cut()
        waitFor("the session to end") { session.state.value is SessionState.Disconnected }
        assertTrue((session.state.value as SessionState.Disconnected).message.startsWith("Connection lost"))
        Thread.sleep(400)
        assertEquals(1, relay.accepted.get())
    }
}
