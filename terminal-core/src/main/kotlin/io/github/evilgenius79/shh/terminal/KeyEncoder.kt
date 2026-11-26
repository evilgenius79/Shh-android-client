package io.github.evilgenius79.shh.terminal

import java.nio.charset.StandardCharsets

/** Keys that do not produce a character of their own. */
public enum class TerminalKey {
    UP, DOWN, LEFT, RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    ENTER, TAB, BACKSPACE, ESCAPE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    KEYPAD_0, KEYPAD_1, KEYPAD_2, KEYPAD_3, KEYPAD_4, KEYPAD_5, KEYPAD_6, KEYPAD_7, KEYPAD_8, KEYPAD_9,
    KEYPAD_DECIMAL, KEYPAD_DIVIDE, KEYPAD_MULTIPLY, KEYPAD_SUBTRACT, KEYPAD_ADD, KEYPAD_ENTER,
}

/**
 * Turns key presses into the byte sequences an xterm would send, taking the terminal's current
 * keyboard modes into account.
 */
public object KeyEncoder {
    private const val ESC = "\u001b"

    /**
     * Encodes a non character key.
     *
     * @param modifiers combination of [KeyModifiers] bits.
     * @param applicationCursorKeys DECCKM, see [TerminalEmulator.isApplicationCursorKeys].
     * @param applicationKeypad DECKPAM, see [TerminalEmulator.isApplicationKeypad].
     * @param newLineMode LNM, see [TerminalEmulator.isNewLineMode].
     */
    @JvmStatic
    public fun encode(
        key: TerminalKey,
        modifiers: Int = 0,
        applicationCursorKeys: Boolean = false,
        applicationKeypad: Boolean = false,
        newLineMode: Boolean = false,
    ): ByteArray = encodeToString(key, modifiers, applicationCursorKeys, applicationKeypad, newLineMode)
        .toByteArray(StandardCharsets.UTF_8)

    /** Like [encode] but reads the keyboard modes from [emulator]. */
    @JvmStatic
    public fun encode(key: TerminalKey, modifiers: Int, emulator: TerminalEmulator): ByteArray = encode(
        key,
        modifiers,
        emulator.isApplicationCursorKeys,
        emulator.isApplicationKeypad,
        emulator.isNewLineMode,
    )

    private fun encodeToString(
        key: TerminalKey,
        modifiers: Int,
        appCursor: Boolean,
        appKeypad: Boolean,
        newLineMode: Boolean,
    ): String {
        // xterm encodes modifiers as 1 + bit mask: shift 1, alt 2, ctrl 4.
        val mod = modifiers and (KeyModifiers.SHIFT or KeyModifiers.ALT or KeyModifiers.CTRL)
        val alt = mod and KeyModifiers.ALT != 0
        return when (key) {
            TerminalKey.UP -> cursorKey('A', mod, appCursor)
            TerminalKey.DOWN -> cursorKey('B', mod, appCursor)
            TerminalKey.RIGHT -> cursorKey('C', mod, appCursor)
            TerminalKey.LEFT -> cursorKey('D', mod, appCursor)
            TerminalKey.HOME -> cursorKey('H', mod, appCursor)
            TerminalKey.END -> cursorKey('F', mod, appCursor)
            TerminalKey.INSERT -> tildeKey(2, mod)
            TerminalKey.DELETE -> tildeKey(3, mod)
            TerminalKey.PAGE_UP -> tildeKey(5, mod)
            TerminalKey.PAGE_DOWN -> tildeKey(6, mod)
            TerminalKey.F1 -> functionKey('P', mod)
            TerminalKey.F2 -> functionKey('Q', mod)
            TerminalKey.F3 -> functionKey('R', mod)
            TerminalKey.F4 -> functionKey('S', mod)
            TerminalKey.F5 -> tildeKey(15, mod)
            TerminalKey.F6 -> tildeKey(17, mod)
            TerminalKey.F7 -> tildeKey(18, mod)
            TerminalKey.F8 -> tildeKey(19, mod)
            TerminalKey.F9 -> tildeKey(20, mod)
            TerminalKey.F10 -> tildeKey(21, mod)
            TerminalKey.F11 -> tildeKey(23, mod)
            TerminalKey.F12 -> tildeKey(24, mod)
            TerminalKey.ENTER -> (if (alt) ESC else "") + (if (newLineMode) "\r\n" else "\r")
            TerminalKey.TAB -> when {
                mod and KeyModifiers.SHIFT != 0 -> "$ESC[Z"
                alt -> "$ESC\t"
                else -> "\t"
            }
            TerminalKey.BACKSPACE -> {
                // DEL by default, like xterm; Ctrl turns it into BS so "delete word" bindings work.
                val base = if (mod and KeyModifiers.CTRL != 0) "\b" else "\u007f"
                if (alt) ESC + base else base
            }
            TerminalKey.ESCAPE -> if (alt) ESC + ESC else ESC
            TerminalKey.KEYPAD_ENTER -> if (appKeypad) "${ESC}OM" else if (newLineMode) "\r\n" else "\r"
            TerminalKey.KEYPAD_0 -> keypad('0', 'p', appKeypad)
            TerminalKey.KEYPAD_1 -> keypad('1', 'q', appKeypad)
            TerminalKey.KEYPAD_2 -> keypad('2', 'r', appKeypad)
            TerminalKey.KEYPAD_3 -> keypad('3', 's', appKeypad)
            TerminalKey.KEYPAD_4 -> keypad('4', 't', appKeypad)
            TerminalKey.KEYPAD_5 -> keypad('5', 'u', appKeypad)
            TerminalKey.KEYPAD_6 -> keypad('6', 'v', appKeypad)
            TerminalKey.KEYPAD_7 -> keypad('7', 'w', appKeypad)
            TerminalKey.KEYPAD_8 -> keypad('8', 'x', appKeypad)
            TerminalKey.KEYPAD_9 -> keypad('9', 'y', appKeypad)
            TerminalKey.KEYPAD_DECIMAL -> keypad('.', 'n', appKeypad)
            TerminalKey.KEYPAD_DIVIDE -> keypad('/', 'o', appKeypad)
            TerminalKey.KEYPAD_MULTIPLY -> keypad('*', 'j', appKeypad)
            TerminalKey.KEYPAD_SUBTRACT -> keypad('-', 'm', appKeypad)
            TerminalKey.KEYPAD_ADD -> keypad('+', 'k', appKeypad)
        }
    }

