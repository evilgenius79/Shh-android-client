package io.github.evilgenius79.shh.session

import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.InternetProtocol
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
import io.github.evilgenius79.shh.ssh.AddressFamily
import io.github.evilgenius79.shh.ssh.SshCertificate
import io.github.evilgenius79.shh.ssh.SshShell
import io.github.evilgenius79.shh.ssh.SecurityKeyAnswer
import io.github.evilgenius79.shh.ssh.SecurityKeyRequest
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

/** A shell that ends this soon after the user typed was ended by the user. */
private const val EXIT_FOLLOWS_INPUT_MS = 10_000L

/** Where a session is in its life. */
sealed interface SessionState {
    /** Still setting up; [messages] is the progress so far. */
    data class Connecting(val messages: List<String>) : SessionState

    data object Connected : SessionState

    /**
     * The connection broke and the session is getting it back by itself. [reason] is what
     * happened, [attempt] counts the tries so far, starting at 1.
     */
    data class Reconnecting(val reason: String, val attempt: Int) : SessionState

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

    /** A program on the server wants to sign something with the key called [keyName]. */
    class AgentUse(val target: SshTarget, val keyName: String, private val answer: CompletableFuture<AgentAnswer>) : SessionPrompt {
        fun respond(decision: AgentAnswer) {
            answer.complete(decision)
        }
    }

    /**
     * Logging in to [target] takes the security key that holds the key called [keyName]: the
     * hardware has to sign [request].
     */
    class SecurityKey(
        val target: SshTarget,
        val keyName: String,
        val request: SecurityKeyRequest,
        private val answer: CompletableFuture<SecurityKeyAnswer>,
    ) : SessionPrompt {
        fun respond(result: SecurityKeyAnswer) {
            answer.complete(result)
        }
    }
}

/** What the user says to a server that wants to use one of their keys. */
enum class AgentAnswer {
    DENY,

    /** This once; the next request asks again. */
    ONCE,

    /** This key may be used by this session until it is closed. */
    SESSION,
}

