package io.github.evilgenius79.shh

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.ForwardRule
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.Identity
import io.github.evilgenius79.shh.data.InternetProtocol
import io.github.evilgenius79.shh.data.KnownHost
import io.github.evilgenius79.shh.data.Settings
import io.github.evilgenius79.shh.data.Snippet
import io.github.evilgenius79.shh.data.ThemeMode
import io.github.evilgenius79.shh.session.AgentAnswer
import io.github.evilgenius79.shh.session.SessionEnvironment
import io.github.evilgenius79.shh.session.SessionManager
import io.github.evilgenius79.shh.session.SessionPrompt
import io.github.evilgenius79.shh.session.SessionState
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.InMemoryHostKeyStore
import io.github.evilgenius79.shh.ssh.KeyAlgorithm
import io.github.evilgenius79.shh.ssh.SshFailure
import io.github.evilgenius79.shh.ssh.SshKeys
import io.github.evilgenius79.shh.ui.AgentUseDialog
import io.github.evilgenius79.shh.ui.DisconnectedBarContent
import io.github.evilgenius79.shh.ui.HelpScreen
import io.github.evilgenius79.shh.ui.HostEditScreen
import io.github.evilgenius79.shh.ui.HostsScreen
import io.github.evilgenius79.shh.ui.KeysScreen
import io.github.evilgenius79.shh.ui.KnownHostsScreen
import io.github.evilgenius79.shh.ui.ReconnectingBar
import io.github.evilgenius79.shh.ui.Screen
import io.github.evilgenius79.shh.ui.SettingsScreen
import io.github.evilgenius79.shh.ui.ShhRoot
import io.github.evilgenius79.shh.ui.ShhTheme
import io.github.evilgenius79.shh.ui.SnippetsScreen
import io.github.evilgenius79.shh.ui.TerminalScreen
import io.github.evilgenius79.shh.ui.describeCertificate
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the Compose screens the way a user would and saves a screenshot of each to
 * `app/build/outputs/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val secrets = TestSecretBox()
    private val dark = Settings(themeMode = ThemeMode.DARK, dynamicColor = false)
    private val light = Settings(themeMode = ThemeMode.LIGHT, dynamicColor = false)

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File("build/outputs/screenshots", "$name.png").path)
    }

    private fun show(settings: Settings = dark, content: @Composable () -> Unit) {
        compose.setContent { ShhTheme(settings, content) }
    }

    private val sampleKey by lazy { SshKeys.generate(KeyAlgorithm.ED25519) }

    private fun sampleData(settings: Settings = dark): AppData {
        val key = sampleKey
        return AppData(
            hosts = listOf(
                Host(id = "1", name = "Web server", host = "web01.example.org", username = "deploy", group = "Production", identityId = "k1"),
                Host(id = "2", name = "Database", host = "10.0.4.20", username = "postgres", group = "Production", jumpHostId = "1"),
                Host(id = "3", host = "raspberrypi.local", username = "pi", port = 2222),
                Host(id = "4", name = "Router", host = "192.168.1.1", username = "admin", allowLegacyAlgorithms = true),
            ),
            identities = listOf(
                Identity("k1", "Phone key", key.description, key.publicKey.toAuthorizedKey(), key.publicKey.fingerprint, secrets.encryptText("x"), 1),
            ),
            knownHosts = listOf(KnownHost("web01.example.org", 22, key.publicKey.type, key.publicKey.base64, 1)),
            snippets = listOf(Snippet("s1", "Disk usage", "df -h"), Snippet("s2", "Update packages", "sudo apt update && sudo apt upgrade")),
            settings = settings,
        )
    }

    private fun container(data: AppData): AppContainer {
        val store = AppStore(File(temp.root, "store-${System.nanoTime()}.json"), Executor { it.run() })
        store.replaceAll(data)
        return AppContainer(store, secrets, SessionManager(context, store, secrets, InMemoryHostKeyStore()))
    }

    // ----- host list -----

    @Test
    fun hostListShowsGroupsAndConnectsOnTap() {
        val connected = ArrayList<Host>()
        val navigated = ArrayList<Screen>()
        val data = sampleData()
        show {
            HostsScreen(data, emptyList(), { connected.add(it) }, {}, {}, {}, {}, {}, {}, { navigated.add(it) })
        }
        compose.onNodeWithText("Production").assertIsDisplayed()
        compose.onNodeWithText("Ungrouped").assertIsDisplayed()
        compose.onNodeWithText("via Web server").assertIsDisplayed()
        compose.onNodeWithText("pi@raspberrypi.local:2222").assertIsDisplayed()
        shot("hosts-dark")

        compose.onNodeWithText("Database").performClick()
        assertEquals(listOf("2"), connected.map { it.id })

        compose.onNodeWithContentDescription("Keys").performClick()
        assertEquals(listOf<Screen>(Screen.Keys), navigated)

        // The how-to is in the menu.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("How to use").performClick()
        assertEquals(listOf(Screen.Keys, Screen.Help), navigated)
    }

    @Test
    fun typingAnAddressOffersQuickConnect() {
        val quick = ArrayList<Host>()
        show(light) {
            HostsScreen(sampleData(light), emptyList(), {}, { quick.add(it) }, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText("Search or connect to user@host").performTextInput("root@203.0.113.9:2200")
        compose.onNodeWithText("Connect to root@203.0.113.9:2200").assertIsDisplayed()
        // None of the saved hosts match, so only the quick connect row is left.
        assertTrue(compose.onAllNodesWithTextCount("Web server") == 0)
        shot("hosts-quick-connect-light")
        compose.onNodeWithText("Connect to root@203.0.113.9:2200").performClick()
        assertEquals(Triple("root", "203.0.113.9", 2200), quick.single().let { Triple(it.username, it.host, it.port) })
    }

    @Test
    fun searchFiltersTheList() {
        show { HostsScreen(sampleData(), emptyList(), {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Search or connect to user@host").performTextInput("rasp")
        compose.onNodeWithText("pi@raspberrypi.local:2222").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("Database"))
    }

    @Test
    fun emptyHostListExplainsWhatToDo() {
        var added = 0
        show(light) { HostsScreen(AppData(settings = light), emptyList(), {}, {}, {}, {}, {}, {}, { added++ }, {}) }
        compose.onNodeWithText("No hosts yet").assertIsDisplayed()
        shot("hosts-empty-light")
        // The label sits inside the floating button, whose semantics are merged into the button.
        compose.onNodeWithText("Add host", useUnmergedTree = true).performClick()
        assertEquals(1, added)
    }

    // ----- host editor -----

    @Test
    fun hostEditorValidatesAndSaves() {
        var saved: Pair<Host, String?>? = null
        val data = sampleData()
        show {
            HostEditScreen(data, Host(id = "new", host = ""), isNew = true, hasSavedPassword = false, onSave = { h, p -> saved = h to p }, onBack = {})
        }
        // Saving an empty form is refused and the problems are pointed out.
        compose.onNodeWithText("Save").performClick()
        assertNull(saved)
        compose.onNodeWithText("Host name or IP address").assertIsDisplayed()

        compose.onNodeWithText("Address").performTextInput("build.example.org")
        compose.onNodeWithText("User name").performTextInput("ci")
        compose.onNodeWithText("Name (optional)").performTextInput("Build box")
        shot("host-edit-dark")
        compose.onNodeWithText("Save").performClick()
        val (host, password) = assertNotNull(saved)
        assertEquals("Build box", host.name)
        assertEquals("build.example.org", host.host)
        assertEquals("ci", host.username)
        assertEquals(22, host.port)
        assertNull(password, "the password was not touched")
    }

    @Test
    fun hostEditorOffersTheInternetProtocolAndAgentForwarding() {
        var saved: Host? = null
        val original = Host(id = "h", host = "router.lan", username = "root")
        show { HostEditScreen(sampleData(), original, isNew = false, hasSavedPassword = false, onSave = { h, _ -> saved = h }, onBack = {}) }

        // Untouched, a host keeps the cautious defaults.
        compose.onNodeWithText("Save").performClick()
        assertEquals(InternetProtocol.AUTOMATIC to false, assertNotNull(saved).let { it.internetProtocol to it.forwardAgent })

        compose.onNodeWithContentDescription("Internet protocol: Automatic (try each address)").performScrollTo().performClick()
        compose.onNodeWithText("IPv4 only").performClick()
        compose.onNodeWithText("Forward the SSH agent").performScrollTo().performClick()
        shot("host-edit-connection")
        compose.onNodeWithText("Save").performClick()
        assertEquals(InternetProtocol.IPV4 to true, assertNotNull(saved).let { it.internetProtocol to it.forwardAgent })

        // Mosh is something to switch on per host: it needs something on the server.
        assertTrue(!assertNotNull(saved).useMosh)
        compose.onNodeWithText("Use Mosh").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        assertTrue(assertNotNull(saved).useMosh)
    }

    @Test
    fun aServerThatWantsToUseAKeyIsPutToTheUser() {
        val answers = ArrayList<AgentAnswer>()
        fun question() = SessionPrompt.AgentUse(
            io.github.evilgenius79.shh.ssh.SshTarget("git-box.example.org", 22, "dev"),
            "Phone key",
            java.util.concurrent.CompletableFuture<AgentAnswer>().also { answer -> answer.thenAccept { answers.add(it) } },
        )
        var current by androidx.compose.runtime.mutableStateOf(question())
        show { AgentUseDialog(current) }
        compose.onNodeWithText("git-box.example.org asks to sign in somewhere else with your key \"Phone key\"", substring = true).assertIsDisplayed()
        shot("agent-use")
        compose.onNodeWithText("Allow once").performClick()
        current = question()
        compose.onNodeWithText("Allow for this session").performClick()
        current = question()
        compose.onNodeWithText("Deny").performClick()
        assertEquals(listOf(AgentAnswer.ONCE, AgentAnswer.SESSION, AgentAnswer.DENY), answers)
    }

    @Test
    fun aCertificateCanBeAddedToAKeyAndRemovedAgain() {
        org.junit.Assume.assumeTrue("ssh-keygen is needed to make a certificate", TestCertificates.available)
        val container = container(sampleData())
        fun stored() = container.store.data.value.identities.single().certificate
        val certificate = TestCertificates.certify(sampleKey, principal = "deploy")
        val forAnotherKey = TestCertificates.certify(SshKeys.generate(KeyAlgorithm.ED25519), principal = "deploy")

        show {
            val data by container.store.data.collectAsState()
            KeysScreen(container, data, onBack = {})
        }
        compose.onNodeWithText("Phone key").performClick()
        compose.onNodeWithText("Add a certificate…").performScrollTo().performClick()

        // What is not a certificate, or not one for this key, is refused with the reason.
        // (The field is found by being the one thing that can be typed into: the word
        // "Certificate" is also a heading in the dialog underneath.)
        val field = androidx.compose.ui.test.hasSetTextAction()
        compose.onNode(field).performTextInput("this is not a certificate")
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("This is not an SSH certificate").assertIsDisplayed()
        compose.onNode(field).performTextReplacement(forAnotherKey.toLine())
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("This certificate was issued for a different key").assertIsDisplayed()
        assertNull(stored())

        compose.onNode(field).performTextReplacement(certificate.toLine("deploy@laptop") + "\n")
        compose.onNodeWithText("Add").performClick()
        assertEquals(certificate.toLine(), stored())
        compose.onNodeWithText("\"issued-for-the-test\" for deploy, valid until", substring = true).performScrollTo().assertIsDisplayed()
        shot("key-with-certificate")

        compose.onNodeWithText("Remove certificate").performScrollTo().performClick()
        assertNull(stored())
        compose.onNodeWithText("Add a certificate…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun certificatesAreDescribedByWhatMattersAboutThem() {
        org.junit.Assume.assumeTrue(TestCertificates.available)
        val certificate = TestCertificates.certify(sampleKey, principal = "deploy,root", id = "laptop-2026")
        val now = java.util.Date()
        assertTrue(describeCertificate(context.resources, certificate, now).startsWith("\"laptop-2026\" for deploy, root, valid until "))
        assertTrue(describeCertificate(context.resources, certificate, java.util.Date(now.time + 3_600_000)).contains("expired on "))
        assertTrue(describeCertificate(context.resources, certificate, java.util.Date(now.time - 3_600_000)).endsWith("not valid yet"))
    }

    @Test
    fun hostEditorShowsExistingForwards() {
        val host = Host(
            id = "1", name = "Web server", host = "web01.example.org", username = "deploy",
            forwards = listOf(
                ForwardRule("f1", ForwardKind.LOCAL, "127.0.0.1", 5432, "db.internal", 5432),
                ForwardRule("f2", ForwardKind.DYNAMIC, bindPort = 1080),
            ),
        )
        show(light) { HostEditScreen(sampleData(light), host, isNew = false, hasSavedPassword = true, onSave = { _, _ -> }, onBack = {}) }
        compose.onNodeWithText("-L 127.0.0.1:5432:db.internal:5432").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("-D 127.0.0.1:1080").performScrollTo().assertIsDisplayed()
        shot("host-edit-forwards-light")
    }

    // ----- other screens -----

    @Test
    fun keysScreenListsKeys() {
        val data = sampleData()
        val container = container(data)
        show { KeysScreen(container, data, onBack = {}) }
        compose.onNodeWithText("Phone key").assertIsDisplayed()
        compose.onNodeWithText("Ed25519 · used by 1 host").assertIsDisplayed()
        shot("keys-dark")
    }

    @Test
    fun knownHostsAndSnippets() {
        val forgotten = ArrayList<KnownHost>()
        val data = sampleData(light)
        show(light) { KnownHostsScreen(data, onForget = { forgotten.add(it) }, onBack = {}) }
        compose.onNodeWithText("web01.example.org").assertIsDisplayed()
        compose.onNodeWithText(sampleKey.publicKey.fingerprint).assertIsDisplayed()
        shot("known-hosts-light")
    }

    @Test
    fun snippetsScreen() {
        show { SnippetsScreen(sampleData().snippets, onSave = {}, onDelete = {}, onBack = {}) }
        compose.onNodeWithText("Disk usage").assertIsDisplayed()
        compose.onNodeWithText("df -h").assertIsDisplayed()
        shot("snippets-dark")
    }

    @Test
    fun settingsChangesArePersisted() {
        val data = sampleData()
        val container = container(data)
        var editKeys = 0
        show { SettingsScreen(container, data, onBack = {}, onEditKeys = { editKeys++ }) }
        shot("settings-dark")
        assertTrue(container.store.data.value.settings.vibrateOnBell)
        compose.onNodeWithText("Vibrate on bell").performScrollTo().performClick()
        assertTrue(!container.store.data.value.settings.vibrateOnBell)

        compose.onNodeWithText("Keys of the bar").performScrollTo().performClick()
        assertEquals(1, editKeys, "the key bar is arranged on a screen of its own")
        compose.onNodeWithText("Hide the keys with a hardware keyboard").performScrollTo().performClick()
        assertTrue(container.store.data.value.settings.hideKeysWithKeyboard)
    }

    // ----- security keys -----

    private fun securityKeyQuestion(device: SoftwareAuthenticator, verify: Boolean): Pair<SessionPrompt.SecurityKey, java.util.concurrent.CompletableFuture<io.github.evilgenius79.shh.ssh.SecurityKeyAnswer>> {
        val credential = device.addCredential(ed25519 = true)
        val request = io.github.evilgenius79.shh.ssh.SecurityKeyRequest(
            "sk-ssh-ed25519@openssh.com", credential.application, credential.id, ByteArray(32) { 7 }, touchRequired = true, verificationRequired = verify,
        )
        val answer = java.util.concurrent.CompletableFuture<io.github.evilgenius79.shh.ssh.SecurityKeyAnswer>()
        val target = io.github.evilgenius79.shh.ssh.SshTarget("web01.example.org", 22, "deploy")
        return SessionPrompt.SecurityKey(target, "Key on my security key", request, answer) to answer
    }

    @Test
    fun theSecurityKeyDialogAsksForTheKeyThenItsPinAndThenTheKeyAgain() {
        val device = SoftwareAuthenticator(pin = "246810")
        val (question, answer) = securityKeyQuestion(device, verify = true)
        val finder = TestKeyFinder()
        show { io.github.evilgenius79.shh.ui.SecurityKeyDialog(question, finder) }
        compose.onNodeWithText("Logging in to web01.example.org takes the security key that holds \u201cKey on my security key\u201d.").assertIsDisplayed()
        compose.onNodeWithText("Hold the key against the back of the phone and keep it there, or plug it in and touch it.").assertIsDisplayed()
        shot("security-key-present-dark")

        finder.tap(device)
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("This key wants its PIN first. That is the PIN of the security key itself, not a password of the server.") == 1 }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("135790")
        shot("security-key-pin-dark")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Hold the key against the back of the phone again and keep it there, or plug it in and touch it.").assertIsDisplayed()

        // The wrong PIN: the key says so, and how many tries are left.
        finder.tap(device)
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("That PIN is not right. 7 more tries before the key locks itself.") == 1 }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("246810")
        compose.onNodeWithText("Continue").performClick()
        finder.tap(device)
        compose.waitUntil(5_000) { answer.isDone }
        val signed = answer.get() as io.github.evilgenius79.shh.ssh.SecurityKeyAnswer.Signed
        assertEquals(5, signed.assertion.flags, "touched, and the PIN was checked")
        assertTrue(!finder.isLooking)
    }

    @Test
    fun theSecurityKeyDialogLetsTheUserGoOnWithoutTheKeyOrGiveUp() {
        val (first, skipped) = securityKeyQuestion(SoftwareAuthenticator(), verify = false)
        val finder = TestKeyFinder(nfc = io.github.evilgenius79.shh.security.SecurityKeyFinder.Nfc.SWITCHED_OFF, usb = true)
        var question by androidx.compose.runtime.mutableStateOf(first)
        show { io.github.evilgenius79.shh.ui.SecurityKeyDialog(question, finder) }
        compose.onNodeWithText("Plug the key in and touch it. NFC is switched off; with it on, the key can also be held against the phone.").assertIsDisplayed()
        compose.onNodeWithText("Open the NFC settings").assertIsDisplayed()
        compose.onNodeWithText("Log in another way").performClick()
        assertEquals(io.github.evilgenius79.shh.ssh.SecurityKeyAnswer.Skip, skipped.get())

        val (second, cancelled) = securityKeyQuestion(SoftwareAuthenticator(), verify = false)
        question = second
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(io.github.evilgenius79.shh.ssh.SecurityKeyAnswer.Cancel, cancelled.get())
        assertEquals(2, finder.starts, "each question looks for keys afresh")
    }

    @Test
    fun aKeyCanBeMadeOnASecurityKeyFromTheKeysScreen() {
        val data = sampleData()
        val container = container(data)
        val device = SoftwareAuthenticator(pin = "246810")
        val finder = TestKeyFinder()
        show {
            val current by container.store.data.collectAsState()
            KeysScreen(container, current, onBack = {}, securityKeys = finder)
        }
        compose.onNodeWithContentDescription("Add key").performClick()
        compose.onNodeWithText("New key on a security key").performClick()
        shot("security-key-new-dark")
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Blue key")
        compose.onNodeWithText("ECDSA P-256").performClick()
        compose.onNodeWithText("Every FIDO2 security key can make this kind.").assertIsDisplayed()
        compose.onNodeWithText("Ed25519").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("The new key \u201cBlue key\u201d will be made on your security key.").assertIsDisplayed()
        assertTrue(finder.isLooking)

        // The security key has a PIN, so it makes nothing before it gets it.
        finder.tap(device)
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("PIN of the security key") >= 1 }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("246810")
        compose.onNodeWithText("Continue").performClick()
        finder.tap(device)
        compose.waitUntil(5_000) { container.store.data.value.identities.size == 2 }

        val made = container.store.data.value.identities.first { it.name == "Blue key" }
        assertEquals("Ed25519 on a security key", made.algorithm)
        assertTrue(made.publicKey.startsWith("sk-ssh-ed25519@openssh.com "))
        val credential = device.credentials.single()
        val loaded = io.github.evilgenius79.shh.ssh.SshKeys.load(secrets.decryptText(made.encryptedPrivateKey))
        assertEquals(io.github.evilgenius79.shh.ssh.SshKeys.securityKey(true, credential.point, "ssh:", credential.id).publicKey, loaded.publicKey)

        // It is listed like any other key, and says where its private half is.
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Blue key") == 1 }
        compose.onNodeWithText("Blue key").performClick()
        compose.onNodeWithText("The private key is on the security key.", substring = true).assertIsDisplayed()
    }

    @Test
    fun whatToDoWithTheKeyDependsOnWhatThePhoneHas() {
        val ready = io.github.evilgenius79.shh.security.SecurityKeyFinder.Nfc.READY
        val off = io.github.evilgenius79.shh.security.SecurityKeyFinder.Nfc.SWITCHED_OFF
        val absent = io.github.evilgenius79.shh.security.SecurityKeyFinder.Nfc.ABSENT
        fun how(nfc: io.github.evilgenius79.shh.security.SecurityKeyFinder.Nfc, usb: Boolean, again: Boolean) =
            io.github.evilgenius79.shh.ui.howToPresent(context.resources, nfc, usb, again)
        assertEquals("Hold the key against the back of the phone and keep it there.", how(ready, false, false))
        assertEquals("Plug the key in again and touch it.", how(absent, true, true))
        assertEquals("NFC is switched off. Switch it on to hold the key against the phone.", how(off, false, false))
        assertEquals("This phone has neither NFC nor a USB port that a key could use.", how(absent, false, false))
    }

    // ----- importing an SSH config -----

    private fun importPlan(text: String, saved: List<Host> = emptyList()) =
        io.github.evilgenius79.shh.data.ConfigImport.plan(io.github.evilgenius79.shh.ssh.SshConfig.parse(text.trimIndent()), saved)

    @Test
    fun importingAConfigShowsWhatWillBeAddedAndAsksForAMissingUserName() {
        val plan = importPlan(
            """
            Host web
                HostName web01.example.org
                User deploy
                ProxyJump bastion
                IdentityFile ~/.ssh/id_web
            Host bastion
                HostName bastion.example.org
            Host router
                HostName 192.168.1.1
            Host Database
                User postgres
            """,
            saved = sampleData().hosts,
        )
        var imported: List<Host>? = null
        var dismissed = 0
        show { io.github.evilgenius79.shh.ui.ConfigImportDialog(plan, onImport = { imported = it }, onDismiss = { dismissed++ }) }
        shot("config-import-dark")
        compose.onNodeWithText("3 hosts will be added:").assertIsDisplayed()
        compose.onNodeWithText("deploy@web01.example.org").assertIsDisplayed()
        compose.onNodeWithText("Saved already and left as they are: Database").assertIsDisplayed()
        compose.onNodeWithText("web logs in with a key file", substring = true).assertExists()

        // Two of the hosts have no user: nothing is added before one is given.
        compose.onNodeWithText("The file names no user for bastion, router", substring = true).assertExists()
        compose.onNodeWithText("Add 3 hosts").assertIsNotEnabled()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("two words")
        compose.onNodeWithText("Add 3 hosts").assertIsNotEnabled()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("admin")
        compose.onNodeWithText("Add 3 hosts").assertIsEnabled().performClick()

        val added = assertNotNull(imported)
        assertEquals(listOf("admin@bastion.example.org", "deploy@web01.example.org", "admin@192.168.1.1"), added.map { it.address })
        assertEquals(added[0].id, added[1].jumpHostId)
        assertEquals(0, dismissed)

        // Stored, they are hosts like any other.
        val container = container(sampleData())
        container.store.addHosts(added)
        container.store.addHosts(added)
        assertEquals(sampleData().hosts.size + 3, container.store.data.value.hosts.size, "adding the same hosts twice adds them once")
    }

    @Test
    fun importingAConfigWithNothingNewSaysSo() {
        var dismissed = 0
        show { io.github.evilgenius79.shh.ui.ConfigImportDialog(importPlan("Host Database\n  User x", sampleData().hosts), onImport = {}, onDismiss = { dismissed++ }) }
        compose.onNodeWithText("Every host this file names is saved already.").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun theHostListOffersToImportAnSshConfig() {
        val data = sampleData()
        show {
            HostsScreen(
                data = data, sessions = emptyList(), onConnect = {}, onQuickConnect = {}, onOpenSession = {}, onEdit = {},
                onDuplicate = {}, onDelete = {}, onAdd = {}, onNavigate = {},
            )
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Import SSH config").assertIsDisplayed()
    }

    @Test
    fun settingsShowTheFontInUseAndGoBackToTheBuiltInOne() {
        val fonts = io.github.evilgenius79.shh.terminal.TerminalFonts
        val stored = fonts.install(context, TerminalFontsTest.font(TerminalFontsTest.MONOSPACED))
        val data = sampleData(dark.copy(terminalFont = stored, terminalFontName = "Droid Sans Mono"))
        val container = container(data)
        show {
            val current by container.store.data.collectAsState()
            SettingsScreen(container, current, onBack = {})
        }
        compose.onNodeWithText("Droid Sans Mono. Tap to choose another font file.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use the built-in font").performScrollTo().performClick()
        assertNull(container.store.data.value.settings.terminalFont)
        assertNull(container.store.data.value.settings.terminalFontName)
        assertTrue(!fonts.file(context, stored).exists(), "the app's copy of the font is removed")
        compose.onNodeWithText("JetBrains Mono, built in", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use the built-in font").assertDoesNotExist()
    }

    @Test
    fun settingsDoNotClaimAFontWhoseFileIsNotThere() {
        // What a backup restored on another device looks like: the name of a font, but no file.
        val data = sampleData(dark.copy(terminalFont = "font-1", terminalFontName = "Fira Code"))
        show { SettingsScreen(container(data), data, onBack = {}) }
        compose.onNodeWithText("JetBrains Mono, built in", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use the built-in font").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "+keysexposed-qwerty")
    fun theTerminalLeavesTheKeyBarOutWhileAKeyboardIsConnectedIfAskedTo() {
        val data = sampleData()
        val session = TerminalSession("s", data.hosts[0], SessionEnvironment({ data }, secrets, InMemoryHostKeyStore()))
        session.close()
        var settings by androidx.compose.runtime.mutableStateOf(data.settings)
        show {
            TerminalScreen(
                sessions = listOf(session), active = session, settings = settings, snippets = data.snippets,
                identities = data.identities, onSelect = {}, onClose = {}, onNewSession = {}, onOpenFiles = {},
                onFontSize = {}, onHelp = {}, onBack = {},
            )
        }
        compose.onNodeWithText("CTRL").assertIsDisplayed()
        settings = settings.copy(hideKeysWithKeyboard = true)
        compose.onNodeWithText("CTRL").assertDoesNotExist()
        compose.onNodeWithText("Reconnect").assertIsDisplayed()
        // An arrangement of one's own reaches the terminal as well.
        settings = settings.copy(hideKeysWithKeyboard = false, keyBar = listOf("F5"))
        compose.onNodeWithText("F5").assertIsDisplayed()
        compose.onNodeWithText("ESC").assertDoesNotExist()
    }

    @Test
    fun secretFieldKeepsEveryCharacterWhenTypedOneByOne() {
        val state = androidx.compose.foundation.text.input.TextFieldState()
        show { io.github.evilgenius79.shh.ui.SecretField(state, "Password") }
        // One character at a time without letting the UI settle in between, like fast typing.
        // (An earlier version mirrored the text through a callback and lost characters here.)
        val field = compose.onNode(androidx.compose.ui.test.hasSetTextAction())
        for (ch in "shh-Test-pw-1") field.performTextInput(ch.toString())
        compose.waitForIdle()
        assertEquals("shh-Test-pw-1", state.text.toString())
    }

    // ----- terminal -----

    @Test
    fun terminalScreenLaysOutTerminalKeysAndStatus() {
        val data = sampleData()
        val session = TerminalSession("s", data.hosts[0], SessionEnvironment({ data }, secrets, InMemoryHostKeyStore()))
        // No network here: show recorded output, then end the session so the status bar appears.
        synchronized(session.emulator) {
            session.emulator.append(File("../terminal-core/src/test/resources/fixtures/ls-color.raw").readBytes())
        }
        session.close()
        var closed = 0
        var help = 0
        show {
            TerminalScreen(
                sessions = listOf(session), active = session, settings = data.settings, snippets = data.snippets,
                identities = data.identities, onSelect = {}, onClose = { closed++ }, onNewSession = {}, onOpenFiles = {},
                onFontSize = {}, onHelp = { help++ }, onBack = {},
            )
        }
        compose.onNodeWithText("Web server").assertIsDisplayed()
        compose.onNodeWithText("CTRL").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").assertIsDisplayed()
        shot("terminal-screen-dark")

        // The terminal has the input focus without being touched first, so that a hardware
        // keyboard types into it rather than operating the buttons of the top bar.
        fun terminalIn(view: android.view.View): io.github.evilgenius79.shh.terminal.TerminalView? = when (view) {
            is io.github.evilgenius79.shh.terminal.TerminalView -> view
            is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { terminalIn(view.getChildAt(it)) }
            else -> null
        }
        val root = (compose.onRoot().fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        assertTrue(assertNotNull(terminalIn(root.rootView)).isFocused, "the terminal view has the focus")
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, closed)

        // The how-to is one tap away from where the keys are.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("How to use").performClick()
        assertEquals(1, help)
    }

    @Test
    fun whileReconnectingTheBarSaysSoAndLetsTheUserHurryOrStopIt() {
        var tries = 0
        var stops = 0
        show {
            ReconnectingBar(
                SessionState.Reconnecting("Connection lost: Broken transport; encountered EOF", attempt = 3),
                onTryNow = { tries++ },
                onStop = { stops++ },
            )
        }
        shot("reconnecting")
        compose.onNodeWithText("Connection lost: Broken transport; encountered EOF").assertIsDisplayed()
        compose.onNodeWithText("Reconnecting, attempt 3…").assertIsDisplayed()
        compose.onNodeWithText("Try now").performClick()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(1 to 1, tries to stops)
    }

    @Test
    fun theSessionManagerListensForTheNetworkComingBack() {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val before = org.robolectric.Shadows.shadowOf(manager).networkCallbacks.size
        container(sampleData())
        // That is what makes a dropped session reconnect at once instead of at its next try.
        assertEquals(before + 1, org.robolectric.Shadows.shadowOf(manager).networkCallbacks.size)
    }

    // ----- arriving through a link, a shortcut or a notification -----

    @Test
    fun anSshLinkFillsInTheAddressButDoesNotConnect() {
        val container = container(sampleData())
        show { ShhRoot(container) }
        compose.onNodeWithText("Web server").assertIsDisplayed()

        container.launch.value = Launch.Offer("root@203.0.113.5:2200")
        compose.onNodeWithText("Connect to root@203.0.113.5:2200").assertIsDisplayed()
        assertEquals(emptyList(), container.sessions.sessions.value, "following a link opens no connection by itself")
        assertNull(container.launch.value, "the request is dealt with once")
        shot("hosts-ssh-link")
    }

    @Test
    fun aNotificationOrShortcutLeadsToTheSession() {
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val data = sampleData()
        val container = container(data)
        show { ShhRoot(container) }

        // A shortcut asks for a connection to a saved host: its terminal opens.
        container.launch.value = Launch.Connect("3")
        compose.onNodeWithText("CTRL").assertIsDisplayed()
        val first = container.sessions.sessions.value.single()
        assertEquals("3", first.host.id)

        // A second session, then the notification of the first one is tapped.
        val second = container.sessions.open(data.hosts[3])
        compose.runOnIdle { }
        container.launch.value = Launch.ShowSession(first.id)
        compose.waitForIdle()
        assertEquals(first.id, container.sessions.visibleSessionId)
        container.launch.value = Launch.ShowSession(second.id)
        compose.waitForIdle()
        assertEquals(second.id, container.sessions.visibleSessionId)

        // Requests for things that no longer exist are dropped quietly.
        container.launch.value = Launch.ShowSession("gone")
        container.launch.value = Launch.Connect("no-such-host")
        compose.waitForIdle()
        assertEquals(2, container.sessions.sessions.value.size)
        container.sessions.closeAll()
    }

    // ----- how-to -----

    @Test
    fun theHowToExplainsTheKeysAndGestures() {
        var back = 0
        show { HelpScreen(onBack = { back++ }) }
        compose.onNodeWithText("How to use Shh").assertIsDisplayed()
        compose.onNodeWithText("The key bar").assertIsDisplayed()
        // The two things nobody can guess: one tap applies once, two taps lock.
        compose.onNodeWithText("applies to the next key you press", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Tap twice in a row to lock it", substring = true).assertIsDisplayed()
        shot("help-dark")
        compose.onNodeWithText("Makes the text smaller or larger.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pinch").assertIsDisplayed()
        shot("help-dark-touch")
        compose.onNodeWithText("Browse the server's files", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    // ----- the bar under a session that is over -----

    /** Fails unless the button called [label] shows its label whole and on one line. */
    private fun assertLabelIntact(label: String) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val layouts = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertEquals(1, layout.lineCount, "\"$label\" is broken over several lines")
        // The framework's own overflow flag is no use here: for a label that may not wrap it is
        // set whenever there was room to spare. Compare the box with what the text needs.
        val needed = layout.multiParagraph.maxIntrinsicWidth
        assertTrue(layout.size.width + 0.5f >= needed, "\"$label\" is cut off: it needs $needed px and got ${layout.size.width}")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-normal-long-notround-any-mdpi-keyshidden-nonav")
    fun theDisconnectedBarOffersLocalNetworkAccessAndFitsANarrowScreen() {
        // A small phone set to large text: the three buttons cannot share one line.
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        var allowed = 0
        var reconnects = 0
        show {
            DisconnectedBarContent(
                state = SessionState.Disconnected("Timed out connecting to 192.168.1.20:22", SshFailure.TIMEOUT),
                localNetworkBlocked = true,
                onAllowLocalNetwork = { allowed++ },
                onReconnect = { reconnects++ },
                onClose = {},
            )
        }
        shot("disconnected-blocked-narrow")
        compose.onNodeWithText("Android is not letting this app reach devices on your local network", substring = true).assertIsDisplayed()
        // None of them may be squeezed until its label breaks or is cut off, which is what a
        // plain row does to the last one.
        for (label in listOf("Close", "Reconnect", "Allow access")) assertLabelIntact(label)
        compose.onNodeWithText("Allow access").performClick()
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(1 to 1, allowed to reconnects)
    }

    @Test
    fun theDisconnectedBarSuggestsWhatToCheckWhenAccessIsNotTheProblem() {
        show {
            DisconnectedBarContent(
                state = SessionState.Disconnected("Timed out connecting to example.org:22", SshFailure.TIMEOUT),
                localNetworkBlocked = false,
                onAllowLocalNetwork = {},
                onReconnect = {},
                onClose = {},
            )
        }
        shot("disconnected-timeout")
        compose.onNodeWithText("Nothing answered", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Reconnect").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("Allow access"))
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
