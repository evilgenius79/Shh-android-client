package io.github.evilgenius79.shh.mosh

import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The client against a real `mosh-server` on this machine, over UDP on the loopback interface.
 * Skipped where no `mosh-server` is installed.
 *
 * Mosh transports the state of the screen, not a stream of output, so the assertions are about
 * what the project's terminal emulator shows after being fed what the client delivers.
 */
class MoshServerTest {
    private val servers = ArrayList<TestServer>()
    private val clients = ArrayList<MoshClient>()
    private val files = ArrayList<File>()
    private val network = TestNetwork()

    @Before
    fun requireServer() {
        assumeTrue("mosh-server is not installed", TestServer.available)
    }

    @After
    fun cleanUp() {
        network.lossIn = 0.0
        network.lossOut = 0.0
        network.blackoutOut = false
        network.delayMs = 0
        network.jitterMs = 0
        clients.forEach { it.close() }
        // Whatever the clients did not get to shut down in an orderly way.
        servers.forEach { it.close() }
        files.forEach { it.delete() }
        network.close()
    }

    private fun server(vararg command: String = arrayOf("/bin/sh")): TestServer =
        TestServer.start(*command).also { servers.add(it) }

    /**
     * Starts a client and, like a user would, waits for the remote shell's prompt before typing.
     * (The server starts the shell when the client's first datagram arrives; input that gets
     * there before the shell is ready is echoed by the terminal ahead of the prompt.)
     */
    private fun connect(server: TestServer, screen: TestScreen, awaitPrompt: Boolean = true): MoshClient {
        val (columns, rows) = screen.size
        val client = MoshClient("127.0.0.1", server.port, server.key, columns, rows, screen, network.socketFactory)
        clients.add(client)
        client.start()
        if (awaitPrompt) screen.awaitScreen("the shell's prompt", 30_000) { it.trimEnd().endsWith("$") || it.trimEnd().endsWith("#") }
        return client
    }

    private fun MoshClient.type(text: String) = send(text.toByteArray())

    private fun temporaryFile(): File = File.createTempFile("mosh-test", ".txt").also { files.add(it) }

    /** Lines of letters and digits that do not compress well, so that they fill datagrams. */
    private fun randomLines(count: Int, width: Int, seed: Long): List<String> {
        val random = Random(seed)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return List(count) { String(CharArray(width) { alphabet[random.nextInt(alphabet.length)] }) }
    }

    /** A shell loop that prints `tick-1` to `tick-n`, a hundred a second, and then [last]. */
    private fun ticks(count: Int, last: String) =
        "i=0; while [ \$i -lt $count ]; do i=\$((i+1)); echo tick-\$i; sleep 0.01; done; echo $last-\$((i*2))\n"

    /** The screen after [ticks]: as many of the last ticks as fit above the final line and the prompt. */
    private fun screenAfterTicks(count: Int, last: String, rows: Int): String =
        ((count - rows + 3..count).map { "tick-$it" } + "$last-${count * 2}" + "$").joinToString("\n")

    private fun awaitTrue(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    // ---- A command and its output ---------------------------------------------------------

    @Test
    fun `a typed command runs and its output comes back`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)

        client.type("echo out-\$((6*7))\n")