/** What a session needs from the rest of the app. */
class SessionEnvironment(
    val data: () -> AppData,
    val secrets: SecretBox,
    val hostKeys: HostKeyStore,
    /** Called when the remote program rings the bell. */
    val onBell: (TerminalSession) -> Unit = {},
    /**
     * Called when a session's shell ended by itself. The flag says whether the user brought that
     * about, by typing "exit" or Ctrl+D, as opposed to a shell that gave up on its own.
     */
    val onShellExited: (TerminalSession, Boolean) -> Unit = { _, _ -> },
    /** Called when the remote program asks to copy text (only when the setting allows it). */
    val onClipboard: (String) -> Unit = {},
    /** Called after a session connected, to record the time on the host. */
    val onConnected: (Host) -> Unit = {},
    /**
     * How long to wait before each automatic attempt to get a broken connection back. When the
     * list is used up the session gives up.
     */
    val reconnectDelaysMs: List<Long> = DEFAULT_RECONNECT_DELAYS_MS,
) {
    companion object {
        /** Quick at first, for a network that only blinked; then patient, for about five minutes. */
        val DEFAULT_RECONNECT_DELAYS_MS: List<Long> =
            listOf(1_000, 2_000, 5_000, 10_000, 20_000, 30_000, 30_000, 30_000, 30_000, 30_000, 60_000, 60_000)
    }
}

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

    /** Guards the decisions about what happens when a connection ends. */
    private val lifecycle = Any()

    /** The thread waiting for the next automatic attempt. Interrupting it makes the attempt now. */
    @Volatile
    private var reconnectWait: Thread? = null

    /** Why the last automatic attempt failed, for the message when the session gives up. */
    @Volatile
    private var lastReconnectError: String? = null

    /**
     * Passwords typed into this session's prompts, by `user@host:port`. They let the session get
     * a broken connection back without asking again. Held in memory only, never saved, and wiped
     * when the session is closed.
     */
    private val typedPasswords = java.util.concurrent.ConcurrentHashMap<String, CharArray>()

    private fun passwordKey(user: String, host: String, port: Int) = "$user@$host:$port"

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

    /**
     * While the session is waiting to try again by itself: try now. Called when the network comes
     * back, and by the user who does not want to wait.
     */
    fun retryNow() {
        if (stateFlow.value is SessionState.Reconnecting) reconnectWait?.interrupt()
    }

    /** Gives up getting the connection back; the session is then simply disconnected. */
    fun stopReconnecting() {
        synchronized(lifecycle) {
            val current = stateFlow.value as? SessionState.Reconnecting ?: return
            generation++
            reconnectWait?.interrupt()
            cancelPrompt()
            stateFlow.value = SessionState.Disconnected(current.reason)
            teardownConnection()
        }
    }

    /**
     * The connection of an established session broke. Tries to get it back, patiently: the usual
     * cause is a phone that changed networks or lost its signal for a moment.
     */
    private fun beginAutoReconnect(reason: String) {
        synchronized(lifecycle) {
            if (closed.get() || stateFlow.value !is SessionState.Connected) return
            cancelPrompt()
            teardownConnection()
            writer.shutdownNow()
            writer = newWriter()
            printNotice("$reason. Reconnecting…")
            lastReconnectError = null
            scheduleReconnect(reason, attempt = 1)
        }
    }

    private fun scheduleReconnect(reason: String, attempt: Int) {
        val delays = environment.reconnectDelaysMs
        if (attempt > delays.size) {
            val detail = lastReconnectError?.let { ": $it" }.orEmpty()
            finish("$reason. Could not reconnect$detail", null)
            return
        }
        val mine = ++generation
        stateFlow.value = SessionState.Reconnecting(reason, attempt)
        val thread = Thread({
            try {
                Thread.sleep(delays[attempt - 1])
            } catch (_: InterruptedException) {
                // Asked to try right away.
            }
            if (mine == generation && !closed.get()) runConnection(mine)
        }, "shh-reconnect-$id")
        thread.isDaemon = true
        reconnectWait = thread
        thread.start()
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
            if (stateFlow.value is SessionState.Reconnecting) printNotice("Reconnected")
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

    /** Reads a stored key, with its certificate when it has a usable one. */
    private fun load(identity: io.github.evilgenius79.shh.data.Identity): LoadedKey {
        val key = SshKeys.load(environment.secrets.decryptText(identity.encryptedPrivateKey))
        val certificate = identity.certificate ?: return key
        // A certificate that cannot be read or does not fit is no reason to lose the key.
        return try {
            key.withCertificate(SshCertificate.parse(certificate))
        } catch (_: Exception) {
            key
        }
    }

    private fun buildTarget(host: Host, data: AppData): SshTarget {
        val keys = ArrayList<LoadedKey>()
        data.identity(host.identityId)?.let { keys.add(load(it)) }
        val password = host.encryptedPassword?.let { environment.secrets.decryptText(it).toCharArray() }
            ?: typedPasswords[passwordKey(host.username, host.host, host.port)]?.copyOf()
        val family = when (host.internetProtocol) {
            InternetProtocol.AUTOMATIC -> AddressFamily.ANY
            InternetProtocol.IPV4 -> AddressFamily.IPV4
            InternetProtocol.IPV6 -> AddressFamily.IPV6
        }
        // The agent holds every key in the app, like an agent on a desktop does. They are only
        // read when the server first asks, and each use is put to the user.
        val agent: (() -> List<LoadedKey>)? = if (!host.forwardAgent) null else {
            { environment.data().identities.mapNotNull { identity -> runCatching { load(identity) }.getOrNull() } }
        }
        return SshTarget(host.host, host.port, host.username, keys, password, host.allowLegacyAlgorithms, family, agent)
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
        // A shell that ended by itself reports how. Without that report the output stopped
        // because the connection broke, or because the shell was killed.
        val status = shell.awaitExit(2000)
        if (attempt != generation || closed.get() || stateFlow.value !is SessionState.Connected) return
        if (status != null) {
            finish("Session ended (exit status $status)", null)
            // The status does not say whether the user meant to leave: a shell passes on that of
            // its last command, so "exit" after a failed grep ends with 1. What tells is that
            // the user typed something just before.
            environment.onShellExited(this, System.currentTimeMillis() - lastUserInputAt <= EXIT_FOLLOWS_INPUT_MS)
            return
        }
        // The transport says so through its listener, but not always before this point.
        val active = connection
        var broken = false
        for (i in 0 until 10) {
            broken = active == null || !active.isConnected
            if (broken || attempt != generation) break
            Thread.sleep(100)
        }
        if (attempt != generation || closed.get()) return
        if (broken) connectionLost(null) else if (stateFlow.value is SessionState.Connected) finish("Session ended", null)
    }

    private fun onConnectionLost(reason: String?) {
        if (closed.get()) return
        connectionLost(reason)
    }

    private fun connectionLost(reason: String?) {
        if (stateFlow.value !is SessionState.Connected) return
        val message = if (reason.isNullOrBlank()) "Connection lost" else "Connection lost: $reason"
        if (environment.data().settings.autoReconnect) beginAutoReconnect(message) else finish(message, null)
    }

    /** A connection attempt failed. An automatic one is repeated unless repeating cannot help. */
    private fun fail(message: String, failure: SshFailure?) {
        val current = stateFlow.value
        val pointless = failure == SshFailure.AUTH_FAILED || failure == SshFailure.HOST_KEY_REJECTED || failure == SshFailure.CANCELLED
        if (current is SessionState.Reconnecting && !pointless) {
            synchronized(lifecycle) {
                if (closed.get() || stateFlow.value !is SessionState.Reconnecting) return
                lastReconnectError = message
                teardownConnection()
                scheduleReconnect(current.reason, current.attempt + 1)
            }
        } else {
            finish(message, failure)
        }
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

    /** When the user last typed, pressed a key or pasted. The terminal's own answers do not count. */
    @Volatile
    private var lastUserInputAt = 0L

    fun sendText(text: String, modifiers: Int = 0) {
        lastUserInputAt = System.currentTimeMillis()
        write(KeyEncoder.encodeText(text, modifiers))
    }

    fun sendKey(key: TerminalKey, modifiers: Int = 0) {
        lastUserInputAt = System.currentTimeMillis()
        val bytes = synchronized(emulator) { KeyEncoder.encode(key, modifiers, emulator) }
        write(bytes)
    }

    fun paste(text: String) {
        lastUserInputAt = System.currentTimeMillis()
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
        reconnectWait?.interrupt()
        cancelPrompt()
        stateFlow.value = SessionState.Disconnected("Closed")
        teardownConnection()
        writer.shutdownNow()
        for (password in typedPasswords.values) password.fill('\u0000')
        typedPasswords.clear()
    }

    // ----- TerminalHost: the emulator talking back -----

    override fun onBell() = environment.onBell(this)

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

    /** One question at a time: whoever asks second waits until the first has its answer. */
    private val promptTurn = java.util.concurrent.locks.ReentrantLock()

    private fun <T> ask(build: (CompletableFuture<T>) -> SessionPrompt, fallback: T): T {
        promptTurn.lock()
        try {
            if (closed.get()) return fallback
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
        } finally {
            promptTurn.unlock()
        }
    }

    /** Keys the user allowed the server to use for as long as this session is open. */
    private val agentKeysAllowed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun confirmAgentUse(target: SshTarget, key: io.github.evilgenius79.shh.ssh.SshPublicKey): Boolean {
        if (!isConnected) return false
        if (key.fingerprint in agentKeysAllowed) return true
        val name = environment.data().identities.firstOrNull { it.fingerprint == key.fingerprint }?.name ?: key.fingerprint
        return when (ask({ SessionPrompt.AgentUse(target, name, it) }, AgentAnswer.DENY)) {
            AgentAnswer.DENY -> false
            AgentAnswer.ONCE -> true
            AgentAnswer.SESSION -> {
                agentKeysAllowed.add(key.fingerprint)
                true
            }
        }
    }

    override fun signWithSecurityKey(target: SshTarget, key: io.github.evilgenius79.shh.ssh.SshPublicKey, request: SecurityKeyRequest): SecurityKeyAnswer {
        val name = environment.data().identities.firstOrNull { it.fingerprint == key.fingerprint }?.name ?: key.fingerprint
        // A session that is closed while the question is open gives up rather than trying passwords.
        return ask<SecurityKeyAnswer>({ SessionPrompt.SecurityKey(target, name, request, it) }, SecurityKeyAnswer.Cancel)
    }

    private fun cancelPrompt() {
        when (val current = promptFlow.value) {
            is SessionPrompt.HostKey -> current.respond(HostKeyDecision.REJECT)
            is SessionPrompt.Password -> current.respond(null)
            is SessionPrompt.Challenge -> current.respond(null)
            is SessionPrompt.AgentUse -> current.respond(AgentAnswer.DENY)
            is SessionPrompt.SecurityKey -> current.respond(SecurityKeyAnswer.Cancel)
            null -> Unit
        }
        promptFlow.value = null
    }

    override fun confirmHostKey(request: HostKeyRequest): HostKeyDecision =
        ask({ SessionPrompt.HostKey(request, it) }, HostKeyDecision.REJECT)

    override fun requestPassword(target: SshTarget, attempt: Int): CharArray? {
        val typed: CharArray? = ask({ SessionPrompt.Password(target, attempt, it) }, null)
        if (typed != null) {
            typedPasswords.put(passwordKey(target.username, target.host, target.port), typed.copyOf())?.fill('\u0000')
        }
        return typed
    }

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
