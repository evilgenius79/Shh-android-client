package io.github.evilgenius79.shh.session

import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.SecretBox
import io.github.evilgenius79.shh.ssh.ConnectOptions
import io.github.evilgenius79.shh.ssh.HostKeyDecision
import io.github.evilgenius79.shh.ssh.HostKeyRequest
import io.github.evilgenius79.shh.ssh.HostKeyStore
import io.github.evilgenius79.shh.ssh.InteractiveChallenge
import io.github.evilgenius79.shh.ssh.LoadedKey
import io.github.evilgenius79.shh.ssh.PortForward
import io.github.evilgenius79.shh.ssh.SshConnectException
import io.github.evilgenius79.shh.ssh.SshConnection
import io.github.evilgenius79.shh.ssh.SshConnector
import io.github.evilgenius79.shh.ssh.SshFailure
import io.github.evilgenius79.shh.ssh.SshKeys
import io.github.evilgenius79.shh.ssh.SshPrompts
import io.github.evilgenius79.shh.ssh.SshShell
import io.github.evilgenius79.shh.ssh.SshTarget
import io.github.evilgenius79.shh.terminal.ColorScheme
import io.github.evilgenius79.shh.terminal.KeyEncoder
import io.github.evilgenius79.shh.terminal.TerminalColors
import io.github.evilgenius79.shh.terminal.TerminalEmulator
import io.github.evilgenius79.shh.terminal.TerminalHost
import io.github.evilgenius79.shh.terminal.TerminalKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Where a session is in its life. */
sealed interface SessionState {
    /** Still setting up; [messages] is the progress so far. */
    data class Connecting(val messages: List<String>) : SessionState

    data object Connected : SessionState

    /** Over. [failure] is set when connecting failed, as opposed to an established session ending. */
    data class Disconnected(val message: String, val failure: SshFailure? = null) : SessionState
}

/** A question the session needs the user to answer before it can continue. */
sealed interface SessionPrompt {
    class HostKey(val request: HostKeyRequest, private val answer: CompletableFuture<HostKeyDecision>) : SessionPrompt {
        fun respond(decision: HostKeyDecision) {
            answer.complete(decision)
        }
    }

    class Password(val target: SshTarget, val attempt: Int, private val answer: CompletableFuture<CharArray?>) : SessionPrompt {
        fun respond(password: CharArray?) {
            answer.complete(password)
        }
    }

    class Challenge(val challenge: InteractiveChallenge, private val answer: CompletableFuture<List<String>?>) : SessionPrompt {
        fun respond(answers: List<String>?) {
            answer.complete(answers)
        }
    }
}

/** What a session needs from the rest of the app. */
class SessionEnvironment(
    val data: () -> AppData,
    val secrets: SecretBox,
    val hostKeys: HostKeyStore,
    /** Called when the remote program rings the bell. */
    val onBell: () -> Unit = {},
    /** Called when the remote program asks to copy text (only when the setting allows it). */
    val onClipboard: (String) -> Unit = {},
    /** Called after a session connected, to record the time on the host. */
    val onConnected: (Host) -> Unit = {},
)

/**
 * One terminal tab: an SSH connection, the shell running on it and the emulator showing it.
 *
 * All network work happens on the session's own threads. The emulator is guarded by its own
 * monitor: lock it to feed data or to draw.
 */
