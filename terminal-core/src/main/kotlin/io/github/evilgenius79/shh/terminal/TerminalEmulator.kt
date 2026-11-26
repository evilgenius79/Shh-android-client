package io.github.evilgenius79.shh.terminal

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * An xterm compatible terminal emulator: it consumes the byte stream coming from the remote side
 * and maintains the resulting screen, scrollback and terminal modes.
 *
 * The class has no Android dependencies and does no rendering or I/O of its own; everything it
 * wants to tell the outside world goes through [TerminalHost].
 *
 * **Threading:** instances are not thread safe. Callers that feed data on one thread and render
 * on another must hold the same lock (conventionally the emulator instance itself) around both.
 */
public class TerminalEmulator(
    columns: Int,
    rows: Int,
    private val host: TerminalHost,
    maxScrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,
    /** The live colour table. Renderers resolve cell colours through it. */
    public val colors: TerminalColors = TerminalColors(),
) {
    /** Width of the screen in cells. */
    public var columns: Int = columns
        private set

    /** Height of the screen in cells. */
    public var rows: Int = rows
        private set

    private var mainScreen: Array<TerminalLine> = Array(rows) { TerminalLine(columns) }
    private var altScreen: Array<TerminalLine> = Array(rows) { TerminalLine(columns) }
    private var screen: Array<TerminalLine> = mainScreen
    private val scrollback = ArrayDeque<TerminalLine>()

    /**
     * How many lines at the end of the scrollback may be moved back onto the screen when it
     * grows. Lines above an explicitly cleared screen are not eligible, so clearing the screen and
     * then enlarging it does not bring old output back.
     */
    private var pullableLines = 0

    /** Maximum number of lines kept in the scrollback. */
    public var maxScrollbackLines: Int = maxScrollbackLines
        set(value) {
            field = maxOf(0, value)
            trimScrollback()
        }

    /**
     * Total number of lines that have moved from the screen into the scrollback so far. A view
     * that is scrolled back can use the delta to keep showing the same lines.
     */
    public var scrollCounter: Long = 0
        private set

    /** Cursor row, 0 based from the top of the screen. */
    public var cursorRow: Int = 0
        private set

    /** Cursor column, 0 based. */
    public var cursorCol: Int = 0
        private set

    /** The cursor sits on the last column and the next printable character wraps first. */
    private var wrapPending = false

    private var style: Long = TextStyle.DEFAULT
    private var topMargin = 0
    private var bottomMargin = rows - 1
    private var leftMargin = 0
    private var rightMargin = columns - 1

    private var originMode = false
    private var autoWrap = true
    private var insertMode = false
    private var newLineMode = false
    private var leftRightMarginMode = false

    /** DECCKM: cursor keys send `ESC O x` instead of `ESC [ x`. */
    public var isApplicationCursorKeys: Boolean = false
        private set

    /** DECKPAM: the numeric keypad sends application sequences. */
    public var isApplicationKeypad: Boolean = false
        private set

    /** DECTCEM. */
    public var isCursorVisible: Boolean = true
        private set

    /** DECSCNM: the whole screen is shown with foreground and background swapped. */
    public var isReverseVideo: Boolean = false
        private set

    /** Mode 2004: pasted text is wrapped in `ESC[200~` ... `ESC[201~`. */
    public var isBracketedPasteMode: Boolean = false
        private set

    /** Mode 1004: focus changes are reported with `ESC[I` / `ESC[O`. */
    public var isFocusReportingEnabled: Boolean = false
        private set

    /** Mode 1007: on the alternate screen the scroll wheel sends cursor up/down keys. */
    public var isAlternateScrollMode: Boolean = true
        private set

    /** Mode 2026: the application is in the middle of an update and asks not to be drawn yet. */
    public var isSynchronizedUpdate: Boolean = false
        private set

    /** True when LNM is set, in which case Enter sends CR LF. */
    public val isNewLineMode: Boolean get() = newLineMode

    /** One of [MOUSE_NONE], [MOUSE_X10], [MOUSE_NORMAL], [MOUSE_BUTTON_EVENT], [MOUSE_ANY_EVENT]. */
    public var mouseTrackingMode: Int = MOUSE_NONE
        private set

    private var mouseEncoding = MOUSE_ENCODING_DEFAULT

    /** True when the application wants mouse events, so touches should not scroll or select. */
    public val isMouseTrackingActive: Boolean get() = mouseTrackingMode != MOUSE_NONE

    /** True while the alternate screen (used by full screen programs) is showing. */
    public val isAlternateScreenActive: Boolean get() = screen === altScreen

    public var cursorShape: CursorShape = CursorShape.BLOCK
        private set

    public var isCursorBlinking: Boolean = true
        private set

    /** Window title as set by the application. */
    public var title: String = ""
        private set

    private val titleStack = ArrayDeque<String>()

    private val charsets = IntArray(4) { CHARSET_ASCII }
    private var glCharset = 0
    private var singleShift = -1

    private class SavedCursor {
        var valid = false
        var row = 0
        var col = 0
        var style = TextStyle.DEFAULT
        var originMode = false
        var autoWrap = true
        var wrapPending = false
        var glCharset = 0
        val charsets = IntArray(4) { CHARSET_ASCII }
    }

    private val savedMain = SavedCursor()
    private val savedAlt = SavedCursor()

    private var tabStops = BooleanArray(columns) { it > 0 && it % 8 == 0 }
    private var lastPrintedCodePoint = 0

    private var cellWidthPx = 0
    private var cellHeightPx = 0

    // ---------------------------------------------------------------------------------------
    // Parser state
    // ---------------------------------------------------------------------------------------

    private var state = STATE_GROUND
    private var utf8Remaining = 0
    private var utf8Value = 0
    private var utf8Minimum = 0

    private val csiParams = IntArray(MAX_PARAMS)
    private val csiSubParam = BooleanArray(MAX_PARAMS)
    private var csiParamCount = 0
    private var csiPrivate = 0
    private var csiIntermediate = 0
    private var csiIntermediateCount = 0
    private var csiInvalid = false
    private var csiParamStarted = false

    private var escIntermediate = 0
    private val stringBuffer = StringBuilder()
    private var stringKind = STRING_IGNORE
    private var stringOverflow = false

    init {
        require(columns > 0 && rows > 0) { "Terminal size must be positive" }
    }

    // ---------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------

    /** Feeds bytes received from the remote side. Partial UTF-8 sequences are carried over. */
    public fun append(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
        var i = offset
        val end = offset + count
        while (i < end) {
            val b = data[i++].toInt() and 0xFF
            if (utf8Remaining > 0) {
                if ((b and 0xC0) == 0x80) {
                    utf8Value = (utf8Value shl 6) or (b and 0x3F)
                    if (--utf8Remaining == 0) {
                        val cp = utf8Value
                        val valid = cp >= utf8Minimum && cp <= 0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)
                        process(if (valid) cp else REPLACEMENT_CHAR)
                    }
                    continue
                }
                // The sequence was cut short: show a replacement and look at this byte afresh.
                utf8Remaining = 0
                process(REPLACEMENT_CHAR)
            }
            when {
                b < 0x80 -> process(b)
                (b and 0xE0) == 0xC0 -> startUtf8(b and 0x1F, 1, 0x80)
                (b and 0xF0) == 0xE0 -> startUtf8(b and 0x0F, 2, 0x800)
                (b and 0xF8) == 0xF0 -> startUtf8(b and 0x07, 3, 0x10000)
                else -> process(REPLACEMENT_CHAR)
            }
        }
    }

    /** Convenience for tests and local echo: feeds [text] as UTF-8. */
    public fun append(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        append(bytes, 0, bytes.size)
    }

    private fun startUtf8(initial: Int, remaining: Int, minimum: Int) {
        utf8Value = initial
        utf8Remaining = remaining
        utf8Minimum = minimum
    }

    private fun process(cp: Int) {
        when (state) {
            STATE_GROUND -> {
                if (cp >= 0x20) {
                    if (cp == 0x7F || (cp in 0x80..0x9F)) return
                    emitChar(cp)
                } else {
                    executeControl(cp)
                }
            }
            STATE_ESCAPE -> processEscape(cp)
            STATE_ESCAPE_INTERMEDIATE -> processEscapeIntermediate(cp)
            STATE_CSI -> processCsi(cp)
            STATE_STRING -> processString(cp)
            STATE_STRING_ESCAPE -> {
                if (cp == '\\'.code) {
                    finishString(bellTerminated = false)
                } else {
                    // ESC followed by something other than "\" aborts the string.
                    state = STATE_ESCAPE
                    escIntermediate = 0
                    processEscape(cp)
                }
            }
        }
    }

    private fun executeControl(cp: Int) {
        when (cp) {
            0x07 -> host.onBell()
            0x08 -> backspace()
            0x09 -> tabForward(1)
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> carriageReturn()
            0x0E -> glCharset = 1
            0x0F -> glCharset = 0
            0x18, 0x1A -> state = STATE_GROUND
            0x1B -> {
                state = STATE_ESCAPE
                escIntermediate = 0
            }
            else -> Unit
        }
    }

    private fun processEscape(cp: Int) {
        when {
            cp < 0x20 -> executeControl(cp)
            cp == 0x7F -> Unit
            cp < 0x30 -> {
                escIntermediate = cp
                state = STATE_ESCAPE_INTERMEDIATE
            }
            cp == '['.code -> {
                csiParamCount = 0
                csiPrivate = 0
                csiIntermediate = 0
                csiIntermediateCount = 0
                csiInvalid = false
                csiParamStarted = false
                state = STATE_CSI
            }
            cp == ']'.code -> startString(STRING_OSC)
            cp == 'P'.code -> startString(STRING_DCS)
            cp == 'X'.code || cp == '^'.code || cp == '_'.code -> startString(STRING_IGNORE)
            cp <= 0x7E -> {
                state = STATE_GROUND
                dispatchEscape(cp)
            }
            else -> state = STATE_GROUND
        }
    }

    private fun processEscapeIntermediate(cp: Int) {
        when {
            cp < 0x20 -> executeControl(cp)
            cp == 0x7F -> Unit
            cp < 0x30 -> Unit // A second intermediate: nothing we implement uses one.
            cp <= 0x7E -> {
                state = STATE_GROUND
                dispatchEscapeIntermediate(escIntermediate, cp)
            }
            else -> state = STATE_GROUND
        }
    }

    private fun dispatchEscape(final: Int) {
        when (final.toChar()) {
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> index()
            'E' -> {
                carriageReturn()
                index()
            }
            'H' -> if (cursorCol < columns) tabStops[cursorCol] = true
            'M' -> reverseIndex()
            'N' -> singleShift = 2
            'O' -> singleShift = 3
            'Z' -> reportPrimaryAttributes()
            'c' -> fullReset()
            'n' -> glCharset = 2
            'o' -> glCharset = 3
            '=' -> isApplicationKeypad = true
            '>' -> isApplicationKeypad = false
            else -> Unit
        }
    }

    private fun dispatchEscapeIntermediate(intermediate: Int, final: Int) {
        when (intermediate.toChar()) {
            '(' -> charsets[0] = final
            ')' -> charsets[1] = final
            '*' -> charsets[2] = final
            '+' -> charsets[3] = final
            '#' -> if (final == '8'.code) screenAlignmentTest()
            else -> Unit
        }
    }

    private fun processCsi(cp: Int) {
        when {
            cp < 0x20 -> executeControl(cp)
            cp == 0x7F -> Unit
            cp >= '0'.code && cp <= '9'.code -> {
                if (csiIntermediateCount > 0) {
                    csiInvalid = true
                } else {
                    startCsiParam()
                    val index = csiParamCount - 1
                    if (index < MAX_PARAMS) {
                        val current = if (csiParams[index] < 0) 0 else csiParams[index]
                        csiParams[index] = minOf(current * 10 + (cp - '0'.code), MAX_PARAM_VALUE)
                    }
                }
            }
            cp == ';'.code || cp == ':'.code -> {
                if (csiIntermediateCount > 0) {
                    csiInvalid = true
                } else {
                    startCsiParam()
                    if (csiParamCount < MAX_PARAMS) {
                        csiParams[csiParamCount] = -1
                        csiSubParam[csiParamCount] = cp == ':'.code
                    }
                    csiParamCount++
                }
            }
            cp >= '<'.code && cp <= '?'.code -> {
                if (csiParamStarted || csiIntermediateCount > 0) csiInvalid = true else csiPrivate = cp
            }
            cp < 0x30 -> {
                csiIntermediate = cp
                csiIntermediateCount++
            }
            cp <= 0x7E -> {
                state = STATE_GROUND
                if (csiParamCount > MAX_PARAMS) csiParamCount = MAX_PARAMS
                if (!csiInvalid && csiIntermediateCount <= 1) dispatchCsi(cp)
            }
            else -> state = STATE_GROUND
        }
    }

    private fun startCsiParam() {
        if (!csiParamStarted) {
            csiParamStarted = true
            csiParams[0] = -1
            csiSubParam[0] = false
            csiParamCount = 1
        }
    }

    private fun startString(kind: Int) {
        stringKind = kind
        stringBuffer.setLength(0)
        stringOverflow = false
        state = STATE_STRING
    }

    private fun processString(cp: Int) {
        when (cp) {
            0x1B -> state = STATE_STRING_ESCAPE
            0x07 -> {
                // xterm lets BEL end an OSC. Inside other strings it is just data.
                if (stringKind == STRING_OSC) finishString(bellTerminated = true)
            }
            0x18, 0x1A -> state = STATE_GROUND
            else -> {
                if (stringKind != STRING_IGNORE && cp >= 0x20) {
                    if (stringBuffer.length < MAX_STRING_LENGTH) stringBuffer.appendCodePoint(cp) else stringOverflow = true
                }
            }
        }
    }

    private fun finishString(bellTerminated: Boolean) {
        state = STATE_GROUND
        if (stringOverflow) return
        when (stringKind) {
            STRING_OSC -> dispatchOsc(stringBuffer.toString(), bellTerminated)
            STRING_DCS -> dispatchDcs(stringBuffer.toString())
        }
        stringBuffer.setLength(0)
    }

    // ---------------------------------------------------------------------------------------
    // Printing
    // ---------------------------------------------------------------------------------------

    private fun emitChar(codePointIn: Int) {
        var codePoint = codePointIn
        if (codePoint < 0x7F) {
            val set = if (singleShift >= 0) {
                val shifted = charsets[singleShift]
                singleShift = -1
                shifted
            } else {
                charsets[glCharset]
            }
            if (set != CHARSET_ASCII) codePoint = translateCharset(set, codePoint)
            if (codePoint < 0x7F) {
                printChar(codePoint, 1)
                lastPrintedCodePoint = codePoint
                return
            }
        }
        val width = WcWidth.width(codePoint)
        if (width == 0) {
            attachCombining(codePoint)
            return
        }
        printChar(codePoint, width)
        lastPrintedCodePoint = codePoint
    }

    private fun translateCharset(set: Int, codePoint: Int): Int = when (set) {
        CHARSET_DEC_SPECIAL -> if (codePoint in 0x5F..0x7E) DEC_SPECIAL_GRAPHICS[codePoint - 0x5F].code else codePoint
        CHARSET_UK -> if (codePoint == '#'.code) 0xA3 else codePoint
        else -> codePoint
    }

    private fun printChar(codePoint: Int, width: Int) {
        if (wrapPending) {
            if (autoWrap) wrapToNextLine(if (cursorCol <= rightMargin) rightMargin else columns - 1)
            wrapPending = false
        }
        // The margins only confine the cursor while it is inside them.
        val leftLimit = if (cursorCol >= leftMargin) leftMargin else 0
        val rightLimit = if (cursorCol <= rightMargin) rightMargin else columns - 1
        if (width == 2 && cursorCol + 1 > rightLimit) {
            // No room for both halves of a double width character on this line.
            if (rightLimit - leftLimit < 1) return
            if (autoWrap) wrapToNextLine(rightLimit) else cursorCol = rightLimit - 1
        }

        val line = screen[cursorRow]
        if (insertMode) {
            splitWideAt(line, cursorCol)
            line.insertCells(cursorCol, width, rightLimit + 1, TextStyle.eraseStyle(style))
            repairWide(line)
        }
        splitWideAt(line, cursorCol)
        if (width == 2) {
            splitWideAt(line, cursorCol + 1)
            line.set(cursorCol, codePoint, style or TextStyle.WIDE)
            line.set(cursorCol + 1, 0, style or TextStyle.WIDE_TRAILER)
        } else {
            line.set(cursorCol, codePoint, style)
        }

        if (cursorCol + width > rightLimit) {
            cursorCol = rightLimit
            wrapPending = autoWrap
        } else {
            cursorCol += width
        }
    }

    /** Moves to the start of the next line because text ran past [rightLimit]. */
    private fun wrapToNextLine(rightLimit: Int) {
        // Only a wrap at the real edge of the screen joins two lines into one logical line.
        if (rightLimit == columns - 1 && leftMargin == 0) screen[cursorRow].isWrapped = true
        cursorCol = if (cursorCol >= leftMargin) leftMargin else 0
        index()
    }

    private fun attachCombining(codePoint: Int) {
        val line = screen[cursorRow]
        var col = if (wrapPending) cursorCol else cursorCol - 1
        if (col < 0) return
        if (TextStyle.has(line.style[col], TextStyle.WIDE_TRAILER) && col > 0) col--
        line.appendCombining(col, codePoint)
    }

    /**
     * Called before cell [col] is overwritten. If it is one half of a double width character the
     * other half is blanked so no orphan is left behind.
     */
    private fun splitWideAt(line: TerminalLine, col: Int) {
        if (col < 0 || col >= columns) return
        val cellStyle = line.style[col]
        if (TextStyle.has(cellStyle, TextStyle.WIDE_TRAILER)) {
            if (col > 0) line.set(col - 1, 0, TextStyle.withoutWidth(line.style[col - 1]))
            line.set(col, 0, TextStyle.withoutWidth(cellStyle))
        } else if (TextStyle.has(cellStyle, TextStyle.WIDE)) {
            if (col + 1 < columns) line.set(col + 1, 0, TextStyle.withoutWidth(line.style[col + 1]))
            line.set(col, 0, TextStyle.withoutWidth(cellStyle))
        }
    }

    /** Blanks any half of a double width character whose other half went missing. */
    private fun repairWide(line: TerminalLine) {
        var col = 0
        while (col < columns) {
            val cellStyle = line.style[col]
            if (TextStyle.has(cellStyle, TextStyle.WIDE)) {
                if (col + 1 < columns && TextStyle.has(line.style[col + 1], TextStyle.WIDE_TRAILER)) {
                    col += 2
                    continue
                }
                line.set(col, 0, TextStyle.withoutWidth(cellStyle))
            } else if (TextStyle.has(cellStyle, TextStyle.WIDE_TRAILER)) {
                line.set(col, 0, TextStyle.withoutWidth(cellStyle))
            }
            col++
        }
    }

    private fun eraseCells(line: TerminalLine, from: Int, toExclusive: Int) {
        if (from >= toExclusive) return
        splitWideAt(line, from)
        splitWideAt(line, toExclusive - 1)
        line.clear(from, toExclusive, TextStyle.eraseStyle(style))
    }

    // ---------------------------------------------------------------------------------------
    // Cursor movement
    // ---------------------------------------------------------------------------------------

    private fun index() {
        wrapPending = false
        if (cursorRow == bottomMargin) {
            if (cursorCol in leftMargin..rightMargin) scrollUp(topMargin, bottomMargin, 1, true)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorRow == topMargin) {
            if (cursorCol in leftMargin..rightMargin) scrollDown(topMargin, bottomMargin, 1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun lineFeed() {
        // An explicit line break ends the logical line, whatever was wrapped here before.
        screen[cursorRow].isWrapped = false
        index()
        if (newLineMode) carriageReturn()
    }

    private fun carriageReturn() {
        cursorCol = if (cursorCol >= leftMargin) leftMargin else 0
        wrapPending = false
    }

    private fun backspace() {
        val limit = if (cursorCol >= leftMargin) leftMargin else 0
        if (cursorCol > limit) cursorCol--
        wrapPending = false
    }

    private fun cursorUp(count: Int) {
        val limit = if (cursorRow >= topMargin) topMargin else 0
        cursorRow = maxOf(limit, cursorRow - count)
        wrapPending = false
    }

    private fun cursorDown(count: Int) {
        val limit = if (cursorRow <= bottomMargin) bottomMargin else rows - 1
        cursorRow = minOf(limit, cursorRow + count)
        wrapPending = false
    }

    private fun cursorForward(count: Int) {
        val limit = if (cursorCol <= rightMargin) rightMargin else columns - 1
        cursorCol = minOf(limit, cursorCol + count)
        wrapPending = false
    }

    private fun cursorBackward(count: Int) {
        val limit = if (cursorCol >= leftMargin) leftMargin else 0
        cursorCol = maxOf(limit, cursorCol - count)
        wrapPending = false
    }

    /** Absolute positioning with 0 based coordinates, honouring origin mode. */
    private fun cursorTo(row: Int, col: Int) {
        setCursorRow(row)
        setCursorCol(col)
    }

    private fun setCursorRow(row: Int) {
        cursorRow = if (originMode) {
            (topMargin + row).coerceIn(topMargin, bottomMargin)
        } else {
            row.coerceIn(0, rows - 1)
        }
        wrapPending = false
    }

    private fun setCursorCol(col: Int) {
        cursorCol = if (originMode) {
            (leftMargin + col).coerceIn(leftMargin, rightMargin)
        } else {
            col.coerceIn(0, columns - 1)
        }
        wrapPending = false
    }

    private fun tabForward(count: Int) {
        val limit = if (cursorCol <= rightMargin) rightMargin else columns - 1
        var col = cursorCol
        repeat(count) {
            var next = col + 1
            while (next < limit && !tabStops[next]) next++
            col = minOf(next, limit)
        }
        cursorCol = col
    }

    private fun tabBackward(count: Int) {
        val limit = if (cursorCol >= leftMargin) leftMargin else 0
        var col = cursorCol
        repeat(count) {
            var previous = col - 1
            while (previous > limit && !tabStops[previous]) previous--
            col = maxOf(previous, limit)
        }
        cursorCol = col
        wrapPending = false
    }

    private fun saveCursor() {
        val slot = if (screen === altScreen) savedAlt else savedMain
        slot.valid = true
        slot.row = cursorRow
        slot.col = cursorCol
        slot.style = style
        slot.originMode = originMode
        slot.autoWrap = autoWrap
        slot.wrapPending = wrapPending
        slot.glCharset = glCharset
        System.arraycopy(charsets, 0, slot.charsets, 0, 4)
    }

    private fun restoreCursor() {
        val slot = if (screen === altScreen) savedAlt else savedMain
        if (!slot.valid) {
            cursorRow = 0
            cursorCol = 0
            style = TextStyle.DEFAULT
            originMode = false
            wrapPending = false
            glCharset = 0
            java.util.Arrays.fill(charsets, CHARSET_ASCII)
            return
        }
        cursorRow = slot.row.coerceIn(0, rows - 1)
        cursorCol = slot.col.coerceIn(0, columns - 1)
        style = slot.style
        originMode = slot.originMode
        autoWrap = slot.autoWrap
        wrapPending = slot.wrapPending && cursorCol == slot.col
        glCharset = slot.glCharset
        System.arraycopy(slot.charsets, 0, charsets, 0, 4)
    }

    // ---------------------------------------------------------------------------------------
    // Scrolling and editing
    // ---------------------------------------------------------------------------------------

    /**
     * Scrolls rows [top]..[bottom] up by [count] lines within the left/right margins.
     *
     * @param toScrollback lines leaving the top of the main screen are kept as history.
     */
    private fun scrollUp(top: Int, bottom: Int, count: Int, toScrollback: Boolean) {
        val height = bottom - top + 1
        val n = minOf(count, height)
        if (n <= 0) return
        val blank = TextStyle.eraseStyle(style)
        if (leftMargin == 0 && rightMargin == columns - 1) {
            val keep = toScrollback && top == 0 && screen === mainScreen && maxScrollbackLines > 0
            repeat(n) {
                val departing = screen[top]
                System.arraycopy(screen, top + 1, screen, top, height - 1)
                val fresh = if (keep) {
                    pushToScrollback(departing)
                    TerminalLine(columns)
                } else {
                    departing
                }
                fresh.reset(columns, blank)
                screen[bottom] = fresh
            }
        } else {
            val width = rightMargin - leftMargin + 1
            for (row in top..bottom) {
                val line = screen[row]
                splitWideAt(line, leftMargin)
                splitWideAt(line, rightMargin)
                if (row + n <= bottom) {
                    line.copyCells(screen[row + n], leftMargin, leftMargin, width)
                } else {
                    line.clear(leftMargin, rightMargin + 1, blank)
                }
                line.isWrapped = false
                repairWide(line)
            }
        }
    }

    private fun scrollDown(top: Int, bottom: Int, count: Int) {
        val height = bottom - top + 1
        val n = minOf(count, height)
        if (n <= 0) return
        val blank = TextStyle.eraseStyle(style)
        if (leftMargin == 0 && rightMargin == columns - 1) {
            repeat(n) {
                val departing = screen[bottom]
                System.arraycopy(screen, top, screen, top + 1, height - 1)
                departing.reset(columns, blank)
                screen[top] = departing
            }
        } else {
            val width = rightMargin - leftMargin + 1
            for (row in bottom downTo top) {
                val line = screen[row]
                splitWideAt(line, leftMargin)
                splitWideAt(line, rightMargin)
                if (row - n >= top) {
                    line.copyCells(screen[row - n], leftMargin, leftMargin, width)
                } else {
                    line.clear(leftMargin, rightMargin + 1, blank)
                }
                line.isWrapped = false
                repairWide(line)
            }
        }
    }

    private fun pushToScrollback(line: TerminalLine) {
        line.trimToContent()
        scrollback.addLast(line)
        scrollCounter++
        if (pullableLines < Int.MAX_VALUE) pullableLines++
        trimScrollback()
    }

    private fun trimScrollback() {
        while (scrollback.size > maxScrollbackLines) scrollback.removeFirst()
        if (pullableLines > scrollback.size) pullableLines = scrollback.size
    }

    private fun cursorInsideMargins(): Boolean =
        cursorRow in topMargin..bottomMargin && cursorCol in leftMargin..rightMargin

    private fun insertLines(count: Int) {
        if (!cursorInsideMargins()) return
        scrollDown(cursorRow, bottomMargin, count)
        cursorCol = leftMargin
        wrapPending = false
    }

    private fun deleteLines(count: Int) {
        if (!cursorInsideMargins()) return
        scrollUp(cursorRow, bottomMargin, count, false)
        cursorCol = leftMargin
        wrapPending = false
    }

    private fun insertChars(count: Int) {
        if (!cursorInsideMargins()) return
        val line = screen[cursorRow]
        splitWideAt(line, cursorCol)
        line.insertCells(cursorCol, count, rightMargin + 1, TextStyle.eraseStyle(style))
        repairWide(line)
        wrapPending = false
    }

    private fun deleteChars(count: Int) {
        if (!cursorInsideMargins()) return
        val line = screen[cursorRow]
        splitWideAt(line, cursorCol)
        line.deleteCells(cursorCol, count, rightMargin + 1, TextStyle.eraseStyle(style))
        repairWide(line)
        wrapPending = false
    }

    private fun eraseChars(count: Int) {
        if (wrapPending) return
        eraseCells(screen[cursorRow], cursorCol, minOf(columns, cursorCol + count))
    }

    /**
     * EL. With a wrap pending the cursor is logically past the last column, so erasing "from the
     * cursor to the end of the line" erases nothing. xterm itself would erase the last character
     * here; like VTE, konsole and tmux we do not, because programs such as grep and gcc emit
     * `CSI K` right after coloured text and would otherwise lose the last character of every
     * line that exactly fills the screen width.
     */
    private fun eraseInLine(mode: Int) {
        val line = screen[cursorRow]
        when (mode) {
            0 -> if (!wrapPending) {
                eraseCells(line, cursorCol, columns)
                line.isWrapped = false
            }
            1 -> eraseCells(line, 0, cursorCol + 1)
            2 -> {
                eraseCells(line, 0, columns)
                line.isWrapped = false
            }
        }
    }

    private fun eraseInDisplay(mode: Int) {
        val blank = TextStyle.eraseStyle(style)
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (row in cursorRow + 1 until rows) screen[row].reset(columns, blank)
            }
            1 -> {
                for (row in 0 until cursorRow) screen[row].reset(columns, blank)
                eraseInLine(1)
            }
            2 -> {
                for (row in 0 until rows) screen[row].reset(columns, blank)
                if (screen === mainScreen) pullableLines = 0
            }
            3 -> {
                scrollback.clear()
                pullableLines = 0
            }
        }
    }

    private fun screenAlignmentTest() {
        for (row in 0 until rows) {
            val line = screen[row]
            line.reset(columns, TextStyle.DEFAULT)
            for (col in 0 until columns) line.set(col, 'E'.code, TextStyle.DEFAULT)
        }
        topMargin = 0
        bottomMargin = rows - 1
        leftMargin = 0
        rightMargin = columns - 1
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
    }

    private fun repeatLastChar(count: Int) {
        val codePoint = lastPrintedCodePoint
        if (codePoint == 0) return
        val width = WcWidth.width(codePoint)
        if (width == 0) return
        repeat(minOf(count, columns * rows)) { printChar(codePoint, width) }
    }

    // ---------------------------------------------------------------------------------------
    // Screens and resets
    // ---------------------------------------------------------------------------------------

    private fun switchScreen(toAlternate: Boolean, clearAlternate: Boolean) {
        val target = if (toAlternate) altScreen else mainScreen
        if (toAlternate && clearAlternate) {
            val blank = TextStyle.eraseStyle(style)
            for (line in altScreen) line.reset(columns, blank)
        }
        if (screen !== target) {
            screen = target
            wrapPending = false
        }
    }

    private fun softReset() {
        isCursorVisible = true
        topMargin = 0
        bottomMargin = rows - 1
        leftMargin = 0
        rightMargin = columns - 1
        leftRightMarginMode = false
        insertMode = false
        originMode = false
        autoWrap = true
        newLineMode = false
        isApplicationCursorKeys = false
        isApplicationKeypad = false
        style = TextStyle.DEFAULT
        glCharset = 0
        singleShift = -1
        java.util.Arrays.fill(charsets, CHARSET_ASCII)
        savedMain.valid = false
        savedAlt.valid = false
        wrapPending = false
    }

    private fun fullReset() {
        softReset()
        screen = mainScreen
        for (line in mainScreen) line.reset(columns, TextStyle.DEFAULT)
        for (line in altScreen) line.reset(columns, TextStyle.DEFAULT)
        scrollback.clear()
        pullableLines = 0
        cursorRow = 0
        cursorCol = 0
        isReverseVideo = false
        isBracketedPasteMode = false
        isFocusReportingEnabled = false
        isAlternateScrollMode = true
        isSynchronizedUpdate = false
        mouseTrackingMode = MOUSE_NONE
        mouseEncoding = MOUSE_ENCODING_DEFAULT
        cursorShape = CursorShape.BLOCK
        isCursorBlinking = true
        tabStops = BooleanArray(columns) { it > 0 && it % 8 == 0 }
        lastPrintedCodePoint = 0
        titleStack.clear()
        colors.reset()
        host.onColorsChanged()
    }

    // ---------------------------------------------------------------------------------------
    // CSI
    // ---------------------------------------------------------------------------------------

    /** Parameter [index], or [default] when it was omitted. */
    private fun param(index: Int, default: Int): Int {
        if (index >= csiParamCount) return default
        val value = csiParams[index]
        return if (value < 0) default else value
    }

    /** Parameter [index] as a repeat count: omitted and 0 both mean 1. */
    private fun count(index: Int): Int {
        val value = param(index, 1)
        return if (value == 0) 1 else value
    }

    private fun dispatchCsi(final: Int) {
        val ch = final.toChar()
        if (csiPrivate == 0 && csiIntermediateCount == 0) {
            when (ch) {
                '@' -> insertChars(count(0))
                'A' -> cursorUp(count(0))
                'B', 'e' -> cursorDown(count(0))
                'C', 'a' -> cursorForward(count(0))
                'D' -> cursorBackward(count(0))
                'E' -> {
                    cursorDown(count(0))
                    carriageReturn()
                }
                'F' -> {
                    cursorUp(count(0))
                    carriageReturn()
                }
                'G', '`' -> setCursorCol(count(0) - 1)
                'H', 'f' -> cursorTo(count(0) - 1, count(1) - 1)
                'I' -> tabForward(count(0))
                'J' -> eraseInDisplay(param(0, 0))
                'K' -> eraseInLine(param(0, 0))
                'L' -> insertLines(count(0))
                'M' -> deleteLines(count(0))
                'P' -> deleteChars(count(0))
                'S' -> scrollUp(topMargin, bottomMargin, count(0), true)
                'T' -> scrollDown(topMargin, bottomMargin, count(0))
                'X' -> eraseChars(count(0))
                'Z' -> tabBackward(count(0))
                'b' -> repeatLastChar(count(0))
                'c' -> if (param(0, 0) == 0) reportPrimaryAttributes()
                'd' -> setCursorRow(count(0) - 1)
                'g' -> clearTabStops(param(0, 0))
                'h' -> for (i in 0 until maxOf(1, csiParamCount)) setAnsiMode(param(i, 0), true)
                'l' -> for (i in 0 until maxOf(1, csiParamCount)) setAnsiMode(param(i, 0), false)
                'm' -> selectGraphicRendition()
                'n' -> deviceStatusReport(param(0, 0))
                'r' -> setTopBottomMargins()
                's' -> if (leftRightMarginMode) setLeftRightMargins() else saveCursor()
                't' -> windowOperation()
                'u' -> restoreCursor()
                else -> Unit
            }
            return
        }
        if (csiPrivate == '?'.code && csiIntermediateCount == 0) {
            when (ch) {
                'J' -> eraseInDisplay(param(0, 0))
                'K' -> eraseInLine(param(0, 0))
                'h' -> for (i in 0 until maxOf(1, csiParamCount)) setDecMode(param(i, 0), true)
                'l' -> for (i in 0 until maxOf(1, csiParamCount)) setDecMode(param(i, 0), false)
                'n' -> if (param(0, 0) == 6) reply("\u001b[?${reportRow()};${reportCol()}R")
                else -> Unit
            }
            return
        }
        if (csiPrivate == '>'.code && csiIntermediateCount == 0) {
            when (ch) {
                // Secondary device attributes. Like xterm: "VT420, patch level, no ROM cartridge".
                'c' -> if (param(0, 0) == 0) reply("\u001b[>41;$XTERM_PATCH_LEVEL;0c")
                'q' -> if (param(0, 0) == 0) reply("\u001bP>|$TERMINAL_NAME\u001b\\")
                else -> Unit
            }
            return
        }
        if (csiIntermediateCount == 1) {
            when (csiIntermediate.toChar()) {
                ' ' -> if (ch == 'q' && csiPrivate == 0) setCursorStyle(param(0, 0))
                '!' -> if (ch == 'p' && csiPrivate == 0) softReset()
                '$' -> if (ch == 'p') reportMode(csiPrivate == '?'.code, param(0, 0))
                else -> Unit
            }
        }
    }

    private fun clearTabStops(mode: Int) {
        when (mode) {
            0 -> if (cursorCol < columns) tabStops[cursorCol] = false
            3 -> java.util.Arrays.fill(tabStops, false)
        }
    }

    private fun setAnsiMode(mode: Int, enabled: Boolean) {
        when (mode) {
            4 -> insertMode = enabled
            20 -> newLineMode = enabled
        }
    }

    private fun setDecMode(mode: Int, enabled: Boolean) {
        when (mode) {
            1 -> isApplicationCursorKeys = enabled
            5 -> isReverseVideo = enabled
            6 -> {
                originMode = enabled
                cursorTo(0, 0)
            }
            7 -> {
                autoWrap = enabled
                if (!enabled) wrapPending = false
            }
            9 -> mouseTrackingMode = if (enabled) MOUSE_X10 else MOUSE_NONE
            12 -> isCursorBlinking = enabled
            25 -> isCursorVisible = enabled
            47 -> switchScreen(enabled, false)
            66 -> isApplicationKeypad = enabled
            69 -> {
                leftRightMarginMode = enabled
                if (!enabled) {
                    leftMargin = 0
                    rightMargin = columns - 1
                }
            }
            1000 -> mouseTrackingMode = if (enabled) MOUSE_NORMAL else MOUSE_NONE
            1002 -> mouseTrackingMode = if (enabled) MOUSE_BUTTON_EVENT else MOUSE_NONE
            1003 -> mouseTrackingMode = if (enabled) MOUSE_ANY_EVENT else MOUSE_NONE
            1004 -> isFocusReportingEnabled = enabled
            1005 -> mouseEncoding = if (enabled) MOUSE_ENCODING_UTF8 else MOUSE_ENCODING_DEFAULT
            1006 -> mouseEncoding = if (enabled) MOUSE_ENCODING_SGR else MOUSE_ENCODING_DEFAULT
            1015 -> mouseEncoding = if (enabled) MOUSE_ENCODING_URXVT else MOUSE_ENCODING_DEFAULT
            1007 -> isAlternateScrollMode = enabled
            1047 -> {
                if (!enabled && screen === altScreen) {
                    val blank = TextStyle.eraseStyle(style)
                    for (line in altScreen) line.reset(columns, blank)
                }
                switchScreen(enabled, false)
            }
            1048 -> if (enabled) saveCursor() else restoreCursor()
            1049 -> {
                if (enabled) {
                    if (screen !== altScreen) saveCursor()
                    switchScreen(true, true)
                } else if (screen === altScreen) {
                    switchScreen(false, false)
                    restoreCursor()
                }
            }
            2004 -> isBracketedPasteMode = enabled
            2026 -> isSynchronizedUpdate = enabled
        }
    }

    /** DECRQM answer for a DEC private mode: 1 set, 2 reset, 0 not recognised. */
    private fun decModeState(mode: Int): Int {
        val enabled = when (mode) {
            1 -> isApplicationCursorKeys
            5 -> isReverseVideo
            6 -> originMode
            7 -> autoWrap
            9 -> mouseTrackingMode == MOUSE_X10
            12 -> isCursorBlinking
            25 -> isCursorVisible
            47, 1047, 1049 -> screen === altScreen
            66 -> isApplicationKeypad
            69 -> leftRightMarginMode
            1000 -> mouseTrackingMode == MOUSE_NORMAL
            1002 -> mouseTrackingMode == MOUSE_BUTTON_EVENT
            1003 -> mouseTrackingMode == MOUSE_ANY_EVENT
            1004 -> isFocusReportingEnabled
            1005 -> mouseEncoding == MOUSE_ENCODING_UTF8
            1006 -> mouseEncoding == MOUSE_ENCODING_SGR
            1015 -> mouseEncoding == MOUSE_ENCODING_URXVT
            1007 -> isAlternateScrollMode
            2004 -> isBracketedPasteMode
            2026 -> isSynchronizedUpdate
            else -> return 0
        }
        return if (enabled) 1 else 2
    }

    private fun reportMode(decPrivate: Boolean, mode: Int) {
        if (decPrivate) {
            reply("\u001b[?$mode;${decModeState(mode)}\$y")
        } else {
            val state = when (mode) {
                4 -> if (insertMode) 1 else 2
                20 -> if (newLineMode) 1 else 2
                else -> 0
            }
            reply("\u001b[$mode;$state\$y")
        }
    }

    private fun setTopBottomMargins() {
        val top = count(0) - 1
        val bottom = minOf(param(1, rows).let { if (it == 0) rows else it }, rows) - 1
        if (top < bottom) {
            topMargin = top
            bottomMargin = bottom
            cursorTo(0, 0)
        }
    }

    private fun setLeftRightMargins() {
        val left = count(0) - 1
        val right = minOf(param(1, columns).let { if (it == 0) columns else it }, columns) - 1
        if (left < right) {
            leftMargin = left
            rightMargin = right
            cursorTo(0, 0)
        }
    }

    private fun setCursorStyle(code: Int) {
        when (code) {
            0, 1 -> setCursor(CursorShape.BLOCK, true)
            2 -> setCursor(CursorShape.BLOCK, false)
            3 -> setCursor(CursorShape.UNDERLINE, true)
            4 -> setCursor(CursorShape.UNDERLINE, false)
            5 -> setCursor(CursorShape.BAR, true)
            6 -> setCursor(CursorShape.BAR, false)
        }
    }

    private fun setCursor(shape: CursorShape, blinking: Boolean) {
        cursorShape = shape
        isCursorBlinking = blinking
    }

    private fun cursorStyleCode(): Int = when (cursorShape) {
        CursorShape.BLOCK -> if (isCursorBlinking) 1 else 2
        CursorShape.UNDERLINE -> if (isCursorBlinking) 3 else 4
        CursorShape.BAR -> if (isCursorBlinking) 5 else 6
    }

    private fun reportRow(): Int = (if (originMode) cursorRow - topMargin else cursorRow) + 1

    private fun reportCol(): Int = (if (originMode) cursorCol - leftMargin else cursorCol) + 1

    private fun deviceStatusReport(kind: Int) {
        when (kind) {
            5 -> reply("\u001b[0n")
            6 -> reply("\u001b[${reportRow()};${reportCol()}R")
        }
    }

    private fun reportPrimaryAttributes() {
        // "VT220 with ANSI colour". Deliberately modest: no sixel, no printer.
        reply("\u001b[?62;22c")
    }

    private fun windowOperation() {
        when (param(0, 0)) {
            14 -> if (cellWidthPx > 0) reply("\u001b[4;${rows * cellHeightPx};${columns * cellWidthPx}t")
            16 -> if (cellWidthPx > 0) reply("\u001b[6;$cellHeightPx;${cellWidthPx}t")
            18 -> reply("\u001b[8;$rows;${columns}t")
            19 -> reply("\u001b[9;$rows;${columns}t")
            22 -> {
                if (titleStack.size >= MAX_TITLE_STACK) titleStack.removeFirst()
                titleStack.addLast(title)
            }
            23 -> titleStack.removeLastOrNull()?.let { setTitle(it) }
        }
    }

    private fun selectGraphicRendition() {
        if (csiParamCount == 0) {
            style = TextStyle.DEFAULT
            return
        }
        var i = 0
        while (i < csiParamCount) {
            val p = param(i, 0)
            when (p) {
                0 -> style = TextStyle.DEFAULT
                1 -> style = style or TextStyle.BOLD
                2 -> style = style or TextStyle.FAINT
                3 -> style = style or TextStyle.ITALIC
                4 -> {
                    var kind = 1
                    if (i + 1 < csiParamCount && csiSubParam[i + 1]) {
                        kind = param(i + 1, 1)
                        i++
                    }
                    style = style and UNDERLINE_BITS.inv()
                    when (kind) {
                        0 -> Unit
                        2 -> style = style or TextStyle.UNDERLINE or TextStyle.UNDERLINE_DOUBLE
                        3, 4, 5 -> style = style or TextStyle.UNDERLINE or TextStyle.UNDERLINE_CURLY
                        else -> style = style or TextStyle.UNDERLINE
                    }
                }
                5, 6 -> style = style or TextStyle.BLINK
                7 -> style = style or TextStyle.INVERSE
                8 -> style = style or TextStyle.INVISIBLE
                9 -> style = style or TextStyle.STRIKETHROUGH
                21 -> style = (style and UNDERLINE_BITS.inv()) or TextStyle.UNDERLINE or TextStyle.UNDERLINE_DOUBLE
                22 -> style = style and (TextStyle.BOLD or TextStyle.FAINT).inv()
                23 -> style = style and TextStyle.ITALIC.inv()
                24 -> style = style and UNDERLINE_BITS.inv()
                25 -> style = style and TextStyle.BLINK.inv()
                27 -> style = style and TextStyle.INVERSE.inv()
                28 -> style = style and TextStyle.INVISIBLE.inv()
                29 -> style = style and TextStyle.STRIKETHROUGH.inv()
                in 30..37 -> style = TextStyle.withForeground(style, TextStyle.indexed(p - 30))
                38, 48, 58 -> {
                    val result = parseExtendedColor(i)
                    val color = (result ushr 32).toInt()
                    i = (result and 0xFFFFFFFFL).toInt()
                    if (color != NO_COLOR) {
                        if (p == 38) style = TextStyle.withForeground(style, color)
                        if (p == 48) style = TextStyle.withBackground(style, color)
                    }
                    continue
                }
                39 -> style = TextStyle.withForeground(style, TextStyle.COLOR_DEFAULT)
                in 40..47 -> style = TextStyle.withBackground(style, TextStyle.indexed(p - 40))
                49 -> style = TextStyle.withBackground(style, TextStyle.COLOR_DEFAULT)
                in 90..97 -> style = TextStyle.withForeground(style, TextStyle.indexed(p - 90 + 8))
                in 100..107 -> style = TextStyle.withBackground(style, TextStyle.indexed(p - 100 + 8))
            }
            i++
            // Skip sub parameters of attributes we do not interpret.
            while (i < csiParamCount && csiSubParam[i]) i++
        }
    }

    /**
     * Parses the colour that follows SGR 38/48/58 at parameter [start]. Returns the colour (or
     * [NO_COLOR]) in the upper 32 bits and the index of the next unread parameter in the lower.
     */
    private fun parseExtendedColor(start: Int): Long {
        var color = NO_COLOR
        var next: Int
        if (start + 1 < csiParamCount && csiSubParam[start + 1]) {
            // Colon separated form, e.g. 38:2::R:G:B or 38:5:N.
            var end = start + 1
            while (end < csiParamCount && csiSubParam[end]) end++
            val n = end - (start + 1)
            val kind = param(start + 1, 0)
            if (kind == 5 && n >= 2) {
                color = TextStyle.indexed(param(start + 2, 0))
            } else if (kind == 2 && n >= 5) {
                color = TextStyle.rgb(param(start + 3, 0), param(start + 4, 0), param(start + 5, 0))
            } else if (kind == 2 && n == 4) {
                color = TextStyle.rgb(param(start + 2, 0), param(start + 3, 0), param(start + 4, 0))
            }
            next = end
        } else {
            // Semicolon separated form, e.g. 38;2;R;G;B or 38;5;N.
            val kind = param(start + 1, -1)
            if (kind == 5 && start + 2 < csiParamCount) {
                color = TextStyle.indexed(param(start + 2, 0))
                next = start + 3
            } else if (kind == 2 && start + 4 < csiParamCount) {
                color = TextStyle.rgb(param(start + 2, 0), param(start + 3, 0), param(start + 4, 0))
                next = start + 5
            } else {
                next = csiParamCount
            }
        }
        return (color.toLong() shl 32) or (next.toLong() and 0xFFFFFFFFL)
    }

    // ---------------------------------------------------------------------------------------
    // OSC and DCS
    // ---------------------------------------------------------------------------------------

    private fun dispatchOsc(text: String, bellTerminated: Boolean) {
        val separator = text.indexOf(';')
        val code = (if (separator < 0) text else text.substring(0, separator)).toIntOrNull() ?: return
        val argument = if (separator < 0) "" else text.substring(separator + 1)
        val terminator = if (bellTerminated) "\u0007" else "\u001b\\"
        when (code) {
            0, 2 -> setTitle(argument)
            4 -> {
                val parts = argument.split(';')
                var changed = false
                var i = 0
                while (i + 1 < parts.size) {
                    val index = parts[i].toIntOrNull()
                    val spec = parts[i + 1]
                    if (index != null && index in 0..255) {
                        if (spec == "?") {
                            reply("\u001b]4;$index;${TerminalColors.format(colors.paletteColor(index))}$terminator")
                        } else {
                            val rgb = TerminalColors.parse(spec)
                            if (rgb >= 0) {
                                colors.setPaletteColor(index, rgb)
                                changed = true
                            }
                        }
                    }
                    i += 2
                }
                if (changed) host.onColorsChanged()
            }
            10, 11, 12 -> {
                // One OSC can set several consecutive dynamic colours: "10;fg;bg;cursor".
                var target = code
                var changed = false
                for (spec in argument.split(';')) {
                    if (target > 12) break
                    if (spec == "?") {
                        val current = when (target) {
                            10 -> colors.foreground
                            11 -> colors.background
                            else -> colors.cursor
                        }
                        reply("\u001b]$target;${TerminalColors.format(current)}$terminator")
                    } else {
                        val rgb = TerminalColors.parse(spec)
                        if (rgb >= 0) {
                            when (target) {
                                10 -> colors.foreground = rgb
                                11 -> colors.background = rgb
                                else -> colors.cursor = rgb
                            }
                            changed = true
                        }
                    }
                    target++
                }
                if (changed) host.onColorsChanged()
            }
            52 -> {
                val split = argument.indexOf(';')
                if (split < 0) return
                val payload = argument.substring(split + 1)
                // Reading the clipboard ("?") is refused: it would leak local data to the remote.
                if (payload == "?") return
                val decoded = try {
                    Base64.getMimeDecoder().decode(payload)
                } catch (_: IllegalArgumentException) {
                    return
                }
                host.onClipboardCopy(String(decoded, StandardCharsets.UTF_8))
            }
            104 -> {
                if (argument.isEmpty()) {
                    for (index in 0..255) colors.resetPaletteColor(index)
                } else {
                    for (part in argument.split(';')) part.toIntOrNull()?.let { colors.resetPaletteColor(it) }
                }
                host.onColorsChanged()
            }
            110 -> {
                colors.foreground = colors.scheme.foreground
                host.onColorsChanged()
            }
            111 -> {
                colors.background = colors.scheme.background
                host.onColorsChanged()
            }
            112 -> {
                colors.cursor = colors.scheme.cursor
                host.onColorsChanged()
            }
        }
    }

    private fun setTitle(newTitle: String) {
        val cleaned = if (newTitle.length > MAX_TITLE_LENGTH) newTitle.substring(0, MAX_TITLE_LENGTH) else newTitle
        if (cleaned != title) {
            title = cleaned
            host.onTitleChanged(cleaned)
        }
    }

    private fun dispatchDcs(text: String) {
        if (text.startsWith("\$q")) {
            // DECRQSS: report the current value of a setting.
            val answer = when (text.substring(2)) {
                " q" -> "${cursorStyleCode()} q"
                "r" -> "${topMargin + 1};${bottomMargin + 1}r"
                "s" -> "${leftMargin + 1};${rightMargin + 1}s"
                "m" -> "0m"
                else -> null
            }
            reply(if (answer != null) "\u001bP1\$r$answer\u001b\\" else "\u001bP0\$r\u001b\\")
        } else if (text.startsWith("+q")) {
            // XTGETTCAP: we do not answer termcap queries; say so instead of staying silent.
            reply("\u001bP0+r\u001b\\")
        }
    }

    private fun reply(text: String) {
        host.write(text.toByteArray(StandardCharsets.UTF_8))
    }

    // ---------------------------------------------------------------------------------------
    // Queries for renderers and for the UI
    // ---------------------------------------------------------------------------------------

    /** Number of history lines above the screen. Always 0 while the alternate screen is showing. */
    public val scrollbackSize: Int get() = if (screen === altScreen) 0 else scrollback.size

    /**
     * Returns a line by row: 0 until [rows] address the screen, negative values address the
     * scrollback with -1 being the line just above the screen.
     */
    public fun row(index: Int): TerminalRow {
        if (index >= 0) return screen[index]
        return scrollback[scrollback.size + index]
    }

    /**
     * Text between two positions, both inclusive, using the same row numbering as [row]. Soft
     * wrapped lines are joined without a line break and trailing blanks are dropped.
     */
    public fun getText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        val out = StringBuilder()
        val first = maxOf(startRow, -scrollbackSize)
        val last = minOf(endRow, rows - 1)
        for (r in first..last) {
            val line = row(r) as TerminalLine
            val from = if (r == startRow) startCol else 0
            val to = if (r == endRow) minOf(endCol + 1, columns) else columns
            val lineStart = out.length
            line.appendText(out, from, to)
            val wrapped = line.isWrapped && r != last
            if (!wrapped) {
                var end = out.length
                while (end > lineStart && out[end - 1] == ' ') end--
                out.setLength(end)
                if (r != last) out.append('\n')
            }
        }
        return out.toString()
    }

    /** The visible screen as text, one line per row, mainly for tests and accessibility. */
    public fun screenText(): String {
        val out = StringBuilder()
        for (r in 0 until rows) {
            val lineStart = out.length
            screen[r].appendText(out, 0, columns)
            var end = out.length
            while (end > lineStart && out[end - 1] == ' ') end--
            out.setLength(end)
            if (r != rows - 1) out.append('\n')
        }
        return out.toString()
    }

    /** Tells the emulator how large a cell is in pixels so it can answer size queries. */
    public fun setCellSize(widthPx: Int, heightPx: Int) {
        cellWidthPx = widthPx
        cellHeightPx = heightPx
    }

    /** Drops the scrollback, like `CSI 3 J`. */
    public fun clearScrollback() {
        scrollback.clear()
        pullableLines = 0
    }

    /** Resets the terminal to its power-on state, like `ESC c`. */
    public fun reset() {
        state = STATE_GROUND
        utf8Remaining = 0
        fullReset()
    }

    // ---------------------------------------------------------------------------------------
    // Events from the user side
    // ---------------------------------------------------------------------------------------

    /**
     * Sends [text] as a paste: line breaks become carriage returns and, when the application
     * asked for bracketed paste, the text is wrapped in the paste markers. Escape characters are
     * removed so pasted text cannot smuggle in control sequences or end the bracket early.
     */
    public fun paste(text: String) {
        val cleaned = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    cleaned.append('\r')
                    i++
                }
                ch == '\n' -> cleaned.append('\r')
                ch == '\u001b' || (ch in '\u0080'..'\u009f') -> Unit
                else -> cleaned.append(ch)
            }
            i++
        }
        if (cleaned.isEmpty()) return
        val body = cleaned.toString()
        reply(if (isBracketedPasteMode) "\u001b[200~$body\u001b[201~" else body)
    }

    /** Reports a focus change to the application if it asked for that (mode 1004). */
    public fun reportFocus(focused: Boolean) {
        if (isFocusReportingEnabled) reply(if (focused) "\u001b[I" else "\u001b[O")
    }

    /**
     * Reports a mouse event to the application if mouse tracking is on.
     *
     * @param button one of the `MOUSE_BUTTON_*` constants.
     * @param column 0 based cell column.
     * @param row 0 based cell row.
     * @param action one of [MOUSE_PRESS], [MOUSE_RELEASE], [MOUSE_MOVE].
     * @param modifiers combination of [KeyModifiers] bits.
     * @return true when a report was sent.
     */
    public fun sendMouseEvent(button: Int, column: Int, row: Int, action: Int, modifiers: Int = 0): Boolean {
        val mode = mouseTrackingMode
        if (mode == MOUSE_NONE) return false
        val isWheel = button >= MOUSE_WHEEL_UP
        when (action) {
            MOUSE_RELEASE -> if (mode == MOUSE_X10 || isWheel) return false
            MOUSE_MOVE -> {
                if (mode != MOUSE_BUTTON_EVENT && mode != MOUSE_ANY_EVENT) return false
                if (mode == MOUSE_BUTTON_EVENT && button == MOUSE_BUTTON_NONE) return false
            }
        }
        val x = column.coerceIn(0, columns - 1) + 1
        val y = row.coerceIn(0, rows - 1) + 1

        var code = if (button == MOUSE_BUTTON_NONE) 3 else button
        if (action == MOUSE_MOVE) code += 32
        if (mode != MOUSE_X10) {
            if (modifiers and KeyModifiers.SHIFT != 0) code += 4
            if (modifiers and KeyModifiers.ALT != 0) code += 8
            if (modifiers and KeyModifiers.CTRL != 0) code += 16
        }

        when (mouseEncoding) {
            MOUSE_ENCODING_SGR -> {
                val final = if (action == MOUSE_RELEASE) 'm' else 'M'
                reply("\u001b[<$code;$x;$y$final")
            }
            MOUSE_ENCODING_URXVT -> {
                val legacy = if (action == MOUSE_RELEASE) (code and 3.inv()) or 3 else code
                reply("\u001b[${legacy + 32};$x;${y}M")
            }
            else -> {
                val legacy = if (action == MOUSE_RELEASE) (code and 3.inv()) or 3 else code
                val utf8 = mouseEncoding == MOUSE_ENCODING_UTF8
                val limit = if (utf8) 2015 else 223
                if (x > limit || y > limit) return false
                val out = java.io.ByteArrayOutputStream(12)
                out.write(0x1B)
                out.write('['.code)
                out.write('M'.code)
                writeMouseValue(out, legacy + 32, utf8)
                writeMouseValue(out, x + 32, utf8)
                writeMouseValue(out, y + 32, utf8)
                host.write(out.toByteArray())
            }
        }
        return true
    }

    private fun writeMouseValue(out: java.io.ByteArrayOutputStream, value: Int, utf8: Boolean) {
        if (utf8 && value >= 0x80) {
            out.write(0xC0 or (value shr 6))
            out.write(0x80 or (value and 0x3F))
        } else {
            out.write(value)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Resizing
    // ---------------------------------------------------------------------------------------

    /**
     * Changes the size of the screen. Text on the main screen and in the scrollback is re-wrapped
     * to the new width; the alternate screen is simply cropped or padded because the programs
     * that use it repaint themselves when told about the new size.
     */
    public fun resize(newColumns: Int, newRows: Int) {
        require(newColumns > 0 && newRows > 0) { "Terminal size must be positive" }
        if (newColumns == columns && newRows == rows) return
        val oldColumns = columns
        val oldRows = rows
        val onAlternate = screen === altScreen

        // Where the main screen's cursor is. While the alternate screen is showing that is the
        // position saved when it was entered.
        val mainRow: Int
        val mainCol: Int
        if (!onAlternate) {
            mainRow = cursorRow
            mainCol = cursorCol
        } else if (savedMain.valid) {
            mainRow = savedMain.row.coerceIn(0, oldRows - 1)
            mainCol = savedMain.col.coerceIn(0, oldColumns - 1)
        } else {
            mainRow = 0
            mainCol = 0
        }

        // Everything worth keeping: the history plus the screen down to the last used line.
        var lastUsed = mainRow
        for (r in oldRows - 1 downTo mainRow + 1) {
            if (mainScreen[r].contentLength() > 0) {
                lastUsed = r
                break
            }
        }
        val oldTop = scrollback.size
        val source = ArrayList<TerminalLine>(oldTop + lastUsed + 1)
        source.addAll(scrollback)
        for (r in 0..lastUsed) source.add(mainScreen[r])

        var lines: List<TerminalLine> = source
        var cursorLine = oldTop + mainRow
        var cursorColumn = mainCol
        var topLine = oldTop
        if (newColumns != oldColumns) {
            val result = reflow(source, oldColumns, newColumns, cursorLine, cursorColumn, topLine)
            lines = result.lines
            cursorLine = result.cursorLine
            cursorColumn = result.cursorCol
            topLine = result.topLine
        }

        // Decide which line becomes the first row of the screen.
        val total = lines.size
        var top = topLine
        if (total - top > newRows) {
            // Does not fit any more: the lines at the top turn into history.
            val pushed = total - newRows - top
            top += pushed
            pullableLines += pushed
        } else {
            // Room to spare: bring history back, but only lines that are still "attached".
            val room = newRows - (total - top)
            val pull = minOf(room, top, pullableLines)
            if (pull > 0) {
                top -= pull
                pullableLines -= pull
            }
        }

        scrollback.clear()
        for (i in 0 until top) {
            val line = lines[i]
            line.trimToContent()
            scrollback.addLast(line)
        }
        mainScreen = Array(newRows) { r ->
            if (top + r < total) lines[top + r].also { it.resize(newColumns) } else TerminalLine(newColumns)
        }
        trimScrollback()

        altScreen = Array(newRows) { r ->
            if (r < oldRows) altScreen[r].also { it.resize(newColumns) } else TerminalLine(newColumns)
        }
        screen = if (onAlternate) altScreen else mainScreen

        columns = newColumns
        rows = newRows
        topMargin = 0
        bottomMargin = newRows - 1
        leftMargin = 0
        rightMargin = newColumns - 1
        wrapPending = false

        val newMainRow = (cursorLine - top).coerceIn(0, newRows - 1)
        val newMainCol = cursorColumn.coerceIn(0, newColumns - 1)
        if (onAlternate) {
            cursorRow = cursorRow.coerceIn(0, newRows - 1)
            cursorCol = cursorCol.coerceIn(0, newColumns - 1)
            if (savedMain.valid) {
                savedMain.row = newMainRow
                savedMain.col = newMainCol
            }
        } else {
            cursorRow = newMainRow
            cursorCol = newMainCol
        }

        if (newColumns != oldColumns) {
            val stops = BooleanArray(newColumns) { it > 0 && it % 8 == 0 }
            System.arraycopy(tabStops, 0, stops, 0, minOf(oldColumns, newColumns))
            tabStops = stops
        }
    }

    private class ReflowResult(
        val lines: ArrayList<TerminalLine>,
        val cursorLine: Int,
        val cursorCol: Int,
        val topLine: Int,
    )

    /**
     * Re-wraps [source] from [oldCols] to [newCols] columns. Lines joined by a soft wrap are
     * treated as one logical line. [cursorLine]/[cursorCol] and [topLine] (the first line of the
     * screen) are indices into [source] and are mapped to the corresponding place in the result.
     */
    private fun reflow(
        source: List<TerminalLine>,
        oldCols: Int,
        newCols: Int,
        cursorLine: Int,
        cursorCol: Int,
        topLine: Int,
    ): ReflowResult {
        val out = ArrayList<TerminalLine>(source.size)
        var newCursorLine = -1
        var newCursorCol = 0
        var newTop = -1
        var i = 0
        while (i < source.size) {
            var current = TerminalLine(newCols)
            var x = 0
            var physical = i
            while (true) {
                val line = source[physical]
                val continues = line.isWrapped && physical + 1 < source.size
                if (physical == topLine && newTop < 0) newTop = out.size
                var length = minOf(line.contentLength(), oldCols)
                val hasCursor = physical == cursorLine
                // Keep blank cells in front of the cursor so that it keeps its distance.
                if (hasCursor && !continues) length = maxOf(length, minOf(cursorCol, oldCols))
                var col = 0
                while (col < length) {
                    val cellStyle = line.style(col)
                    if (TextStyle.has(cellStyle, TextStyle.WIDE_TRAILER)) {
                        if (hasCursor && col == cursorCol && newCursorLine < 0) {
                            newCursorLine = out.size
                            newCursorCol = maxOf(0, x - 1)
                        }
                        col++
                        continue
                    }
                    val width = if (TextStyle.has(cellStyle, TextStyle.WIDE)) 2 else 1
                    if (width > newCols) {
                        col++
                        continue
                    }
                    if (x + width > newCols) {
                        current.isWrapped = true
                        out.add(current)
                        current = TerminalLine(newCols)
                        x = 0
                    }
                    if (hasCursor && col == cursorCol) {
                        newCursorLine = out.size
                        newCursorCol = x
                    }
                    current.copyCell(line, col, x)
                    if (width == 2) {
                        current.set(x + 1, 0, TextStyle.withoutWidth(cellStyle) or TextStyle.WIDE_TRAILER)
                    }
                    x += width
                    col++
                }
                if (hasCursor && newCursorLine < 0) {
                    // The cursor sits after the last character of its line.
                    newCursorLine = out.size
                    newCursorCol = minOf(x, newCols - 1)
                }
                physical++
                if (!continues) break
            }
            out.add(current)
            i = physical
        }
        if (newTop < 0) newTop = out.size
        if (newCursorLine < 0) newCursorLine = maxOf(0, out.size - 1)
        return ReflowResult(out, newCursorLine, newCursorCol, newTop)
    }

    public companion object {
        public const val DEFAULT_SCROLLBACK_LINES: Int = 5000

        /** What XTVERSION reports. */
        public const val TERMINAL_NAME: String = "Shh"

        /**
         * xterm patch level reported in secondary device attributes. Programs such as vim use it
         * to decide which xterm features (SGR mouse reports, bracketed paste) they may use.
         */
        private const val XTERM_PATCH_LEVEL = 320

        public const val MOUSE_NONE: Int = 0
        public const val MOUSE_X10: Int = 9
        public const val MOUSE_NORMAL: Int = 1000
        public const val MOUSE_BUTTON_EVENT: Int = 1002
        public const val MOUSE_ANY_EVENT: Int = 1003

        public const val MOUSE_BUTTON_LEFT: Int = 0
        public const val MOUSE_BUTTON_MIDDLE: Int = 1
        public const val MOUSE_BUTTON_RIGHT: Int = 2

        /** Pointer movement with no button held, only reported in [MOUSE_ANY_EVENT] mode. */
        public const val MOUSE_BUTTON_NONE: Int = 3
        public const val MOUSE_WHEEL_UP: Int = 64
        public const val MOUSE_WHEEL_DOWN: Int = 65

        public const val MOUSE_PRESS: Int = 0
        public const val MOUSE_RELEASE: Int = 1
        public const val MOUSE_MOVE: Int = 2

        private const val MOUSE_ENCODING_DEFAULT = 0
        private const val MOUSE_ENCODING_UTF8 = 1005
        private const val MOUSE_ENCODING_SGR = 1006
        private const val MOUSE_ENCODING_URXVT = 1015

        private const val STATE_GROUND = 0
        private const val STATE_ESCAPE = 1
        private const val STATE_ESCAPE_INTERMEDIATE = 2
        private const val STATE_CSI = 3
        private const val STATE_STRING = 4
        private const val STATE_STRING_ESCAPE = 5

        private const val STRING_IGNORE = 0
        private const val STRING_OSC = 1
        private const val STRING_DCS = 2

        private const val MAX_PARAMS = 32
        private const val MAX_PARAM_VALUE = 99999
        private const val MAX_STRING_LENGTH = 1 shl 20
        private const val MAX_TITLE_LENGTH = 1024
        private const val MAX_TITLE_STACK = 16

        private const val REPLACEMENT_CHAR = 0xFFFD
        private const val NO_COLOR = -1

        private const val CHARSET_ASCII = 'B'.code
        private const val CHARSET_DEC_SPECIAL = '0'.code
        private const val CHARSET_UK = 'A'.code

        private const val UNDERLINE_BITS =
            TextStyle.UNDERLINE or TextStyle.UNDERLINE_DOUBLE or TextStyle.UNDERLINE_CURLY

        /** DEC Special Graphics: what the characters 0x5F..0x7E turn into (line drawing etc.). */
        private const val DEC_SPECIAL_GRAPHICS =
            " ◆▒␉␌␍␊°±␤␋┘┐┌└┼" +
                "⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }
}

/** Modifier key bits used by [KeyEncoder] and mouse reports. */
public object KeyModifiers {
    public const val SHIFT: Int = 1
    public const val ALT: Int = 2
    public const val CTRL: Int = 4
}
