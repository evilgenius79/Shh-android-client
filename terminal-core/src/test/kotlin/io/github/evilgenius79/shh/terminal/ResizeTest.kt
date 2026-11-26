package io.github.evilgenius79.shh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResizeTest {
    @Test
    fun shrinkingRowsMovesTopLinesToHistoryAndGrowingBringsThemBack() {
        val t = TestTerminal(10, 4).feed("1\r\n2\r\n3\r\n4")
        t.emulator.resize(10, 2)
        assertEquals(listOf("3", "4"), t.lines())
        assertEquals(listOf("1", "2"), t.history())
        assertEquals(1 to 1, t.cursor)

        t.emulator.resize(10, 4)
        assertEquals(listOf("1", "2", "3", "4"), t.lines())
        assertEquals(emptyList(), t.history())
        assertEquals(3 to 1, t.cursor)
    }

    @Test
    fun shrinkingRowsDropsBlankLinesBelowTheCursorFirst() {
        val t = TestTerminal(10, 4).feed("1\r\n2")
        t.emulator.resize(10, 3)
        assertEquals(listOf("1", "2", ""), t.lines())
        assertEquals(0, t.emulator.scrollbackSize)
        assertEquals(1 to 1, t.cursor)
    }

    @Test
    fun growingRowsPullsNaturallyScrolledHistoryBack() {
        val t = TestTerminal(10, 2).feed("1\r\n2\r\n3\r\n4")
        assertEquals(listOf("1", "2"), t.history())
        t.emulator.resize(10, 3)
        assertEquals(listOf("2", "3", "4"), t.lines())
        assertEquals(listOf("1"), t.history())
        assertEquals(2 to 1, t.cursor)
    }

    @Test
    fun growingRowsAfterClearingTheScreenDoesNotBringOldOutputBack() {
        val t = TestTerminal(10, 2).feed("1\r\n2\r\n3")
        t.feed("\\e[H\\e[2Jp")
        t.emulator.resize(10, 4)
        assertEquals(listOf("p", "", "", ""), t.lines())
        assertEquals(listOf("1"), t.history())
        assertEquals(0 to 1, t.cursor)
    }

    @Test
    fun contentBelowTheCursorIsKept() {
        val t = TestTerminal(10, 4).feed("1\r\n2\r\n3\r\n4\\e[1;1H")
        t.emulator.resize(10, 2)
        // Everything down to the last used line stays; the cursor is clamped onto the screen.
        assertEquals(listOf("3", "4"), t.lines())
        assertEquals(listOf("1", "2"), t.history())
        assertEquals(0 to 0, t.cursor)
    }

    @Test
    fun narrowingRewrapsLines() {
        val t = TestTerminal(10, 4).feed("abcdefghij\r\nxy")
        t.emulator.resize(5, 4)
        assertEquals(listOf("abcde", "fghij", "xy", ""), t.lines())
        assertTrue(t.emulator.row(0).isWrapped)
        assertFalse(t.emulator.row(1).isWrapped)
        assertEquals(2 to 2, t.cursor)
    }

    @Test
    fun wideningJoinsWrappedLinesAgain() {
        val t = TestTerminal(5, 4).feed("abcdefgh\r\nxy")
        assertEquals(listOf("abcde", "fgh", "xy", ""), t.lines())
        t.emulator.resize(10, 4)
        assertEquals(listOf("abcdefgh", "xy", "", ""), t.lines())
        assertFalse(t.emulator.row(0).isWrapped)
        assertEquals(1 to 2, t.cursor)
    }

    @Test
    fun hardLineBreaksSurviveReflow() {
        val t = TestTerminal(5, 4).feed("abcde\r\nfg")
        t.emulator.resize(10, 4)
        assertEquals(listOf("abcde", "fg", "", ""), t.lines())
    }

    @Test
    fun cursorFollowsItsCharacterThroughReflow() {
        val t = TestTerminal(10, 3).feed("abcdefgh")
        assertEquals(0 to 8, t.cursor)
        t.emulator.resize(5, 3)
        assertEquals(listOf("abcde", "fgh", ""), t.lines())
        assertEquals(1 to 3, t.cursor)
        t.emulator.resize(10, 3)
        assertEquals(0 to 8, t.cursor)
    }

    @Test
    fun cursorInTheMiddleOfALineIsTracked() {
        val t = TestTerminal(10, 3).feed("abcdefgh\\e[1;3H")
        t.emulator.resize(4, 3)
        assertEquals(listOf("abcd", "efgh", ""), t.lines())
        assertEquals(0 to 2, t.cursor)
        t.feed("\\e[1;1H")
        t.emulator.resize(10, 3)
        assertEquals(listOf("abcdefgh", "", ""), t.lines())
    }

    @Test
    fun narrowingPushesOverflowIntoHistory() {
        val t = TestTerminal(8, 2).feed("abcdefgh\r\n12345678")
        t.emulator.resize(4, 2)
        assertEquals(listOf("1234", "5678"), t.lines())
        assertEquals(listOf("abcdefgh"), listOf(t.emulator.getText(-2, 0, -1, 3)))
        assertEquals(2, t.emulator.scrollbackSize)
    }

    @Test
    fun historyIsReflowedToo() {
        val t = TestTerminal(4, 2).feed("abcdefgh\r\nxy\r\nz")
        assertEquals(listOf("abcd", "efgh"), t.history())
        t.emulator.resize(8, 2)
        assertEquals(listOf("abcdefgh"), t.history())
        assertEquals(listOf("xy", "z"), t.lines())
    }

    @Test
    fun wideCharactersAreNeverSplitByReflow() {
        val t = TestTerminal(6, 3).feed("a\u4e2d\u6587b")
        t.emulator.resize(4, 3)
        // "a中" fills three cells, the next wide character does not fit in the fourth.
        assertEquals(listOf("a\u4e2d", "\u6587b", ""), t.lines())
        t.emulator.resize(6, 3)
        assertEquals(listOf("a\u4e2d\u6587b", "", ""), t.lines())
    }

    @Test
    fun colouredBlanksCountAsContent() {
        val t = TestTerminal(6, 3).feed("\\e[41m\\e[K\\e[0m\r\nx")
        t.emulator.resize(6, 2)
        assertEquals(TextStyle.indexed(1), TextStyle.background(t.styleAt(0, 5)))
    }

    @Test
    fun alternateScreenIsCroppedAndMainScreenIsPreserved() {
        val t = TestTerminal(10, 3).feed("main line\r\nsecond\\e[?1049h\\e[Hfull\r\nscreen\r\napp")
        t.emulator.resize(6, 2)
        assertTrue(t.emulator.isAlternateScreenActive)
        assertEquals(listOf("full", "screen"), t.lines())
        t.feed("\\e[?1049l")
        // The main screen was reflowed to the new width while it was hidden.
        assertEquals("main line\nsecond", t.emulator.getText(-t.emulator.scrollbackSize, 0, 1, 5))
        assertEquals(listOf("ine", "second"), t.lines())
    }

    @Test
    fun marginsAreResetByResize() {
        val t = TestTerminal(10, 5).feed("\\e[2;3r")
        t.emulator.resize(10, 6)
        t.feed("\\e[6;1H\n\n")
        // Scrolling now involves the whole screen again, so lines reach the history.
        assertEquals(2, t.emulator.scrollbackSize)
    }

    @Test
    fun scrollbackLimitIsEnforced() {
        val t = TestTerminal(10, 2, scrollback = 3)
        for (i in 1..10) t.feed("$i\r\n")
        assertEquals(listOf("7", "8", "9"), t.history())
        t.emulator.maxScrollbackLines = 1
        assertEquals(listOf("9"), t.history())
        t.emulator.maxScrollbackLines = 0
        t.feed("x\r\ny\r\n")
        assertEquals(0, t.emulator.scrollbackSize)
    }

    @Test
    fun resizeToSameSizeIsANoOp() {
        val t = TestTerminal(10, 3).feed("abc\\e[2;3r")
        t.emulator.resize(10, 3)
        t.feed("\\e[3;1H\n")
        assertEquals(0, t.emulator.scrollbackSize)
    }

    @Test
    fun tabStopsExtendToNewColumns() {
        val t = TestTerminal(10, 2)
        t.emulator.resize(30, 2)
        t.feed("\t\t\tx")
        assertEquals(0 to 25, t.cursor)
    }
}