class TerminalSession(
    val id: String,
    val host: Host,
    private val environment: SessionEnvironment,
) : TerminalHost, SshPrompts {
    val emulator: TerminalEmulator

    private val stateFlow = MutableStateFlow<SessionState>(SessionState.Connecting(emptyList()))
    val state: StateFlow<SessionState> = stateFlow.asStateFlow()

    private val titleFlow = MutableStateFlow(host.title)
    val title: StateFlow<String> = titleFlow.asStateFlow()

    private val promptFlow = MutableStateFlow<SessionPrompt?>(null)

    /** The question currently waiting for the user, if any. */
    val prompt: StateFlow<SessionPrompt?> = promptFlow.asStateFlow()

    private val forwardsFlow = MutableStateFlow<List<PortForward>>(emptyList())

    /** Port forwards currently running on this session. */
    val forwards: StateFlow<List<PortForward>> = forwardsFlow.asStateFlow()

    /** Invoked (on any thread) whenever the screen content changed and should be redrawn. */
    @Volatile
    var onScreenChanged: (() -> Unit)? = null

    @Volatile
    var connection: SshConnection? = null
        private set

    @Volatile
    private var shell: SshShell? = null
    private val closed = AtomicBoolean(false)
    private var writer: ExecutorService = newWriter()
    private var columns = 80
    private var rows = 24
    private var cellWidthPx = 0
    private var cellHeightPx = 0

    /** Bumped on every (re)connect so that threads of an earlier attempt know they are stale. */
    @Volatile
    private var generation = 0

    init {
        val settings = environment.data().settings
        val scheme = ColorScheme.byId(host.colorSchemeId ?: settings.colorSchemeId)
        emulator = TerminalEmulator(columns, rows, this, settings.scrollbackLines, TerminalColors(scheme))
    }

    private fun newWriter(): ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "shh-write-$id").apply { isDaemon = true }
    }

    val isConnected: Boolean get() = stateFlow.value is SessionState.Connected

    /** Starts connecting. Returns immediately; follow [state] for the outcome. */
    fun connect() {
        if (closed.get()) return
        val attempt = ++generation
        stateFlow.value = SessionState.Connecting(emptyList())
        Thread({ runConnection(attempt) }, "shh-session-$id").apply { isDaemon = true }.start()
    }

    /** Connects again after the session ended, keeping the scrollback. */
    fun reconnect() {
        teardownConnection()
        writer.shutdownNow()
        writer = newWriter()
        printNotice("Reconnecting…")
        connect()
    }

    private fun runConnection(attempt: Int) {
        val data = environment.data()
        try {
            val target = buildTarget(host, data)
            val jumps = buildJumpChain(host, data)
            val options = ConnectOptions(keepAliveSeconds = host.keepAliveSeconds, compression = host.compression)
            val newConnection = SshConnector.connect(target, environment.hostKeys, this, jumps, options)
            if (attempt != generation || closed.get()) {
                newConnection.close()
                return
            }
            connection = newConnection
            newConnection.onDisconnect { reason -> if (attempt == generation) onConnectionLost(reason) }

            val (cols, lines) = synchronized(emulator) { columns to rows }
            val newShell = newConnection.openShell(host.termType, cols, lines, cols * cellWidthPx, lines * cellHeightPx, mapOf("LANG" to "C.UTF-8"))
            shell = newShell
            stateFlow.value = SessionState.Connected
            environment.onConnected(host)
            startForwards(newConnection)
            if (host.startupCommand.isNotBlank()) write((host.startupCommand + "\r").toByteArray(Charsets.UTF_8))
            pump(newShell, attempt)
        } catch (e: SshConnectException) {
            if (attempt == generation && !closed.get()) fail(describe(e), e.failure)
        } catch (e: IOException) {
            if (attempt == generation && !closed.get()) fail(e.message ?: "Connection failed", SshFailure.IO)
        } catch (e: java.security.GeneralSecurityException) {
            if (attempt == generation && !closed.get()) fail("A saved secret could not be decrypted: ${e.message}", SshFailure.IO)
        }
    }

    private fun buildTarget(host: Host, data: AppData): SshTarget {
        val keys = ArrayList<LoadedKey>()
        data.identity(host.identityId)?.let { identity ->
            keys.add(SshKeys.load(environment.secrets.decryptText(identity.encryptedPrivateKey)))
        }
        val password = host.encryptedPassword?.let { environment.secrets.decryptText(it).toCharArray() }
        return SshTarget(host.host, host.port, host.username, keys, password, host.allowLegacyAlgorithms)
    }

    /** Follows `jumpHostId` links, outermost hop first. A cycle simply ends the chain. */
    private fun buildJumpChain(host: Host, data: AppData): List<SshTarget> {
        val chain = ArrayList<SshTarget>()
        val seen = HashSet<String>()
        seen.add(host.id)
        var next = data.host(host.jumpHostId)
        while (next != null && seen.add(next.id)) {
            chain.add(0, buildTarget(next, data))
            next = data.host(next.jumpHostId)
        }
        return chain
    }

    private fun startForwards(connection: SshConnection) {
        for (rule in host.forwards.filter { it.enabled }) {
            try {
                when (rule.kind) {
                    ForwardKind.LOCAL -> connection.forwardLocal(rule.bindAddress, rule.bindPort, rule.destinationHost, rule.destinationPort)
                    ForwardKind.REMOTE -> connection.forwardRemote(rule.bindAddress, rule.bindPort, rule.destinationHost, rule.destinationPort)
                    ForwardKind.DYNAMIC -> connection.forwardDynamic(rule.bindAddress, rule.bindPort)
                }
            } catch (e: IOException) {
                printNotice("Port forward ${rule.summary} failed: ${e.message}")
            }
        }
        forwardsFlow.value = connection.activeForwards
    }

    /** Adds a port forward to the running session. */
    @Throws(IOException::class)
    fun addForward(kind: ForwardKind, bindAddress: String, bindPort: Int, destinationHost: String, destinationPort: Int): PortForward {
        val active = connection ?: throw IOException("Not connected")
        val forward = when (kind) {
            ForwardKind.LOCAL -> active.forwardLocal(bindAddress, bindPort, destinationHost, destinationPort)
            ForwardKind.REMOTE -> active.forwardRemote(bindAddress, bindPort, destinationHost, destinationPort)
            ForwardKind.DYNAMIC -> active.forwardDynamic(bindAddress, bindPort)
        }
        forwardsFlow.value = active.activeForwards
        return forward
    }

    fun removeForward(forward: PortForward) {
        forward.close()
        forwardsFlow.value = connection?.activeForwards ?: emptyList()
    }

    /** Copies shell output into the emulator until the shell ends. */
    private fun pump(shell: SshShell, attempt: Int) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = shell.output.read(buffer)
                if (n < 0) break
                synchronized(emulator) { emulator.append(buffer, 0, n) }
                onScreenChanged?.invoke()
            }
        } catch (_: IOException) {
            // The connection broke; reported through the disconnect listener.
        }
        if (attempt != generation || closed.get()) return
        val status = shell.awaitExit(2000)
        if (stateFlow.value is SessionState.Connected) {
            val message = if (status != null) "Session ended (exit status $status)" else "Session ended"
            finish(message, null)
        }
    }

    private fun onConnectionLost(reason: String?) {
        if (closed.get()) return
        if (stateFlow.value is SessionState.Connected) {
            finish(if (reason.isNullOrBlank()) "Connection lost" else "Connection lost: $reason", null)
        }
    }

    private fun fail(message: String, failure: SshFailure?) {
        finish(message, failure)
    }

    private fun finish(message: String, failure: SshFailure?) {
        cancelPrompt()
        stateFlow.value = SessionState.Disconnected(message, failure)
        printNotice(message)
        teardownConnection()
    }

    private fun teardownConnection() {
        val old = connection
        connection = null
        shell = null
        forwardsFlow.value = emptyList()
        if (old != null) Thread({ old.close() }, "shh-close-$id").apply { isDaemon = true }.start()
    }

    /** Writes a line into the terminal that comes from the app rather than from the server. */
    private fun printNotice(text: String) {
        synchronized(emulator) {
            // Leave whatever mode a full screen program left behind, then print on a fresh line.
            emulator.append("\u001b[?1049l\u001b[?25h\u001b[0m\r\n\u001b[2m[$text]\u001b[0m\r\n")
        }
        onScreenChanged?.invoke()
    }

    private fun describe(e: SshConnectException): String = when (e.failure) {
        SshFailure.AUTH_FAILED -> {
            val methods = e.serverAuthMethods.joinToString(", ")
            if (methods.isEmpty()) "Authentication failed" else "Authentication failed (server accepts: $methods)"
        }
        SshFailure.CANCELLED -> "Cancelled"
        else -> e.message ?: "Connection failed"
    }

    // ----- input from the user -----

    /**
     * Sends raw bytes to the shell. Never blocks: the bytes are queued for the writer thread, so
     * this is safe on the main thread and also serves as the emulator's way to answer queries.
     */
    override fun write(data: ByteArray) {
        val target = shell ?: return
        try {
            writer.execute {
                try {
                    target.write(data)
                } catch (_: IOException) {
                    // A broken connection is reported through the disconnect listener.
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    fun sendText(text: String, modifiers: Int = 0) = write(KeyEncoder.encodeText(text, modifiers))

    fun sendKey(key: TerminalKey, modifiers: Int = 0) {
        val bytes = synchronized(emulator) { KeyEncoder.encode(key, modifiers, emulator) }
        write(bytes)
    }

    fun paste(text: String) {
        synchronized(emulator) { emulator.paste(text) }
    }

    /** Tells the session how large the view is. */
    fun resize(newColumns: Int, newRows: Int, cellWidth: Int, cellHeight: Int) {
        synchronized(emulator) {
            cellWidthPx = cellWidth
            cellHeightPx = cellHeight
            emulator.setCellSize(cellWidth, cellHeight)
            if (newColumns == columns && newRows == rows) return
            columns = newColumns
            rows = newRows
            emulator.resize(newColumns, newRows)
        }
        val target = shell ?: return
        try {
            writer.execute {
                try {
                    target.resize(newColumns, newRows, newColumns * cellWidth, newRows * cellHeight)
                } catch (_: IOException) {
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Ends the session for good. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation++
        cancelPrompt()
        stateFlow.value = SessionState.Disconnected("Closed")
        teardownConnection()
        writer.shutdownNow()
    }

    // ----- TerminalHost: the emulator talking back -----

    override fun onBell() = environment.onBell()

    override fun onTitleChanged(title: String) {
        titleFlow.value = title.ifBlank { host.title }
    }

    override fun onClipboardCopy(text: String) {
        if (environment.data().settings.allowRemoteClipboard) environment.onClipboard(text)
    }

    override fun onColorsChanged() {
        onScreenChanged?.invoke()
    }

    // ----- SshPrompts: questions asked while connecting -----

    private fun <T> ask(build: (CompletableFuture<T>) -> SessionPrompt, fallback: T): T {
        val answer = CompletableFuture<T>()
        val prompt = build(answer)
        promptFlow.value = prompt
        return try {
            answer.get()
        } catch (_: Exception) {
            fallback
        } finally {
            if (promptFlow.value === prompt) promptFlow.value = null
        }
    }

    private fun cancelPrompt() {
        when (val current = promptFlow.value) {
            is SessionPrompt.HostKey -> current.respond(HostKeyDecision.REJECT)
            is SessionPrompt.Password -> current.respond(null)
            is SessionPrompt.Challenge -> current.respond(null)
            null -> Unit
        }
        promptFlow.value = null
    }

    override fun confirmHostKey(request: HostKeyRequest): HostKeyDecision =
        ask({ SessionPrompt.HostKey(request, it) }, HostKeyDecision.REJECT)

    override fun requestPassword(target: SshTarget, attempt: Int): CharArray? =
        ask({ SessionPrompt.Password(target, attempt, it) }, null)

    override fun answerChallenge(challenge: InteractiveChallenge): List<String>? =
        ask({ SessionPrompt.Challenge(challenge, it) }, null)

    override fun onBanner(target: SshTarget, banner: String) {
        synchronized(emulator) { emulator.append(banner.replace("\r\n", "\n").replace("\n", "\r\n")) }
        onScreenChanged?.invoke()
    }

    override fun onProgress(message: String) {
        val current = stateFlow.value
        if (current is SessionState.Connecting) stateFlow.value = SessionState.Connecting(current.messages + message)
    }
}
