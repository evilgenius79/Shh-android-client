package io.github.evilgenius79.shh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.evilgenius79.shh.data.AppData
import io.github.evilgenius79.shh.data.Host
import io.github.evilgenius79.shh.session.SessionEnvironment
import io.github.evilgenius79.shh.session.TerminalSession
import io.github.evilgenius79.shh.ssh.InMemoryHostKeyStore
import io.github.evilgenius79.shh.terminal.ColorScheme
import io.github.evilgenius79.shh.terminal.TerminalView
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders recorded output of real programs through the actual [TerminalView] drawing code, using
 * Robolectric's native graphics so that fonts and colours are the real thing. The images are
 * written to `app/build/outputs/screenshots` for inspection; the assertions check the properties
 * that can be verified mechanically.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "xxhdpi")
class TerminalViewRenderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fixtures = File("../terminal-core/src/test/resources/fixtures")
    private val output = File("build/outputs/screenshots").apply { mkdirs() }

    private fun session(scheme: ColorScheme = ColorScheme.DEFAULT_DARK): TerminalSession {
        val data = AppData().let { it.copy(settings = it.settings.copy(colorSchemeId = scheme.id)) }
        return TerminalSession("test", Host(id = "h", host = "example.org"), SessionEnvironment({ data }, TestSecretBox(), InMemoryHostKeyStore()))
    }

    /** Lays the view out at exactly [columns] x [rows] cells, replays [fixture] and draws it. */
    private fun render(name: String, fixture: String, columns: Int = 80, rows: Int = 24, scheme: ColorScheme = ColorScheme.DEFAULT_DARK): Pair<TerminalSession, Bitmap> {
        val session = session(scheme)
        val view = TerminalView(context)
        view.fontSizeSp = 12f
        view.cursorBlink = false
        val (width, height) = view.sizeFor(columns, rows)
        view.attach(session)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        assertEquals(columns to rows, session.emulator.columns to session.emulator.rows, "the view sized the terminal")

        val bytes = File(fixtures, "$fixture.raw").readBytes()
        synchronized(session.emulator) { session.emulator.append(bytes) }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return session to bitmap
    }

    private fun distinctColors(bitmap: Bitmap): Set<Int> {
        val colors = HashSet<Int>()
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) colors.add(bitmap.getPixel(x, y))
        return colors
    }

    @Test
    fun rendersVimWithSyntaxHighlighting() {
        val (session, bitmap) = render("terminal-vim", "vim-syntax")
        assertTrue(session.emulator.isAlternateScreenActive)
        // The background is the scheme's, and the highlighted source uses many colours.
        assertEquals(0xFF000000.toInt() or ColorScheme.DEFAULT_DARK.background, bitmap.getPixel(bitmap.width - 2, bitmap.height / 2))
        assertTrue(distinctColors(bitmap).size > 20, "expected coloured, anti-aliased text")
    }

    @Test
    fun rendersHtop() {
        val (_, bitmap) = render("terminal-htop", "htop-color", scheme = ColorScheme.DRACULA)
        assertTrue(distinctColors(bitmap).size > 20)
    }

    @Test
    fun rendersLineDrawingAndWideCharacters() {
        render("terminal-curses", "curses", scheme = ColorScheme.SOLARIZED_DARK)
        render("terminal-unicode", "unicode", scheme = ColorScheme.DEFAULT_LIGHT)
        render("terminal-sgr", "printf-sgr", scheme = ColorScheme.TOKYO_NIGHT)
        render("terminal-tmux", "tmux-nested", scheme = ColorScheme.GRUVBOX_DARK)
    }

    @Test
    fun greenOnBlackDrawsPlainTextInGreenOnPureBlack() {
        val (_, bitmap) = render("terminal-green", "bash", scheme = ColorScheme.GREEN)
        assertEquals(0xFF000000.toInt(), bitmap.getPixel(bitmap.width - 2, bitmap.height - 2), "the background is pure black")
        val green = 0xFF000000.toInt() or ColorScheme.GREEN.foreground
        var solidGreen = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) == green) solidGreen++
        assertTrue(solidGreen > 500, "text that asks for no colour is drawn in the scheme's green, found $solidGreen such pixels")
    }

    // ----- touch -----

    private fun laidOutView(session: TerminalSession, columns: Int = 40, rows: Int = 10): TerminalView {
        val view = TerminalView(context)
        view.cursorBlink = false
        val (width, height) = view.sizeFor(columns, rows)
        view.attach(session)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return view
    }

    private fun touch(view: View, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val event = android.view.MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** Drags a finger vertically from [fromY] to [toY] in small steps. */
    private fun drag(view: View, fromY: Float, toY: Float) {
        val start = android.os.SystemClock.uptimeMillis()
        touch(view, android.view.MotionEvent.ACTION_DOWN, 50f, fromY, start, start)
        val steps = 20
        for (i in 1..steps) {
            touch(view, android.view.MotionEvent.ACTION_MOVE, 50f, fromY + (toY - fromY) * i / steps, start, start + i * 10L)
        }
        touch(view, android.view.MotionEvent.ACTION_UP, 50f, toY, start, start + steps * 10L + 400)
    }

    @Test
    fun draggingScrollsThroughTheHistoryAndTypingReturnsToTheBottom() {
        val session = session()
        val view = laidOutView(session)
        synchronized(session.emulator) { for (i in 1..100) session.emulator.append("line $i\r\n") }
        assertEquals(0, view.scrollOffset)
        val cell = view.sizeFor(1, 1).second

        // Finger moves down: older lines come into view.
        drag(view, fromY = cell * 1f, toY = cell * 8f)
        assertTrue(view.scrollOffset in 5..7, "scrolled back by about seven lines, was ${view.scrollOffset}")

        // New output arriving while scrolled back does not move what the user is reading.
        val before = view.scrollOffset
        synchronized(session.emulator) { for (i in 1..5) session.emulator.append("more $i\r\n") }
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        assertEquals(before + 5, view.scrollOffset)

        // It cannot be dragged past either end.
        repeat(30) { drag(view, fromY = cell * 1f, toY = cell * 9f) }
        assertEquals(session.emulator.scrollbackSize, view.scrollOffset)
        repeat(30) { drag(view, fromY = cell * 9f, toY = cell * 1f) }
        assertEquals(0, view.scrollOffset)

        drag(view, fromY = cell * 1f, toY = cell * 8f)
        assertTrue(view.scrollOffset > 0)
        view.typed("x")
        assertEquals(0, view.scrollOffset, "typing jumps back to the live screen")
    }

    @Test
    fun longPressSelectsTheWordUnderTheFinger() {
        val session = session()
        val view = laidOutView(session)
        synchronized(session.emulator) { session.emulator.append("ls /usr/local/bin and more\r\n") }
        val (cellWidth, cellHeight) = view.sizeFor(1, 1)
        val start = android.os.SystemClock.uptimeMillis()
        // Press on the "l" of "local" and hold.
        touch(view, android.view.MotionEvent.ACTION_DOWN, cellWidth * 9.5f, cellHeight * 0.5f, start, start)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            android.view.ViewConfiguration.getLongPressTimeout() + 200L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        assertEquals("/usr/local/bin", view.selectedText(), "a path is selected as one word")

        // Dragging on extends the selection to where the finger is.
        touch(view, android.view.MotionEvent.ACTION_MOVE, cellWidth * 20.5f, cellHeight * 0.5f, start, start + 900)
        assertEquals("/usr/local/bin and", view.selectedText())
        touch(view, android.view.MotionEvent.ACTION_UP, cellWidth * 20.5f, cellHeight * 0.5f, start, start + 1000)

        view.copySelection()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("/usr/local/bin and", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(null, view.selectedText(), "copying ends the selection")
    }

    @Test
    fun aSelectionEndsWhenTheScreenSizeChanges() {
        val session = session()
        val view = laidOutView(session, columns = 40, rows = 10)
        synchronized(session.emulator) { for (i in 1..30) session.emulator.append("word$i and some more text\r\n") }
        val (cellWidth, cellHeight) = view.sizeFor(1, 1)
        val start = android.os.SystemClock.uptimeMillis()
        touch(view, android.view.MotionEvent.ACTION_DOWN, cellWidth * 1.5f, cellHeight * 4.5f, start, start)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            android.view.ViewConfiguration.getLongPressTimeout() + 200L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        touch(view, android.view.MotionEvent.ACTION_UP, cellWidth * 1.5f, cellHeight * 4.5f, start, start + 700)
        assertEquals("word26", view.selectedText())

        // Laying the view out again at the same size changes nothing.
        view.layout(0, 0, view.width, view.height)
        assertEquals("word26", view.selectedText())

        // The keyboard opens and leaves room for six rows: every line moves up, so the positions
        // that were selected now hold other text. The selection must not silently follow them.
        val (width, height) = view.sizeFor(40, 6)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        assertEquals(6, session.emulator.rows)
        assertEquals(null, view.selectedText())
    }

    /** Two fingers at [first] and [second]; [action] says what they are doing. */
    private fun twoFingers(view: View, action: Int, first: Pair<Float, Float>, second: Pair<Float, Float>, downTime: Long, eventTime: Long) {
        val properties = Array(2) { index ->
            android.view.MotionEvent.PointerProperties().apply {
                id = index
                toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = arrayOf(first, second).map { (x, y) ->
            android.view.MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val event = android.view.MotionEvent.obtain(
            downTime, eventTime, action, 2, properties, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /**
     * Puts two fingers on the view [from] pixels apart, one above the other, and moves them until
     * they are [to] apart. The second finger is lifted first; the first one then keeps moving for
     * a moment before it is lifted too, as a real hand does.
     */
    private fun pinch(view: View, from: Float, to: Float) {
        val x = view.width / 2f
        val middle = view.height / 2f
        val start = android.os.SystemClock.uptimeMillis()
        val secondFinger = 1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT
        touch(view, android.view.MotionEvent.ACTION_DOWN, x, middle - from / 2, start, start)
        twoFingers(view, android.view.MotionEvent.ACTION_POINTER_DOWN or secondFinger, x to middle - from / 2, x to middle + from / 2, start, start + 10)
        val steps = 10
        for (i in 1..steps) {
            val span = from + (to - from) * i / steps
            twoFingers(view, android.view.MotionEvent.ACTION_MOVE, x to middle - span / 2, x to middle + span / 2, start, start + 10 + i * 5L)
        }
        twoFingers(view, android.view.MotionEvent.ACTION_POINTER_UP or secondFinger, x to middle - to / 2, x to middle + to / 2, start, start + 65)
        for (i in 1..5) {
            touch(view, android.view.MotionEvent.ACTION_MOVE, x, middle - to / 2 + i * 40f, start, start + 65 + i * 5L)
        }
        touch(view, android.view.MotionEvent.ACTION_UP, x, middle - to / 2 + 200f, start, start + 95)
        // Anything the gesture left behind (a long press, a fling) gets its chance to fire.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            android.view.ViewConfiguration.getLongPressTimeout() + 500L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    @Test
    fun pinchingChangesTheTextSizeAndNothingElse() {
        val session = session()
        val view = laidOutView(session, columns = 40, rows = 30)
        synchronized(session.emulator) { for (i in 1..200) session.emulator.append("line $i\r\n") }
        val reported = ArrayList<Float>()
        view.onFontSizeChanged = { reported.add(it) }
        assertEquals(13f, view.fontSizeSp)
        assertEquals(40, session.emulator.columns)

        // Fingers move apart to twice the distance: the text grows, by a little less than twice
        // because the first few millimetres only establish that this is a pinch.
        pinch(view, from = view.height * 0.4f, to = view.height * 0.8f)
        val enlarged = view.fontSizeSp
        assertTrue(enlarged in 20f..26f, "about twice 13sp, was $enlarged")
        assertEquals(enlarged, Math.round(enlarged).toFloat(), "whole steps only")
        assertEquals(listOf(enlarged), reported, "the new size is reported once, when the pinch ends")
        assertTrue(session.emulator.columns in 20..26, "fewer columns fit: ${session.emulator.columns}")
        assertEquals(0, view.scrollOffset, "pinching is not scrolling, and neither is the finger that stays down afterwards")
        assertEquals(null, view.selectedText(), "nor is it a long press")

        // Fingers move together again.
        pinch(view, from = view.height * 0.8f, to = view.height * 0.4f)
        assertTrue(view.fontSizeSp in 10f..14f, "about half of $enlarged, was ${view.fontSizeSp}")
        assertEquals(listOf(enlarged, view.fontSizeSp), reported)
        assertEquals(0, view.scrollOffset)

        // It stops at the limits instead of making the text unreadable or enormous.
        repeat(6) { pinch(view, from = view.height * 0.8f, to = view.height * 0.3f) }
        assertEquals(TerminalView.MIN_FONT_SP, view.fontSizeSp)
        repeat(6) { pinch(view, from = view.height * 0.3f, to = view.height * 0.8f) }
        assertEquals(TerminalView.MAX_FONT_SP, view.fontSizeSp)

        // One finger scrolls as before once the pinch is over.
        view.fontSizeSp = 13f
        val cell = view.sizeFor(1, 1).second
        drag(view, fromY = cell * 1f, toY = cell * 8f)
        assertTrue(view.scrollOffset in 5..7, "scrolled back by about seven lines, was ${view.scrollOffset}")
    }

    @Test
    fun emptyTerminalIsFilledWithTheBackgroundAndShowsTheCursor() {
        val session = session(ColorScheme.DEFAULT_LIGHT)
        val view = TerminalView(context)
        view.cursorBlink = false
        val (width, height) = view.sizeFor(20, 5)
        view.attach(session)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val background = 0xFF000000.toInt() or ColorScheme.DEFAULT_LIGHT.background
        assertEquals(background, bitmap.getPixel(width - 1, height - 1))
        // The view has no focus in this test, so the cursor is drawn as an outline at the origin.
        assertEquals(0xFF000000.toInt() or ColorScheme.DEFAULT_LIGHT.cursor, bitmap.getPixel(0, 0))
        assertTrue(Color.alpha(bitmap.getPixel(width / 2, height / 2)) == 255)
    }
}
