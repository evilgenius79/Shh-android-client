package io.github.evilgenius79.shh.ssh

import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Interoperability with the real OpenSSH tools.
 *
 * The `ssh-keygen` tests run wherever that program is installed. The server tests additionally
 * need the two servers started by `tools/test-sshd.sh start` and the `SHH_TEST_OPENSSH`
 * environment variable it prints; without that they are skipped.
 */
class OpenSshInteropTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val hostKeys = InMemoryHostKeyStore()
    private val prompts = TestPrompts()
    private val open = ArrayList<AutoCloseable>()

    private class Servers(spec: String) {
        private val parts = spec.split('|')
        val host = parts[0]
        val port = parts[1].toInt()
        val legacyPort = parts[2].toInt()
        val user = parts[3]
        val password = parts[4]
        val authorizedKeys = File(parts[5])
    }

    private val servers: Servers? = System.getenv("SHH_TEST_OPENSSH")?.takeIf { it.isNotBlank() }?.let { Servers(it) }
    private var authorizedKeysBefore: String? = null

    @Before
    fun rememberAuthorizedKeys() {
        authorizedKeysBefore = servers?.authorizedKeys?.readText()
    }

    @After
    fun tearDown() {
        for (resource in open.asReversed()) runCatching { resource.close() }
        authorizedKeysBefore?.let { servers?.authorizedKeys?.writeText(it) }
    }

    private fun servers(): Servers {
        assumeTrue("SHH_TEST_OPENSSH is not set; see tools/test-sshd.sh", servers != null)
        return servers!!
    }

    private fun connect(
        keys: List<LoadedKey> = emptyList(),
        password: String? = servers().password,
        port: Int = servers().port,
        legacy: Boolean = false,
        jumps: List<SshTarget> = emptyList(),
        compression: Boolean = false,
    ): SshConnection {
        val s = servers()
        val target = SshTarget(s.host, port, s.user, keys, password?.toCharArray(), legacy)
        return SshConnector.connect(target, hostKeys, prompts, jumps, ConnectOptions(compression = compression)).also { open.add(it) }
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(30, TimeUnit.SECONDS)) { "${command.joinToString(" ")} timed out" }
        check(process.exitValue() == 0) { "${command.joinToString(" ")} failed: $output" }
        return output
    }

    private fun haveSshKeygen(): Boolean = try {
        ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    } catch (_: Exception) {
        false
    }

    private fun privateKeyFile(text: String): File {
        val file = temp.newFile()
        file.writeText(text)
        // ssh-keygen refuses private keys that other users can read.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        return file
    }

    // ----- certificates -----

    @Test
    fun aCertificateLogsInWhereTheKeyItselfIsUnknown() {
        assumeTrue(haveSshKeygen())
        val s = servers()
        val authority = File(s.authorizedKeys.parentFile, "user_ca")
        assumeTrue("The test server has no certificate authority; start it again with tools/test-sshd.sh", authority.exists())
        val authorityKey = privateKeyFile(authority.readText())

        /** Has the test authority sign [key] for the account [principal]. */
        fun certify(key: LoadedKey, principal: String): SshCertificate {
            val publicKey = temp.newFile("key-${System.nanoTime()}.pub")
            publicKey.writeText(key.publicKey.toAuthorizedKey("cert-test") + "\n")
            run("ssh-keygen", "-q", "-s", authorityKey.path, "-I", "issued-for-the-test", "-n", principal, "-V", "+10m", publicKey.path)
            return SshCertificate.parse(File(publicKey.path.removeSuffix(".pub") + "-cert.pub").readText())
        }

        for (algorithm in listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_256, KeyAlgorithm.ECDSA_521)) {
            val key = SshKeys.generate(algorithm)
            val certificate = certify(key, s.user)
            assertEquals("issued-for-the-test", certificate.keyId)
            assertEquals(listOf(s.user), certificate.principals)
            assertEquals(key.publicKey, certificate.publicKey)
            assertTrue(certificate.isValidAt() && !certificate.isHostCertificate)
            assertTrue(!certificate.isValidAt(java.util.Date(System.currentTimeMillis() + 3_600_000)), "valid for ten minutes, not for an hour")
            assertEquals(certificate.toLine(), SshCertificate.parse(certificate.toLine("a comment")).toLine(), "survives being written and read")

            // The key is in no authorized_keys file: on its own it is turned away.
            prompts.passwords.clear()
            assertFailsWith<SshConnectException>(algorithm.name) { connect(keys = listOf(key), password = null) }
            // With the certificate it is let in.
            val connection = connect(keys = listOf(key.withCertificate(certificate)), password = null)
            assertEquals(s.user, connection.execute("whoami").stdout.trim(), algorithm.name)
        }

        // A certificate for another account does not open this one.
        val key = SshKeys.generate(KeyAlgorithm.ED25519)
        prompts.passwords.clear()
        assertFailsWith<SshConnectException> { connect(keys = listOf(key.withCertificate(certify(key, "somebody-else"))), password = null) }
        // And a certificate cannot be attached to a key it was not issued for.
        val other = SshKeys.generate(KeyAlgorithm.ED25519)
        assertFailsWith<IllegalArgumentException> { other.withCertificate(certify(key, s.user)) }
        // What is not a certificate is refused when read.
        assertFailsWith<UnsupportedKeyException> { SshCertificate.parse(key.publicKey.toAuthorizedKey("plain key")) }
        assertFailsWith<UnsupportedKeyException> { SshCertificate.parse("ssh-ed25519-cert-v01@openssh.com AAAAnot-base64!") }
    }

    // ----- agent forwarding -----

    @Test
    fun programsOnTheServerCanLogInElsewhereThroughTheForwardedAgent() {
        val s = servers()
        val keys = listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_256, KeyAlgorithm.RSA_3072).map { SshKeys.generate(it) }
        val target = SshTarget(s.host, s.port, s.user, emptyList(), s.password.toCharArray(), agentKeys = { keys })
        val connection = SshConnector.connect(target, hostKeys, prompts, emptyList(), ConnectOptions()).also { open.add(it) }

        // The server's own tool sees the three keys. Listing lends nothing, so nobody is asked.
        val listing = connection.execute("ssh-add -l")
        assertTrue(connection.isAgentForwarded)
        for (key in keys) assertTrue(listing.stdout.contains(key.publicKey.fingerprint), "${key.description} is listed in:\n${listing.stdout}${listing.stderr}")
        assertEquals(emptyList(), prompts.agentRequests.toList())

        // From the server, log in to the server again with nothing but the agent to prove who
        // we are. Each kind of key in turn is the only one the server knows.
        val again = "ssh -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null " +
            "-o PreferredAuthentications=publickey -p ${s.port} ${s.user}@127.0.0.1 'echo through-agent-\$((3*3))'"
        for (key in keys) {
            s.authorizedKeys.writeText(key.publicKey.toAuthorizedKey("agent-test") + "\n")
            prompts.agentRequests.clear()
            val result = connection.execute(again)
            assertEquals("through-agent-9", result.stdout.trim(), "${key.description}: ${result.stderr}")
            assertEquals(listOf(key.publicKey), prompts.agentRequests.toList(), "asked once, for the key that was used")
        }

        // When the user says no, the login from the server fails.
        prompts.allowAgent = false
        assertTrue(!connection.execute(again).isSuccess)
        prompts.allowAgent = true

        // An interactive shell has the agent as well.
        val shell = connection.openShell(columns = 120, rows = 24)
        shell.write("ssh-add -l; echo listed-\$((5+5))\n".toByteArray())
        val seen = readUntil(shell.output, "listed-10")
        assertTrue(seen.contains(keys[0].publicKey.fingerprint), seen)
        shell.close()

        // And without agent forwarding there is no agent to talk to.
        val plain = connect().execute("ssh-add -l")
        assertTrue(!plain.isSuccess && !plain.stdout.contains("SHA256:"), plain.stdout + plain.stderr)
    }

    // ----- key files, checked with ssh-keygen -----

    @Test
    fun sshKeygenReadsTheKeysWeWrite() {
        assumeTrue(haveSshKeygen())
        for (algorithm in KeyAlgorithm.values().filter { it != KeyAlgorithm.RSA_4096 }) {
            val key = SshKeys.generate(algorithm)
            val file = privateKeyFile(SshKeys.toOpenSshPrivateKey(key, "written-by-shh"))
            // "ssh-keygen -y" derives the public key from the private key file.
            val derived = run("ssh-keygen", "-y", "-f", file.path).trim()
            assertEquals(key.publicKey.toAuthorizedKey("written-by-shh"), derived, algorithm.name)
        }
    }

    @Test
    fun sshKeygenDecryptsTheEncryptedKeysWeWrite() {
        assumeTrue(haveSshKeygen())
        for (algorithm in listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_384, KeyAlgorithm.RSA_3072)) {
            val key = SshKeys.generate(algorithm)
            val file = privateKeyFile(SshKeys.toOpenSshPrivateKey(key, "enc", "pass phrase".toCharArray()))
            val derived = run("ssh-keygen", "-y", "-P", "pass phrase", "-f", file.path).trim()
            assertEquals(key.publicKey.toAuthorizedKey("enc"), derived, algorithm.name)
            // And it must really be encrypted: the wrong passphrase is refused.
            assertFailsWith<IllegalStateException> { run("ssh-keygen", "-y", "-P", "wrong", "-f", file.path) }
        }
    }

    /**
     * Passphrases with characters outside ASCII. OpenSSH uses the bytes exactly as typed, which
     * on any modern system means UTF-8, so that is what we have to feed the key derivation.
     * The passphrase is handed to ssh-keygen as octal escapes through the shell so the result
     * does not depend on the locale the JVM happens to run in.
     */
    @Test
    fun nonAsciiPassphrasesAreInterchangeableWithOpenSsh() {
        assumeTrue(haveSshKeygen())
        val passphrase = "pässwörd €"
        val octal = passphrase.toByteArray(Charsets.UTF_8).joinToString("") { "\\%03o".format(it.toInt() and 0xFF) }

        // Written by us, read by OpenSSH.
        val key = SshKeys.generate(KeyAlgorithm.ED25519)
        val ours = privateKeyFile(SshKeys.toOpenSshPrivateKey(key, "utf8", passphrase.toCharArray()))
        val derived = run("sh", "-c", "ssh-keygen -y -P \"\$(printf '$octal')\" -f '${ours.path}'").trim()
        assertEquals(key.publicKey.toAuthorizedKey("utf8"), derived)

        // Written by OpenSSH, read by us.
        val theirs = File(temp.root, "utf8-key")
        run("sh", "-c", "ssh-keygen -q -t ed25519 -N \"\$(printf '$octal')\" -f '${theirs.path}'")
        val expected = assertNotNull(SshPublicKey.parse(File(theirs.path + ".pub").readText()))
        assertEquals(expected, SshKeys.load(theirs.readText(), passphrase.toCharArray()).publicKey)
        assertFailsWith<WrongPassphraseException> { SshKeys.load(theirs.readText(), "passwoerd".toCharArray()) }
    }

    @Test
    fun fingerprintsMatchSshKeygen() {
        assumeTrue(haveSshKeygen())
        val key = SshKeys.generate(KeyAlgorithm.ED25519)
        val file = temp.newFile("id.pub")
        file.writeText(key.publicKey.toAuthorizedKey("fp") + "\n")
        assertTrue(run("ssh-keygen", "-l", "-f", file.path).contains(key.publicKey.fingerprint))
        assertTrue(run("ssh-keygen", "-l", "-E", "md5", "-f", file.path).contains("MD5:" + key.publicKey.md5Fingerprint))
    }

    @Test
    fun weReadTheKeysSshKeygenWrites() {
        assumeTrue(haveSshKeygen())
        // type, extra arguments, passphrase
        val cases = listOf(
            Triple("ed25519", emptyList(), ""),
            Triple("ed25519", emptyList(), "s3cret"),
            Triple("rsa", listOf("-b", "3072"), ""),
            Triple("rsa", listOf("-b", "3072"), "s3cret"),
            Triple("rsa", listOf("-b", "3072", "-m", "PEM"), ""),
            Triple("rsa", listOf("-b", "3072", "-m", "PEM"), "s3cret"),
            Triple("rsa", listOf("-b", "3072", "-m", "PKCS8"), ""),
            Triple("rsa", listOf("-b", "3072", "-m", "PKCS8"), "s3cret"),
            Triple("ecdsa", listOf("-b", "256"), ""),
            Triple("ecdsa", listOf("-b", "384"), "s3cret"),
            Triple("ecdsa", listOf("-b", "521", "-m", "PEM"), ""),
            Triple("ecdsa", listOf("-b", "256", "-m", "PEM"), "s3cret"),
            Triple("ecdsa", listOf("-b", "256", "-m", "PKCS8"), ""),
        )
        for ((index, case) in cases.withIndex()) {
            val (type, extra, passphrase) = case
            val label = "$type $extra passphrase=${passphrase.isNotEmpty()}"
            val file = File(temp.root, "k$index")
            run("ssh-keygen", "-q", "-t", type, *extra.toTypedArray(), "-N", passphrase, "-C", "made-by-openssh", "-f", file.path)
            val expected = assertNotNull(SshPublicKey.parse(File(file.path + ".pub").readText()), label)
            val text = file.readText()

            assertEquals(passphrase.isNotEmpty(), SshKeys.isEncrypted(text), label)
            if (passphrase.isNotEmpty()) {
                assertFailsWith<PassphraseRequiredException>(label) { SshKeys.load(text) }
                assertFailsWith<WrongPassphraseException>(label) { SshKeys.load(text, "nope".toCharArray()) }
            }
            val loaded = SshKeys.load(text, passphrase.takeIf { it.isNotEmpty() }?.toCharArray())
            assertEquals(expected, loaded.publicKey, label)

            // Re-export in our writer and make sure OpenSSH derives the same public key again.
            val rewritten = privateKeyFile(SshKeys.toOpenSshPrivateKey(loaded, "round-trip"))
            assertEquals(expected.toAuthorizedKey("round-trip"), run("ssh-keygen", "-y", "-f", rewritten.path).trim(), label)
        }
    }

    // ----- a real OpenSSH server -----

    @Test
    fun passwordLoginAndServerIdentification() {
        val connection = connect()
        assertTrue(connection.serverVersion.startsWith("OpenSSH_"), connection.serverVersion)
        // OpenSSH offers Ed25519, RSA and ECDSA host keys here; our preference order picks Ed25519.
        assertEquals("ssh-ed25519", prompts.hostKeyRequests.single().key.type)
        // The fingerprint we show must be the one "ssh-keygen -l" prints for the host key.
        val expected = run("ssh-keygen", "-l", "-f", "/tmp/shh-test-sshd/host_ed25519.pub")
        assertTrue(expected.contains(prompts.hostKeyRequests.single().key.fingerprint), expected)
    }

    @Test
    fun wrongPasswordIsRejectedByTheRealServer() {
        servers() // skip here, outside assertFailsWith, when no test server is configured
        val error = assertFailsWith<SshConnectException> { connect(password = "definitely wrong") }
        assertTrue(error.failure == SshFailure.CANCELLED || error.failure == SshFailure.AUTH_FAILED, "was ${error.failure}")
    }

    @Test
    fun publicKeyLoginWithEveryKeyTypeWeGenerate() {
        val s = servers()
        for (algorithm in KeyAlgorithm.values().filter { it != KeyAlgorithm.RSA_4096 }) {
            val key = SshKeys.generate(algorithm)
            s.authorizedKeys.writeText(key.publicKey.toAuthorizedKey("interop") + "\n")
            val connection = connect(keys = listOf(key), password = null)
            assertEquals("ok-${algorithm.name}\n", connection.execute("echo ok-${algorithm.name}").stdout)
            connection.close()
        }
        assertTrue(prompts.passwordRequests.isEmpty(), "key login must not fall back to asking for a password")
    }

    @Test
    fun realTerminalHasTheRequestedSizeAndUtf8Mode() {
        val shell = connect().openShell("xterm-256color", 97, 31, environment = mapOf("LANG" to "C.UTF-8"))
        // The markers are written as MA''RK so that the terminal's echo of the command line does
        // not already contain them; only the command's real output does.
        shell.write("stty size; echo TERM=\$TERM LANG=\$LANG; echo IUTF8=\$(stty -a | tr ' ;' '\\n\\n' | grep -x -c iutf8); echo MA''RK1\n".toByteArray())
        val first = readUntil(shell.output, "MARK1\r\n")
        assertTrue(first.contains("31 97"), first)
        assertTrue(first.contains("TERM=xterm-256color LANG=C.UTF-8"), first)
        // "stty -a" lists the flag as "iutf8" when set and "-iutf8" when not.
        assertTrue(first.contains("IUTF8=1"), "the terminal must be in UTF-8 mode:\n$first")

        shell.resize(120, 40)
        shell.write("stty size; echo MA''RK2\n".toByteArray())
        val second = readUntil(shell.output, "MARK2\r\n")
        assertTrue(second.contains("40 120"), second)

        shell.write("exit 5\n".toByteArray())
        val buffer = ByteArray(256)
        while (shell.output.read(buffer) >= 0) continue
        assertEquals(5, shell.awaitExit(5000))
    }

    @Test
    fun executeRunsRealCommands() {
        val connection = connect()
        val result = connection.execute("printf 'out'; printf 'err' >&2; exit 4")
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertEquals(4, result.exitStatus)
        assertEquals("3\n", connection.execute("wc -l | tr -d ' '", stdin = "a\nb\nc\n".toByteArray()).stdout)
    }

    /**
     * A command that exits at once may close its channel before we get to close its stdin; that
     * must not be reported as an error. (This did fail on CI, through a jump host.)
     */
    @Test
    fun manyShortCommandsDirectlyAndThroughAJumpHost() {
        val s = servers()
        val direct = connect()
        repeat(150) { i -> assertEquals("d$i\n", direct.execute("echo d$i").stdout) }
        val jump = SshTarget(s.host, s.port, s.user, password = s.password.toCharArray())
        val jumped = connect(jumps = listOf(jump))
        repeat(150) { i -> assertEquals("j$i\n", jumped.execute("echo j$i").stdout) }
    }

    @Test
    fun compressionWorksAgainstOpenSsh() {
        val connection = connect(compression = true)
        val big = connection.execute("head -c 300000 /dev/zero | tr '\\0' 'z'").stdout
        assertEquals(300_000, big.length)
        assertTrue(big.all { it == 'z' })
    }

    @Test
    fun sftpAgainstOpenSsh() {
        val connection = connect()
        val sftp = connection.openSftp().also { open.add(it) }
        val home = sftp.homeDirectory()
        assertEquals("/home/${servers().user}", home)
        val dir = "$home/shh-interop-${System.nanoTime()}"
        sftp.makeDirectory(dir)
        try {
            val payload = ByteArray(700_000) { (it % 253).toByte() }
            sftp.upload(payload.inputStream(), "$dir/data.bin")
            assertEquals(payload.size.toLong(), assertNotNull(sftp.stat("$dir/data.bin")).size)
            // The server's own checksum of what arrived.
            val remoteSum = connection.execute("sha256sum '$dir/data.bin' | cut -d' ' -f1").stdout.trim()
            val localSum = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            assertEquals(localSum, remoteSum)

            val sink = ByteArrayOutputStream()
            sftp.download("$dir/data.bin", sink)
            assertTrue(payload.contentEquals(sink.toByteArray()))

            sftp.setPermissions("$dir/data.bin", "640".toInt(8))
            assertEquals("-rw-r-----", assertNotNull(sftp.stat("$dir/data.bin")).permissionString)

            connection.execute("mkdir '$dir/sub' && ln -s sub '$dir/link-to-dir' && ln -s data.bin '$dir/link-to-file' && ln -s nowhere '$dir/dangling'")
            val listing = sftp.list(dir).associateBy { it.name }
            assertEquals(setOf("sub", "link-to-dir", "link-to-file", "dangling", "data.bin"), listing.keys)
            assertTrue(listing.getValue("sub").isDirectory)
            assertTrue(listing.getValue("link-to-dir").isSymlink)
            assertTrue(listing.getValue("link-to-dir").isDirectory, "a link to a directory can be entered")
            assertTrue(listing.getValue("link-to-file").isSymlink)
            assertTrue(!listing.getValue("link-to-file").isDirectory)
            assertTrue(!listing.getValue("dangling").isDirectory)
            assertEquals("sub", sftp.readLink("$dir/link-to-dir"))
            assertEquals("$dir/sub", sftp.canonicalize("$dir/link-to-dir"))

            sftp.rename("$dir/data.bin", "$dir/sub/renamed.bin")
            assertEquals(listOf("renamed.bin"), sftp.list("$dir/sub").map { it.name })
        } finally {
            sftp.delete(dir)
        }
        assertEquals(null, sftp.stat(dir))
    }

    @Test
    fun deletingALinkLeavesItsTargetAlone() {
        val connection = connect()
        val sftp = connection.openSftp().also { open.add(it) }
        val dir = "${sftp.homeDirectory()}/shh-link-${System.nanoTime()}"
        sftp.makeDirectory(dir)
        try {
            connection.execute("mkdir '$dir/real' && echo keep > '$dir/real/file' && ln -s real '$dir/link'")
            sftp.delete("$dir/link")
            assertEquals("keep\n", sftp.readText("$dir/real/file"))
        } finally {
            sftp.delete(dir)
        }
    }

    @Test
    fun portForwardsAgainstOpenSsh() {
        val echo = EchoServer().also { open.add(it) }
        val connection = connect()
        val local = connection.forwardLocal("127.0.0.1", 0, "127.0.0.1", echo.port)
        assertEquals("local", roundTrip(local.boundPort, "local"))

        val remote = connection.forwardRemote("127.0.0.1", 0, "127.0.0.1", echo.port)
        assertTrue(remote.boundPort > 0)
        assertEquals("remote", roundTrip(remote.boundPort, "remote"))

        val dynamic = connection.forwardDynamic("127.0.0.1", 0)
        java.net.Socket("127.0.0.1", dynamic.boundPort).use { socket ->
            socket.soTimeout = 10_000
            val out = java.io.DataOutputStream(socket.getOutputStream())
            val input = java.io.DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            input.readFully(ByteArray(2))
            out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1))
            out.writeShort(echo.port)
            val reply = ByteArray(10).also { input.readFully(it) }
            assertEquals(0, reply[1].toInt())
            out.write("dynamic".toByteArray())
            assertEquals("dynamic", String(ByteArray(7).also { input.readFully(it) }))
        }
    }

    @Test
    fun jumpThroughOpenSsh() {
        val s = servers()
        val jump = SshTarget(s.host, s.port, s.user, password = s.password.toCharArray())
        val connection = connect(jumps = listOf(jump))
        assertEquals("hop\n", connection.execute("echo hop").stdout)
    }

    @Test
    fun legacyServerNeedsLegacyAlgorithmsAndUsesKeyboardInteractive() {
        val s = servers()
        val refused = assertFailsWith<SshConnectException> { connect(port = s.legacyPort) }
        assertEquals(SshFailure.PROTOCOL, refused.failure, refused.message)

        // With legacy algorithms allowed it works. This server only offers keyboard-interactive
        // (PAM), whose "Password:" prompt is answered with the stored password.
        val connection = connect(port = s.legacyPort, legacy = true)
        assertEquals("legacy\n", connection.execute("echo legacy").stdout)
        assertEquals("ssh-rsa", prompts.hostKeyRequests.last().key.type)
        assertTrue(prompts.challenges.isEmpty(), "the stored password answered the prompt")
        assertTrue(prompts.passwordRequests.isEmpty())
    }

    @Test
    fun legacyServerPromptReachesTheUserWhenNoPasswordIsStored() {
        val s = servers()
        prompts.answers.add(s.password)
        val connection = connect(port = s.legacyPort, legacy = true, password = null)
        assertTrue(connection.isConnected)
        val prompt = prompts.challenges.single().prompts.single()
        assertTrue(prompt.text.contains("assword"), prompt.text)
        assertTrue(!prompt.echo)
    }
}
