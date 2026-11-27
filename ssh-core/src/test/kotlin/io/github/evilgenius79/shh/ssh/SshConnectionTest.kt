package io.github.evilgenius79.shh.ssh

import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End to end tests of the SSH layer against an SSH server running inside the test JVM. */
class SshConnectionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: TestSshServer
    private val hostKeys = InMemoryHostKeyStore()
    private val prompts = TestPrompts()
    private val open = ArrayList<AutoCloseable>()

    @Before
    fun setUp() {
        server = TestSshServer().start()
        server.passwords["alice"] = "wonderland"
    }

    @After
    fun tearDown() {
        for (resource in open.asReversed()) runCatching { resource.close() }
        server.close()
    }

    private fun target(
        user: String = "alice",
        password: String? = "wonderland",
        keys: List<LoadedKey> = emptyList(),
        on: TestSshServer = server,
    ) = SshTarget("127.0.0.1", on.port, user, keys, password?.toCharArray())

    private fun connect(target: SshTarget = target(), jumps: List<SshTarget> = emptyList()): SshConnection =
        SshConnector.connect(target, hostKeys, prompts, jumps, ConnectOptions(connectTimeoutMs = 5000)).also { open.add(it) }

    // ----- host keys -----

    @Test
    fun unknownHostKeyIsShownAndRemembered() {
        connect()
        val request = prompts.hostKeyRequests.single()
        assertEquals(HostKeyStatus.UNKNOWN, request.status)
        assertEquals("127.0.0.1", request.host)
        assertEquals(server.port, request.port)
        assertEquals("ecdsa-sha2-nistp256", request.key.type)
        assertTrue(request.key.fingerprint.startsWith("SHA256:"))
        assertEquals(listOf(request.key), hostKeys.find("127.0.0.1", server.port))

        // The second connection is silent.
        connect()
        assertEquals(1, prompts.hostKeyRequests.size)
    }

    @Test
    fun acceptingOnceDoesNotRememberTheKey() {
        prompts.hostKeyDecision = HostKeyDecision.ACCEPT_ONCE
        connect()
        assertTrue(hostKeys.find("127.0.0.1", server.port).isEmpty())
        connect()
        assertEquals(2, prompts.hostKeyRequests.size)
    }

    @Test
    fun rejectingTheHostKeyAbortsBeforeAnyCredentialIsSent() {
        prompts.hostKeyDecision = HostKeyDecision.REJECT
        val error = assertFailsWith<SshConnectException> { connect() }
        assertEquals(SshFailure.HOST_KEY_REJECTED, error.failure)
        assertTrue(server.passwordAttempts.isEmpty(), "no password may reach a server whose key was rejected")
    }

    @Test
    fun changedHostKeyIsReportedAsChanged() {
        connect().close()
        val port = server.port
        server.close()
        // Same address, different host key: what a reinstalled server or an interception looks like.
        server = TestSshServer(port = port).start()
        server.passwords["alice"] = "wonderland"

        prompts.hostKeyDecision = HostKeyDecision.REJECT
        val error = assertFailsWith<SshConnectException> { connect() }
        assertEquals(SshFailure.HOST_KEY_REJECTED, error.failure)
        val request = prompts.hostKeyRequests.last()
        assertEquals(HostKeyStatus.CHANGED, request.status)
        assertEquals(1, request.knownKeys.size)
        assertTrue(server.passwordAttempts.isEmpty())

        // Accepting replaces the stored key.
        prompts.hostKeyDecision = HostKeyDecision.ACCEPT_AND_REMEMBER
        connect()
        assertEquals(listOf(prompts.hostKeyRequests.last().key), hostKeys.find("127.0.0.1", port))
    }

    @Test
    fun aKnownKeyTypeIsPreferredWhenTheServerHasSeveral() {
        // Trust is recorded for the server's ECDSA key. A second server process with the same
        // ECDSA key plus an RSA key must still be recognised, whichever it lists first.
        val ec = TestSshServer.newEcKeyPair()
        server.close()
        server = TestSshServer(hostKey = ec).start()
        server.passwords["alice"] = "wonderland"
        connect()
        assertEquals(1, prompts.hostKeyRequests.size)
        assertEquals("ecdsa-sha2-nistp256", prompts.hostKeyRequests[0].key.type)
    }

    // ----- password authentication -----

    @Test
    fun storedPasswordLogsInWithoutAsking() {
        val connection = connect()
        assertTrue(connection.isConnected)
        assertTrue(prompts.passwordRequests.isEmpty())
        assertTrue(connection.serverVersion.startsWith("APACHE-SSHD"), connection.serverVersion)
    }

    @Test
    fun missingPasswordIsRequestedFromTheUser() {
        prompts.passwords.add("wonderland")
        connect(target(password = null))
        assertEquals(listOf(1), prompts.passwordRequests)
    }

    @Test
    fun wrongStoredPasswordFallsBackToAskingAndRetries() {
        prompts.passwords.addAll(listOf("nope", "wonderland"))
        connect(target(password = "stale"))
        assertEquals(listOf(1, 2), prompts.passwordRequests)
        assertEquals(listOf("stale", "nope", "wonderland"), server.passwordAttempts.toList())
    }

    @Test
    fun passwordAttemptsAreLimited() {
        prompts.passwords.addAll(listOf("a", "b", "c", "d", "e"))
        val error = assertFailsWith<SshConnectException> { connect(target(password = null)) }
        assertEquals(SshFailure.AUTH_FAILED, error.failure)
        assertEquals(listOf(1, 2, 3), prompts.passwordRequests)
        assertTrue(error.serverAuthMethods.contains("password"))
    }

    /**
     * What OpenSSH does when logging in takes longer than its LoginGraceTime (two minutes by
     * default): it closes the connection without a word, typically while the user is still typing
     * the password. [noticedAfterMillis] is how much later the "user" submits it.
     */
    private fun serverHangsUpWhileThePasswordIsTyped(noticedAfterMillis: Long): SshConnectException {
        prompts.passwords.addAll(listOf("wonderland", "wonderland", "wonderland"))
        prompts.whilePasswordPromptIsOpen = {
            server.dropClients()
            Thread.sleep(noticedAfterMillis)
        }
        return assertFailsWith<SshConnectException> { connect(target(password = null)) }
    }

    /** The error and everything that caused it, for assertion messages. */
    private fun describe(error: Throwable): String =
        generateSequence(error) { it.cause }.take(8).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    @Test
    fun aServerThatHangsUpDuringLoginIsNotReportedAsAWrongPassword() {
        val error = serverHangsUpWhileThePasswordIsTyped(noticedAfterMillis = 500)
        assertEquals(SshFailure.IO, error.failure, describe(error))
        assertTrue(error.message!!.contains("closed the connection"), error.message)
        assertEquals(emptyList(), server.passwordAttempts.toList(), "the password never reached the server")
        assertEquals(listOf(1), prompts.passwordRequests, "nobody is asked again on a dead connection")
    }

    @Test
    fun aServerThatHangsUpAtTheMomentThePasswordIsSentIsReportedTheSameWay() {
        val error = serverHangsUpWhileThePasswordIsTyped(noticedAfterMillis = 0)
        assertEquals(SshFailure.IO, error.failure, describe(error))
        assertTrue(error.message!!.contains("closed the connection"), error.message)
        assertEquals(listOf(1), prompts.passwordRequests)
    }

    @Test
    fun aServerThatGivesUpWaitingForTheLoginIsQuotedInTheError() {
        // Other servers announce that they are hanging up. Their reason is passed on.
        server.loginTimeout(400)
        prompts.passwords.add("wonderland")
        prompts.whilePasswordPromptIsOpen = { Thread.sleep(1500) }
        val error = assertFailsWith<SshConnectException> { connect(target(password = null)) }
        assertEquals(SshFailure.IO, error.failure, describe(error))
        // This server's words for it; they are vague, but they are what it said.
        assertEquals("127.0.0.1:${server.port} closed the connection during login: Protocol error or corrupt packet", error.message)
        assertEquals(emptyList(), server.passwordAttempts.toList())
    }

    @Test
    fun dismissingThePasswordPromptCancels() {
        val error = assertFailsWith<SshConnectException> { connect(target(password = null)) }
        assertEquals(SshFailure.CANCELLED, error.failure)
    }

    // ----- which address to connect to -----

    private val loopback4 = java.net.InetAddress.getByAddress("server.test", byteArrayOf(127, 0, 0, 1))

    /** An IPv6 address where nothing listens: the server of these tests is bound to 127.0.0.1 only. */
    private val loopback6 = java.net.InetAddress.getByAddress("server.test", ByteArray(15) + 1)

    private fun resolving(vararg addresses: java.net.InetAddress, block: () -> Unit) {
        val before = SshConnector.resolver
        SshConnector.resolver = { name -> if (name == "server.test") arrayOf(*addresses) else before(name) }
        try {
            block()
        } finally {
            SshConnector.resolver = before
        }
    }

    private fun named(family: AddressFamily = AddressFamily.ANY) =
        SshTarget("server.test", server.port, "alice", emptyList(), "wonderland".toCharArray(), addressFamily = family)

    @Test
    fun aNameWithSeveralAddressesIsTriedUntilOneAnswers() = resolving(loopback6, loopback4) {
        // The first address does not answer, which is the usual fate of an IPv6 address on a
        // network that only pretends to have IPv6. The second one is the server.
        val connection = connect(named())
        assertEquals("hello", connection.execute("echo hello").stdout.trim())
        assertTrue(prompts.progress.any { it.contains("did not answer, trying 127.0.0.1") }, prompts.progress.toString())
        // The host key is remembered under the name, not under whichever address worked.
        assertEquals(1, hostKeys.find("server.test", server.port).size)
    }

    @Test
    fun theAddressFamilyCanBeForced() = resolving(loopback6, loopback4) {
        assertEquals("hello", connect(named(AddressFamily.IPV4)).execute("echo hello").stdout.trim())
        assertTrue(prompts.progress.none { it.contains("did not answer") }, "IPv4 only: the other address is not even tried")

        // IPv6 only: nothing listens there, or the machine has no IPv6 at all. Either way the
        // connection is not made, and the IPv4 address is not used behind the user's back.
        val error = assertFailsWith<SshConnectException> { connect(named(AddressFamily.IPV6)) }
        assertTrue(error.failure in setOf(SshFailure.REFUSED, SshFailure.UNREACHABLE, SshFailure.TIMEOUT, SshFailure.IO), error.message)
    }

    @Test
    fun aNameWithoutAnAddressOfTheWantedFamilySaysSo() = resolving(loopback4) {
        val error = assertFailsWith<SshConnectException> { connect(named(AddressFamily.IPV6)) }
        assertEquals(SshFailure.UNREACHABLE, error.failure)
        assertEquals("server.test has no IPv6 address", error.message)
    }

    @Test
    fun aServerThatAnswersButRefusesTheLoginIsNotTriedAtItsOtherAddresses() = resolving(loopback4, loopback4) {
        prompts.passwords.clear()
        val target = SshTarget("server.test", server.port, "alice", emptyList(), "wrong".toCharArray())
        val error = assertFailsWith<SshConnectException> { connect(target) }
        assertTrue(error.failure == SshFailure.CANCELLED || error.failure == SshFailure.AUTH_FAILED, error.message)
        assertTrue(prompts.progress.none { it.contains("did not answer") })
    }

    // ----- the agent: what it tells and what it signs -----

    private fun agentFor(vararg keys: LoadedKey): AgentForwarder {
        val connection = connect()
        // The forwarder needs a connection to exist; these tests talk to it directly.
        val field = SshConnection::class.java.getDeclaredField("clients").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val client = (field.get(connection) as List<net.schmizz.sshj.SSHClient>).last()
        return AgentForwarder(client.connection, { keys.toList() }) { key -> prompts.confirmAgentUse(target(), key) }
    }

    private fun request(type: Byte, body: net.schmizz.sshj.common.Buffer.PlainBuffer.() -> Unit = {}): ByteArray =
        net.schmizz.sshj.common.Buffer.PlainBuffer().putByte(type).also(body).compactData

    @Test
    fun theAgentListsItsKeysAndSignsOnlyWithPermission() {
        val ed = SshKeys.generate(KeyAlgorithm.ED25519)
        val ec = SshKeys.generate(KeyAlgorithm.ECDSA_256)
        val rsa = SshKeys.generate(KeyAlgorithm.RSA_3072)
        val agent = agentFor(ed, ec, rsa)

        // Listing: three keys, in order, and nobody is asked.
        val listing = net.schmizz.sshj.common.Buffer.PlainBuffer(agent.answer(request(AgentForwarder.REQUEST_IDENTITIES)))
        assertEquals(AgentForwarder.IDENTITIES_ANSWER, listing.readByte())
        assertEquals(3, listing.readUInt32AsInt())
        for (key in listOf(ed, ec, rsa)) {
            assertTrue(key.publicKey.blob.contentEquals(listing.readBytes()))
            listing.readString()
        }
        assertEquals(emptyList(), prompts.agentRequests.toList())

        // Signing: each signature verifies with the public key, under the algorithm asked for.
        val data = "data to sign".toByteArray()
        for ((key, flags, expected) in listOf(
            Triple(ed, 0, "ssh-ed25519"),
            Triple(ec, 0, "ecdsa-sha2-nistp256"),
            Triple(rsa, AgentForwarder.RSA_SHA2_512, "rsa-sha2-512"),
            Triple(rsa, AgentForwarder.RSA_SHA2_256, "rsa-sha2-256"),
        )) {
            val reply = net.schmizz.sshj.common.Buffer.PlainBuffer(
                agent.answer(request(AgentForwarder.SIGN_REQUEST) { putBytes(key.publicKey.blob).putBytes(data).putUInt32(flags.toLong()) }),
            )
            assertEquals(AgentForwarder.SIGN_RESPONSE, reply.readByte(), expected)
            val signature = net.schmizz.sshj.common.Buffer.PlainBuffer(reply.readBytes())
            assertEquals(expected, signature.readString())
            val verifier = net.schmizz.sshj.common.Factory.Named.Util.create(net.schmizz.sshj.DefaultConfig().keyAlgorithms, expected).newSignature()
            verifier.initVerify(key.keyPair.public)
            verifier.update(data)
            // What the library verifies is the signature as it travels: the name and the bytes.
            val wire = net.schmizz.sshj.common.Buffer.PlainBuffer().putString(expected).putBytes(signature.readBytes()).compactData
            assertTrue(verifier.verify(wire), "$expected signature verifies")
        }
        assertEquals(listOf(ed.publicKey, ec.publicKey, rsa.publicKey, rsa.publicKey), prompts.agentRequests.toList(), "asked every time")

        // Refused, unknown key, unknown request, garbage: a plain failure each time.
        prompts.allowAgent = false
        val refused = agent.answer(request(AgentForwarder.SIGN_REQUEST) { putBytes(ed.publicKey.blob).putBytes(data).putUInt32(0) })
        assertTrue(AgentForwarder.FAILURE_REPLY.contentEquals(refused))
        prompts.allowAgent = true
        val stranger = SshKeys.generate(KeyAlgorithm.ED25519)
        val unknown = agent.answer(request(AgentForwarder.SIGN_REQUEST) { putBytes(stranger.publicKey.blob).putBytes(data).putUInt32(0) })
        assertTrue(AgentForwarder.FAILURE_REPLY.contentEquals(unknown))
        assertTrue(AgentForwarder.FAILURE_REPLY.contentEquals(agent.answer(request(17))), "adding keys from the server side is not on offer")
        assertTrue(AgentForwarder.FAILURE_REPLY.contentEquals(agent.answer(byteArrayOf(AgentForwarder.SIGN_REQUEST, 0, 0))))
        assertTrue(AgentForwarder.FAILURE_REPLY.contentEquals(agent.answer(ByteArray(0))))
    }

    // ----- public key authentication -----

    @Test
    fun publicKeyLoginWithEveryKeyType() {
        for (algorithm in listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_256, KeyAlgorithm.ECDSA_384, KeyAlgorithm.ECDSA_521, KeyAlgorithm.RSA_3072)) {
            val key = SshKeys.generate(algorithm)
            server.authorizedKeys.clear()
            server.authorizedKeys.add(key.keyPair.public)
            val connection = connect(target(password = null, keys = listOf(key)))
            assertTrue(connection.isConnected, algorithm.name)
            connection.close()
        }
        assertTrue(prompts.passwordRequests.isEmpty())
        assertTrue(server.passwordAttempts.isEmpty())
    }

    @Test
    fun keysAreTriedInOrderUntilOneFits() {
        val wrong = SshKeys.generate(KeyAlgorithm.ED25519)
        val right = SshKeys.generate(KeyAlgorithm.ED25519)
        server.authorizedKeys.add(right.keyPair.public)
        assertTrue(connect(target(password = null, keys = listOf(wrong, right))).isConnected)
    }

    @Test
    fun aKeyReloadedFromItsOpenSshEncodingStillLogsIn() {
        val key = SshKeys.generate(KeyAlgorithm.ED25519)
        server.authorizedKeys.add(key.keyPair.public)
        val reloaded = SshKeys.load(SshKeys.toOpenSshPrivateKey(key, "c", "pw".toCharArray()), "pw".toCharArray())
        assertTrue(connect(target(password = null, keys = listOf(reloaded))).isConnected)
    }

    @Test
    fun rejectedKeyFallsBackToPassword() {
        val key = SshKeys.generate(KeyAlgorithm.ED25519)
        assertTrue(connect(target(keys = listOf(key))).isConnected)
        assertEquals(listOf("wonderland"), server.passwordAttempts.toList())
    }

    // ----- keyboard-interactive -----

    @Test
    fun keyboardInteractivePromptsReachTheUser() {
        server.passwords.clear()
        server.interactivePrompts = listOf("Verification code: " to true)
        server.interactiveAnswers = listOf("123456")
        prompts.answers.add("123456")
        connect(target(password = null))
        val challenge = prompts.challenges.single()
        assertEquals("Test login", challenge.name)
        assertEquals("Answer the questions", challenge.instruction)
        assertEquals("Verification code: ", challenge.prompts.single().text)
        assertTrue(challenge.prompts.single().echo)
    }

    @Test
    fun storedPasswordAnswersAnInteractivePasswordPrompt() {
        server.passwords.clear()
        server.interactivePrompts = listOf("Password: " to false, "One-time code: " to false)
        server.interactiveAnswers = listOf("wonderland", "42")
        prompts.answers.add("42")
        connect()
        // Only the one-time code needed the user.
        assertEquals("One-time code: ", prompts.challenges.single().prompts.single().text)
    }

    @Test
    fun multiFactorLoginUsesTheKeyThenTheCode() {
        val key = SshKeys.generate(KeyAlgorithm.ED25519)
        server.authorizedKeys.add(key.keyPair.public)
        server.interactivePrompts = listOf("Code: " to false)
        server.interactiveAnswers = listOf("7")
        server.requireAuthMethods("publickey,keyboard-interactive")
        prompts.answers.add("7")
        assertTrue(connect(target(password = null, keys = listOf(key))).isConnected)
        assertEquals(1, prompts.challenges.size)
    }

    @Test
    fun wrongCredentialsFailWithAuthFailed() {
        val error = assertFailsWith<SshConnectException> { connect(target(user = "mallory", password = "x")) }
        // The stored password is refused, then the user gives up at the prompt.
        assertTrue(error.failure == SshFailure.CANCELLED || error.failure == SshFailure.AUTH_FAILED)
    }

    @Test
    fun bannerIsDelivered() {
        server.banner("Authorised users only\n")
        val connection = connect()
        assertEquals("Authorised users only", connection.banner?.trim())
        assertEquals("Authorised users only", prompts.banners.single().trim())
    }

    // ----- shell -----

    @Test
    fun shellReceivesTerminalParametersAndEchoes() {
        val shell = connect().openShell("xterm-256color", 132, 43, environment = mapOf("LANG" to "en_US.UTF-8"))
        val greeting = readUntil(shell.output, "\r\n")
        assertEquals("READY server 132x43 term=xterm-256color lang=en_US.UTF-8\r\n", greeting)
        shell.write("héllo wörld\n".toByteArray())
        assertEquals("ECHO:héllo wörld\r\n", readUntil(shell.output, "\r\n"))
        assertTrue(shell.isOpen)
    }

    @Test
    fun resizingTheShellReachesTheServer() {
        val shell = connect().openShell(columns = 80, rows = 24)
        readUntil(shell.output, "\r\n")
        shell.resize(100, 30)
        assertEquals("SIZE 100x30\r\n", readUntil(shell.output, "\r\n"))
    }

    @Test
    fun shellExitStatusIsReported() {
        val shell = connect().openShell(columns = 80, rows = 24)
        readUntil(shell.output, "\r\n")
        shell.write("exit 7\n".toByteArray())
        // Reading to the end of the stream means the channel has closed.
        val buffer = ByteArray(64)
        while (shell.output.read(buffer) >= 0) continue
        assertEquals(7, shell.awaitExit(5000))
        assertFalse(shell.isOpen)
    }

    @Test
    fun severalShellsShareOneConnection() {
        val connection = connect()
        val first = connection.openShell(columns = 80, rows = 24)
        val second = connection.openShell(columns = 90, rows = 25)
        assertTrue(readUntil(first.output, "\r\n").contains("80x24"))
        assertTrue(readUntil(second.output, "\r\n").contains("90x25"))
        first.close()
        second.write("still here\n".toByteArray())
        assertEquals("ECHO:still here\r\n", readUntil(second.output, "\r\n"))
    }

    // ----- exec -----

    @Test
    fun executeCollectsOutputAndStatus() {
        val connection = connect()
        val ok = connection.execute("echo hello")
        assertEquals("hello\n", ok.stdout)
        assertEquals(0, ok.exitStatus)
        assertTrue(ok.isSuccess)

        val failed = connection.execute("fail")
        assertEquals("it went wrong\n", failed.stderr)
        assertEquals(3, failed.exitStatus)
        assertFalse(failed.isSuccess)

        assertEquals("piped in", connection.execute("cat", stdin = "piped in".toByteArray()).stdout)
    }

    @Test
    fun aCommandThatFinishesBeforeItsInputIsClosedStillSucceeds() {
        // A command that exits at once closes its channel; if that happens before we get to
        // close its stdin, closing must not be reported as an error. The hook forces that order
        // (it happened by chance on a CI machine, through a jump host).
        val connection = connect()
        connection.beforeClosingStdin = { Thread.sleep(400) }
        val result = connection.execute("echo quick")
        assertEquals("quick\n", result.stdout)
        assertEquals(0, result.exitStatus)
        // With input the command never asked for, too.
        assertEquals("quick\n", connection.execute("echo quick", stdin = "ignored".toByteArray()).stdout)
    }

    // ----- lifecycle -----

    @Test
    fun closingNotifiesListenersOnce() {
        val connection = connect()
        val calls = ArrayList<String?>()
        connection.onDisconnect { calls.add(it) }
        connection.close()
        connection.close()
        assertEquals(1, calls.size)
        assertFalse(connection.isConnected)
        // A listener added afterwards still learns about it.
        var late = false
        connection.onDisconnect { late = true }
        assertTrue(late)
    }

    @Test
    fun losingTheServerNotifiesListeners() {
        val connection = connect()
        val shell = connection.openShell(columns = 80, rows = 24)
        readUntil(shell.output, "\r\n")
        val lost = CountDownLatch(1)
        connection.onDisconnect { lost.countDown() }
        server.dropClients()
        assertTrue(lost.await(10, TimeUnit.SECONDS), "disconnect must be noticed")
        assertFalse(connection.isConnected)
        // A reader blocked on the shell is released: either end of stream or an I/O error.
        val ended = try {
            shell.output.read() == -1
        } catch (_: java.io.IOException) {
            true
        }
        assertTrue(ended)
    }

    // ----- connection failures -----

    @Test
    fun refusedConnectionIsReportedAsRefused() {
        val unused = ServerSocket(0).use { it.localPort }
        val error = assertFailsWith<SshConnectException> {
            SshConnector.connect(SshTarget("127.0.0.1", unused, "alice"), hostKeys, prompts)
        }
        assertEquals(SshFailure.REFUSED, error.failure)
    }

    @Test
    fun unknownHostIsReportedAsUnknownHost() {
        val error = assertFailsWith<SshConnectException> {
            SshConnector.connect(SshTarget("no-such-host.invalid", 22, "alice"), hostKeys, prompts, options = ConnectOptions(connectTimeoutMs = 5000))
        }
        assertEquals(SshFailure.UNKNOWN_HOST, error.failure)
    }

    @Test
    fun aServerThatIsNotSshIsAProtocolError() {
        val http = ServerSocket(0)
        open.add(http)
        Thread {
            runCatching {
                http.accept().use { it.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()) }
            }
        }.apply { isDaemon = true }.start()
        val error = assertFailsWith<SshConnectException> {
            SshConnector.connect(SshTarget("127.0.0.1", http.localPort, "alice"), hostKeys, prompts)
        }
        assertTrue(error.failure == SshFailure.PROTOCOL || error.failure == SshFailure.IO, "was ${error.failure}")
    }

    @Test
    fun aServerThatHangsUpAtOnceIsExplained() {
        // What a client sees of an OpenSSH server that is turning its address away for a while
        // after failed logins, or that has too many logins under way.
        val door = ServerSocket(0)
        open.add(door)
        Thread {
            runCatching {
                while (true) door.accept().close()
            }
        }.apply { isDaemon = true }.start()
        val error = assertFailsWith<SshConnectException> {
            SshConnector.connect(SshTarget("127.0.0.1", door.localPort, "alice"), hostKeys, prompts)
        }
        assertEquals(SshFailure.IO, error.failure, "not a refusal of the login, and nothing a setting would cure")
        assertTrue(error.message!!.startsWith("127.0.0.1:${door.localPort} hung up at once."), error.message)
        assertTrue("wait a minute and try again" in error.message!!, error.message)
    }

    // ----- port forwarding -----

    @Test
    fun localForwardReachesTheTarget() {
        val echo = EchoServer().also { open.add(it) }
        val forward = connect().forwardLocal("127.0.0.1", 0, "127.0.0.1", echo.port)
        assertTrue(forward.boundPort > 0)
        assertEquals(PortForward.Kind.LOCAL, forward.kind)
        assertEquals("ping", roundTrip(forward.boundPort, "ping"))
        // Several connections at once, and a larger payload.
        val big = "x".repeat(200_000)
        assertEquals(big, roundTrip(forward.boundPort, big))
    }

    @Test
    fun localForwardSurvivesATargetThatRefuses() {
        val dead = ServerSocket(0).use { it.localPort }
        val connection = connect()
        val forward = connection.forwardLocal("127.0.0.1", 0, "127.0.0.1", dead)
        repeat(3) {
            Socket("127.0.0.1", forward.boundPort).use { socket ->
                socket.soTimeout = 5000
                assertEquals(-1, socket.getInputStream().read(), "the refused connection is closed, not left hanging")
            }
        }
        assertTrue(forward.isOpen)
        assertTrue(connection.isConnected)
    }

    @Test
    fun closingALocalForwardStopsListening() {
        val echo = EchoServer().also { open.add(it) }
        val connection = connect()
        val forward = connection.forwardLocal("127.0.0.1", 0, "127.0.0.1", echo.port)
        assertEquals(listOf(forward), connection.activeForwards)
        forward.close()
        assertFalse(forward.isOpen)
        assertTrue(connection.activeForwards.isEmpty())
        assertFailsWith<java.io.IOException> { Socket("127.0.0.1", forward.boundPort).close() }
    }

    @Test
    fun remoteForwardReachesTheDevice() {
        val echo = EchoServer().also { open.add(it) }
        val forward = connect().forwardRemote("127.0.0.1", 0, "127.0.0.1", echo.port)
        assertTrue(forward.boundPort > 0, "the server picks a port when 0 is requested")
        // The listening socket lives in the server, which here is this same machine.
        assertEquals("pong", roundTrip(forward.boundPort, "pong"))
        forward.close()
    }

    @Test
    fun dynamicForwardSpeaksSocks5() {
        val echo = EchoServer().also { open.add(it) }
        val forward = connect().forwardDynamic("127.0.0.1", 0)
        Socket("127.0.0.1", forward.boundPort).use { socket ->
            socket.soTimeout = 10_000
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            // CONNECT to a host name.
            val host = "localhost".toByteArray()
            out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()))
            out.write(host)
            out.writeShort(echo.port)
            val reply = ByteArray(10).also { input.readFully(it) }
            assertEquals(5, reply[0].toInt())
            assertEquals(0, reply[1].toInt(), "request granted")
            out.write("through socks".toByteArray())
            val back = ByteArray(13).also { input.readFully(it) }
            assertEquals("through socks", String(back))
        }
    }

    @Test
    fun dynamicForwardReportsARefusedTarget() {
        val dead = ServerSocket(0).use { it.localPort }
        val forward = connect().forwardDynamic("127.0.0.1", 0)
        Socket("127.0.0.1", forward.boundPort).use { socket ->
            socket.soTimeout = 10_000
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            input.readFully(ByteArray(2))
            out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1))
            out.writeShort(dead)
            val reply = ByteArray(10).also { input.readFully(it) }
            assertTrue(reply[1].toInt() != 0, "a failure code is returned")
        }
    }

    @Test
    fun dynamicForwardSpeaksSocks4a() {
        val echo = EchoServer().also { open.add(it) }
        val forward = connect().forwardDynamic("127.0.0.1", 0)
        Socket("127.0.0.1", forward.boundPort).use { socket ->
            socket.soTimeout = 10_000
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(4, 1))
            out.writeShort(echo.port)
            out.write(byteArrayOf(0, 0, 0, 1)) // 0.0.0.x: host name follows
            out.write("user\u0000localhost\u0000".toByteArray())
            val reply = ByteArray(8).also { input.readFully(it) }
            assertEquals(0x5A, reply[1].toInt())
            out.write("v4".toByteArray())
            val back = ByteArray(2).also { input.readFully(it) }
            assertEquals("v4", String(back))
        }
    }

    // ----- jump hosts -----

    @Test
    fun connectsThroughAJumpHost() {
        val inner = TestSshServer(name = "inner").start().also { open.add(it) }
        inner.passwords["bob"] = "builder"
        val connection = connect(
            target = SshTarget("127.0.0.1", inner.port, "bob", password = "builder".toCharArray()),
            jumps = listOf(target()),
        )
        // Both servers presented a host key.
        assertEquals(setOf(server.port, inner.port), prompts.hostKeyRequests.map { it.port }.toSet())
        val shell = connection.openShell(columns = 80, rows = 24)
        assertTrue(readUntil(shell.output, "\r\n").startsWith("READY inner"))
        assertTrue(prompts.progress.any { it.contains("through the jump host") })
    }

    @Test
    fun aFailingJumpTargetIsReportedAndLeavesNothingBehind() {
        val dead = ServerSocket(0).use { it.localPort }
        val error = assertFailsWith<SshConnectException> {
            connect(target = SshTarget("127.0.0.1", dead, "bob", password = "x".toCharArray()), jumps = listOf(target()))
        }
        assertEquals(SshFailure.UNREACHABLE, error.failure)
    }

    // ----- SFTP -----

    @Test
    fun sftpFileOperations() {
        val root = temp.newFolder("sftp").toPath()
        server.close()
        server = TestSshServer().also { it.enableSftp(root) }.start()
        server.passwords["alice"] = "wonderland"
        val sftp = connect().openSftp().also { open.add(it) }

        val home = sftp.homeDirectory()
        assertEquals("/", home)
        assertTrue(sftp.list("/").isEmpty())

        sftp.makeDirectory("/docs")
        sftp.writeText("/docs/readme.txt", "hello sftp\n")
        sftp.writeText("/b.txt", "b")
        sftp.writeText("/A.txt", "a")
        assertEquals("hello sftp\n", root.resolve("docs/readme.txt").toFile().readText())

        val listing = sftp.list("/")
        assertEquals(listOf("docs", "A.txt", "b.txt"), listing.map { it.name }, "directories first, then by name ignoring case")
        assertTrue(listing[0].isDirectory)
        assertEquals(SftpFileType.DIRECTORY, listing[0].type)
        assertEquals(SftpFileType.FILE, listing[1].type)
        assertEquals("/docs", listing[0].path)
        assertEquals(1L, listing[1].size)
        assertTrue(listing[1].modifiedSeconds > 0)
        assertTrue(listing[0].permissionString.startsWith("d"))

        assertEquals("hello sftp\n", sftp.readText("/docs/readme.txt"))
        assertEquals(11L, assertNotNull(sftp.stat("/docs/readme.txt")).size)
        assertNull(sftp.stat("/missing"))

        sftp.rename("/b.txt", "/docs/moved.txt")
        assertEquals(listOf("moved.txt", "readme.txt"), sftp.list("/docs").map { it.name })

        sftp.setPermissions("/A.txt", "600".toInt(8))
        assertEquals("600".toInt(8), assertNotNull(sftp.stat("/A.txt")).permissions and "777".toInt(8))
        assertEquals("-rw-------", assertNotNull(sftp.stat("/A.txt")).permissionString)

        sftp.delete("/docs")
        assertEquals(listOf("A.txt"), sftp.list("/").map { it.name })
        assertFalse(root.resolve("docs").toFile().exists())
    }

    @Test
    fun sftpTransfersReportProgressAndCanBeCancelled() {
        val root = temp.newFolder("sftp2").toPath()
        server.close()
        server = TestSshServer().also { it.enableSftp(root) }.start()
        server.passwords["alice"] = "wonderland"
        val sftp = connect().openSftp().also { open.add(it) }

        val payload = ByteArray(1_500_000) { (it * 31 % 251).toByte() }
        val uploaded = ArrayList<Long>()
        sftp.upload(payload.inputStream(), "/blob.bin", onProgress = { uploaded.add(it) })
        assertEquals(payload.size.toLong(), uploaded.last())
        assertTrue(uploaded.size > 1, "progress is reported as the transfer goes")
        assertTrue(payload.contentEquals(root.resolve("blob.bin").toFile().readBytes()))

        val sink = ByteArrayOutputStream()
        var seen = 0L
        sftp.download("/blob.bin", sink, onProgress = { seen = it })
        assertEquals(payload.size.toLong(), seen)
        assertTrue(payload.contentEquals(sink.toByteArray()))

        var chunks = 0
        assertFailsWith<TransferCancelledException> {
            sftp.download("/blob.bin", ByteArrayOutputStream(), isCancelled = { ++chunks > 3 })
        }
        // The session is still usable afterwards.
        assertEquals(listOf("blob.bin"), sftp.list("/").map { it.name })
        assertFailsWith<java.io.IOException> { sftp.readText("/blob.bin", maxBytes = 1000) }
    }
}
