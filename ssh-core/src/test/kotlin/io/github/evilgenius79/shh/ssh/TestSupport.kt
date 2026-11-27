package io.github.evilgenius79.shh.ssh

import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge as ServerChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** An SSH server running inside the test JVM. */
class TestSshServer(
    hostKey: KeyPair = newEcKeyPair(),
    port: Int = 0,
    val name: String = "server",
) : AutoCloseable {
    private val server: SshServer = SshServer.setUpDefaultServer()

    /** user -> password accepted by password authentication. */
    val passwords = HashMap<String, String>()

    /** Public keys accepted by public key authentication. */
    val authorizedKeys = CopyOnWriteArrayList<PublicKey>()

    /** When set, keyboard-interactive asks these prompts and expects these answers. */
    var interactivePrompts: List<Pair<String, Boolean>> = emptyList()
    var interactiveAnswers: List<String> = emptyList()

    /** Everything password authentication was offered, for assertions. */
    val passwordAttempts = CopyOnWriteArrayList<String>()

    val port: Int get() = server.port

    init {
        server.host = "127.0.0.1"
        server.port = port
        server.keyPairProvider = KeyPairProvider.wrap(hostKey)
        server.setPasswordAuthenticator { user, password, _ ->
            passwordAttempts.add(password)
            passwords[user] == password
        }
        server.setPublickeyAuthenticator { _, key, _ -> authorizedKeys.any { it == key } }
        server.keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
            override fun generateChallenge(session: ServerSession, username: String, lang: String?, subMethods: String?): ServerChallenge? {
                if (interactivePrompts.isEmpty()) return null
                val challenge = ServerChallenge()
                challenge.interactionName = "Test login"
                challenge.interactionInstruction = "Answer the questions"
                challenge.languageTag = ""
                for ((text, echo) in interactivePrompts) challenge.addPrompt(text, echo)
                return challenge
            }

            override fun authenticate(session: ServerSession, username: String, responses: List<String>): Boolean =
                interactivePrompts.isNotEmpty() && responses == interactiveAnswers
        }
        server.setShellFactory { TestShell(name) }
        server.setCommandFactory { _, command -> TestCommand(command) }
        server.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
    }

    /** Requires several methods in sequence, e.g. "publickey,keyboard-interactive". */
    fun requireAuthMethods(methods: String) {
        CoreModuleProperties.AUTH_METHODS.set(server, methods)
    }

    fun banner(text: String) {
        CoreModuleProperties.WELCOME_BANNER.set(server, text)
    }

    fun enableSftp(root: Path) {
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        server.fileSystemFactory = VirtualFileSystemFactory(root)
    }

    fun start(): TestSshServer {
        server.start()
        return this
    }

    /** Drops every client without warning, like a network failure would. */
    fun dropClients() {
        for (session in server.activeSessions) session.close(true)
    }

    /** How long a client may take to log in before the server disconnects it, saying why. */
    fun loginTimeout(millis: Long) {
        CoreModuleProperties.AUTH_TIMEOUT.set(server, java.time.Duration.ofMillis(millis))
    }

    override fun close() {
        server.stop(true)
    }

    companion object {
        fun newEcKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return generator.generateKeyPair()
        }

        fun newRsaKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            return generator.generateKeyPair()
        }
    }
}

/**
 * A scripted "shell": greets with the terminal parameters it was given, echoes lines back and
 * reports window size changes. `exit N` ends it with status N.
 */
private class TestShell(private val serverName: String) : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var exit: ExitCallback? = null
    private var worker: Thread? = null

    override fun setInputStream(stream: InputStream) {
        input = stream
    }

    override fun setOutputStream(stream: OutputStream) {
        output = stream
    }

    override fun setErrorStream(stream: OutputStream) = Unit

    override fun setExitCallback(callback: ExitCallback) {
        exit = callback
    }

    private fun say(text: String) {
        synchronized(output) {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    override fun start(channel: ChannelSession, env: Environment) {
        val vars = env.env
        env.addSignalListener({ _: Channel, _: Signal ->
            say("SIZE ${env.env[Environment.ENV_COLUMNS]}x${env.env[Environment.ENV_LINES]}\r\n")
        }, Signal.WINCH)
        worker = thread(isDaemon = true) {
            try {
                say("READY $serverName ${vars[Environment.ENV_COLUMNS]}x${vars[Environment.ENV_LINES]} term=${vars[Environment.ENV_TERM]} lang=${vars["LANG"]}\r\n")
                val reader = input.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("exit")) {
                        exit?.onExit(line.removePrefix("exit").trim().toIntOrNull() ?: 0)
                        return@thread
                    }
                    say("ECHO:$line\r\n")
                }
                exit?.onExit(0)
            } catch (_: Exception) {
                exit?.onExit(1)
            }
        }
    }

    override fun destroy(channel: ChannelSession) {
        worker?.interrupt()
    }
}

