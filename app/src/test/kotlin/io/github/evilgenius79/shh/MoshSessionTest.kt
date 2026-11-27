package io.github.evilgenius79.shh

import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.session.SessionEnvironment
import io.github.evilgenius79.shh.session.SessionPrompt
import io.github.evilgenius79.shh.session.SessionState
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.HostKeyDecision
import io.github.evilgenius79.shh.ssh.InMemoryHostKeyStore
import org.junit.After
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sessions over Mosh, against the real thing: the test OpenSSH server to log in to (see
 * `tools/test-sshd.sh` and `SHH_TEST_OPENSSH`) and a `mosh-server` installed on this machine.
 * Skipped where either is missing.
 */
class MoshSessionTest {
    private val spec: List<String>? = System.getenv("SHH_TEST_OPENSSH")?.takeIf { it.isNotBlank() }?.split('|')
    private val secrets = TestSecretBox()
    private val hostKeys = InMemoryHostKeyStore()
    private val sessions = ArrayList<TerminalSession>()

    /** What sessions reported about shells that ended: the session, and whether the user left. */
    private val exits = CopyOnWriteArrayList<Pair<TerminalSession, Boolean>>()

    @After
    fun tearDown() {
        for (session in sessions) session.close()
    }

    private fun moshInstalled(): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "mosh-server").canExecute() }

    private fun host(startup: String = ""): Host {
        assumeTrue("SHH_TEST_OPENSSH is not set; see tools/test-sshd.sh", spec != null)
        assumeTrue("mosh-server is not installed", moshInstalled())
        val s = spec!!
        return Host(
            id = "mosh", name = "Mosh test", host = s[0], port = s[1].toInt(), username = s[3],
            encryptedPassword = secrets.encryptText(s[4]), startupCommand = startup, useMosh = true,
        )
    }

    private fun open(host: Host, environment: (AppData) -> SessionEnvironment = ::environment): TerminalSession {
        val data = AppData(hosts = listOf(host))
        return TerminalSession("m${sessions.size}", host, environment(data)).also {
            sessions.add(it)
            it.connect()
            waitFor("the host key prompt or the login") { it.prompt.value is SessionPrompt.HostKey || it.isConnected }
            (it.prompt.value as? SessionPrompt.HostKey)?.respond(HostKeyDecision.ACCEPT_AND_REMEMBER)
            waitFor("the session to connect", 40_000) { it.isConnected }
        }
    }

    private fun environment(data: AppData) = SessionEnvironment({ data }, secrets, hostKeys, onShellExited = { session, byUser -> exits.add(session to byUser) })

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                "Timed out waiting for: $what\n" + sessions.joinToString("\n---\n") { "${it.state.value}\n" + it.screen().trimEnd() }
            }
            Thread.sleep(25)
        }
    }

    private fun TerminalSession.screen(): String = synchronized(emulator) { emulator.screenText() }

    private fun TerminalSession.run(command: String, expected: String) {
        sendText("$command\n")
        waitFor("\"$expected\" after \"$command\"") { screen().lines().any { it.trim() == expected } }
    }

    /** The Mosh servers the test user has running. */
    private fun servers(): Int {
        val process = ProcessBuilder("pgrep", "-u", spec!![3], "-c", "mosh-server").redirectErrorStream(true).start()
        val count = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(10, TimeUnit.SECONDS)
        return count.toIntOrNull() ?: 0
    }

    @Test
    fun aSessionMovesFromSshToMoshAndWorksAsATerminalThere() {
        val before = host().let { servers() }
        val session = open(host(startup = "echo started-\$((20+3))"))
        assertTrue(session.isMosh.value, "the terminal runs over Mosh")
        waitFor("SSH to be let go") { session.connection == null }
        assertEquals(before + 1, servers(), "one Mosh server more than before")
        assertEquals(0L, session.silentForMs.value)

        // The command to run after login was typed into the shell, as with SSH.
        waitFor("the startup command") { session.screen().lines().any { it.trim() == "started-23" } }
        session.run("echo mosh-\$((6*7))", "mosh-42")
        session.run("echo \$TERM", "xterm-256color")
        // The shell there is no child of an SSH login any more.
        session.run("echo conn-\${SSH_TTY:-none}", "conn-none")

        // A new size goes to the server, and the emulator follows when the server has it.
        session.resize(100, 30, 10, 20)
        waitFor("the emulator to get the server's new size") { synchronized(session.emulator) { session.emulator.columns to session.emulator.rows } == 100 to 30 }
        session.run("stty size", "30 100")

        // "Reset terminal" must not wipe a screen that only the server can draw again: it has
        // the server draw it afresh, and what was there is there again afterwards.
        session.run("clear; echo keep-\$((30+3))", "keep-33")
        session.resetTerminal()
        Thread.sleep(1_500)
        waitFor("the screen to be drawn again") { session.screen().lines().any { it.trim() == "keep-33" } }
        assertEquals(100 to 30, synchronized(session.emulator) { session.emulator.columns to session.emulator.rows }, "and the size is what it was")
        session.run("stty size", "30 100")

        // The network changed: the session carries on from a new socket, with nothing to log in to.
        session.retryNow()
        session.run("echo after-\$((1+1))", "after-2")
        assertIs<SessionState.Connected>(session.state.value)
        assertNull(session.prompt.value)

        // Leaving the shell ends the session and the server, and counts as the user's doing.
        session.sendText("exit\n")
        waitFor("the session to end") { session.state.value is SessionState.Disconnected }
        assertEquals("Session ended", (session.state.value as SessionState.Disconnected).message)
        waitFor("the report that the shell ended") { exits.isNotEmpty() }
        assertEquals(listOf(session to true), exits.toList())
        assertTrue(!session.isMosh.value)
        waitFor("the Mosh server to go") { servers() == before }
    }

    @Test
    fun closingTheTabEndsTheMoshServer() {
        val before = host().let { servers() }
        val session = open(host())
        session.run("echo here-\$((3+4))", "here-7")
        assertEquals(before + 1, servers())
        session.close()
        waitFor("the Mosh server to go") { servers() == before }
        assertEquals(emptyList(), exits.toList(), "closing a tab is not the shell ending by itself")
    }

    @Test
    fun whereUdpDoesNotGetThroughTheSessionCarriesOnOverSsh() {
        // A firewall that lets SSH through and drops UDP, which is common.
        val blocked = { object : DatagramSocket() {
            override fun send(packet: DatagramPacket) = Unit
        } as DatagramSocket }
        val session = open(host()) { data ->
            SessionEnvironment({ data }, secrets, hostKeys, moshSockets = blocked, moshFirstContactMs = 1_500)
        }
        assertTrue(!session.isMosh.value)
        assertTrue(session.connection != null, "the SSH connection was kept")
        val screen = session.screen().replace("\n", "")
        assertTrue(Regex("\\[Mosh did not answer on UDP port \\d+ within 1 seconds\\. A firewall may be in the way\\. Carrying on over SSH]").containsMatchIn(screen), session.screen())
        session.run("echo ssh-\$((2+2))", "ssh-4")
        session.run("echo conn-\${SSH_TTY:+tty}", "conn-tty")
        // The server that was started waits a minute for a client before it goes. The other
        // tests count servers, so this one does not leave it to that.
        session.run("pkill -x mosh-server; echo tidy-\$((4+4))", "tidy-8")
        waitFor("the unused Mosh server to go") { servers() == 0 || !session.isConnected }
    }

    @Test
    fun whereTheHostHasNoMoshTheSessionCarriesOnOverSsh() {
        val session = open(host()) { data ->
            SessionEnvironment({ data }, secrets, hostKeys, moshServerCommand = "no-such-mosh-server new -s")
        }
        assertTrue(!session.isMosh.value)
        val screen = session.screen().replace("\n", "")
        assertTrue("[Mosh could not be started on this host (" in screen && "Is it installed there? Carrying on over SSH]" in screen, session.screen())
        assertTrue("no-such-mosh-server" in screen, "what the server's shell said about it is shown: ${session.screen()}")
        session.run("echo ssh-\$((5+5))", "ssh-10")
    }
}
