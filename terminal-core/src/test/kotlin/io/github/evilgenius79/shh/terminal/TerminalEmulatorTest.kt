package io.github.evilgenius79.shh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalEmulatorTest {
    // ----- printing and wrapping -----

    @Test
    fun printsTextAndAdvancesCursor() {
        val t = TestTerminal(20, 5).feed("hello")
        assertEquals("hello", t.line(0))
        assertEquals(0 to 5, t.cursor)
    }

    @Test
    fun carriageReturnAndLineFeed() {
        val t = TestTerminal(20, 5).feed("ab\r\ncd\nef")
        assertEquals(listOf("ab", "cd", "  ef", "", ""), t.lines())
        assertEquals(2 to 4, t.cursor)
    }

    @Test
    fun wrapIsDeferredUntilNextCharacter() {
        val t = TestTerminal(5, 3).feed("abcde")
        // The cursor stays on the last column until something else is printed.
        assertEquals(0 to 4, t.cursor)
        t.feed("f")
        assertEquals(listOf("abcde", "f", ""), t.lines())
        assertEquals(1 to 1, t.cursor)
        assertTrue(t.emulator.row(0).isWrapped)
        assertFalse(t.emulator.row(1).isWrapped)
    }

    @Test
    fun carriageReturnCancelsPendingWrap() {
        val t = TestTerminal(5, 3).feed("abcde\rX")
        assertEquals(listOf("Xbcde", "", ""), t.lines())
    }

    @Test
    fun noAutoWrapOverwritesLastColumn() {
        val t = TestTerminal(5, 3).feed("\\e[?7labcdefg")
        assertEquals(listOf("abcdg", "", ""), t.lines())
        assertEquals(0 to 4, t.cursor)
    }

    @Test
    fun scrollsWhenWritingPastBottom() {
        val t = TestTerminal(10, 3).feed("1\r\n2\r\n3\r\n4")
        assertEquals(listOf("2", "3", "4"), t.lines())
        assertEquals(listOf("1"), t.history())
        assertEquals(1L, t.emulator.scrollCounter)
    }

    @Test
    fun backspaceAndTab() {
        val t = TestTerminal(20, 3).feed("ab\bX\tY")
        assertEquals("aX      Y", t.line(0))
        assertEquals(0 to 9, t.cursor)
    }

    @Test
    fun bellIsReported() {
        val t = TestTerminal().feed("\u0007\u0007")
        assertEquals(2, t.bells)
    }

    // ----- cursor movement -----

    @Test
    fun cursorPositioning() {
        val t = TestTerminal(20, 10)
        t.feed("\\e[5;7H")
        assertEquals(4 to 6, t.cursor)
        t.feed("\\e[2A\\e[3C")
        assertEquals(2 to 9, t.cursor)
        t.feed("\\e[B\\e[4D")
        assertEquals(3 to 5, t.cursor)
        t.feed("\\e[H")
        assertEquals(0 to 0, t.cursor)
        t.feed("\\e[99;99H")
        assertEquals(9 to 19, t.cursor)
        t.feed("\\e[3G\\e[4d")
        assertEquals(3 to 2, t.cursor)
        t.feed("\\e[2E")
        assertEquals(5 to 0, t.cursor)
        t.feed("\\e[5C\\e[F")
        assertEquals(4 to 0, t.cursor)
    }

    @Test
    fun cursorMovementStopsAtScreenEdges() {
        val t = TestTerminal(10, 5).feed("\\e[99A\\e[99D")
        assertEquals(0 to 0, t.cursor)
        t.feed("\\e[99B\\e[99C")
        assertEquals(4 to 9, t.cursor)
    }

    @Test
    fun saveAndRestoreCursor() {
        val t = TestTerminal(20, 5).feed("\\e[2;3H\\e[31m\\e7\\e[H\\e[0mX\\e8Y")
        assertEquals(1 to 3, t.cursor)
        assertEquals("  Y", t.line(1))
        assertEquals(TextStyle.indexed(1), TextStyle.foreground(t.styleAt(1, 2)))
    }

    @Test
    fun reverseIndexScrollsDownAtTop() {
        val t = TestTerminal(10, 3).feed("a\r\nb\r\nc\\e[H\\eM")
        assertEquals(listOf("", "a", "b"), t.lines())
    }

    @Test
    fun tabStops() {
        val t = TestTerminal(40, 3)
        t.feed("\\e[3g") // clear all
        t.feed("\\e[1;5H\\eH\\e[1;12H\\eH\\e[H")
        t.feed("\tA\tB")
        assertEquals("    A      B", t.line(0))
        t.feed("\\e[Z\\e[Z")
        assertEquals(0 to 4, t.cursor)
    }

    // ----- erasing and editing -----

    @Test
    fun eraseInLine() {
        val t = TestTerminal(10, 3).feed("0123456789\\e[1;5H\\e[K")
        assertEquals("0123", t.line(0))
        t.feed("\\e[2;1H0123456789\\e[2;5H\\e[1K")
        assertEquals("     56789", t.line(1))
        t.feed("\\e[3;1Habc\\e[2K")
        assertEquals("", t.line(2))
    }

    @Test
    fun eraseInDisplay() {
        val t = TestTerminal(5, 4).feed("aaaaa\r\nbbbbb\r\nccccc\r\nddddd")
        t.feed("\\e[2;3H\\e[J")
        assertEquals(listOf("aaaaa", "bb", "", ""), t.lines())
        t.feed("\\e[2J")
        assertEquals(listOf("", "", "", ""), t.lines())
        assertEquals(1 to 2, t.cursor)
    }

    @Test
    fun eraseAboveCursor() {
        val t = TestTerminal(5, 3).feed("aaaaa\r\nbbbbb\r\nccccc\\e[2;3H\\e[1J")
        assertEquals(listOf("", "   bb", "ccccc"), t.lines())
    }

    @Test
    fun eraseUsesCurrentBackground() {
        val t = TestTerminal(10, 3).feed("\\e[44m\\e[K")
        assertEquals(TextStyle.indexed(4), TextStyle.background(t.styleAt(0, 9)))
        // Attributes other than the background do not leak into erased cells.
        t.feed("\\e[1;4m\\e[2K")
        assertFalse(TextStyle.has(t.styleAt(0, 0), TextStyle.BOLD))
    }

    @Test
    fun clearingScrollback() {
        val t = TestTerminal(10, 2).feed("1\r\n2\r\n3\r\n4")
        assertEquals(2, t.emulator.scrollbackSize)
        t.feed("\\e[3J")
        assertEquals(0, t.emulator.scrollbackSize)
        assertEquals(listOf("3", "4"), t.lines())
    }

    @Test
    fun insertAndDeleteCharacters() {
        val t = TestTerminal(10, 2).feed("abcdef\\e[1;3H\\e[2@")
        assertEquals("ab  cdef", t.line(0))
        t.feed("\\e[3P")
        assertEquals("abdef", t.line(0))
        t.feed("\\e[2X")
        assertEquals("ab  f", t.line(0))
    }

    @Test
    fun insertModeShiftsText() {
        val t = TestTerminal(10, 2).feed("abc\\e[1;2H\\e[4hXY\\e[4lZ")
        assertEquals("aXYZc", t.line(0))
    }

    @Test
    fun insertAndDeleteLines() {
        val t = TestTerminal(5, 4).feed("a\r\nb\r\nc\r\nd\\e[2;1H\\e[L")
        assertEquals(listOf("a", "", "b", "c"), t.lines())
        t.feed("\\e[2M")
        assertEquals(listOf("a", "c", "", ""), t.lines())
    }

    @Test
    fun repeatCharacter() {
        val t = TestTerminal(10, 2).feed("x\\e[4b")
        assertEquals("xxxxx", t.line(0))
    }

    // ----- scroll regions -----

    @Test
    fun scrollRegionConfinesScrolling() {
        val t = TestTerminal(5, 5).feed("1\r\n2\r\n3\r\n4\r\n5")
        t.feed("\\e[2;4r") // rows 2..4, cursor goes home
        assertEquals(0 to 0, t.cursor)
        t.feed("\\e[4;1H\n")
        assertEquals(listOf("1", "3", "4", "", "5"), t.lines())
        // Nothing goes to the scrollback when the region does not start at the top.
        assertEquals(0, t.emulator.scrollbackSize)
    }

    @Test
    fun scrollUpAndDownCommands() {
        val t = TestTerminal(5, 4).feed("1\r\n2\r\n3\r\n4\\e[2S")
        assertEquals(listOf("3", "4", "", ""), t.lines())
        t.feed("\\e[T")
        assertEquals(listOf("", "3", "4", ""), t.lines())
    }

    @Test
    fun originModeIsRelativeToMargins() {
        val t = TestTerminal(10, 10).feed("\\e[3;6r\\e[?6h\\e[1;1HX")
        assertEquals("X", t.line(2))
        t.feed("\\e[99;1HY")
        assertEquals("Y", t.line(5))
        t.feed("\\e[6n")
        assertEquals("\u001b[4;2R", t.takeWritten())
    }

    @Test
    fun leftRightMargins() {
        val t = TestTerminal(10, 3).feed("0123456789\r\nabcdefghij\r\nABCDEFGHIJ")
        t.feed("\\e[?69h\\e[3;6s") // columns 3..6
        t.feed("\\e[1;3H\\e[M") // delete line inside the margins
        assertEquals(listOf("01cdef6789", "abCDEFghij", "AB    GHIJ"), t.lines())
    }

    // ----- attributes -----

    @Test
    fun basicAttributes() {
        val t = TestTerminal().feed("\\e[1;3;4;7;9mX\\e[22;23;24;27;29mY\\e[0mZ")
        val x = t.styleAt(0, 0)
        assertTrue(TextStyle.has(x, TextStyle.BOLD))
        assertTrue(TextStyle.has(x, TextStyle.ITALIC))
        assertTrue(TextStyle.has(x, TextStyle.UNDERLINE))
        assertTrue(TextStyle.has(x, TextStyle.INVERSE))
        assertTrue(TextStyle.has(x, TextStyle.STRIKETHROUGH))
        assertEquals(TextStyle.DEFAULT, t.styleAt(0, 1))
        assertEquals(TextStyle.DEFAULT, t.styleAt(0, 2))
    }

    @Test
    fun ansiAndBrightColors() {
        val t = TestTerminal().feed("\\e[31;42mA\\e[91;102mB\\e[39;49mC")
        assertEquals(TextStyle.indexed(1), TextStyle.foreground(t.styleAt(0, 0)))
        assertEquals(TextStyle.indexed(2), TextStyle.background(t.styleAt(0, 0)))
        assertEquals(TextStyle.indexed(9), TextStyle.foreground(t.styleAt(0, 1)))
        assertEquals(TextStyle.indexed(10), TextStyle.background(t.styleAt(0, 1)))
        assertEquals(TextStyle.DEFAULT, t.styleAt(0, 2))
    }

    @Test
    fun indexedAndTrueColors() {
        val t = TestTerminal().feed("\\e[38;5;208mA\\e[48;2;10;20;30mB\\e[0m")
        assertEquals(TextStyle.indexed(208), TextStyle.foreground(t.styleAt(0, 0)))
        assertEquals(TextStyle.rgb(10, 20, 30), TextStyle.background(t.styleAt(0, 1)))
    }

    @Test
    fun colonSeparatedColors() {
        val t = TestTerminal().feed("\\e[38:2::1:2:3mA\\e[0m\\e[38:2:4:5:6mB\\e[0m\\e[48:5:99mC")
        assertEquals(TextStyle.rgb(1, 2, 3), TextStyle.foreground(t.styleAt(0, 0)))
        assertEquals(TextStyle.rgb(4, 5, 6), TextStyle.foreground(t.styleAt(0, 1)))
        assertEquals(TextStyle.indexed(99), TextStyle.background(t.styleAt(0, 2)))
    }

    @Test
    fun attributesAfterExtendedColorStillApply() {
        val t = TestTerminal().feed("\\e[38;5;1;1mA")
        assertEquals(TextStyle.indexed(1), TextStyle.foreground(t.styleAt(0, 0)))
        assertTrue(TextStyle.has(t.styleAt(0, 0), TextStyle.BOLD))
    }

    @Test
    fun underlineStyles() {
        val t = TestTerminal().feed("\\e[4:3mA\\e[4:0mB\\e[21mC\\e[24mD")
        assertTrue(TextStyle.has(t.styleAt(0, 0), TextStyle.UNDERLINE_CURLY))
        assertFalse(TextStyle.has(t.styleAt(0, 1), TextStyle.UNDERLINE))
        assertTrue(TextStyle.has(t.styleAt(0, 2), TextStyle.UNDERLINE_DOUBLE))
        assertEquals(TextStyle.DEFAULT, t.styleAt(0, 3))
    }

    // ----- character sets and unicode -----

    @Test
    fun decSpecialGraphics() {
        val t = TestTerminal().feed("\\e(0lqk\\e(Blqk")
        assertEquals("\u250c\u2500\u2510lqk", t.line(0))
    }

    @Test
    fun shiftOutUsesG1() {
        val t = TestTerminal().feed("\\e)0\u000ex\u000fx")
        assertEquals("\u2502x", t.line(0))
    }

    @Test
    fun wideCharactersTakeTwoCells() {
        val t = TestTerminal(10, 2).feed("\u4e2d\u6587a")
        assertEquals("\u4e2d\u6587a", t.line(0))
        assertEquals(0 to 5, t.cursor)
        assertTrue(TextStyle.has(t.styleAt(0, 0), TextStyle.WIDE))
        assertTrue(TextStyle.has(t.styleAt(0, 1), TextStyle.WIDE_TRAILER))
    }

    @Test
    fun wideCharacterWrapsWhenItDoesNotFit() {
        val t = TestTerminal(5, 2).feed("abcd\u4e2d")
        assertEquals(listOf("abcd", "\u4e2d"), t.lines())
        assertTrue(t.emulator.row(0).isWrapped)
    }

    @Test
    fun overwritingHalfOfWideCharacterClearsTheOtherHalf() {
        val t = TestTerminal(10, 2).feed("\u4e2d\\e[1;2HX")
        assertEquals(" X", t.line(0))
        assertFalse(TextStyle.has(t.styleAt(0, 0), TextStyle.WIDE))
    }

    @Test
    fun combiningMarksJoinThePreviousCell() {
        val t = TestTerminal(10, 2).feed("e\u0301x")
        assertEquals("e\u0301x", t.line(0))
        assertEquals(0 to 2, t.cursor)
        assertEquals("e\u0301", t.emulator.row(0).cluster(0))
    }

    @Test
    fun utf8SplitAcrossChunks() {
        val t = TestTerminal(10, 2)
        val bytes = "\u20ac\ud83d\ude00".toByteArray(Charsets.UTF_8)
        for (b in bytes) t.emulator.append(byteArrayOf(b))
        assertEquals("\u20ac\ud83d\ude00", t.line(0))
        assertEquals(0 to 3, t.cursor)
    }

    @Test
    fun invalidUtf8BecomesReplacementCharacter() {
        val t = TestTerminal(10, 2)
        t.emulator.append(byteArrayOf(0xFF.toByte(), 'a'.code.toByte(), 0xC3.toByte(), 'b'.code.toByte()))
        assertEquals("\ufffda\ufffdb", t.line(0))
        // Overlong encoding of '/' must not decode to '/'.
        t.emulator.append(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), 0xC0.toByte(), 0xAF.toByte()))
        assertEquals("\ufffd", t.line(1))
    }

    // ----- screens -----

    @Test
    fun alternateScreenIsSeparateAndRestoresCursor() {
        val t = TestTerminal(10, 3).feed("main\\e[?1049h")
        assertTrue(t.emulator.isAlternateScreenActive)
        assertEquals(listOf("", "", ""), t.lines())
        t.feed("\\e[Halt")
        assertEquals("alt", t.line(0))
        t.feed("\\e[?1049l")
        assertFalse(t.emulator.isAlternateScreenActive)
        assertEquals("main", t.line(0))
        assertEquals(0 to 4, t.cursor)
    }

    @Test
    fun alternateScreenHasNoScrollback() {
        val t = TestTerminal(10, 2).feed("a\r\nb\r\nc")
        assertEquals(1, t.emulator.scrollbackSize)
        t.feed("\\e[?1049h1\r\n2\r\n3\r\n4")
        assertEquals(0, t.emulator.scrollbackSize)
        t.feed("\\e[?1049l")
        assertEquals(1, t.emulator.scrollbackSize)
        assertEquals(listOf("b", "c"), t.lines())
    }

    @Test
    fun fullResetClearsEverything() {
        val t = TestTerminal(10, 2).feed("a\r\nb\r\nc\\e[31m\\e[?1049h\\e[?2004h\\ec")
        assertFalse(t.emulator.isAlternateScreenActive)
        assertFalse(t.emulator.isBracketedPasteMode)
        assertEquals(0, t.emulator.scrollbackSize)
        assertEquals(listOf("", ""), t.lines())
        assertEquals(0 to 0, t.cursor)
        t.feed("X")
        assertEquals(TextStyle.DEFAULT, t.styleAt(0, 0))
    }

    @Test
    fun screenAlignmentTestFillsWithE() {
        val t = TestTerminal(3, 2).feed("\\e#8")
        assertEquals(listOf("EEE", "EEE"), t.lines())
    }

    // ----- modes and reports -----

    @Test
    fun modesAreTracked() {
        val t = TestTerminal().feed("\\e[?1h\\e=\\e[?25l\\e[?2004h\\e[?1004h\\e[?1002;1006h")
        val e = t.emulator
        assertTrue(e.isApplicationCursorKeys)
        assertTrue(e.isApplicationKeypad)
        assertFalse(e.isCursorVisible)
        assertTrue(e.isBracketedPasteMode)
        assertTrue(e.isFocusReportingEnabled)
        assertEquals(TerminalEmulator.MOUSE_BUTTON_EVENT, e.mouseTrackingMode)
        t.feed("\\e[?1l\\e>\\e[?25h\\e[?2004l\\e[?1002l")
        assertFalse(e.isApplicationCursorKeys)
        assertFalse(e.isApplicationKeypad)
        assertTrue(e.isCursorVisible)
        assertFalse(e.isBracketedPasteMode)
        assertFalse(e.isMouseTrackingActive)
    }

    @Test
    fun deviceAttributesAndStatusReports() {
        val t = TestTerminal(80, 24)
        t.feed("\\e[c")
        assertEquals("\u001b[?62;22c", t.takeWritten())
        t.feed("\\e[>c")
        assertEquals("\u001b[>41;320;0c", t.takeWritten())
        t.feed("\\e[5n")
        assertEquals("\u001b[0n", t.takeWritten())
        t.feed("\\e[3;5H\\e[6n")
        assertEquals("\u001b[3;5R", t.takeWritten())
        t.feed("\\e[18t")
        assertEquals("\u001b[8;24;80t", t.takeWritten())
        t.feed("\\e[>q")
        assertEquals("\u001bP>|Shh\u001b\\", t.takeWritten())
    }

    @Test
    fun modeQueries() {
        val t = TestTerminal().feed("\\e[?2004h\\e[?2004\$p\\e[?25\$p\\e[?12345\$p")
        assertEquals("\u001b[?2004;1\$y\u001b[?25;1\$y\u001b[?12345;0\$y", t.takeWritten())
    }

    @Test
    fun keyboardProtocolQueryIsAnsweredOnlyByDeviceAttributes() {
        // Programs probe for the kitty keyboard protocol with "CSI ? u" followed by "CSI c" and
        // take a lone device attributes answer as "not supported".
        val t = TestTerminal().feed("\\e[?u\\e[c")
        assertEquals("\u001b[?62;22c", t.takeWritten())
    }

    @Test
    fun cursorStyle() {
        val t = TestTerminal().feed("\\e[6 q")
        assertEquals(CursorShape.BAR, t.emulator.cursorShape)
        assertFalse(t.emulator.isCursorBlinking)
        t.feed("\\eP\$q q\\e\\")
        assertEquals("\u001bP1\$r6 q\u001b\\", t.takeWritten())
        t.feed("\\e[3 q")
        assertEquals(CursorShape.UNDERLINE, t.emulator.cursorShape)
        assertTrue(t.emulator.isCursorBlinking)
    }

    // ----- OSC -----

    @Test
    fun titleCanBeSetWithBelOrStringTerminator() {
        val t = TestTerminal().feed("\\e]0;first\u0007")
        assertEquals("first", t.title)
        t.feed("\\e]2;second\\e\\after")
        assertEquals("second", t.title)
        assertEquals("after", t.line(0))
    }

    @Test
    fun titleStack() {
        val t = TestTerminal().feed("\\e]2;one\u0007\\e[22t\\e]2;two\u0007\\e[23t")
        assertEquals("one", t.emulator.title)
    }

    @Test
    fun colorQueriesAnswerWithTheSameTerminator() {
        val t = TestTerminal()
        t.emulator.colors.background = 0x102030
        t.feed("\\e]11;?\u0007")
        assertEquals("\u001b]11;rgb:1010/2020/3030\u0007", t.takeWritten())
        t.feed("\\e]10;?\\e\\")
        assertTrue(t.takeWritten().endsWith("\u001b\\"))
    }

    @Test
    fun colorsCanBeSetAndReset() {
        val t = TestTerminal()
        val original = t.emulator.colors.paletteColor(1)
        t.feed("\\e]4;1;rgb:ff/00/80\u0007\\e]11;#123456\u0007")
        assertEquals(0xFF0080, t.emulator.colors.paletteColor(1))
        assertEquals(0x123456, t.emulator.colors.background)
        assertEquals(2, t.colorChanges)
        t.feed("\\e]104\u0007\\e]111\u0007")
        assertEquals(original, t.emulator.colors.paletteColor(1))
        assertEquals(t.emulator.colors.scheme.background, t.emulator.colors.background)
    }

    @Test
    fun clipboardCanBeWrittenButNotRead() {
        val t = TestTerminal().feed("\\e]52;c;aGVsbG8gd29ybGQ=\u0007")
        assertEquals("hello world", t.clipboard)
        t.clipboard = null
        t.feed("\\e]52;c;?\u0007")
        assertNull(t.clipboard)
        assertEquals("", t.takeWritten())
    }

    @Test
    fun unknownSequencesAreSwallowed() {
        val t = TestTerminal().feed("a\\e[?9999h\\e[99999999999999zb\\e_ignored\\e\\c\\ePjunk\\e\\d\\e]9999;x\u0007e")
        assertEquals("abcde", t.line(0))
    }

    @Test
    fun cancelAbortsASequence() {
        val t = TestTerminal().feed("a\\e[3\u0018b\\e]0;x\u001ac")
        assertEquals("abc", t.line(0))
        assertNull(t.title)
    }

    @Test
    fun controlCharactersInsideSequencesStillExecute() {
        val t = TestTerminal(10, 3).feed("ab\\e[\r2Cx")
        assertEquals("abx", t.line(0))
    }

    // ----- user side events -----

    @Test
    fun pasteIsBracketedOnlyWhenRequested() {
        val t = TestTerminal()
        t.emulator.paste("a\nb\r\nc")
        assertEquals("a\rb\rc", t.takeWritten())
        t.feed("\\e[?2004h")
        t.emulator.paste("x\u001b[201~y")
        assertEquals("\u001b[200~x[201~y\u001b[201~", t.takeWritten())
    }

    @Test
    fun focusReports() {
        val t = TestTerminal()
        t.emulator.reportFocus(true)
        assertEquals("", t.takeWritten())
        t.feed("\\e[?1004h")
        t.emulator.reportFocus(true)
        t.emulator.reportFocus(false)
        assertEquals("\u001b[I\u001b[O", t.takeWritten())
    }

    @Test
    fun mouseReportsInSgrEncoding() {
        val t = TestTerminal().feed("\\e[?1000;1006h")
        val e = t.emulator
        assertTrue(e.sendMouseEvent(TerminalEmulator.MOUSE_BUTTON_LEFT, 4, 2, TerminalEmulator.MOUSE_PRESS))
        assertTrue(e.sendMouseEvent(TerminalEmulator.MOUSE_BUTTON_LEFT, 4, 2, TerminalEmulator.MOUSE_RELEASE))
        assertEquals("\u001b[<0;5;3M\u001b[<0;5;3m", t.takeWritten())
        // Motion is not reported in plain click tracking mode.
        assertFalse(e.sendMouseEvent(TerminalEmulator.MOUSE_BUTTON_LEFT, 5, 2, TerminalEmulator.MOUSE_MOVE))
        assertTrue(e.sendMouseEvent(TerminalEmulator.MOUSE_WHEEL_UP, 0, 0, TerminalEmulator.MOUSE_PRESS, KeyModifiers.CTRL))
        assertEquals("\u001b[<80;1;1M", t.takeWritten())
    }

    @Test
    fun mouseReportsInLegacyEncoding() {
        val t = TestTerminal().feed("\\e[?1002h")
        val e = t.emulator
        e.sendMouseEvent(TerminalEmulator.MOUSE_BUTTON_RIGHT, 0, 0, TerminalEmulator.MOUSE_PRESS)
        e.sendMouseEvent(TerminalEmulator.MOUSE_BUTTON_RIGHT, 1, 0, TerminalEmulator.MOUSE_MOVE)
        e.sendMouseEvent(TerminalEmulator.MOUSE_BUTTON_RIGHT, 1, 0, TerminalEmulator.MOUSE_RELEASE)
        assertEquals("\u001b[M\"!!\u001b[MB\"!\u001b[M#\"!", t.takeWritten())
    }

    @Test
    fun noMouseReportsWithoutTracking() {
        val t = TestTerminal()
        assertFalse(t.emulator.sendMouseEvent(TerminalEmulator.MOUSE_BUTTON_LEFT, 0, 0, TerminalEmulator.MOUSE_PRESS))
        assertEquals("", t.takeWritten())
    }

    // ----- text extraction -----

    @Test
    fun selectedTextJoinsWrappedLines() {
        val t = TestTerminal(5, 4).feed("abcdefgh\r\nxy")
        assertEquals("abcdefgh\nxy", t.emulator.getText(0, 0, 2, 4))
        assertEquals("cdefg", t.emulator.getText(0, 2, 1, 1))
    }

    @Test
    fun selectedTextReachesIntoScrollback() {
        val t = TestTerminal(10, 2).feed("one\r\ntwo\r\nthree")
        assertEquals("one\ntwo\nthree", t.emulator.getText(-1, 0, 1, 9))
    }
}
