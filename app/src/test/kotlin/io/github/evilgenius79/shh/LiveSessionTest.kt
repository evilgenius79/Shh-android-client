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

        // The window title set by the shell prompt is picked up.
        waitFor("the title") { session.title.value.contains(spec!![3]) }

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
        view.ctrlLatched = true
        var consumed = 0
        view.onModifiersConsumed = { consumed++ }
        ime.commitText("c", 1)
        assertEquals(1, consumed)
        assertTrue(!view.ctrlLatched)
        // Wait for the shell to react before typing on: it throws away type-ahead when it is
        // interrupted, exactly as it would for a person typing implausibly fast.
        waitFor("the interrupt") { session.screen().contains("never-run^C") }
        ime.commitText("echo after-\$((7+1))\n", 1)
        waitFor("output after Ctrl+C") { session.screen().contains("after-8") }

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
