package io.github.evilgenius79.shh.ssh

import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthNone
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyPair
import java.util.concurrent.TimeoutException

/** Opens SSH connections. */
object SshConnector {
    /** How long the server may stay silent while the connection is being set up. */
    private const val HANDSHAKE_READ_TIMEOUT_MS = 30_000

    /**
     * Upper bound for one step of the handshake. It has to be generous because the step includes
     * the time the user takes to check a host key or type a password.
     */
    private const val PROMPT_TIMEOUT_MS = 10 * 60_000

    private const val OPERATION_TIMEOUT_MS = 30_000
    private const val MAX_PASSWORD_PROMPTS = 3

    /**
     * Connects and logs in to [target], going through [jumps] first (like `ssh -J`). Blocks until
     * the connection is ready, so call it from a background thread.
     *
     * @throws SshConnectException with a [SshFailure] describing what went wrong.
     */
    @Throws(SshConnectException::class)
    fun connect(
        target: SshTarget,
        hostKeys: HostKeyStore,
        prompts: SshPrompts,
        jumps: List<SshTarget> = emptyList(),
        options: ConnectOptions = ConnectOptions(),
    ): SshConnection {
        SshSecurity.install()
        val clients = ArrayList<SSHClient>()
        var banner: String? = null
        try {
            for (hop in jumps + target) {
                val client = open(hop, clients.lastOrNull(), options, hostKeys, prompts)
                clients.add(client)
                banner = client.userAuth.banner?.takeIf { it.isNotBlank() }
            }
        } catch (e: SshConnectException) {
            for (client in clients.asReversed()) runCatching { client.disconnect() }
            throw e
        }
        val agent = target.agentKeys?.let { keys ->
            AgentForwarder(clients.last().connection, keys) { key -> prompts.confirmAgentUse(target, key) }
        }
        return SshConnection(clients, target, banner, agent)
    }