/** `exec` handler: "echo X" prints X, "cat" copies stdin, "fail" writes to stderr and exits 3. */
private class TestCommand(private val command: String) : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var error: OutputStream
    private var exit: ExitCallback? = null

    override fun setInputStream(stream: InputStream) {
        input = stream
    }

    override fun setOutputStream(stream: OutputStream) {
        output = stream
    }

    override fun setErrorStream(stream: OutputStream) {
        error = stream
    }

    override fun setExitCallback(callback: ExitCallback) {
        exit = callback
    }

    override fun start(channel: ChannelSession, env: Environment) {
        thread(isDaemon = true) {
            var status = 0
            try {
                when {
                    command.startsWith("echo ") -> output.write((command.removePrefix("echo ") + "\n").toByteArray())
                    command == "cat" -> input.copyTo(output)
                    command == "fail" -> {
                        error.write("it went wrong\n".toByteArray())
                        status = 3
                    }
                    else -> {
                        error.write("unknown command\n".toByteArray())
                        status = 127
                    }
                }
                output.flush()
                error.flush()
            } finally {
                exit?.onExit(status)
            }
        }
    }

    override fun destroy(channel: ChannelSession) = Unit
}

/** Scripted answers for the questions a connection attempt may ask, recording what was asked. */
class TestPrompts(
    var hostKeyDecision: HostKeyDecision = HostKeyDecision.ACCEPT_AND_REMEMBER,
    val passwords: ArrayDeque<String?> = ArrayDeque(),
    val answers: ArrayDeque<String?> = ArrayDeque(),
) : SshPrompts {
    val hostKeyRequests = CopyOnWriteArrayList<HostKeyRequest>()

    /** Keys the forwarded agent was asked to sign with, and what to answer. */
    val agentRequests = CopyOnWriteArrayList<SshPublicKey>()
    var allowAgent = true

    override fun confirmAgentUse(target: SshTarget, key: SshPublicKey): Boolean {
        agentRequests.add(key)
        return allowAgent
    }
    val passwordRequests = CopyOnWriteArrayList<Int>()
    val challenges = CopyOnWriteArrayList<InteractiveChallenge>()
    val banners = CopyOnWriteArrayList<String>()
    val progress = CopyOnWriteArrayList<String>()

    override fun confirmHostKey(request: HostKeyRequest): HostKeyDecision {
        hostKeyRequests.add(request)
        return hostKeyDecision
    }

    /** Runs while the "user" is looking at the password prompt, before the answer is given. */
    var whilePasswordPromptIsOpen: () -> Unit = {}

    override fun requestPassword(target: SshTarget, attempt: Int): CharArray? {
        passwordRequests.add(attempt)
        whilePasswordPromptIsOpen()
        return passwords.removeFirstOrNull()?.toCharArray()
    }

    override fun answerChallenge(challenge: InteractiveChallenge): List<String>? {
        challenges.add(challenge)
        val answer = answers.removeFirstOrNull() ?: return null
        return listOf(answer)
    }

    override fun onBanner(target: SshTarget, banner: String) {
        banners.add(banner)
    }

    override fun onProgress(message: String) {
        progress.add(message)
    }
}

/** A TCP server that sends back whatever it receives, as a target for port forwards. */
class EchoServer : AutoCloseable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    socket.use {
                        try {
                            it.getInputStream().copyTo(it.getOutputStream())
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

/** Reads from [stream] until the collected text contains [marker]; fails after [timeoutMs]. */
fun readUntil(stream: InputStream, marker: String, timeoutMs: Long = 10_000): String {
    val collected = StringBuilder()
    val deadline = System.currentTimeMillis() + timeoutMs
    val buffer = ByteArray(4096)
    val reader = thread(isDaemon = true) {
        try {
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                synchronized(collected) { collected.append(String(buffer, 0, n, Charsets.UTF_8)) }
                if (synchronized(collected) { collected.contains(marker) }) break
            }
        } catch (_: Exception) {
        }
    }
    reader.join(maxOf(1, deadline - System.currentTimeMillis()))
    val text = synchronized(collected) { collected.toString() }
    check(text.contains(marker)) { "Did not see \"$marker\" within ${timeoutMs}ms. Got: \"$text\"" }
    return text
}

/** Sends [text] through a fresh connection to a local port and returns what comes back. */
fun roundTrip(port: Int, text: String): String {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        socket.getOutputStream().write(text.toByteArray())
        socket.getOutputStream().flush()
        val buffer = ByteArray(text.toByteArray().size)
        var read = 0
        while (read < buffer.size) {
            val n = socket.getInputStream().read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read)
    }
}