        // The command line as typed contains "out-$((6*7))"; only its output contains "out-42".
        val text = screen.awaitScreen("the output of echo") { "out-42\n$" in it }
        // The first line is empty: the server starts the shell by sending it a newline.
        assertEquals("\n$ echo out-\$((6*7))\nout-42\n$", text.trimEnd())
    }

    @Test
    fun `text beyond ASCII and control characters pass through in both directions`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)

        // Typed as UTF-8, echoed by the remote terminal, printed by the remote shell.
        client.type("echo 'gr\u00fc\u00dfe \u4e2d\u6587 \u20ac' x-\$((2*4))\n")
        screen.awaitScreen("the non-ASCII output") { "gr\u00fc\u00dfe \u4e2d\u6587 \u20ac x-8\n$" in it }

        // Ctrl-C interrupts the remote program; without it this would take a hundred seconds.
        client.type("sleep 100; echo slept-\$((1+1))\n")
        screen.awaitScreen("the command line") { it.trimEnd().endsWith("echo slept-\$((1+1))") }
        client.send(byteArrayOf(3))
        client.type("echo interrupted-\$((3*3))\n")
        val text = screen.awaitScreen("the prompt after the interrupt") { "interrupted-9\n$" in it }
        assertTrue("slept-2" !in text, text)
    }

    @Test
    fun `lines that scroll off the screen a few at a time go to the emulator's scrollback`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)

        client.type(ticks(120, "last"))
        screen.awaitScreen("the end of the loop") { it == screenAfterTicks(120, "last", rows = 24) }

        // Mosh sends screens, not output: what scrolls past between two of them is never seen.
        // But when the screen moves up by a few lines the server says so by scrolling, and the
        // lines pushed out are kept, in order.
        val scrolledOff = screen.history().filter { it.startsWith("tick-") }.map { it.removePrefix("tick-").trim().toInt() }
        assertTrue(scrolledOff.size >= 5, "only $scrolledOff scrolled off")
        assertEquals(scrolledOff.sorted().distinct(), scrolledOff)
        assertTrue(scrolledOff.all { it <= 120 - 22 }, "$scrolledOff")
    }

    @Test
    fun `the command MoshBootstrap suggests starts a server that can be connected to`() {
        // Run it the way an SSH server runs an "exec" request: through a shell.
        val server = TestServer.startWith(listOf("/bin/sh", "-c", MoshBootstrap.serverCommand())).also { servers.add(it) }
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)

        client.type("echo bootstrap-\$((40+2))\n")
        screen.awaitScreen("the output of echo") { "bootstrap-42" in it }
        // "-c 256" is what gives the remote program a colour terminal.
        client.type("echo term-\$TERM-\$((1+1))\n")
        screen.awaitScreen("the terminal type") { "term-xterm-256color-2" in it }
    }

    @Test
    fun `a server started on a terminal is found and reached as well`() {
        assumeTrue("script(1) from util-linux is not installed", hasUtilLinuxScript())
        // The reference client asks for a terminal on the SSH channel. The server then prints
        // everything, errors included, to that terminal, with CR LF line ends and a blank line
        // before the line that matters.
        val commandLine = listOf("script", "-qfec", MoshBootstrap.serverCommand(), "/dev/null")
        val server = TestServer.startWith(commandLine).also { servers.add(it) }
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)

        client.type("echo terminal-\$((40+2))\n")
        screen.awaitScreen("the output of echo") { "terminal-42" in it }
    }

    private fun hasUtilLinuxScript(): Boolean = try {
        val process = ProcessBuilder("script", "--version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor() == 0 && "util-linux" in output
    } catch (e: java.io.IOException) {
        false
    }

    // ---- More than fits a datagram --------------------------------------------------------

    @Test
    fun `output of several kilobytes arrives complete and in order`() {
        val lines = randomLines(count = 60, width = 150, seed = 1)
        val file = temporaryFile().apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        val server = server()
        val screen = TestScreen(160, 40)
        val client = connect(server, screen)

        client.type("cat ${file.path}\n")

        // Forty rows: the last 39 lines of the file and the prompt after them.
        val expected = (lines.takeLast(39) + "$").joinToString("\n")
        screen.awaitScreen("the end of the file") { it == expected }
        // 39 lines of 150 random characters do not fit one datagram even compressed, so this
        // went through fragmentation and reassembly.
        assertTrue(client.engine!!.fragmentsOfSplitInstructions > 0, "nothing was fragmented")
    }

    @Test
    fun `a paste of many kilobytes arrives complete and in order`() {
        val lines = randomLines(count = 300, width = 100, seed = 2)
        val pasted = lines.joinToString("\n", postfix = "\n")
        val file = temporaryFile()
        val server = server()
        val screen = TestScreen(120, 30)
        val client = connect(server, screen)

        client.type("cat > ${file.path}\n")
        // Wait for cat to be reading: the shell is done echoing the command line.
        screen.awaitScreen("the command line") { it.trimEnd().endsWith(file.path) }
        client.type(pasted)
        client.send(byteArrayOf(4)) // Ctrl-D at the start of a line: end of file
        client.type("echo pasted-\$((40+2))\n")
        screen.awaitScreen("the paste to be through", 30_000) { "pasted-42" in it }

        assertEquals(pasted.length, file.readText().length)
        assertContentEquals(pasted.toByteArray(), file.readBytes())
        // It did not go out as one instruction.
        assertTrue(client.engine!!.instructionsSent >= pasted.length / Transport.MAX_INPUT_PER_INSTRUCTION)
    }

    // ---- Size -----------------------------------------------------------------------------

    @Test
    fun `the remote side has the size the client started with`() {
        val server = server()
        val screen = TestScreen(132, 43)
        val client = connect(server, screen)

        client.type("stty size; echo ok-\$((1+1))\n")
        screen.awaitScreen("the size as the remote terminal sees it") { "43 132\nok-2" in it }
        // The server starts out at 80 by 24 and says so when it changes.
        assertEquals(listOf(132 to 43), screen.resizes())
    }

    @Test
    fun `a resize reaches the remote side and is reported back`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)
        client.type("echo before-\$((1+2))\n")
        screen.awaitScreen("the first command") { "before-3" in it }
        assertEquals(emptyList(), screen.resizes())

        client.resize(100, 30)
        awaitTrue("the server's screen to have the new size") { screen.size == (100 to 30) }
        // The repaint that comes with the new size keeps what was on the screen.
        screen.awaitScreen("the repainted screen") { "before-3" in it }

        client.type("stty size; echo ok-\$((2+2))\n")
        screen.awaitScreen("the size as the remote terminal sees it") { "30 100\nok-4" in it }
        assertEquals(listOf(100 to 30), screen.resizes())
    }

    @Test
    fun `an emulator resized before the server knows ends up with the same screen`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)
        client.type("seq 1 30; echo before-\$((1+2))\n")
        screen.awaitScreen("the first command") { "before-3\n$" in it }

        // The owner's view shrinks and it resizes its emulator on the spot, which re-wraps and
        // scrolls on its own. The server's repaint for the new size puts that right.
        screen.resizeEagerly(60, 12)
        client.resize(60, 12)
        awaitTrue("the server's screen to have the new size") { screen.size == (60 to 12) }
        client.type("echo after-\$((2+3))\n")
        screen.awaitScreen("the second command") { "after-5\n$" in it }
        assertEquals(screen.text(), screen.eagerText())

        // And when it grows again.
        screen.resizeEagerly(100, 30)
        client.resize(100, 30)
        awaitTrue("the server's screen to have the new size") { screen.size == (100 to 30) }
        client.type("echo later-\$((3+4))\n")
        screen.awaitScreen("the third command") { "later-7\n$" in it }
        assertEquals(screen.text(), screen.eagerText())
    }

    // ---- Roaming --------------------------------------------------------------------------

    @Test
    fun `the session continues from a new local port`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)
        client.type("echo first-\$((10+1))\n")
        screen.awaitScreen("output before roaming") { "first-11" in it }
        val first = network.sockets.single()

        client.rebind()
        awaitTrue("a new socket") { network.sockets.size == 2 }
        val second = network.sockets[1]
        awaitTrue("the old socket to be closed") { first.isClosed }
        assertNotEquals(first.localPort, second.localPort)
        // Everything from here on can only arrive on the new socket: the server has noticed the
        // new address by itself.
        client.type("echo second-\$((20+2))\n")
        screen.awaitScreen("output after roaming") { "second-22" in it }

        // And again, this time while output is on its way.
        client.type("sleep 0.3; echo third-\$((30+3))\n")
        client.rebind()
        screen.awaitScreen("output across a change of address") { "third-33" in it }
        assertEquals(3, network.sockets.size)
    }

    @Test
    fun `the client replaces a socket that stops working`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)
        client.type("echo first-\$((10+1))\n")
        screen.awaitScreen("output before the failure") { "first-11" in it }

        // The network goes away under the socket, as when a phone leaves Wi-Fi: receiving
        // fails, and so does every attempt to send. Nobody calls rebind().
        network.breakExistingSockets()
        client.type("echo second-\$((20+2))\n")

        screen.awaitScreen("output after the failure") { "second-22" in it }
        assertTrue(network.sockets.size >= 2, "no new socket was made")
        assertTrue(network.sockets.first().isClosed)
    }

    @Test
    fun `datagrams shrink when the path turns out not to carry large ones`() {
        val lines = randomLines(count = 200, width = 100, seed = 5)
        val pasted = lines.joinToString("\n", postfix = "\n")
        val file = temporaryFile()
        val server = server()
        val screen = TestScreen(120, 30)
        val client = connect(server, screen)
        client.type("cat > ${file.path}\n")
        screen.awaitScreen("the command line") { it.trimEnd().endsWith(file.path) }

        // From now on sending more than 700 bytes at once fails with "message too long".
        network.pathLimit = 700
        val sentBefore = network.sentSizes.size
        client.type(pasted)
        client.send(byteArrayOf(4))
        client.type("echo pasted-\$((40+2))\n")
        screen.awaitScreen("the paste to be through", 30_000) { "pasted-42" in it }

        assertContentEquals(pasted.toByteArray(), file.readBytes())
        val sizes = network.sentSizes.drop(sentBefore)
        assertTrue(sizes.all { it <= 500 }, "sizes: ${sizes.distinct().sorted()}")
        // The instalments of the paste did not fit one such datagram each.
        assertTrue(sizes.count { it == 500 } > 20, "sizes: ${sizes.distinct().sorted()}")
    }

    // ---- A bad network --------------------------------------------------------------------

    @Test
    fun `the screen is right in the end with thirty percent of datagrams lost each way`() {
        val lines = randomLines(count = 60, width = 150, seed = 3)
        val file = temporaryFile().apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        val server = server()
        val screen = TestScreen(160, 40)
        network.lossOut = 0.3
        network.lossIn = 0.3
        val client = connect(server, screen)

        client.type("echo first-\$((1+1))\n")
        screen.awaitScreen("the first command", 30_000) { "first-2\n$" in it }

        // A big update, which needs several datagrams to arrive in one go.
        client.type("cat ${file.path}\n")
        screen.awaitScreen("the end of the file", 60_000) { it == (lines.takeLast(39) + "$").joinToString("\n") }

        // A stream of small updates that scroll.
        client.type(ticks(150, "last"))
        screen.awaitScreen("the end of the loop", 60_000) { it == screenAfterTicks(150, "last", rows = 40) }

        assertTrue(network.lostOut.get() >= 10 && network.lostIn.get() >= 10, "lost ${network.lostOut} out, ${network.lostIn} in")
    }

    @Test
    fun `the screen is right in the end on a slow and uneven path`() {
        val server = server()
        val screen = TestScreen(80, 24)
        // A round trip of 160 to 240 ms, reordering included, and a little loss.
        network.delayMs = 80
        network.jitterMs = 40
        network.lossOut = 0.05
        network.lossIn = 0.05
        val client = connect(server, screen)

        client.type("echo first-\$((1+1))\n")
        screen.awaitScreen("the first command", 30_000) { "first-2\n$" in it }
        client.type(ticks(200, "last"))
        screen.awaitScreen("the end of the loop", 60_000) { it == screenAfterTicks(200, "last", rows = 24) }

        client.resize(90, 20)
        client.type("stty size; echo ok-\$((2+2))\n")
        screen.awaitScreen("the size as the remote terminal sees it", 30_000) { "20 90\nok-4" in it }
        assertEquals(90 to 20, screen.size)
    }

    @Test
    fun `keys typed faster than acknowledgements travel are echoed without an update going to waste`() {
        val server = server()
        val screen = TestScreen(80, 24)
        // A round trip of 200 ms, and a key every 40 ms.
        network.delayMs = 100
        val client = connect(server, screen)
        val engine = client.engine!!
        Thread.sleep(500)
        val unusableBefore = engine.unusable
        val appliedBefore = engine.applied

        val typed = "echo_typed_much_faster_than_the_network_answers"
        for (c in typed) {
            client.type(c.toString())
            Thread.sleep(40)
        }
        screen.awaitScreen("the echo of everything typed") { it.trimEnd().endsWith("$ $typed") }

        // The server made each of its updates from the last screen it knew the client to have,
        // which the client had long left. They were usable all the same, because each began
        // with what the one before it had brought.
        assertTrue(engine.applied - appliedBefore >= 5, "only ${engine.applied - appliedBefore} updates")
        assertTrue(engine.unusable - unusableBefore <= 2, "${engine.unusable - unusableBefore} updates could not be used")
    }

    @Test
    fun `the client finds its way back when the server forgets the screen it holds`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)
        client.type(ticks(900, "finished"))
        screen.awaitScreen("the loop to be running") { "tick-30" in it }
        val engine = client.engine!!

        // Nothing gets out any more, while the screen keeps changing. The server, hearing no
        // acknowledgements, falls back to diffing from the last state it knows the client has,
        // and goes on to make more states than the 32 it remembers.
        network.blackoutOut = true
        val stateAtBlackout = engine.newestServerState
        awaitTrue("the server to run ahead") { engine.newestServerState >= stateAtBlackout + 45 }

        // One datagram gets through. Now the server knows the state held here and sends an
        // update that fits, which gives the client a state the server has only just sent ...
        val appliedBefore = engine.applied
        network.letThrough(1)
        awaitTrue("an update that fits") { engine.applied > appliedBefore }

        // ... and, still hearing nothing, forgets again before the acknowledgement arrives:
        // it keeps only the first and the last few of the many states it has sent.
        val stateThen = engine.newestServerState
        awaitTrue("the server to forget that state") { engine.newestServerState >= stateThen + 40 }
        assertEquals(0, engine.recoveries)

        // Contact is back, but the server ignores the acknowledgement of a state it has
        // forgotten, and will never again send an update that fits. Only a repaint helps.
        network.blackoutOut = false
        screen.awaitScreen("the end of the loop", 40_000) { it == screenAfterTicks(900, "finished", rows = 24) }
        assertTrue(engine.recoveries >= 1, "the client never had to recover")
        // The repaint was had by asking for another size and then for the real one again.
        assertEquals(80 to 24, screen.size)
        assertTrue(screen.resizes().size >= 2, "sizes reported: ${screen.resizes()}")
    }

    @Test
    fun `silence from the server is reported, and so is the end of it`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)
        client.type("echo first-\$((1+1))\n")
        screen.awaitScreen("the first command") { "first-2" in it }
        assertEquals(emptyList(), screen.contacts())

        network.lossIn = 1.0
        client.type("echo second-\$((2+2))\n")
        awaitTrue("a report of silence", 10_000) { screen.contacts().size >= 2 }
        val reports = screen.contacts()
        assertTrue(reports[0] >= MoshClient.CONTACT_WARNING_MS, "first report: $reports")
        assertTrue(reports[1] > reports[0], "reports: $reports")

        network.lossIn = 0.0
        awaitTrue("contact to be back") { screen.contacts().last() == 0L }
        // What was typed in the meantime was not lost.
        screen.awaitScreen("the second command") { "second-4" in it }
        assertTrue(server.isRunning())
    }

    // ---- Ending ---------------------------------------------------------------------------

    @Test
    fun `closing the client ends the server`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)
        client.type("echo here-\$((3+4))\n")
        screen.awaitScreen("the session to work") { "here-7" in it }
        assertTrue(server.isRunning())

        client.close()

        assertNull(screen.awaitClosed())
        assertTrue(server.awaitExit(5_000), "mosh-server is still running")
        // Closing again, or using the client afterwards, does no harm.
        client.close()
        client.type("ignored")
        client.resize(10, 10)
        client.rebind()
    }

    @Test
    fun `a server whose client is gone for good ends itself when it was told how long to wait`() {
        // What becomes of a session when the client's process is killed: nothing tells the server.
        val limited = TestServer.startWith(listOf("/bin/sh", "-c", MoshBootstrap.serverCommand("C.UTF-8", abandonAfterSeconds = 4))).also { servers.add(it) }
        val unlimited = TestServer.startWith(listOf("/bin/sh", "-c", MoshBootstrap.serverCommand("C.UTF-8"))).also { servers.add(it) }
        for (server in listOf(limited, unlimited)) {
            val screen = TestScreen(80, 24)
            val client = connect(server, screen)
            client.type("echo alive-\$((5+5))\n")
            screen.awaitScreen("the session to work") { "alive-10" in it }
        }
        // From now on nothing the clients send arrives, their goodbye included.
        network.blackoutOut = true

        assertTrue(limited.awaitExit(20_000), "the server that was given four seconds is still waiting")
        assertTrue(unlimited.isRunning(), "a server without a limit waits for its client, as Mosh means it to")
    }

    @Test
    fun `leaving the remote shell closes the session`() {
        val server = server()
        val screen = TestScreen(80, 24)
        val client = connect(server, screen)
        client.type("echo here-\$((3+4))\n")
        screen.awaitScreen("the session to work") { "here-7" in it }

        client.type("exit\n")

        assertNull(screen.awaitClosed())
        assertTrue(server.awaitExit(5_000), "mosh-server is still running")
    }

    @Test
    fun `a remote program that exits at once still gets its output shown`() {
        val server = server("/bin/sh", "-c", "echo result-\$((3*4))")
        val screen = TestScreen(80, 24)
        connect(server, screen, awaitPrompt = false)

        assertNull(screen.awaitClosed())
        assertEquals("result-12", screen.text().trim())
        assertTrue(server.awaitExit(5_000), "mosh-server is still running")
    }

    @Test
    fun `closing a client that never reached its server reports the end all the same`() {
        val server = server()
        val screen = TestScreen(80, 24)
        // The right port and a wrong key: the server ignores every datagram.
        val client = MoshClient("127.0.0.1", server.port, "AAECAwQFBgcICQoLDA0ODw", 80, 24, screen)
        clients.add(client)
        client.start()
        Thread.sleep(300)
        assertEquals("", screen.text().trimEnd())

        client.close()
        assertNull(screen.awaitClosed(10_000))
        assertTrue(server.isRunning())
    }
}
