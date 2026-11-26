package io.github.evilgenius79.shh.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max

/** A rectangular selection of text in terminal coordinates (rows as in [TerminalEmulator.row]). */
class TerminalSelection(var startRow: Int, var startCol: Int, var endRow: Int, var endCol: Int) {
    /** True when the cell lies between the two ends in reading order. */
    fun contains(row: Int, col: Int): Boolean {
        var r1 = startRow
        var c1 = startCol
        var r2 = endRow
        var c2 = endCol
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            r1 = endRow
            c1 = endCol
            r2 = startRow
            c2 = startCol
        }
        if (row < r1 || row > r2) return false
        if (row == r1 && col < c1) return false
        if (row == r2 && col > c2) return false
        return true
    }

    /** The two ends in reading order as `[row, col, row, col]`. */
    fun ordered(): IntArray =
        if (startRow > endRow || (startRow == endRow && startCol > endCol)) {
            intArrayOf(endRow, endCol, startRow, startCol)
        } else {
            intArrayOf(startRow, startCol, endRow, endCol)
        }
}

/** Draws the contents of a [TerminalEmulator] onto a canvas. */
class TerminalRenderer(private val regular: Typeface, private val bold: Typeface) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** Not anti-aliased on purpose: neighbouring cell backgrounds must meet without a seam. */
    private val fillPaint = Paint()
    private val run = CharArray(512)

    /** Width of one cell in pixels. Fractional, because that is what the font's advance is. */
    var cellWidth: Float = 1f
        private set

    /** Height of one cell in whole pixels. */
    var cellHeight: Int = 1
        private set

    private var baseline = 0
    private var underlineOffset = 0
    private var lineThickness = 1

    init {
        // Programming ligatures would merge characters such as "->" across cells.
        textPaint.fontFeatureSettings = "'liga' 0, 'calt' 0"
    }

    fun setTextSize(pixels: Float) {
        textPaint.typeface = regular
        textPaint.textSize = pixels
        cellWidth = textPaint.measureText("M")
        val metrics = textPaint.fontMetrics
        cellHeight = max(1, ceil(metrics.descent - metrics.ascent + metrics.leading).toInt())
        baseline = ceil(-metrics.ascent).toInt()
        lineThickness = max(1, (pixels / 14f).toInt())
        underlineOffset = baseline + max(1, (metrics.descent * 0.4f).toInt())
    }

    /**
     * Draws the screen. The caller must hold the emulator's lock.
     *
     * @param topRow row shown at the top of the view: 0 for the live screen, negative when
     * scrolled back into the history.
     * @param cursorOn whether the cursor is in the visible phase of its blink.
     * @param focused an unfocused view shows a hollow cursor.
     */
    fun draw(
        canvas: Canvas,
        emulator: TerminalEmulator,
        topRow: Int,
        selection: TerminalSelection?,
        cursorShape: CursorShape,
        cursorOn: Boolean,
        focused: Boolean,
    ) {
        val colors = emulator.colors
        val reverse = emulator.isReverseVideo
        val screenBackground = if (reverse) colors.foreground else colors.background
        canvas.drawColor(opaque(screenBackground))

        val columns = emulator.columns
        val rows = emulator.rows
        for (viewRow in 0 until rows) {
            val row = topRow + viewRow
            val line = emulator.row(row)
            val top = viewRow * cellHeight
            drawBackgrounds(canvas, line, row, columns, top, colors, reverse, screenBackground, selection)
            drawText(canvas, line, row, columns, top, colors, reverse, selection)
        }

        val cursorViewRow = emulator.cursorRow - topRow
        if (emulator.isCursorVisible && cursorViewRow in 0 until rows) {
            drawCursor(canvas, emulator, cursorViewRow, cursorShape, cursorOn, focused, reverse)
        }
    }

    private fun foregroundOf(style: Long, colors: TerminalColors, reverse: Boolean, selected: Boolean): Int {
        var fg = colors.resolve(TextStyle.foreground(style), true)
        var bg = colors.resolve(TextStyle.background(style), false)
        if (TextStyle.has(style, TextStyle.INVERSE) != reverse) {
            val swap = fg
            fg = bg
            bg = swap
        }
        if (selected) return bg
        if (TextStyle.has(style, TextStyle.INVISIBLE)) return bg
        if (TextStyle.has(style, TextStyle.FAINT)) return blend(fg, bg)
        return fg
    }

    private fun backgroundOf(style: Long, colors: TerminalColors, reverse: Boolean, selected: Boolean): Int {
        val fg = colors.resolve(TextStyle.foreground(style), true)
        val bg = colors.resolve(TextStyle.background(style), false)
        val inverted = TextStyle.has(style, TextStyle.INVERSE) != reverse
        // A selected cell is shown inverted relative to how it normally looks.
        return if (inverted != selected) fg else bg
    }

    private fun drawBackgrounds(
        canvas: Canvas,
        line: TerminalRow,
        row: Int,
        columns: Int,
        top: Int,
        colors: TerminalColors,
        reverse: Boolean,
        screenBackground: Int,
        selection: TerminalSelection?,
    ) {
        var runStart = 0
        var runColor = screenBackground
        for (col in 0..columns) {
            val color = if (col == columns) {
                -1
            } else {
                backgroundOf(line.style(col), colors, reverse, selection?.contains(row, col) == true)
            }
            if (color != runColor) {
                if (runColor != screenBackground && col > runStart) {
                    fillPaint.color = opaque(runColor)
                    canvas.drawRect(runStart * cellWidth, top.toFloat(), col * cellWidth, (top + cellHeight).toFloat(), fillPaint)
                }
                runStart = col
                runColor = color
            }
        }
    }

    private fun drawText(
        canvas: Canvas,
        line: TerminalRow,
        row: Int,
        columns: Int,
        top: Int,
        colors: TerminalColors,
        reverse: Boolean,
        selection: TerminalSelection?,
    ) {
        val y = (top + baseline).toFloat()
        var col = 0
        while (col < columns) {
            val style = line.style(col)
            if (TextStyle.has(style, TextStyle.WIDE_TRAILER)) {
                col++
                continue
            }
            val codePoint = line.codePoint(col)
            val selected = selection?.contains(row, col) == true
            val decorated = style and DECORATIONS != 0L
            if ((codePoint == 0 || codePoint == ' '.code) && !decorated) {
                col++
                continue
            }
            applyStyle(style, foregroundOf(style, colors, reverse, selected))
            val x = col * cellWidth
            val wide = TextStyle.has(style, TextStyle.WIDE)
            val cluster = line.cluster(col)
            var cells = if (wide) 2 else 1

            if (cluster != null) {
                canvas.drawText(cluster, x, y, textPaint)
            } else if (codePoint in 0x21..0x7E) {
                // Batch the following plain ASCII cells that look the same into one draw call.
                var count = 0
                var next = col
                while (next < columns && count < run.size) {
                    val nextStyle = line.style(next)
                    if (nextStyle != style) break
                    if ((selection?.contains(row, next) == true) != selected) break
                    val cp = line.codePoint(next)
                    if (line.cluster(next) != null) break
                    if (cp == 0 || cp == ' '.code) {
                        run[count++] = ' '
                    } else if (cp in 0x21..0x7E) {
                        run[count++] = cp.toChar()
                    } else {
                        break
                    }
                    next++
                }
                canvas.drawText(run, 0, count, x, y, textPaint)
                cells = count
            } else if (codePoint > ' '.code) {
                val length = Character.toChars(codePoint, run, 0)
                canvas.drawText(run, 0, length, x, y, textPaint)
            }

            if (decorated) drawDecorations(canvas, style, x, top, cells)
            col += cells
        }
    }

    private fun applyStyle(style: Long, color: Int) {
        textPaint.color = opaque(color)
        textPaint.typeface = if (TextStyle.has(style, TextStyle.BOLD)) bold else regular
        textPaint.textSkewX = if (TextStyle.has(style, TextStyle.ITALIC)) -0.2f else 0f
    }

    private fun drawDecorations(canvas: Canvas, style: Long, x: Float, top: Int, cells: Int) {
        fillPaint.color = textPaint.color
        val right = x + cells * cellWidth
        if (TextStyle.has(style, TextStyle.UNDERLINE)) {
            val lineTop = (top + underlineOffset).toFloat()
            canvas.drawRect(x, lineTop, right, lineTop + lineThickness, fillPaint)
            if (TextStyle.has(style, TextStyle.UNDERLINE_DOUBLE)) {
                canvas.drawRect(x, lineTop + 2 * lineThickness, right, lineTop + 3 * lineThickness, fillPaint)
            }
        }
        if (TextStyle.has(style, TextStyle.STRIKETHROUGH)) {
            val lineTop = top + baseline - textPaint.textSize * 0.3f
            canvas.drawRect(x, lineTop, right, lineTop + lineThickness, fillPaint)
        }
    }

    private fun drawCursor(
        canvas: Canvas,
        emulator: TerminalEmulator,
        viewRow: Int,
        shape: CursorShape,
        cursorOn: Boolean,
        focused: Boolean,
        reverse: Boolean,
    ) {
        val colors = emulator.colors
        val col = emulator.cursorCol
        val line = emulator.row(emulator.cursorRow)
        val style = line.style(col)
        // On the right half of a double width character the cursor covers the whole character.
        val startCol = if (TextStyle.has(style, TextStyle.WIDE_TRAILER) && col > 0) col - 1 else col
        val leadStyle = line.style(startCol)
        val width = if (TextStyle.has(leadStyle, TextStyle.WIDE)) 2 else 1
        val left = startCol * cellWidth
        val right = left + width * cellWidth
        val top = (viewRow * cellHeight).toFloat()
        val bottom = top + cellHeight
        fillPaint.color = opaque(colors.cursor)

        if (!focused) {
            // Hollow box: the terminal is not receiving input.
            val t = max(1, lineThickness).toFloat()
            canvas.drawRect(left, top, right, top + t, fillPaint)
            canvas.drawRect(left, bottom - t, right, bottom, fillPaint)
            canvas.drawRect(left, top, left + t, bottom, fillPaint)
            canvas.drawRect(right - t, top, right, bottom, fillPaint)
            return
        }
        if (!cursorOn) return
        when (shape) {
            CursorShape.UNDERLINE -> canvas.drawRect(left, bottom - max(2, lineThickness * 2), right, bottom, fillPaint)
            CursorShape.BAR -> canvas.drawRect(left, top, left + max(2, lineThickness * 2), bottom, fillPaint)
            CursorShape.BLOCK -> {
                canvas.drawRect(left, top, right, bottom, fillPaint)
                val codePoint = line.codePoint(startCol)
                if (codePoint > ' '.code) {
                    // Redraw the character in the background colour so it stays readable.
                    applyStyle(leadStyle, if (reverse) colors.foreground else colors.background)
                    val cluster = line.cluster(startCol)
                    val y = top + baseline
                    if (cluster != null) {
                        canvas.drawText(cluster, left, y, textPaint)
                    } else {
                        val length = Character.toChars(codePoint, run, 0)
                        canvas.drawText(run, 0, length, left, y, textPaint)
                    }
                }
            }
        }
    }

    private fun opaque(rgb: Int): Int = rgb or (0xFF shl 24)

    private fun blend(a: Int, b: Int): Int {
        val r = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2
        val g = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2
        val blue = ((a and 0xFF) + (b and 0xFF)) / 2
        return (r shl 16) or (g shl 8) or blue
    }

    private companion object {
        const val DECORATIONS = TextStyle.UNDERLINE or TextStyle.STRIKETHROUGH
    }
}
