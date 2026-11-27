package io.github.evilgenius79.shh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.evilgenius79.shh.data.Settings
import io.github.evilgenius79.shh.data.ThemeMode
import io.github.evilgenius79.shh.terminal.ModifierState
import io.github.evilgenius79.shh.terminal.TerminalKey
import io.github.evilgenius79.shh.ui.ExtraKeys
import io.github.evilgenius79.shh.ui.ShhTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PHONE = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav"
private const val SMALL_PHONE = "w320dp-h640dp-normal-long-notround-any-mdpi-keyshidden-nonav"
private const val LANDSCAPE = "w891dp-h411dp-normal-long-notround-any-420dpi-keyshidden-nonav"

/** The bar of extra keys under the terminal: its layout on different screens and what the keys do. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = PHONE)
class ExtraKeysTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = ArrayList<TerminalKey>()
    private val typed = ArrayList<String>()
    private var ctrl by mutableStateOf(ModifierState.OFF)
    private var alt by mutableStateOf(ModifierState.OFF)

    /** The clock the bar measures double taps with. */
    private var now = 10_000L

    private val words = listOf("ESC", "TAB", "CTRL", "ALT", "HOME", "END", "PGUP", "PGDN")
    private val arrows = listOf("Up", "Down", "Left", "Right")

    private fun show(theme: ThemeMode = ThemeMode.DARK) {
        compose.setContent {
            ShhTheme(Settings(themeMode = theme, dynamicColor = false)) {
                // A strip of terminal above the bar, as in the app.
                Column(Modifier.background(Color(if (theme == ThemeMode.DARK) 0xFF101214 else 0xFFFFFFFF))) {
                    Box(Modifier.fillMaxWidth().height(48.dp))
                    ExtraKeys(ctrl, alt, { ctrl = it }, { alt = it }, { sent.add(it) }, { typed.add(it) }, uptimeMillis = { now })
                }
            }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File("build/outputs/screenshots", "$name.png").path)
    }

    private fun key(label: String): SemanticsNodeInteraction =
        if (label in arrows || label == "More keys") compose.onNodeWithContentDescription(label) else compose.onNodeWithText(label)

    private fun bounds(label: String): DpRect = key(label).assertIsDisplayed().getUnclippedBoundsInRoot()

    private fun isShown(label: String): Boolean = compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty()

    /** Fails unless the key called [label] shows its whole label on one line. */
    private fun assertLabelIntact(label: String) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertEquals(1, layout.lineCount, "\"$label\" is broken over several lines")
        val needed = layout.multiParagraph.maxIntrinsicWidth
        assertTrue(layout.size.width + 0.5f >= needed, "\"$label\" is cut off: it needs $needed px and got ${layout.size.width}")
    }

    // ----- layout -----

    @Test
    fun everyKeyIsOnScreenAndTheArrowsFormAnInvertedT() {
        show()
        shot("extra-keys-dark")
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val all = words + arrows + listOf("/", "|", "-", "More keys")
        for (label in all) {
            val box = bounds(label)
            assertTrue(box.left >= 0.dp && box.right <= screen.right, "$label reaches from ${box.left} to ${box.right} on a ${screen.right} wide screen")
        }
        // Nothing to scroll to: sixteen keys in two rows of eight, all the same size.
        assertEquals(2, all.map { bounds(it).top }.distinct().size)
        assertEquals(1, all.map { (bounds(it).right - bounds(it).left).value.toInt() }.distinct().size, "every key is equally wide")

        val up = bounds("Up")
        val down = bounds("Down")
        val left = bounds("Left")
        val right = bounds("Right")
        assertEquals(down.left, up.left, "Up sits right above Down")
        assertTrue(up.bottom <= down.top)
        assertEquals(listOf(down.top, down.top), listOf(left.top, right.top), "Left, Down and Right share the bottom row")
        assertTrue(left.right <= down.left && down.right <= right.left, "in that order")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun aSmallPhoneWithLargeTextShowsEveryLabelWhole() {
        RuntimeEnvironment.setFontScale(1.3f)
        show()
        shot("extra-keys-small-phone-large-text")
        for (label in words) assertLabelIntact(label)
        key("More keys").performClick()
        for (label in listOf("F1", "F10", "F12", "DEL")) assertLabelIntact(label)
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        for (label in words + listOf("F8", "}")) assertTrue(bounds(label).right <= screen.right, label)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun withRoomToSpareTheKeysTakeASingleRow() {
        show()
        shot("extra-keys-landscape")
        val all = words + arrows + listOf("/", "|", "-", "More keys")
        assertEquals(1, all.map { bounds(it).top }.distinct().size, "one row leaves more room for the terminal")
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        for (label in all) assertTrue(bounds(label).right <= screen.right, label)
        key("More keys").performClick()
        shot("extra-keys-landscape-more")
        assertEquals(1, (1..12).map { bounds("F$it").top }.distinct().size, "the function keys share a row too")
    }

    @Test
    fun lightTheme() {
        show(ThemeMode.LIGHT)
        ctrl = ModifierState.ONCE
        shot("extra-keys-light")
        key("CTRL").assertIsDisplayed()
    }

    // ----- behaviour -----

    @Test
    fun keysSendWhatTheyShow() {
        show()
        key("ESC").performClick()
        key("TAB").performClick()
        for (arrow in arrows) key(arrow).performClick()
        key("HOME").performClick()
        key("END").performClick()
        key("PGUP").performClick()
        key("PGDN").performClick()
        assertEquals(
            listOf(
                TerminalKey.ESCAPE, TerminalKey.TAB, TerminalKey.UP, TerminalKey.DOWN, TerminalKey.LEFT, TerminalKey.RIGHT,
                TerminalKey.HOME, TerminalKey.END, TerminalKey.PAGE_UP, TerminalKey.PAGE_DOWN,
            ),
            sent,
        )
        for (character in listOf("/", "|", "-")) key(character).performClick()
        assertEquals(listOf("/", "|", "-"), typed)
    }

    @Test
    fun theMoreKeyOpensThePanelWithFunctionKeysAndAwkwardCharacters() {
        show()
        assertTrue(!isShown("F1") && !isShown("~"), "the panel starts closed")
        key("More keys").performClick()
        shot("extra-keys-more")
        for (number in 1..12) key("F$number").performClick()
        key("DEL").performClick()
        assertEquals(
            listOf(
                TerminalKey.F1, TerminalKey.F2, TerminalKey.F3, TerminalKey.F4, TerminalKey.F5, TerminalKey.F6, TerminalKey.F7,
                TerminalKey.F8, TerminalKey.F9, TerminalKey.F10, TerminalKey.F11, TerminalKey.F12, TerminalKey.DELETE,
            ),
            sent,
        )
        val characters = listOf("~", "`", "^", "\\", "=", "<", ">", "[", "]", "{", "}")
        for (character in characters) key(character).performClick()
        assertEquals(characters, typed)
        // The keys that are always there stay where they were, above the keyboard.
        assertTrue(bounds("F1").bottom <= bounds("ESC").top)
        key("More keys").performClick()
        assertTrue(!isShown("F1"), "and it closes again")
    }

    @Test
    fun ctrlAppliesOnceAndATapPutsItBack() {
        show()
        key("CTRL").performClick()
        assertEquals(ModifierState.ONCE, ctrl)
        now += 2_000
        key("CTRL").performClick()
        assertEquals(ModifierState.OFF, ctrl, "a second tap a while later takes it back")
        assertEquals(ModifierState.OFF, alt)
    }

    @Test
    fun tappingAModifierTwiceInARowLocksItUntilTheNextTap() {
        show()
        key("ALT").performClick()
        now += 150
        key("ALT").performClick()
        assertEquals(ModifierState.LOCKED, alt)
        key("CTRL").performClick()
        shot("extra-keys-modifiers")
        now += 5_000
        key("ALT").performClick()
        assertEquals(ModifierState.OFF, alt)
        assertEquals(ModifierState.ONCE, ctrl, "the other modifier is its own key")

        // Two taps on different modifiers in quick succession are not a double tap.
        now += 5_000
        key("ALT").performClick()
        now += 100
        key("CTRL").performClick()
        now += 100
        key("ALT").performClick()
        assertEquals(ModifierState.OFF, alt)
    }

    @Test
    fun holdingAnArrowRepeatsItButATapSendsItOnce() {
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(100)

        key("Left").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(emptyList(), sent, "nothing yet: it could still be a tap")
        compose.mainClock.advanceTimeBy(700)
        key("Left").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(sent.size in 8..14, "about ten repeats in the 600 ms after the delay, got ${sent.size}")
        assertTrue(sent.all { it == TerminalKey.LEFT })
        val afterRelease = sent.size
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(afterRelease, sent.size, "it stops when the finger lifts")

        sent.clear()
        key("Left").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(100)
        key("Left").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(listOf(TerminalKey.LEFT), sent)

        // Escape does not repeat however long it is held, and a finger that slides off sends nothing.
        sent.clear()
        key("ESC").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(2_000)
        key("ESC").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(listOf(TerminalKey.ESCAPE), sent)
        sent.clear()
        key("ESC").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -300f))
            up()
        }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(emptyList(), sent)
    }
}
