package io.github.evilgenius79.shh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.ForwardKind
import io.github.evilgenius79.shh.data.ForwardRule
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.session.SessionEnvironment
import io.github.evilgenius79.shh.session.SessionPrompt
import io.github.evilgenius79.shh.session.SessionState
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.HostKeyDecision
import io.github.evilgenius79.shh.ssh.HostKeyStatus
import io.github.evilgenius79.shh.ssh.InMemoryHostKeyStore
import io.github.evilgenius79.shh.ssh.SshFailure
import io.github.evilgenius79.shh.terminal.ModifierState
import io.github.evilgenius79.shh.terminal.TerminalKey
import io.github.evilgenius79.shh.terminal.TerminalView
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The app's session layer against a real OpenSSH server: prompts, the shell, resizing, port
 * forwards from the host settings, the end of a session and reconnecting. Needs the servers from
 * `tools/test-sshd.sh start` (see `SHH_TEST_OPENSSH`); skipped otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "xxhdpi")
class LiveSessionTest {
    private val spec: List<String>? = System.getenv("SHH_TEST_OPENSSH")?.takeIf { it.isNotBlank() }?.split('|')
    private val secrets = TestSecretBox()
    private val hostKeys = InMemoryHostKeyStore()
    private val sessions = ArrayList<TerminalSession>()

    @After
    fun tearDown() {
        for (session in sessions) session.close()
    }

    private fun host(savePassword: Boolean = true, forwards: List<ForwardRule> = emptyList(), startup: String = ""): Host {
        assumeTrue("SHH_TEST_OPENSSH is not set; see tools/test-sshd.sh", spec != null)
        val s = spec!!
        return Host(
            id = "live",
            name = "Test server",
            host = s[0],
            port = s[1].toInt(),
            username = s[3],
            encryptedPassword = if (savePassword) secrets.encryptText(s[4]) else null,
            forwards = forwards,
            startupCommand = startup,
        )
    }

