package io.github.evilgenius79.shh

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.KeystoreSecretBox
import io.github.evilgenius79.shh.data.KnownHostsAdapter
import io.github.evilgenius79.shh.session.SessionEnvironment
import io.github.evilgenius79.shh.session.SessionPrompt
import io.github.evilgenius79.shh.session.SessionService
import io.github.evilgenius79.shh.session.SessionState
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.ConnectOptions
import io.github.evilgenius79.shh.ssh.HostKeyDecision
import io.github.evilgenius79.shh.ssh.HostKeyRequest
import io.github.evilgenius79.shh.ssh.InMemoryHostKeyStore
import io.github.evilgenius79.shh.ssh.InteractiveChallenge
import io.github.evilgenius79.shh.ssh.KeyAlgorithm
import io.github.evilgenius79.shh.ssh.SshConnector
import io.github.evilgenius79.shh.ssh.SshKeys
import io.github.evilgenius79.shh.ssh.SshPrompts
import io.github.evilgenius79.shh.ssh.SshSecurity
import io.github.evilgenius79.shh.ssh.SshTarget
import io.github.evilgenius79.shh.ui.installKeyCommand
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.Security
import javax.crypto.KeyAgreement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Checks the things that only a real Android runtime can answer: the cryptography provider swap,
 * the Android Keystore, the foreground service, and SSH itself running on ART.
 *
 * The SSH tests need a server the device can reach, passed as instrumentation arguments:
 *
 *     adb shell am instrument -w \
 *         -e sshHost 10.0.2.2 -e sshPort 2222 -e sshUser shhtest -e sshPassword ... \
 *         -e authorizedKeys /tmp/shh-test-sshd/authorized_keys \
 *         io.github.evilgenius79.shh.test/androidx.test.runner.AndroidJUnitRunner
 *
 * (10.0.2.2 is how an emulator reaches the machine it runs on; `tools/test-sshd.sh start` sets up
 * a matching server.) Without those arguments the SSH tests are skipped.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val arguments get() = InstrumentationRegistry.getArguments()

    private class Server(val host: String, val port: Int, val user: String, val password: String, val authorizedKeys: String?)

    private fun server(): Server {
        val host = arguments.getString("sshHost")
        assumeTrue("no SSH server given (see the class comment)", host != null)
        return Server(
            host!!,
            arguments.getString("sshPort", "22").toInt(),
            arguments.getString("sshUser")!!,
            arguments.getString("sshPassword")!!,
            arguments.getString("authorizedKeys"),
        )
    }

    private object AcceptAll : SshPrompts {
        override fun confirmHostKey(request: HostKeyRequest) = HostKeyDecision.ACCEPT_AND_REMEMBER
        override fun requestPassword(target: SshTarget, attempt: Int): CharArray? = null
        override fun answerChallenge(challenge: InteractiveChallenge): List<String>? = null
    }

    private fun waitFor(what: String, timeoutMs: Long = 120_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for: $what" }
            Thread.sleep(50)
        }
    }

    // ----- cryptography on the device -----

    @Test
    fun bouncyCastleProviderIsTheBundledOne() {
        SshSecurity.install()
        val provider = Security.getProvider("BC")
        // Android's own cut down provider is repackaged under com.android.org.bouncycastle.
        assertEquals("org.bouncycastle.jce.provider.BouncyCastleProvider", provider.javaClass.name)
        assertEquals(context.classLoader, provider.javaClass.classLoader)
        // Algorithms the platform provider lacks and SSH needs.
        KeyAgreement.getInstance("X25519", "BC")
        java.security.Signature.getInstance("Ed25519", "BC")
        java.security.KeyPairGenerator.getInstance("Ed25519", "BC")
    }

    @Test
    fun keysCanBeGeneratedAndReloaded() {
        for (algorithm in listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_256, KeyAlgorithm.ECDSA_521)) {
            val key = SshKeys.generate(algorithm)
            val plain = SshKeys.load(SshKeys.toOpenSshPrivateKey(key, "device"))
            assertEquals(key.publicKey, plain.publicKey, algorithm.name)
            val encrypted = SshKeys.toOpenSshPrivateKey(key, "device", "pass".toCharArray())
            assertTrue(SshKeys.isEncrypted(encrypted))
            assertEquals(key.publicKey, SshKeys.load(encrypted, "pass".toCharArray()).publicKey, algorithm.name)
        }
    }

    @Test
    fun keystoreEncryptionRoundTrip() {
        // BouncyCastle is installed at this point; the Keystore's own cipher must still be used.
        SshSecurity.install()
        val box = KeystoreSecretBox("shh-device-test")
        val secret = "pässword with ünicode €"
        val stored = box.encryptText(secret)
        assertTrue(!stored.contains("ssword"))
        assertEquals(secret, box.decryptText(stored))
        assertNotEquals(stored, box.encryptText(secret), "a fresh nonce is used every time")
        // A second box with the same alias reads it (the key persists in the Keystore).
        assertEquals(secret, KeystoreSecretBox("shh-device-test").decryptText(stored))
        // Tampering is detected.
        val tampered = stored.substring(0, stored.length - 4) + (if (stored.endsWith("AAAA")) "BBBB" else "AAAA")
        assertFailsWith<GeneralSecurityException> { box.decrypt(tampered) }
        assertFailsWith<GeneralSecurityException> { box.decrypt("not base64 !!") }
    }

    @Test
    fun appContainerIsUsable() {
        val container = (context as ShhApp).container
        val encrypted = container.secrets.encryptText("x")
        assertEquals("x", container.secrets.decryptText(encrypted))
        assertTrue(container.sessions.sessions.value.isEmpty())
    }

    // ----- the foreground service -----

    @Test
    fun foregroundServiceStartsWithItsDeclaredType() {
        val container = (context as ShhApp).container
        // A session that can never connect is enough to bring the service up.
        val session = container.sessions.open(Host(id = "nowhere", host = "127.0.0.1", port = 1, username = "nobody"))
        try {
            @Suppress("DEPRECATION")
            waitFor("the session service") {
                context.getSystemService(ActivityManager::class.java).getRunningServices(50)
                    .any { it.service.className == SessionService::class.java.name && it.foreground }
            }
        } finally {
            container.sessions.close(session)
        }
        @Suppress("DEPRECATION")
        waitFor("the session service to stop") {
            context.getSystemService(ActivityManager::class.java).getRunningServices(50)
                .none { it.service.className == SessionService::class.java.name }
        }
    }

    // ----- SSH from the device -----

    @Test
    fun passwordLoginShellExecAndSftp() {
        val s = server()
        val target = SshTarget(s.host, s.port, s.user, password = s.password.toCharArray())
        SshConnector.connect(target, InMemoryHostKeyStore(), AcceptAll, options = ConnectOptions(connectTimeoutMs = 60_000)).use { connection ->
            assertTrue(connection.serverVersion.startsWith("OpenSSH"), connection.serverVersion)
            assertEquals("on-device\n", connection.execute("echo on-device", timeoutSeconds = 120).stdout)

            val shell = connection.openShell(columns = 80, rows = 24)
            shell.write("echo sh''ell-\$((20+3)); exit\n".toByteArray())
            val seen = StringBuilder()
            val buffer = ByteArray(4096)
            while (true) {
                val n = shell.output.read(buffer)
                if (n < 0) break
                seen.append(String(buffer, 0, n))
            }
            assertTrue(seen.contains("shell-23"), seen.toString())

            connection.openSftp().use { sftp ->
                val path = sftp.homeDirectory() + "/shh-device-" + System.nanoTime()
                val payload = ByteArray(200_000) { (it % 251).toByte() }
                sftp.upload(payload.inputStream(), path)
                val back = ByteArrayOutputStream()
                sftp.download(path, back)
                assertTrue(payload.contentEquals(back.toByteArray()))
                sftp.delete(path)
            }
        }
    }

    @Test
    fun keyLoginWithAKeyGeneratedOnTheDevice() {
        val s = server()
        assumeTrue("authorizedKeys argument not given", s.authorizedKeys != null)
        val key = SshKeys.generate(KeyAlgorithm.ED25519)
        val line = key.publicKey.toAuthorizedKey("device-test")
        val store = InMemoryHostKeyStore()
        val withPassword = SshTarget(s.host, s.port, s.user, password = s.password.toCharArray())
        SshConnector.connect(withPassword, store, AcceptAll).use { connection ->
            // The test server reads its authorized keys from a file of its own.
            val result = connection.execute("printf '%s\\n' '$line' >> '${s.authorizedKeys}'", timeoutSeconds = 120)
            assertEquals(0, result.exitStatus, result.stderr)
        }
        try {
            SshConnector.connect(SshTarget(s.host, s.port, s.user, keys = listOf(key)), store, AcceptAll).use { connection ->
                assertEquals("by-key\n", connection.execute("echo by-key", timeoutSeconds = 120).stdout)
            }
        } finally {
            SshConnector.connect(withPassword, store, AcceptAll).use { connection ->
                connection.execute("grep -v 'device-test' '${s.authorizedKeys}' > '${s.authorizedKeys}.tmp'; cat '${s.authorizedKeys}.tmp' > '${s.authorizedKeys}'; rm -f '${s.authorizedKeys}.tmp'", timeoutSeconds = 120)
            }
        }
    }

    @Test
    fun terminalSessionWithRealStorageAndKeystore() {
        val s = server()
        val store = AppStore(File(context.cacheDir, "device-test-${System.nanoTime()}.json"))
        val secrets = KeystoreSecretBox("shh-device-test")
        val host = Host(id = "h", host = s.host, port = s.port, username = s.user, encryptedPassword = secrets.encryptText(s.password))
        store.saveHost(host)
        val session = TerminalSession("device", host, SessionEnvironment({ store.data.value }, secrets, KnownHostsAdapter(store)))
        try {
            session.connect()
            waitFor("the host key prompt") { session.prompt.value is SessionPrompt.HostKey }
            (session.prompt.value as SessionPrompt.HostKey).respond(HostKeyDecision.ACCEPT_AND_REMEMBER)
            waitFor("the connection") { session.isConnected }
            session.resize(80, 24, 10, 20)
            session.sendText("echo term-\$((5*5))\r")
            waitFor("command output in the emulator") { synchronized(session.emulator) { session.emulator.screenText() }.contains("term-25") }
            // The accepted host key went into the app's own known hosts.
            assertEquals(1, store.data.value.knownHosts.size)
            assertEquals(s.host, store.data.value.knownHosts.single().host)
            session.sendText("exit\r")
            waitFor("the end of the session") { session.state.value is SessionState.Disconnected }
        } finally {
            session.close()
        }
    }

    @Test
    fun installKeyCommandWorksOnARealServer() {
        val s = server()
        val target = SshTarget(s.host, s.port, s.user, password = s.password.toCharArray())
        SshConnector.connect(target, InMemoryHostKeyStore(), AcceptAll).use { connection ->
            val marker = "shh-install-test-${System.nanoTime()}"
            val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGXLQfyH6YrCXHL1wd5HkbGnpEqBJB6X6DlmDqNcYQ3r $marker"
            repeat(2) { assertEquals(0, connection.execute(installKeyCommand(line), timeoutSeconds = 120).exitStatus) }
            assertEquals("1\n", connection.execute("grep -c '$marker' ~/.ssh/authorized_keys", timeoutSeconds = 120).stdout)
            connection.execute("grep -v '$marker' ~/.ssh/authorized_keys > ~/.ssh/ak.tmp; cat ~/.ssh/ak.tmp > ~/.ssh/authorized_keys; rm ~/.ssh/ak.tmp", timeoutSeconds = 120)
        }
    }
}
