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
