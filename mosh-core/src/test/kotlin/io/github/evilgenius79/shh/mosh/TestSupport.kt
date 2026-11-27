package io.github.evilgenius79.shh.mosh

import io.github.evilgenius79.shh.terminal.TerminalEmulator
import io.github.evilgenius79.shh.terminal.TerminalHost
import java.io.File
import java.util.concurrent.TimeUnit

/** A real `mosh-server` on this machine, started the way a client's bootstrap would start one. */
internal class TestServer private constructor(val port: Int, val key: String, val pid: Long) : AutoCloseable {

    /** Whether the detached server process still exists. */
    fun isRunning(): Boolean = isRunning(pid)

    /** Waits for the server process to go away by itself. */
    fun awaitExit(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (isRunning()) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(20)
        }
        return true
    }

    /** Stops the server if a test left it running. */
    override fun close() {
        if (!isRunning()) return
        ProcessBuilder("kill", "-KILL", pid.toString()).redirectErrorStream(true).start().waitFor()
        awaitExit(2_000)
    }

    companion object {
        /** Whether there is a `mosh-server` to test against; tests are skipped without one. */
        val available: Boolean by lazy {
            System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "mosh-server").canExecute() }
        }

        /**
         * Starts a server that runs [command] (a POSIX shell by default) and returns once it has
         * printed where it listens. The server detaches itself; the process started here exits.
         */
        fun start(vararg command: String = arrayOf("/bin/sh")): TestServer =
            startWith(listOf("mosh-server", "new", "-i", "127.0.0.1", "-l", "LANG=C.UTF-8", "--") + command)

        /** Starts a server with a command line of the caller's choosing. */
        fun startWith(commandLine: List<String>): TestServer {
            val builder = ProcessBuilder(commandLine)
            builder.environment().apply {
                // A predictable shell: no startup files, a prompt without the working directory.
                remove("ENV")
                put("SHELL", "/bin/sh")
                put("PS1", "$ ")
                put("HOME", System.getProperty("java.io.tmpdir"))
                // Without this the server listens on all addresses; with a stale value from the
                // session the tests run in, it might pick one the tests do not talk to.
                remove("SSH_CONNECTION")
            }
            val process = builder.start()
            process.outputStream.close()
            // The launcher forks the real server and exits, which closes both pipes.
            val errors = StringBuilder()
            val errorReader = Thread { errors.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
            val output = process.inputStream.bufferedReader().readText()
            errorReader.join(10_000)
            check(process.waitFor(10, TimeUnit.SECONDS)) { "mosh-server did not detach" }

            val connect = MoshBootstrap.parseConnect(output)
                ?: error("mosh-server did not start.\nstdout: $output\nstderr: $errors")
            // On a terminal both streams end up in one.
            val pid = Regex("\\[mosh-server detached, pid = ([0-9]+)]").find("$errors$output")?.groupValues?.get(1)?.toLong()
                ?: error("mosh-server did not say which process it became.\nstdout: $output\nstderr: $errors")
            return TestServer(connect.first, connect.second, pid)
        }

        fun isRunning(pid: Long): Boolean {
            val stat = File("/proc/$pid/stat")
            if (File("/proc/self/stat").exists()) {
                // Linux. A process that has exited but was not collected by its parent yet
                // (state Z) counts as gone; under some container inits nobody collects it.
                val text = try {
                    stat.readText()
                } catch (e: java.io.IOException) {
                    return false
                }
                val state = text.substringAfterLast(')').trim().firstOrNull()
                return state != null && state != 'Z' && state != 'X'
            }
            val ps = ProcessBuilder("ps", "-o", "stat=", "-p", pid.toString()).redirectErrorStream(true).start()
            val state = ps.inputStream.bufferedReader().readText().trim()
            ps.waitFor()
            return state.isNotEmpty() && !state.startsWith("Z")
        }
    }
}

/**
 * The owner of a [MoshClient] in tests: feeds the client's output into the project's terminal
 * emulator, so that tests can assert on what a user would see, and records the other callbacks.
 */
internal class TestScreen(columns: Int, rows: Int) : MoshListener, TerminalHost {
    private val lock = Object()
    private val emulator = TerminalEmulator(columns, rows, this, 2000)

    /**
     * A second emulator fed the same output, but resized when the test says so rather than when
     * the server does: what an owner sees that resizes its terminal as soon as its view changes.
     */
    private val eager = TerminalEmulator(columns, rows, this, 2000)
    private var closed = false
    private var closeError: String? = null
    private val contactReports = ArrayList<Long>()
    private val resizes = ArrayList<Pair<Int, Int>>()

    override fun onOutput(data: ByteArray) {
        synchronized(lock) {
            emulator.append(data)
            eager.append(data)
            lock.notifyAll()
        }
    }

    override fun onResize(columns: Int, rows: Int) {
        synchronized(lock) {
            resizes.add(columns to rows)
            emulator.resize(columns, rows)
            lock.notifyAll()
        }
    }

    override fun onContact(silentForMs: Long) {
        synchronized(lock) {
            contactReports.add(silentForMs)
            lock.notifyAll()
        }
    }

    override fun onClosed(error: String?) {
        synchronized(lock) {
            check(!closed) { "onClosed was called twice" }
            closed = true
            closeError = error
            lock.notifyAll()
        }
    }

    /** The server never asks the client's terminal anything, so the emulator has nothing to say. */
    override fun write(data: ByteArray) {
        throw AssertionError("The emulator wanted to answer: ${String(data, Charsets.ISO_8859_1)}")
    }

    /** The visible screen, one line per row. */
    fun text(): String = synchronized(lock) { emulator.screenText() }

    /** Resizes the second emulator, the one that does not wait for the server. */
    fun resizeEagerly(columns: Int, rows: Int) = synchronized(lock) { eager.resize(columns, rows) }

    /** The screen of the second emulator. */
    fun eagerText(): String = synchronized(lock) { eager.screenText() }

    /** Lines that have scrolled off the top, oldest first. */
    fun history(): List<String> = synchronized(lock) {
        (-emulator.scrollbackSize until 0).map { r -> emulator.getText(r, 0, r, emulator.columns - 1) }
    }

    val size: Pair<Int, Int> get() = synchronized(lock) { emulator.columns to emulator.rows }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    fun contacts(): List<Long> = synchronized(lock) { ArrayList(contactReports) }

    fun resizes(): List<Pair<Int, Int>> = synchronized(lock) { ArrayList(resizes) }

    /** Waits until the screen satisfies [condition]; fails with the screen as it is if it never does. */
    fun awaitScreen(what: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, condition: (String) -> Boolean): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (true) {
                val text = emulator.screenText()
                if (condition(text)) return text
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) {
                    throw AssertionError(
                        "Timed out waiting for $what (closed=$closed, error=$closeError). Screen:\n" +
                            text.lines().joinToString("\n") { "|$it" },
                    )
                }
                lock.wait(left)
            }
        }
    }

    /** Waits for [MoshListener.onClosed] and returns its argument. */
    fun awaitClosed(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (!closed) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) throw AssertionError("Timed out waiting for the session to close. Screen:\n" + emulator.screenText())
                lock.wait(left)
            }
            return closeError
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