    /** Looks up a host's addresses. A variable so that tests can decide what a name resolves to. */
    @Volatile
    internal var resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }

    /** An [SSHClient] that connects to the address it is told to, under the name it was given. */
    private class DialingClient(config: net.schmizz.sshj.Config) : SSHClient(config) {
        @Volatile
        var address: InetAddress? = null

        override fun makeInetSocketAddress(hostname: String, port: Int): InetSocketAddress =
            address?.let { InetSocketAddress(it, port) } ?: super.makeInetSocketAddress(hostname, port)
    }

    /** The addresses of [hop] that its address family allows, in the order to try them. */
    private fun addressesOf(hop: SshTarget): List<InetAddress> {
        val all = resolver(hop.host).toList()
        val wanted = when (hop.addressFamily) {
            AddressFamily.ANY -> all
            AddressFamily.IPV4 -> all.filterIsInstance<Inet4Address>()
            AddressFamily.IPV6 -> all.filterIsInstance<Inet6Address>()
        }
        if (wanted.isEmpty()) {
            val kind = if (hop.addressFamily == AddressFamily.IPV4) "IPv4" else "IPv6"
            throw SshConnectException(SshFailure.UNREACHABLE, "${hop.host} has no $kind address")
        }
        return wanted
    }

    /**
     * Connects [client] to the first of the host's addresses that answers. A name often has an
     * IPv6 and an IPv4 address of which only one works from where the phone is.
     */
    private fun dial(client: DialingClient, hop: SshTarget, prompts: SshPrompts) {
        val addresses = addressesOf(hop)
        for ((index, address) in addresses.withIndex()) {
            client.address = address
            try {
                client.connect(hop.host, hop.port)
                return
            } catch (e: IOException) {
                // Only a connection that was never made is worth another address, whatever the
                // reason: refused, no route, no answer, or no IPv6 on this network at all. Once
                // the server answered, what went wrong will go wrong at its other addresses too.
                val neverConnected = !client.isConnected
                if (!neverConnected || index == addresses.lastIndex) throw e
                prompts.onProgress("${address.hostAddress} did not answer, trying ${addresses[index + 1].hostAddress}")
            }
        }
    }

    private fun open(
        hop: SshTarget,
        via: SSHClient?,
        options: ConnectOptions,
        hostKeys: HostKeyStore,
        prompts: SshPrompts,
    ): SSHClient {
        val verifier = TrustOnFirstUseVerifier(hostKeys, prompts)
        val login = Login(hop, prompts)
        val client = DialingClient(SshAlgorithms.config(hop.allowLegacyAlgorithms, options.compression))
        client.connectTimeout = options.connectTimeoutMs
        client.timeout = HANDSHAKE_READ_TIMEOUT_MS
        client.transport.timeoutMs = PROMPT_TIMEOUT_MS
        client.addHostKeyVerifier(verifier)
        if (options.keepAliveSeconds > 0) {
            // Has to be set before connecting: sshj only starts the keep-alive thread if it is
            // already enabled at that point.
            val keepAlive = client.connection.keepAlive
            keepAlive.keepAliveInterval = options.keepAliveSeconds
            (keepAlive as? KeepAliveRunner)?.maxAliveCount = options.keepAliveMaxMissed
        }
        try {
            prompts.onProgress(
                if (via == null) "Connecting to ${hop.host}:${hop.port}" else "Connecting to ${hop.host}:${hop.port} through the jump host",
            )
            if (via == null) {
                dial(client, hop, prompts)
            } else {
                client.connectVia(via.newDirectConnection(hop.host, hop.port))
            }
            prompts.onProgress("Authenticating as ${hop.username}")
            login.run(client)
            // From here on the keep-alive probes watch the connection, so reads may block.
            if (via == null) client.socket.soTimeout = 0
            client.transport.timeoutMs = OPERATION_TIMEOUT_MS
            return client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw translate(e, hop, verifier, login, client)
        }
    }

    /** Works through the authentication methods the server accepts until one logs us in. */
    private class Login(private val hop: SshTarget, private val prompts: SshPrompts) {
        @Volatile
        var cancelled = false
        private var passwordPrompts = 0
        private var storedPasswordUsed = false
        private var storedPasswordUsedInteractively = false

        fun run(client: SSHClient) {
            // Asking for "none" first is how a client learns which methods the server accepts.
            // It also logs in to the rare server that needs no authentication at all.
            if (attempt(client, AuthNone())) return
            client.userAuth.banner?.takeIf { it.isNotBlank() }?.let { prompts.onBanner(hop, it) }

            val candidates = ArrayList<AuthMethod>()
            for (key in hop.keys) {
                // A certificate first: where it is accepted the key needs no entry on the server.
                key.certificate?.let { candidates.add(AuthPublickey(CertificateKeyProvider(key, it))) }
                candidates.add(AuthPublickey(KeyPairWrapper(if (key.securityKey != null) withSecurityKey(key) else key.keyPair)))
            }
            candidates.add(AuthKeyboardInteractive(InteractiveProvider()))
            candidates.add(AuthPassword(PasswordProvider()))

            // The set of acceptable methods changes as we go (multi-factor logins demand a second
            // method after the first succeeds), so it is consulted afresh before every attempt.
            while (candidates.isNotEmpty()) {
                val allowed = client.userAuth.allowedMethods
                val next = candidates.firstOrNull { allowed.contains(it.name) } ?: break
                candidates.remove(next)
                if (attempt(client, next)) return
                if (cancelled) throw PromptCancelledException()
            }
            throw UserAuthException("No authentication method succeeded")
        }

        /** The key pair of [key] with this login's user as the way to the hardware. */
        private fun withSecurityKey(key: LoadedKey): KeyPair = SecurityKeys.withSigner(key.keyPair) { request ->
            if (cancelled) return@withSigner null
            when (val answer = prompts.signWithSecurityKey(hop, key.publicKey, request)) {
                is SecurityKeyAnswer.Signed -> answer.assertion
                SecurityKeyAnswer.Skip -> null
                SecurityKeyAnswer.Cancel -> {
                    cancelled = true
                    null
                }
            }
        }

        private fun attempt(client: SSHClient, method: AuthMethod): Boolean = try {
            client.auth(hop.username, method)
            true
        } catch (e: UserAuthException) {
            // sshj reports a connection that broke while a method was being tried in the same way
            // as the server rejecting that method. Only the cause tells them apart, and asking the
            // client whether it is still connected does not: it says yes for a moment longer.
            val broken = causes(e).firstOrNull { it is TransportException || it is TimeoutException }
            if (broken != null || !client.isConnected) throw LoginInterruptedException(broken ?: e)
            false
        } catch (e: TransportException) {
            throw LoginInterruptedException(e)
        }

        private inner class PasswordProvider : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray {
                val stored = hop.password
                if (stored != null && !storedPasswordUsed) {
                    storedPasswordUsed = true
                    return stored.copyOf()
                }
                passwordPrompts++
                val typed = prompts.requestPassword(hop, passwordPrompts)
                if (typed == null) {
                    cancelled = true
                    return CharArray(0)
                }
                return typed
            }

            override fun shouldRetry(resource: Resource<*>?): Boolean = !cancelled && passwordPrompts < MAX_PASSWORD_PROMPTS
        }

        private inner class InteractiveProvider : ChallengeResponseProvider {
            private var name = ""
            private var instruction = ""

            override fun getSubmethods(): List<String> = emptyList()

            override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
                this.name = name.orEmpty()
                this.instruction = instruction.orEmpty()
            }

            override fun getResponse(prompt: String, echo: Boolean): CharArray {
                if (cancelled) return CharArray(0)
                // Many servers ask for the account password through this mechanism (PAM). Answer
                // that with the stored password, once, instead of bothering the user.
                val stored = hop.password
                if (stored != null && !storedPasswordUsedInteractively && !echo && prompt.contains("password", ignoreCase = true)) {
                    storedPasswordUsedInteractively = true
                    return stored.copyOf()
                }
                val challenge = InteractiveChallenge(hop.host, hop.username, name, instruction, listOf(InteractivePrompt(prompt, echo)))
                val answers = prompts.answerChallenge(challenge)
                if (answers == null || answers.isEmpty()) {
                    cancelled = true
                    return CharArray(0)
                }
                return answers[0].toCharArray()
            }

            override fun shouldRetry(): Boolean = false
        }
    }

    private fun translate(
        error: Throwable,
        hop: SshTarget,
        verifier: TrustOnFirstUseVerifier,
        login: Login,
        client: SSHClient,
    ): SshConnectException {
        if (error is SshConnectException) return error
        val where = "${hop.host}:${hop.port}"
        if (login.cancelled || error is PromptCancelledException) {
            return SshConnectException(SshFailure.CANCELLED, "Cancelled", error)
        }
        if (verifier.rejected != null) {
            return SshConnectException(SshFailure.HOST_KEY_REJECTED, "The host key of $where was not accepted", error)
        }
        val chain = causes(error)
        chain.firstOrNull { it is UnknownHostException }?.let {
            return SshConnectException(SshFailure.UNKNOWN_HOST, "Unknown host: ${hop.host}", error)
        }
        chain.firstOrNull { it is SocketTimeoutException }?.let {
            return SshConnectException(SshFailure.TIMEOUT, "Timed out connecting to $where", error)
        }
        chain.firstOrNull { it is NoRouteToHostException }?.let {
            return SshConnectException(SshFailure.UNREACHABLE, "No route to $where", error)
        }
        chain.firstOrNull { it is ConnectException }?.let {
            val refused = it.message?.contains("refused", ignoreCase = true) == true
            return if (refused) {
                SshConnectException(SshFailure.REFUSED, "Connection refused by $where", error)
            } else {
                SshConnectException(SshFailure.UNREACHABLE, "Could not reach $where: ${it.message}", error)
            }
        }
        chain.firstOrNull { it is OpenFailException }?.let {
            return SshConnectException(SshFailure.UNREACHABLE, "The jump host could not reach $where: ${it.message}", error)
        }
        if (error is LoginInterruptedException) return interruptedLogin(where, error)
        if (error is UserAuthException) {
            val methods = runCatching { client.userAuth.allowedMethods.toList() }.getOrDefault(emptyList())
            return SshConnectException(
                SshFailure.AUTH_FAILED,
                "Authentication failed for ${hop.username}@${hop.host}",
                error,
                methods,
            )
        }
        if (error is SSHException) {
            return when (error.disconnectReason) {
                DisconnectReason.HOST_KEY_NOT_VERIFIABLE ->
                    SshConnectException(SshFailure.HOST_KEY_REJECTED, "The host key of $where was not accepted", error)
                DisconnectReason.KEY_EXCHANGE_FAILED ->
                    SshConnectException(SshFailure.PROTOCOL, "Could not agree on encryption with $where: ${error.message}", error)
                DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE, DisconnectReason.TOO_MANY_CONNECTIONS ->
                    SshConnectException(SshFailure.AUTH_FAILED, "$where closed the connection: ${error.message}", error)
                else -> SshConnectException(SshFailure.PROTOCOL, "SSH error talking to $where: ${error.message}", error)
            }
        }
        return SshConnectException(SshFailure.IO, "Connection to $where failed: ${error.message ?: error.javaClass.simpleName}", error)
    }

    /**
     * The connection ended between the key exchange and the end of the login. The usual reason is
     * a server that limits how long logging in may take (OpenSSH: LoginGraceTime, two minutes by
     * default) and hangs up, mostly without a word, while the user is still typing a password.
     * That is not a wrong password and must not be reported as one.
     */
    private fun interruptedLogin(where: String, error: LoginInterruptedException): SshConnectException {
        val chain = causes(error)
        val transport = chain.firstOrNull { it is TransportException } as TransportException?
        // What the server said when it disconnected, if it said anything.
        val said = transport?.takeIf { it.disconnectReason != DisconnectReason.UNKNOWN }?.message?.trim().orEmpty()
        if (transport?.disconnectReason == DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE) {
            return SshConnectException(SshFailure.AUTH_FAILED, "$where closed the connection: $said", error)
        }
        val message = when {
            said.isNotEmpty() -> "$where closed the connection during login: $said"
            transport == null && chain.any { it is TimeoutException } -> "$where stopped answering during login"
            else -> "$where closed the connection during login. Servers do that when logging in takes too long."
        }
        return SshConnectException(SshFailure.IO, message, error)
    }

    private fun causes(error: Throwable): List<Throwable> = generateSequence(error) { it.cause }.take(8).toList()

    /** The connection broke, or the server stopped answering, in the middle of logging in. */
    private class LoginInterruptedException(cause: Throwable) : Exception(cause)
}
