package io.github.evilgenius79.shh

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import io.github.evilgenius79.shh.terminal.FontException
import io.github.evilgenius79.shh.terminal.TerminalFonts
import io.github.evilgenius79.shh.ui.fontNameOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Choosing a font file for the terminal. Robolectric's native graphics read real font files, and
 * its runtime brings some along: they serve as the fonts a user might pick.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TerminalFontsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun stored(): List<String> = File(context.filesDir, "fonts").list().orEmpty().sorted()

    @Test
    fun aMonospacedFontIsStoredAndCanBeLoaded() {
        val name = TerminalFonts.install(context, font(MONOSPACED))
        assertEquals(listOf(name), stored())
        val typeface = assertNotNull(TerminalFonts.load(TerminalFonts.file(context, name)))
        assertNull(TerminalFonts.problemWith(typeface))

        // Choosing another one replaces it: the app keeps a single font.
        Thread.sleep(5)
        val second = TerminalFonts.install(context, font(OTHER_MONOSPACED))
        assertTrue(second != name)
        assertEquals(listOf(second), stored())

        TerminalFonts.remove(context)
        assertEquals(emptyList(), stored())
        assertNull(TerminalFonts.load(TerminalFonts.file(context, second)), "a font that is gone loads as nothing")
    }

    @Test
    fun aFontWhoseCharactersDifferInWidthIsRefusedAndTheEarlierOneStays() {
        val earlier = TerminalFonts.install(context, font(MONOSPACED))
        val refusal = assertFailsWith<FontException> { TerminalFonts.install(context, font(PROPORTIONAL)) }
        assertTrue("monospaced" in refusal.message!!, refusal.message)
        assertEquals(listOf(earlier), stored())
    }

    @Test
    fun aFileThatIsNoFontIsRefused() {
        val refusal = assertFailsWith<FontException> { TerminalFonts.install(context, ByteArrayInputStream(ByteArray(4096) { (it * 7).toByte() })) }
        assertTrue("not a font" in refusal.message!!, refusal.message)
        assertFailsWith<FontException> { TerminalFonts.install(context, ByteArrayInputStream(ByteArray(0))) }
        assertFailsWith<FontException> { TerminalFonts.install(context, "<html>404 Not Found</html>".byteInputStream()) }
        assertEquals(emptyList(), stored(), "nothing is left behind")
    }

    @Test
    fun anEndlessFileIsStoppedAtTheSizeLimit() {
        val endless = object : InputStream() {
            var served = 0L

            override fun read(): Int = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                served += length
                return length
            }
        }
        val refusal = assertFailsWith<FontException> { TerminalFonts.install(context, endless) }
        assertTrue("larger than 32 MB" in refusal.message!!, refusal.message)
        assertTrue(endless.served <= TerminalFonts.MAX_BYTES + 1024 * 1024, "it stopped reading at the limit, after ${endless.served} bytes")
        assertEquals(emptyList(), stored())
    }

    @Test
    fun theTypefacesTheAppAlreadyHasAreJudgedRight() {
        assertNull(TerminalFonts.problemWith(assertNotNull(ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular))))
        assertNull(TerminalFonts.problemWith(assertNotNull(ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold))))
        assertNotNull(TerminalFonts.problemWith(Typeface.DEFAULT), "the system's text font is not monospaced")
    }

    @Test
    fun aStoredNameCannotPointOutsideTheFontFolder() {
        val folder = File(context.filesDir, "fonts").canonicalFile
        assertEquals(folder, TerminalFonts.file(context, "../../shared_prefs/secrets.xml").canonicalFile.parentFile)
        assertEquals(folder, TerminalFonts.file(context, "/etc/passwd").canonicalFile.parentFile)
    }

    @Test
    fun aFontIsCalledWhatItsFileIsCalled() {
        // The name for a font without one is a text of the app, so the app's resources are asked for it.
        val resources = context.resources
        assertEquals("FiraCode-Regular", fontNameOf(resources, "FiraCode-Regular.ttf"))
        assertEquals("Iosevka Term", fontNameOf(resources, "Iosevka Term.OTF"))
        assertEquals("font.v2", fontNameOf(resources, "font.v2"), "only the endings of font files are dropped")
        assertEquals("A font of your own", fontNameOf(resources, null))
        assertEquals("A font of your own", fontNameOf(resources, "  "))
        assertEquals(60, fontNameOf(resources, "x".repeat(200) + ".ttf").length)
    }

    companion object {
        const val MONOSPACED = "DroidSansMono.ttf"
        const val OTHER_MONOSPACED = "CutiveMono.ttf"
        const val PROPORTIONAL = "DroidSans.ttf"

        /** A font from Robolectric's graphics runtime, which is on the class path of the tests. */
        fun font(name: String): InputStream =
            assertNotNull(TerminalFontsTest::class.java.classLoader!!.getResourceAsStream("fonts/$name"), "Robolectric no longer brings fonts/$name along")
    }
}
