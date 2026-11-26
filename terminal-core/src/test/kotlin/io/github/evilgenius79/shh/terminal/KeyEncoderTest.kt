package io.github.evilgenius79.shh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyEncoderTest {
    private fun enc(
        key: TerminalKey,
        mods: Int = 0,
        appCursor: Boolean = false,
        appKeypad: Boolean = false,
        lnm: Boolean = false,
    ): String = String(KeyEncoder.encode(key, mods, appCursor, appKeypad, lnm), Charsets.UTF_8).replace("\u001b", "^[")

    private fun text(s: String, mods: Int = 0): String =
        String(KeyEncoder.encodeText(s, mods), Charsets.ISO_8859_1).replace("\u001b", "^[")

    @Test
    fun cursorKeys() {
        assertEquals("^[[A", enc(TerminalKey.UP))
        assertEquals("^[[B", enc(TerminalKey.DOWN))
        assertEquals("^[[C", enc(TerminalKey.RIGHT))
        assertEquals("^[[D", enc(TerminalKey.LEFT))
        assertEquals("^[OA", enc(TerminalKey.UP, appCursor = true))
        assertEquals("^[OH", enc(TerminalKey.HOME, appCursor = true))
        assertEquals("^[[F", enc(TerminalKey.END))
    }

    @Test
    fun modifiedCursorKeys() {
        assertEquals("^[[1;2A", enc(TerminalKey.UP, KeyModifiers.SHIFT))
        assertEquals("^[[1;3B", enc(TerminalKey.DOWN, KeyModifiers.ALT))
        assertEquals("^[[1;5C", enc(TerminalKey.RIGHT, KeyModifiers.CTRL))
        assertEquals("^[[1;6D", enc(TerminalKey.LEFT, KeyModifiers.CTRL or KeyModifiers.SHIFT))
        assertEquals("^[[1;8H", enc(TerminalKey.HOME, KeyModifiers.CTRL or KeyModifiers.SHIFT or KeyModifiers.ALT))
        // Modifiers win over application cursor mode, exactly like xterm.
        assertEquals("^[[1;5A", enc(TerminalKey.UP, KeyModifiers.CTRL, appCursor = true))
    }

    @Test
    fun editingKeys() {
        assertEquals("^[[2~", enc(TerminalKey.INSERT))
        assertEquals("^[[3~", enc(TerminalKey.DELETE))
        assertEquals("^[[5~", enc(TerminalKey.PAGE_UP))
        assertEquals("^[[6~", enc(TerminalKey.PAGE_DOWN))
        assertEquals("^[[3;5~", enc(TerminalKey.DELETE, KeyModifiers.CTRL))
        assertEquals("^[[5;2~", enc(TerminalKey.PAGE_UP, KeyModifiers.SHIFT))
    }

    @Test
    fun functionKeys() {
        assertEquals("^[OP", enc(TerminalKey.F1))
        assertEquals("^[OS", enc(TerminalKey.F4))
        assertEquals("^[[15~", enc(TerminalKey.F5))
        assertEquals("^[[17~", enc(TerminalKey.F6))
        assertEquals("^[[21~", enc(TerminalKey.F10))
        assertEquals("^[[23~", enc(TerminalKey.F11))
        assertEquals("^[[24~", enc(TerminalKey.F12))
        assertEquals("^[[1;2P", enc(TerminalKey.F1, KeyModifiers.SHIFT))
        assertEquals("^[[15;5~", enc(TerminalKey.F5, KeyModifiers.CTRL))
    }

    @Test
    fun enterTabBackspaceEscape() {
        assertEquals("\r", enc(TerminalKey.ENTER))
        assertEquals("\r\n", enc(TerminalKey.ENTER, lnm = true))
        assertEquals("^[\r", enc(TerminalKey.ENTER, KeyModifiers.ALT))
        assertEquals("\t", enc(TerminalKey.TAB))
        assertEquals("^[[Z", enc(TerminalKey.TAB, KeyModifiers.SHIFT))
        assertEquals("\u007f", enc(TerminalKey.BACKSPACE))
        assertEquals("\b", enc(TerminalKey.BACKSPACE, KeyModifiers.CTRL))
        assertEquals("^[\u007f", enc(TerminalKey.BACKSPACE, KeyModifiers.ALT))
        assertEquals("^[", enc(TerminalKey.ESCAPE))
    }

    @Test
    fun keypad() {
        assertEquals("5", enc(TerminalKey.KEYPAD_5))
        assertEquals("^[Ou", enc(TerminalKey.KEYPAD_5, appKeypad = true))
        assertEquals("^[OM", enc(TerminalKey.KEYPAD_ENTER, appKeypad = true))
        assertEquals("\r", enc(TerminalKey.KEYPAD_ENTER))
    }

    @Test
    fun controlCharacters() {
        assertEquals("\u0001", text("a", KeyModifiers.CTRL))
        assertEquals("\u0003", text("C", KeyModifiers.CTRL))
        assertEquals("\u001a", text("z", KeyModifiers.CTRL))
        assertEquals("\u0000", text(" ", KeyModifiers.CTRL))
        assertEquals("^[", text("[", KeyModifiers.CTRL))
        assertEquals("\u001c", text("\\", KeyModifiers.CTRL))
        assertEquals("\u001d", text("]", KeyModifiers.CTRL))
        assertEquals("\u001f", text("_", KeyModifiers.CTRL))
        assertEquals("\u007f", text("?", KeyModifiers.CTRL))
        // No control code exists for these, so the character is sent unchanged.
        assertEquals("1", text("1", KeyModifiers.CTRL))
        assertEquals("=", text("=", KeyModifiers.CTRL))
    }

    @Test
    fun altPrefixesEscape() {
        assertEquals("^[x", text("x", KeyModifiers.ALT))
        assertEquals("^[\u0018", text("x", KeyModifiers.ALT or KeyModifiers.CTRL))
        assertEquals("hello", text("hello"))
    }

    @Test
    fun readsModesFromEmulator() {
        val t = TestTerminal().feed("\\e[?1h")
        assertEquals("\u001bOA", String(KeyEncoder.encode(TerminalKey.UP, 0, t.emulator), Charsets.UTF_8))
    }
}
