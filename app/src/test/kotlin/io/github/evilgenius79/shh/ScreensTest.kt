package io.github.evilgenius79.shh

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.ForwardRule
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.data.Identity
import io.github.evilgenius79.shh.data.KnownHost
import io.github.evilgenius79.shh.data.Settings
import io.github.evilgenius79.shh.data.Snippet
import io.github.evilgenius79.shh.data.ThemeMode
import io.github.evilgenius79.shh.session.SessionEnvironment
import io.github.evilgenius79.shh.session.SessionManager
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.InMemoryHostKeyStore
import io.github.evilgenius79.shh.ssh.KeyAlgorithm
import io.github.evilgenius79.shh.ssh.SshKeys
import io.github.evilgenius79.shh.ui.HostEditScreen
import io.github.evilgenius79.shh.ui.HostsScreen
import io.github.evilgenius79.shh.ui.KeysScreen
import io.github.evilgenius79.shh.ui.KnownHostsScreen
import io.github.evilgenius79.shh.ui.Screen
import io.github.evilgenius79.shh.ui.SettingsScreen
import io.github.evilgenius79.shh.ui.ShhTheme
import io.github.evilgenius79.shh.ui.SnippetsScreen
import io.github.evilgenius79.shh.ui.TerminalScreen
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
        show { SettingsScreen(container, data, onBack = {}) }
        shot("settings-dark")
        assertTrue(container.store.data.value.settings.vibrateOnBell)
        compose.onNodeWithText("Vibrate on bell").performScrollTo().performClick()
        assertTrue(!container.store.data.value.settings.vibrateOnBell)
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
        show {
            TerminalScreen(
                sessions = listOf(session), active = session, settings = data.settings, snippets = data.snippets,
                identities = data.identities, onSelect = {}, onClose = { closed++ }, onNewSession = {}, onOpenFiles = {},
                onFontSize = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Web server").assertIsDisplayed()
        compose.onNodeWithText("CTRL").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").assertIsDisplayed()
        shot("terminal-screen-dark")
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, closed)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
