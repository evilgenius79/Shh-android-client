package io.github.evilgenius79.shh.terminal

import java.nio.charset.StandardCharsets

/** A terminal wired to an in-memory host, for tests. */
class TestTerminal(cols: Int = 80, rows: Int = 24, scrollback: Int = 1000) : TerminalHost {
    val emulator = TerminalEmulator(cols, rows, this, scrollback)
    val written = StringBuilder()
    var bells = 0
    var title: String? = null
    var clipboard: String? = null
    var colorChanges = 0

    override fun write(data: ByteArray) {
        written.append(String(data, StandardCharsets.ISO_8859_1))
    }

    override fun onBell() {
        bells++
    }

    override fun onTitleChanged(title: String) {
        this.title = title
    }

    override fun onClipboardCopy(text: String) {
        clipboard = text
    }

    override fun onColorsChanged() {
        colorChanges++
    }

    /** Feeds text; `\e` in the argument is shorthand for ESC. */
    fun feed(text: String): TestTerminal {
        emulator.append(text.replace("\\e", "\u001b"))
        return this
    }

    fun takeWritten(): String = written.toString().also { written.setLength(0) }

    val cursor: Pair<Int, Int> get() = emulator.cursorRow to emulator.cursorCol

    /** Screen rows with trailing blanks removed. */
    fun lines(): List<String> = emulator.screenText().split('\n')

    fun line(row: Int): String = lines()[row]

    fun styleAt(row: Int, col: Int): Long = emulator.row(row).style(col)

    /** Text of the history lines, oldest first. */
    fun history(): List<String> = (-emulator.scrollbackSize until 0).map { r ->
        emulator.getText(r, 0, r, emulator.columns - 1)
    }
}