    private fun open(host: Host): TerminalSession {
        val data = AppData(hosts = listOf(host))
        return TerminalSession("s${sessions.size}", host, SessionEnvironment({ data }, secrets, hostKeys)).also {
            sessions.add(it)
            it.connect()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                // Include what the terminals show: it is usually the quickest way to see why.
                "Timed out waiting for: $what\n" + sessions.joinToString("\n---\n") { it.screen().trimEnd() }
            }
            Thread.sleep(25)
        }
    }

    private fun TerminalSession.screen(): String = synchronized(emulator) { emulator.screenText() }

    private fun TerminalSession.acceptHostKey(expected: HostKeyStatus = HostKeyStatus.UNKNOWN) {
        waitFor("the host key prompt") { prompt.value is SessionPrompt.HostKey }
        val request = prompt.value as SessionPrompt.HostKey
        assertEquals(expected, request.request.status)
        request.respond(HostKeyDecision.ACCEPT_AND_REMEMBER)
    }

    @Test
    fun fullSessionLifecycle() {
        val session = open(host())
        assertIs<SessionState.Connecting>(session.state.value)
        session.acceptHostKey()
        waitFor("the connection") { session.isConnected }
        session.resize(80, 24, 10, 20)

        // Typing reaches the shell and its output reaches the emulator.
        session.sendText("echo marker-\$((40+2))")
        session.sendKey(TerminalKey.ENTER)
        waitFor("the command output") { session.screen().contains("marker-42") }

        // The remote terminal follows the size of the view.
        session.resize(100, 30, 10, 20)
        session.sendText("stty size\r")
        waitFor("the new size") { session.screen().contains("30 100") }

        // A full screen program switches to the alternate screen and back.
        session.sendText("less /etc/passwd\r")
        waitFor("less to start") { synchronized(session.emulator) { session.emulator.isAlternateScreenActive } }
        assertTrue(session.screen().contains("root:"))
        session.sendText("q")
        waitFor("less to quit") { !synchronized(session.emulator) { session.emulator.isAlternateScreenActive } }

        // A window title set by the remote side is picked up. It is set explicitly, and held
        // for a moment, because whether the shell prompt sets one differs between systems.
        session.sendText("printf '\\033]0;custom title\\007'; sleep 1\r")
        waitFor("the title") { session.title.value == "custom title" }

        // Draw the live terminal through the real view, for inspection.
        session.sendText("clear; ls --color=always -la /etc | head -20\r")
        waitFor("the listing") { session.screen().contains("passwd") || session.screen().contains("total") }
        Thread.sleep(300)
        val context: Context = ApplicationProvider.getApplicationContext()
        val view = TerminalView(context)
        view.cursorBlink = false
        view.fontSizeSp = 12f
        val (width, height) = view.sizeFor(100, 30)
        view.attach(session)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/outputs/screenshots").mkdirs()
        File("build/outputs/screenshots/terminal-live-openssh.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        view.attach(null)

        // Leaving the shell ends the session and says so.
        session.sendText("exit 3\r")
        waitFor("the session to end") { session.state.value is SessionState.Disconnected }
        assertEquals("Session ended (exit status 3)", (session.state.value as SessionState.Disconnected).message)
        assertTrue(session.screen().contains("[Session ended (exit status 3)]"))

        // Reconnecting works and no longer asks about the host key.
        session.reconnect()
        waitFor("the reconnection") { session.isConnected }
        assertEquals(null, session.prompt.value)
        session.sendText("echo back-again\r")
        waitFor("output after reconnecting") { session.screen().contains("back-again") }
    }

    /** Everything the keyboard can do, sent through the view exactly as Android delivers it. */
    @Test
    fun keyboardInputReachesTheShell() {
        val session = open(host())
        session.acceptHostKey()
        waitFor("the connection") { session.isConnected }
        val context: Context = ApplicationProvider.getApplicationContext()
        val view = TerminalView(context)
        val (width, height) = view.sizeFor(80, 24)
        view.attach(session)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)

        fun key(code: Int, meta: Int = 0) {
            val now = android.os.SystemClock.uptimeMillis()
            view.onKeyDown(code, android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))
        }

        // An on-screen keyboard: committed text, a backspace expressed as "delete surrounding
        // text", then Enter as a key event.
        val editorInfo = android.view.inputmethod.EditorInfo()
        val ime = view.onCreateInputConnection(editorInfo)
        assertTrue(editorInfo.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        ime.commitText("echo ime-\$((3*3))X", 1)
        ime.deleteSurroundingText(1, 0)
        ime.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        waitFor("text typed on the on-screen keyboard") { session.screen().contains("ime-9") }
        assertTrue(!session.screen().contains("ime-9X"), "the backspace removed the X")

        // Text a keyboard was still composing is sent once it is finished.
        ime.setComposingText("echo composed-\$((2+2))", 1)
        ime.finishComposingText()
        key(android.view.KeyEvent.KEYCODE_ENTER)
        waitFor("composed text") { session.screen().contains("composed-4") }

        // The Ctrl key from the extra key row applies to the next character: Ctrl+C aborts the
        // half typed command, and the latch is used up.
        ime.commitText("echo never-run", 1)
        view.ctrl = ModifierState.ONCE
        var consumed = 0
        view.onModifiersConsumed = { consumed++ }
        ime.commitText("c", 1)
        assertEquals(1, consumed)
        assertEquals(ModifierState.OFF, view.ctrl)
        // Wait for the shell to react before typing on: it throws away type-ahead when it is
        // interrupted, exactly as it would for a person typing implausibly fast.
        waitFor("the interrupt") { session.screen().contains("never-run^C") }

        // Locked, Ctrl stays on for one key after another and nothing reports it as used up:
        // Ctrl+A goes to the start of a half typed line and Ctrl+K erases it. Had the lock let
        // go after the first key, the second would have typed a "k" into the line instead.
        ime.commitText("echo after-\$((7+1))\n", 1)
        waitFor("output after Ctrl+C") { session.screen().contains("after-8") }
        ime.commitText("echo keep-me-not", 1)
        waitFor("the half typed line") { session.screen().contains("keep-me-not") }
        view.ctrl = ModifierState.LOCKED
        ime.commitText("a", 1)
        ime.commitText("k", 1)
        waitFor("Ctrl+A and Ctrl+K to erase the line") { !session.screen().contains("keep-me-not") }
        assertEquals(ModifierState.LOCKED, view.ctrl)
        assertEquals(1, consumed)
        view.ctrl = ModifierState.OFF

        // Cursor keys: recall the previous command with Up and run it again.
        ime.commitText("clear\n", 1)
        waitFor("the screen to clear") { !session.screen().contains("after-8") }
        key(android.view.KeyEvent.KEYCODE_DPAD_UP)
        key(android.view.KeyEvent.KEYCODE_DPAD_UP)
        key(android.view.KeyEvent.KEYCODE_ENTER)
        waitFor("the recalled command") { session.screen().contains("after-8") }

        // Tab completes a command name.
        ime.commitText("ec", 1)
        key(android.view.KeyEvent.KEYCODE_TAB)
        key(android.view.KeyEvent.KEYCODE_TAB)
        ime.commitText("ho tab-\$((6+6))\n", 1)
        waitFor("output of the completed command") { session.screen().contains("tab-12") }

        // A hardware keyboard: Ctrl+L clears the screen.
        key(android.view.KeyEvent.KEYCODE_L, android.view.KeyEvent.META_CTRL_ON)
        waitFor("Ctrl+L") { !session.screen().contains("tab-12") }
        view.attach(null)
    }

    /** In less, man or vim there is no history to scroll, so a swipe has to scroll the program. */
    @Test
    fun swipingScrollsAFullScreenProgram() {
        val session = open(host())
        session.acceptHostKey()
        waitFor("the connection") { session.isConnected }
        val context: Context = ApplicationProvider.getApplicationContext()
        val view = TerminalView(context)
        val (width, height) = view.sizeFor(80, 24)
        view.attach(session)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)

        session.sendText("seq 1000 1999 | less\r")
        waitFor("less to start") { synchronized(session.emulator) { session.emulator.isAlternateScreenActive } }
        waitFor("the first page") { session.screen().lines().first() == "1000" }

        fun touch(action: Int, y: Float, time: Long) {
            val event = android.view.MotionEvent.obtain(0, time, action, 100f, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        // Finger moves up by ten lines: the text moves up with it.
        val cell = view.sizeFor(1, 1).second
        touch(android.view.MotionEvent.ACTION_DOWN, cell * 15f, 0)
        for (i in 1..10) touch(android.view.MotionEvent.ACTION_MOVE, cell * (15f - i), i * 20L)
        touch(android.view.MotionEvent.ACTION_UP, cell * 5f, 600)
        waitFor("less to scroll down") { session.screen().lines().first().toIntOrNull()?.let { it in 1008..1010 } == true }
        assertEquals(0, view.scrollOffset, "the view's own history is not involved")

        // And back down again.
        touch(android.view.MotionEvent.ACTION_DOWN, cell * 5f, 1000)
        for (i in 1..10) touch(android.view.MotionEvent.ACTION_MOVE, cell * (5f + i), 1000 + i * 20L)
        touch(android.view.MotionEvent.ACTION_UP, cell * 15f, 1600)
        waitFor("less to scroll back up") { session.screen().lines().first() == "1000" }
        session.sendText("q")
        view.attach(null)
    }

    @Test
    fun aLongPasteArrivesComplete() {
        val session = open(host())
        session.acceptHostKey()
        waitFor("the connection") { session.isConnected }

        // Two thousand lines, 160 000 characters, each line different from every other.
        val lines = (1..2000).map { number -> "line %04d ".format(number) + "abcdefghijklmnopqrstuvwxyz0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ+".repeat(2).take(69) }
        val text = lines.joinToString("\n", postfix = "\n")
        assertEquals(160_000, text.length)
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        // Into a program that just stores what it is given, without the echo slowing things down.
        val file = "/tmp/shh-paste-${System.nanoTime()}.txt"
        session.sendText("stty -echo; echo waiting-\$((40+2)); cat > $file; stty echo; sha256sum $file; rm $file\n")
        waitFor("the program that takes the paste") { session.screen().contains("waiting-42") }
        session.paste(text)
        // Ctrl+D at the start of a line ends the input.
        session.sendText("d", io.github.evilgenius79.shh.terminal.KeyModifiers.CTRL)
        waitFor("the checksum of what arrived", timeoutMs = 60_000) { Regex("[0-9a-f]{64}").containsMatchIn(session.screen()) }
        assertEquals(expected, Regex("[0-9a-f]{64}").find(session.screen())!!.value, "every character of the paste arrived, in order")
    }

    @Test
    fun leavingTheShellClosesItsTabButAShellThatEndsOnItsOwnStays() {
        assumeTrue(spec != null)
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = io.github.evilgenius79.shh.data.AppStore(File.createTempFile("store", ".json"), java.util.concurrent.Executor { it.run() })
        val manager = io.github.evilgenius79.shh.session.SessionManager(context, store, secrets, hostKeys)
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())

        fun login(startup: String = ""): TerminalSession {
            val session = manager.open(host(startup = startup))
            sessions.add(session)
            if (hostKeys.find(host().host, host().port).isEmpty()) session.acceptHostKey()
            // Not "is connected": a shell that its startup command ends at once may already be
            // over again by the time anybody looks.
            waitFor("the login") { session.state.value !is SessionState.Connecting }
            return session
        }

        fun waitForTheTabToClose() = waitFor("the tab to close") {
            main.idle()
            manager.sessions.value.isEmpty()
        }

        // "exit" at the prompt: the tab goes away, as a terminal window would.
        login().sendText("exit\n")
        waitForTheTabToClose()

        // The same after a command that failed. The shell then ends with that command's status,
        // which says nothing about whether the user meant to leave.
        val afterFailure = login()
        afterFailure.sendText("false\n")
        afterFailure.sendText("exit\n")
        waitForTheTabToClose()

        // Ctrl+D is the other way out.
        val withControlD = login()
        withControlD.sendText("echo ready-\$((6+1))\n")
        waitFor("the prompt") { withControlD.screen().contains("ready-7") }
        withControlD.sendText("d", io.github.evilgenius79.shh.terminal.KeyModifiers.CTRL)
        waitForTheTabToClose()

        // A shell that ends without the user having typed anything stays, so that what it
        // printed can be read. Here the host's startup command ends it.
        val onItsOwn = login(startup = "echo bye-\$((8+1)); exit 3")
        waitFor("the session to end") { onItsOwn.state.value is SessionState.Disconnected }
        main.idle()
        assertEquals(listOf(onItsOwn), manager.sessions.value)
        assertEquals("Session ended (exit status 3)", (onItsOwn.state.value as SessionState.Disconnected).message)
        assertTrue(onItsOwn.screen().contains("bye-9"))
        manager.close(onItsOwn)

        // And with the setting off, leaving the shell keeps the tab too.
        store.updateSettings { it.copy(closeOnExit = false) }
        val kept = login()
        kept.sendText("exit\n")
        waitFor("the session to end") { kept.state.value is SessionState.Disconnected }
        main.idle()
        assertEquals(listOf(kept), manager.sessions.value)
        manager.closeAll()
    }

    @Test
    fun askingForThePasswordAndCancelling() {
        val session = open(host(savePassword = false))
        session.acceptHostKey()
        waitFor("the password prompt") { session.prompt.value is SessionPrompt.Password }
        val first = session.prompt.value as SessionPrompt.Password
        assertEquals(1, first.attempt)
        first.respond("not the password".toCharArray())

        waitFor("the second password prompt") { (session.prompt.value as? SessionPrompt.Password)?.attempt == 2 }
        (session.prompt.value as SessionPrompt.Password).respond(spec!![4].toCharArray())
        waitFor("the connection") { session.isConnected }

        // A second session whose prompt is dismissed ends as cancelled.
        val cancelled = open(host(savePassword = false))
        waitFor("the password prompt") { cancelled.prompt.value is SessionPrompt.Password }
        (cancelled.prompt.value as SessionPrompt.Password).respond(null)
        waitFor("the cancellation") { cancelled.state.value is SessionState.Disconnected }
        assertEquals(SshFailure.CANCELLED, (cancelled.state.value as SessionState.Disconnected).failure)
    }

    @Test
    fun rejectingTheHostKeyEndsTheSession() {
        val session = open(host())
        waitFor("the host key prompt") { session.prompt.value is SessionPrompt.HostKey }
        (session.prompt.value as SessionPrompt.HostKey).respond(HostKeyDecision.REJECT)
        waitFor("the rejection") { session.state.value is SessionState.Disconnected }
        assertEquals(SshFailure.HOST_KEY_REJECTED, (session.state.value as SessionState.Disconnected).failure)
    }

    @Test
    fun closingWhileAPromptIsOpenReleasesTheConnectingThread() {
        val session = open(host())
        waitFor("the host key prompt") { session.prompt.value is SessionPrompt.HostKey }
        session.close()
        assertEquals(null, session.prompt.value)
        assertIs<SessionState.Disconnected>(session.state.value)
    }

    @Test
    fun startupCommandAndConfiguredForwardsAreApplied() {
        val forward = ForwardRule("f", ForwardKind.LOCAL, "127.0.0.1", 0, "127.0.0.1", spec?.get(1)?.toInt() ?: 22)
        val session = open(host(forwards = listOf(forward), startup = "echo started-\$((6*7))"))
        session.acceptHostKey()
        waitFor("the connection") { session.isConnected }
        waitFor("the startup command") { session.screen().contains("started-42") }
        waitFor("the forward") { session.forwards.value.size == 1 }
        // The forward leads back to the SSH server itself, which greets with its version banner.
        val port = session.forwards.value.single().boundPort
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val banner = socket.getInputStream().bufferedReader().readLine()
            assertTrue(banner.startsWith("SSH-2.0-OpenSSH"), banner)
        }
        session.removeForward(session.forwards.value.single())
        assertTrue(session.forwards.value.isEmpty())
    }

    @Test
    fun unreachableServerIsReportedWithAHint() {
        assumeTrue(spec != null)
        val unused = java.net.ServerSocket(0).use { it.localPort }
        val host = Host(id = "x", host = "127.0.0.1", port = unused, username = "nobody")
        val session = open(host)
        waitFor("the failure") { session.state.value is SessionState.Disconnected }
        assertEquals(SshFailure.REFUSED, (session.state.value as SessionState.Disconnected).failure)
    }
}
