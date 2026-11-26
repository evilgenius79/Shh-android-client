package io.github.evilgenius79.shh.terminal

/** What a [TerminalEmulator] needs from whoever owns it. */
public interface TerminalHost {
    /**
     * Bytes the terminal itself wants to send to the remote side: answers to status queries,
     * mouse reports, focus events and so on. Called while the emulator is processing input, so
     * the implementation should hand the data off rather than block.
     */
    public fun write(data: ByteArray)

    /** BEL was received. */
    public fun onBell() {}

    /** The window title changed (OSC 0 / OSC 2). */
    public fun onTitleChanged(title: String) {}

    /** The remote side asked to put [text] on the clipboard (OSC 52). */
    public fun onClipboardCopy(text: String) {}

    /** The palette or one of the default colours changed (OSC 4/10/11/12 and their resets). */
    public fun onColorsChanged() {}
}

/** Shape of the text cursor as selected by DECSCUSR. */
public enum class CursorShape { BLOCK, UNDERLINE, BAR }
