package io.github.evilgenius79.shh

import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.Backup
import io.github.evilgenius79.shh.data.BackupException
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.ForwardRule
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.Identity
import io.github.evilgenius79.shh.data.KnownHost
import io.github.evilgenius79.shh.data.KnownHostsAdapter
import io.github.evilgenius79.shh.data.Snippet
import io.github.evilgenius79.shh.data.ThemeMode
import io.github.evilgenius79.shh.ssh.KeyAlgorithm
import io.github.evilgenius79.shh.ssh.SshKeys
import io.github.evilgenius79.shh.ui.installKeyCommand
import io.github.evilgenius79.shh.ui.parseQuickConnect
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val direct = Executor { it.run() }

    private fun store(file: File = File(temp.root, "shh.json")) = AppStore(file, direct)

    @Test
    fun startsEmptyAndPersistsChanges() {
        val file = File(temp.root, "shh.json")
        val first = store(file)
        assertEquals(AppData(), first.data.value)
        first.saveHost(Host(id = "h1", name = "Pi", host = "pi.local", username = "pi", port = 2222))
        first.saveSnippet(Snippet("s1", "Update", "sudo apt update"))
        first.updateSettings { it.copy(themeMode = ThemeMode.DARK, fontSizeSp = 15) }

        val second = store(file)
        assertEquals(first.data.value, second.data.value)
        assertEquals("pi@pi.local:2222", second.data.value.hosts.single().address)
        assertEquals("Pi", second.data.value.hosts.single().title)
        assertEquals(ThemeMode.DARK, second.data.value.settings.themeMode)
        assertNull(second.loadError)
    }

    @Test
    fun savingAHostWithAnExistingIdReplacesIt() {
        val s = store()
        s.saveHost(Host(id = "h1", host = "a"))
        s.saveHost(Host(id = "h2", host = "b"))
        s.saveHost(Host(id = "h1", host = "changed"))
        assertEquals(listOf("changed", "b"), s.data.value.hosts.map { it.host })
    }

    @Test
    fun deletingAHostDetachesJumpReferences() {
        val s = store()
        s.saveHost(Host(id = "bastion", host = "bastion"))
        s.saveHost(Host(id = "inner", host = "inner", jumpHostId = "bastion"))
        s.deleteHost("bastion")
        assertNull(s.data.value.hosts.single().jumpHostId)
    }

    @Test
    fun deletingAKeyDetachesItFromHosts() {
        val s = store()
        s.saveIdentity(Identity("k1", "key", "Ed25519", "ssh-ed25519 AAAA", "SHA256:x", "enc"))
        s.saveHost(Host(id = "h", host = "h", identityId = "k1"))
        s.deleteIdentity("k1")
        assertTrue(s.data.value.identities.isEmpty())
        assertNull(s.data.value.hosts.single().identityId)
    }

    @Test
    fun unreadableFileIsKeptAsideInsteadOfBeingOverwritten() {
        val file = File(temp.root, "shh.json")
        file.writeText("{ this is not json")
        val s = store(file)
        assertEquals(AppData(), s.data.value)
        assertNotNull(s.loadError)
        val aside = temp.root.listFiles()!!.single { it.name.startsWith("shh.json.corrupt-") }
        assertEquals("{ this is not json", aside.readText())
    }

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        val file = File(temp.root, "shh.json")
        file.writeText("""{"version":7,"futureThing":{"a":1},"hosts":[{"id":"h","host":"x","shiny":true}]}""")
        val s = store(file)
        assertEquals("x", s.data.value.hosts.single().host)
        assertNull(s.loadError)
    }

    @Test
    fun knownHostsAdapterStoresOneKeyPerType() {
        val s = store()
        val adapter = KnownHostsAdapter(s)
        val first = SshKeys.generate(KeyAlgorithm.ED25519).publicKey
        val second = SshKeys.generate(KeyAlgorithm.ED25519).publicKey
        val other = SshKeys.generate(KeyAlgorithm.ECDSA_256).publicKey
        adapter.remember("example.org", 22, first)
        adapter.remember("example.org", 22, other)
        adapter.remember("example.org", 22, second)
        assertEquals(setOf(second, other), adapter.find("example.org", 22).toSet())
        assertTrue(adapter.find("example.org", 2222).isEmpty())
        s.forgetHostKey(s.data.value.knownHosts.first { it.keyType == "ssh-ed25519" })
        assertEquals(listOf(other), adapter.find("example.org", 22))
    }

    @Test
    fun forwardRuleSummariesUseSshNotation() {
        assertEquals("-L 127.0.0.1:8080:db:5432", ForwardRule("1", ForwardKind.LOCAL, "127.0.0.1", 8080, "db", 5432).summary)
        assertEquals("-R 0.0.0.0:9000:localhost:3000", ForwardRule("2", ForwardKind.REMOTE, "0.0.0.0", 9000, "localhost", 3000).summary)
        assertEquals("-D 127.0.0.1:1080", ForwardRule("3", ForwardKind.DYNAMIC, bindPort = 1080).summary)
    }

    // ----- backup -----

    private fun sampleData(secrets: TestSecretBox): AppData {
        val key = SshKeys.generate(KeyAlgorithm.ED25519)
        return AppData(
            hosts = listOf(
                Host(id = "h1", host = "one", username = "u", encryptedPassword = secrets.encryptText("hunter2"), identityId = "k1"),
                Host(id = "h2", host = "two"),
            ),
            identities = listOf(
                Identity("k1", "main", "Ed25519", key.publicKey.toAuthorizedKey(), key.publicKey.fingerprint, secrets.encryptText(SshKeys.toOpenSshPrivateKey(key, "main"))),
            ),
            knownHosts = listOf(KnownHost("one", 22, "ssh-ed25519", key.publicKey.base64, 5)),
            snippets = listOf(Snippet("s", "n", "c")),
        )
    }

    @Test
    fun backupCanBeRestoredOnAnotherDevice() {
        val oldDevice = TestSecretBox("old")
        val newDevice = TestSecretBox("new")
        val data = sampleData(oldDevice)
        val backup = Backup.export(data, oldDevice, "correct horse".toCharArray())

        assertTrue(backup.startsWith("SHH-BACKUP-1\n"))
        assertFalse(backup.contains("hunter2"), "secrets must not be readable in the backup")
        assertFalse(backup.contains("OPENSSH"), "the private key must not be readable in the backup")

        val restored = Backup.import(backup, newDevice, "correct horse".toCharArray())
        assertEquals("hunter2", newDevice.decryptText(restored.hosts[0].encryptedPassword!!))
        assertNull(restored.hosts[1].encryptedPassword)
        val restoredKey = SshKeys.load(newDevice.decryptText(restored.identities.single().encryptedPrivateKey))
        assertEquals(data.identities.single().fingerprint, restoredKey.publicKey.fingerprint)
        assertEquals(data.knownHosts, restored.knownHosts)
        assertEquals(data.snippets, restored.snippets)
        // The secrets were re-encrypted for the new device, not copied.
        assertNotEquals(data.hosts[0].encryptedPassword, restored.hosts[0].encryptedPassword)
    }

    @Test
    fun backupRejectsWrongPassphraseAndTampering() {
        val box = TestSecretBox()
        val backup = Backup.export(sampleData(box), box, "right passphrase".toCharArray())
        assertFailsWith<BackupException> { Backup.import(backup, box, "wrong passphrase".toCharArray()) }
        assertFailsWith<BackupException> { Backup.import("hello", box, "x".toCharArray()) }
        assertFailsWith<BackupException> { Backup.import("SHH-BACKUP-1\n!!!", box, "x".toCharArray()) }
        // Flip one character in the middle of the ciphertext.
        val lines = backup.lines().toMutableList()
        val target = lines[2]
        lines[2] = target.substring(0, 10) + (if (target[10] == 'A') 'B' else 'A') + target.substring(11)
        assertFailsWith<BackupException> { Backup.import(lines.joinToString("\n"), box, "right passphrase".toCharArray()) }
    }

    @Test
    fun twoBackupsOfTheSameDataDiffer() {
        val box = TestSecretBox()
        val data = sampleData(box)
        assertNotEquals(Backup.export(data, box, "p4ssphrase".toCharArray()), Backup.export(data, box, "p4ssphrase".toCharArray()))
    }

    // ----- small pure functions -----

    @Test
    fun quickConnectParsing() {
        fun parse(text: String) = parseQuickConnect(text)?.let { Triple(it.username, it.host, it.port) }
        assertEquals(Triple("root", "example.org", 22), parse("root@example.org"))
        assertEquals(Triple("", "example.org", 22), parse("example.org"))
        assertEquals(Triple("me", "10.0.0.5", 2222), parse("  me@10.0.0.5:2222 "))
        assertEquals(Triple("me", "host", 22), parse("ssh me@host"))
        assertEquals(Triple("me", "host", 2200), parse("ssh://me@host:2200"))
        assertEquals(Triple("root", "2001:db8::1", 22), parse("root@[2001:db8::1]"))
        assertEquals(Triple("root", "2001:db8::1", 2222), parse("root@[2001:db8::1]:2222"))
        assertEquals(Triple("", "fe80::1", 22), parse("fe80::1"))
        assertEquals(Triple("user@corp", "host", 22), parse("user@corp@host"))
        assertNull(parse(""))
        assertNull(parse("two words"))
        assertNull(parse("host:notaport"))
        assertNull(parse("host:70000"))
        assertNull(parse("user@"))
        assertNull(parse("[::1"))
    }

    @Test
    fun installKeyCommandQuotesTheKeyLine() {
        val command = installKeyCommand("ssh-ed25519 AAAA it's mine")
        assertTrue(command.contains("""'ssh-ed25519 AAAA it'\''s mine'"""))
        assertTrue(command.startsWith("umask 077 && mkdir -p ~/.ssh"))
        // Runs for real in a scratch HOME: installs once, stays a single line when run twice.
        val home = temp.newFolder("home")
        repeat(2) {
            val process = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = home.path }.redirectErrorStream(true).start()
            assertEquals(0, process.waitFor(), process.inputStream.readBytes().toString(Charsets.UTF_8))
        }
        val authorized = File(home, ".ssh/authorized_keys")
        assertEquals("ssh-ed25519 AAAA it's mine\n", authorized.readText())
        val permissions = java.nio.file.Files.getPosixFilePermissions(authorized.toPath())
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(permissions))
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(File(home, ".ssh").toPath())))
    }
}
