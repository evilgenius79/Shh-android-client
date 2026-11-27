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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import io.github.evilgenius79.shh.ui.HelpSections
import io.github.evilgenius79.shh.ui.KeyBarLayout
import io.github.evilgenius79.shh.ui.KeyBarScreen
import io.github.evilgenius79.shh.ui.ShhTheme
import io.github.evilgenius79.shh.ui.keyBarShown
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

    /** What the settings hold for the bar: nothing for the standard keys. */
    private var layout by mutableStateOf(emptyList<String>())

    /** An arrangement of one's own, with keys the standard bar does not have. */
    private val own = listOf("ESC", "TAB", "CTRL", "ALT", "MORE", "~", ":wq!", "ENTER", "F5", "INS", "DEL", "LEFT", "DOWN", "UP", "RIGHT", "x")

    private fun show(theme: ThemeMode = ThemeMode.DARK) {
        compose.setContent {
            ShhTheme(Settings(themeMode = theme, dynamicColor = false)) {
                // A strip of terminal above the bar, as in the app.
                Column(Modifier.background(Color(if (theme == ThemeMode.DARK) 0xFF101214 else 0xFFFFFFFF))) {
                    Box(Modifier.fillMaxWidth().height(48.dp))
                    ExtraKeys(ctrl, alt, { ctrl = it }, { alt = it }, { sent.add(it) }, { typed.add(it) }, layout = layout, uptimeMillis = { now })
                }
            }
        }
    }

    private fun showEditor() {
        compose.setContent {
            ShhTheme(Settings(themeMode = ThemeMode.DARK, dynamicColor = false)) { KeyBarScreen(layout, { layout = it }, onBack = {}) }
        }
    }

    /** A place on the bar as the editor shows it, counted from 0 along the rows. */
    private fun place(index: Int): SemanticsNodeInteraction = compose.onNodeWithTag("place:$index")

    /** A key the editor offers for the bar. */
    private fun offered(name: String): SemanticsNodeInteraction = compose.onNodeWithTag("key:$name").performScrollTo()

    private fun arranged(): List<String> = KeyBarLayout.of(layout)

    private fun isPicked(index: Int): Boolean =
        place(index).fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "Place ${index + 1} of 16, selected"

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
    fun theHowToOnlyNamesKeysTheBarReallyHas() {
        show()
        key("More keys").performClick()
        val section = HelpSections.first { it.title == "The key bar" }
        // The key caps shown in front of the explanations, the keys named in capitals in the
        // text ("PGUP", "F12"), and the characters the text lists for the panel.
        val caps = section.items.flatMap { it.keys }
        val named = section.items.flatMap { Regex("\\b[A-Z]{2,}[0-9]*\\b|\\bF[0-9]+\\b").findAll(it.text).map { match -> match.value }.toList() }
        val characters = section.items.single { it.keys == listOf("⋯") }.text.substringAfter("such as ").substringBefore(".").split(" ")
        assertTrue(listOf("CTRL", "ALT", "ESC", "TAB", "PGUP", "PGDN", "DEL", "F1", "F12").all { it in caps + named }, "found $caps and $named")
        assertEquals(listOf("~", "\\", "`", "{", "}", "[", "]"), characters)
        for (label in (caps + named + characters).distinct()) {
            when (label) {
                "←" -> key("Left").assertIsDisplayed()
                "→" -> key("Right").assertIsDisplayed()
                "⋯" -> key("More keys").assertIsDisplayed()
                else -> assertTrue(isShown(label), "the how-to mentions a key called \"$label\" that the bar does not have")
            }
        }
    }

    // ----- an arrangement of one's own -----

    @Test
    fun aBarOfOnesOwnShowsItsKeysInItsOrderAndTheyDoWhatTheyShow() {
        layout = own
        show()
        shot("extra-keys-own")
        val rows = listOf(
            listOf("ESC", "TAB", "CTRL", "ALT", "More keys", "~", ":wq!", "ENTER"),
            listOf("F5", "INS", "DEL", "Left", "Down", "Up", "Right", "x"),
        )
        for (row in rows) {
            assertEquals(1, row.map { bounds(it).top }.distinct().size, "$row share a row")
            assertEquals(row, row.sortedBy { bounds(it).left }, "in the order they were given")
        }
        assertTrue(bounds("ESC").bottom <= bounds("F5").top, "the first eight are the upper row")
        assertTrue(!isShown("HOME") && !isShown("|"), "keys that were not asked for are gone")

        for (label in listOf("ENTER", "F5", "INS", "DEL", "Up")) key(label).performClick()
        assertEquals(listOf(TerminalKey.ENTER, TerminalKey.F5, TerminalKey.INSERT, TerminalKey.DELETE, TerminalKey.UP), sent)
        for (label in listOf(":wq!", "x", "~")) key(label).performClick()
        assertEquals(listOf(":wq!", "x", "~"), typed, "a key with text types exactly that text")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun aBarOfOnesOwnFitsASmallPhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        layout = own
        show()
        shot("extra-keys-own-small-phone-large-text")
        for (label in listOf("ENTER", ":wq!", "ESC", "INS", "x")) assertLabelIntact(label)
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        for (label in listOf("ENTER", "x")) assertTrue(bounds(label).right <= screen.right, label)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun aBarOfOnesOwnTakesASingleRowWhereThereIsRoomUpperRowFirst() {
        layout = own
        show()
        val all = listOf("ESC", "TAB", "CTRL", "ALT", "More keys", "~", ":wq!", "ENTER", "F5", "INS", "DEL", "Left", "Down", "Up", "Right", "x")
        assertEquals(1, all.map { bounds(it).top }.distinct().size)
        assertEquals(all, all.sortedBy { bounds(it).left })
        assertTrue(bounds("x").right <= compose.onRoot().getUnclippedBoundsInRoot().right)
    }

    @Test
    fun aBarWithoutThePanelKeyHasNoPanel() {
        layout = KeyBarLayout.with(emptyList(), KeyBarLayout.DEFAULT.indexOf("MORE"), "F1")
        show()
        compose.onNodeWithContentDescription("More keys").assertDoesNotExist()
        key("F1").performClick()
        assertEquals(listOf(TerminalKey.F1), sent)
        assertTrue(!isShown("F2"))
    }

    @Test
    fun whateverIsStoredBecomesSixteenUsableKeys() {
        val standard = KeyBarLayout.DEFAULT
        assertEquals(16, standard.size)
        assertEquals(standard, KeyBarLayout.of(emptyList()))
        assertEquals(listOf("F1") + standard.drop(1), KeyBarLayout.of(listOf("F1")), "missing places keep the standard key")
        assertEquals(listOf("a", standard[1], "tool", "esc") + standard.drop(4), KeyBarLayout.of(listOf(" a ", "  ", "toolong", "esc")))
        assertEquals(16, KeyBarLayout.of(List(40) { "q" }).size)
        // A name is a key only as it is written: "esc" above is three letters to type.
        assertTrue("ESC" in KeyBarLayout.NAMED && "esc" !in KeyBarLayout.NAMED)
        assertEquals(emptyList(), KeyBarLayout.with(listOf("F1"), 0, "ESC"), "the standard keys are stored as nothing at all")
        assertEquals(KeyBarLayout.NAMED.size + KeyBarLayout.CHARACTERS.size, (KeyBarLayout.NAMED + KeyBarLayout.CHARACTERS).distinct().size, "no key is offered twice")
        assertTrue(standard.all { it in KeyBarLayout.NAMED || it in KeyBarLayout.CHARACTERS }, "every standard key can be put back")
    }

    @Test
    fun aPickedPlaceTakesTheKeyThatIsTappedAndTheNextPlaceIsPicked() {
        showEditor()
        shot("key-bar-editor")
        assertTrue(isPicked(0), "the first place is picked to start with")
        offered("F5").performClick()
        offered("~").performClick()
        assertEquals(listOf("F5", "~"), arranged().take(2))
        assertTrue(isPicked(2) && !isPicked(0))

        place(12).performScrollTo().performClick()
        offered("ENTER").performClick()
        assertEquals("ENTER", arranged()[12])
        assertTrue(isPicked(13))
        assertEquals(listOf("F5", "~") + KeyBarLayout.DEFAULT.subList(2, 12) + "ENTER" + KeyBarLayout.DEFAULT.drop(13), arranged(), "nothing else moved")
        // The bar on the screen is the bar that was arranged.
        place(0).performScrollTo().assertTextEquals("F5")
        place(12).assertTextEquals("ENTER")
        shot("key-bar-editor-changed")

        // After the last place comes the first again.
        place(15).performClick()
        offered("INS").performClick()
        assertEquals("INS", arranged()[15])
        assertTrue(isPicked(0))
    }

    @Test
    fun textOfOnesOwnBecomesAKeyOfAtMostFourCharacters() {
        showEditor()
        val field = compose.onNode(hasSetTextAction())
        compose.onNodeWithText("Use").performScrollTo().assertIsNotEnabled()
        field.performScrollTo().performTextInput(":wq!!!")
        compose.onNodeWithText("Use").performClick()
        assertEquals(":wq!", arranged()[0])
        field.assertTextContains("", substring = false)
        compose.onNodeWithText("Use").assertIsNotEnabled()

        field.performTextInput("ls")
        compose.onNodeWithText("Use").performClick()
        assertEquals(listOf(":wq!", "ls"), arranged().take(2))
    }

    @Test
    fun resetBringsTheStandardKeysBack() {
        layout = own
        showEditor()
        place(6).assertTextEquals(":wq!")
        compose.onNodeWithText("Reset").performClick()
        assertEquals(emptyList(), layout)
        place(6).assertTextEquals("END")
        compose.onNodeWithText("Reset").assertIsNotEnabled()

        // Putting the standard keys back by hand is the same as a reset.
        offered("F5").performClick()
        assertTrue(layout.isNotEmpty())
        place(0).performScrollTo().performClick()
        offered("ESC").performClick()
        assertEquals(emptyList(), layout)
    }

    @Test
    fun takingThePanelKeyOffTheBarIsPointedOut() {
        showEditor()
        assertTrue(compose.onAllNodes(hasText("no key for the panel", substring = true)).fetchSemanticsNodes().isEmpty())
        place(KeyBarLayout.DEFAULT.indexOf("MORE")).performClick()
        offered("F1").performClick()
        compose.onNode(hasText("no key for the panel", substring = true)).assertIsDisplayed()
        // And putting it back takes the remark away.
        place(0).performScrollTo().performClick()
        offered("MORE").performClick()
        assertTrue(compose.onAllNodes(hasText("no key for the panel", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "+keysexposed-qwerty")
    fun withAHardwareKeyboardTheBarStepsAsideIfAsked() {
        var shown = emptyList<Boolean>()
        compose.setContent {
            shown = listOf(keyBarShown(Settings()), keyBarShown(Settings(hideKeysWithKeyboard = true)), keyBarShown(Settings(showExtraKeys = false)))
        }
        compose.waitForIdle()
        assertEquals(listOf(true, false, false), shown)
    }

    @Test
    fun withoutAHardwareKeyboardTheBarStays() {
        var shown = emptyList<Boolean>()
        compose.setContent {
            shown = listOf(keyBarShown(Settings(hideKeysWithKeyboard = true)), keyBarShown(Settings(showExtraKeys = false, hideKeysWithKeyboard = true)))
        }
        compose.waitForIdle()
        assertEquals(listOf(true, false), shown)
    }

    // ----- the modifiers -----

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