    /** Arrow keys, Home and End: `ESC [ X`, `ESC O X` in application mode, `ESC [ 1 ; m X` with modifiers. */
    private fun cursorKey(final: Char, mod: Int, appCursor: Boolean): String = when {
        mod != 0 -> "$ESC[1;${mod + 1}$final"
        appCursor -> "${ESC}O$final"
        else -> "$ESC[$final"
    }

    /** F1 to F4: `ESC O X`, or `ESC [ 1 ; m X` with modifiers. */
    private fun functionKey(final: Char, mod: Int): String =
        if (mod != 0) "$ESC[1;${mod + 1}$final" else "${ESC}O$final"

    /** Keys of the form `ESC [ n ~`, or `ESC [ n ; m ~` with modifiers. */
    private fun tildeKey(number: Int, mod: Int): String =
        if (mod != 0) "$ESC[$number;${mod + 1}~" else "$ESC[$number~"

    private fun keypad(plain: Char, application: Char, appKeypad: Boolean): String =
        if (appKeypad) "${ESC}O$application" else plain.toString()

    /**
     * Encodes typed text. With Ctrl held a single character maps to its control code where one
     * exists (Ctrl+A is 0x01, Ctrl+Space is NUL, Ctrl+? is DEL and so on); with Alt held the
     * text is prefixed with ESC, which is how xterm's "meta sends escape" works.
     */
    @JvmStatic
    public fun encodeText(text: String, modifiers: Int = 0): ByteArray {
        var out = text
        if (modifiers and KeyModifiers.CTRL != 0 && text.codePointCount(0, text.length) == 1) {
            val control = controlCode(text.codePointAt(0))
            if (control >= 0) out = control.toChar().toString()
        }
        if (modifiers and KeyModifiers.ALT != 0) out = ESC + out
        return out.toByteArray(StandardCharsets.UTF_8)
    }

    /** The control character produced by Ctrl plus [codePoint], or -1 when there is none. */
    @JvmStatic
    public fun controlCode(codePoint: Int): Int = when (codePoint) {
        in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        ' '.code, '@'.code, '2'.code -> 0
        '['.code, '3'.code -> 0x1B
        '\\'.code, '4'.code -> 0x1C
        ']'.code, '5'.code -> 0x1D
        '^'.code, '6'.code -> 0x1E
        '_'.code, '-'.code, '7'.code, '/'.code -> 0x1F
        '?'.code, '8'.code -> 0x7F
        else -> -1
    }
}
