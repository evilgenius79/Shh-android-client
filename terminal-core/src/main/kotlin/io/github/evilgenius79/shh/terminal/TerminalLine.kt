package io.github.evilgenius79.shh.terminal

/** Read-only view of one line of the screen or of the scrollback. */
public interface TerminalRow {
    /**
     * Number of cells that are actually stored. Scrollback lines are trimmed, so cells at or
     * beyond this index are blank with the default style.
     */
    public val length: Int

    /** True when this line was soft wrapped, that is it continues on the next line. */
    public val isWrapped: Boolean

    /** Base code point of the cell or 0 when the cell is empty. */
    public fun codePoint(col: Int): Int

    /** The full text of the cell when it holds more than one code point, otherwise null. */
    public fun cluster(col: Int): String?

    /** Packed [TextStyle] of the cell. */
    public fun style(col: Int): Long
}

/**
 * Storage for one line. `text` holds a code point per cell, 0 for an empty cell, or a negative
 * value `-(index + 1)` pointing into [extras] when the cell holds a grapheme cluster (a base
 * character plus combining marks).
 */
internal class TerminalLine(capacity: Int) : TerminalRow {
    var text: IntArray = IntArray(capacity)
    var style: LongArray = LongArray(capacity)
    override var isWrapped: Boolean = false
    private var extras: ArrayList<String>? = null

    override val length: Int get() = text.size

    override fun codePoint(col: Int): Int {
        if (col < 0 || col >= text.size) return 0
        val value = text[col]
        return if (value >= 0) value else extras!![-value - 1].codePointAt(0)
    }

    override fun cluster(col: Int): String? {
        if (col < 0 || col >= text.size) return null
        val value = text[col]
        return if (value >= 0) null else extras!![-value - 1]
    }

    override fun style(col: Int): Long = if (col < 0 || col >= style.size) TextStyle.DEFAULT else style[col]

    fun set(col: Int, codePoint: Int, cellStyle: Long) {
        text[col] = codePoint
        style[col] = cellStyle
    }

    private fun setCluster(col: Int, value: String, cellStyle: Long) {
        var list = extras
        if (list == null) {
            list = ArrayList(2)
            extras = list
        } else if (list.size >= text.size + CLUSTER_SLACK) {
            compactExtras()
            list = extras!!
        }
        list.add(value)
        text[col] = -list.size
        style[col] = cellStyle
    }

    /** Appends a zero width code point to whatever the cell already holds. */
    fun appendCombining(col: Int, codePoint: Int) {
        val value = text[col]
        if (value == 0) return
        if (value > 0) {
            val combined = StringBuilder(4).appendCodePoint(value).appendCodePoint(codePoint).toString()
            setCluster(col, combined, style[col])
        } else {
            val list = extras!!
            val index = -value - 1
            val existing = list[index]
            if (existing.length < MAX_CLUSTER_LENGTH) {
                list[index] = StringBuilder(existing.length + 2).append(existing).appendCodePoint(codePoint).toString()
            }
        }
    }

    /** Drops cluster strings that no cell refers to any more. */
    private fun compactExtras() {
        val old = extras ?: return
        val fresh = ArrayList<String>()
        for (col in text.indices) {
            val value = text[col]
            if (value < 0) {
                fresh.add(old[-value - 1])
                text[col] = -fresh.size
            }
        }
        extras = if (fresh.isEmpty()) null else fresh
    }

    fun clear(from: Int, toExclusive: Int, cellStyle: Long) {
        val start = maxOf(from, 0)
        val end = minOf(toExclusive, text.size)
        if (start >= end) return
        java.util.Arrays.fill(text, start, end, 0)
        java.util.Arrays.fill(style, start, end, cellStyle)
        if (start == 0 && end == text.size) extras = null
    }

    /** Makes the line a blank line of exactly [cols] cells. */
    fun reset(cols: Int, cellStyle: Long) {
        if (text.size != cols) {
            text = IntArray(cols)
            style = LongArray(cols)
        } else {
            java.util.Arrays.fill(text, 0)
        }
        java.util.Arrays.fill(style, cellStyle)
        extras = null
        isWrapped = false
    }

    /** Copies one cell from another line (or this one), translating cluster references. */
    fun copyCell(src: TerminalLine, srcCol: Int, dstCol: Int) {
        val value = if (srcCol < src.text.size) src.text[srcCol] else 0
        val cellStyle = if (srcCol < src.style.size) src.style[srcCol] else TextStyle.DEFAULT
        if (value >= 0) {
            set(dstCol, value, cellStyle)
        } else if (src === this) {
            text[dstCol] = value
            style[dstCol] = cellStyle
        } else {
            setCluster(dstCol, src.extras!![-value - 1], cellStyle)
        }
    }

    /** Copies [count] cells starting at [srcCol] of [src] to [dstCol] of this line. */
    fun copyCells(src: TerminalLine, srcCol: Int, dstCol: Int, count: Int) {
        if (src === this && srcCol < dstCol) {
            for (i in count - 1 downTo 0) copyCell(src, srcCol + i, dstCol + i)
        } else {
            for (i in 0 until count) copyCell(src, srcCol + i, dstCol + i)
        }
    }

    /**
     * Inserts [count] blank cells at [col], moving the cells up to (not including) [endExclusive]
     * to the right. Cells pushed past the end are lost.
     */
    fun insertCells(col: Int, count: Int, endExclusive: Int, cellStyle: Long) {
        val n = minOf(count, endExclusive - col)
        if (n <= 0) return
        val moved = endExclusive - col - n
        if (moved > 0) {
            System.arraycopy(text, col, text, col + n, moved)
            System.arraycopy(style, col, style, col + n, moved)
        }
        java.util.Arrays.fill(text, col, col + n, 0)
        java.util.Arrays.fill(style, col, col + n, cellStyle)
    }

    /**
     * Deletes [count] cells at [col], moving the cells up to (not including) [endExclusive] to the
     * left and filling the gap at the end with blanks.
     */
    fun deleteCells(col: Int, count: Int, endExclusive: Int, cellStyle: Long) {
        val n = minOf(count, endExclusive - col)
        if (n <= 0) return
        val moved = endExclusive - col - n
        if (moved > 0) {
            System.arraycopy(text, col + n, text, col, moved)
            System.arraycopy(style, col + n, style, col, moved)
        }
        java.util.Arrays.fill(text, endExclusive - n, endExclusive, 0)
        java.util.Arrays.fill(style, endExclusive - n, endExclusive, cellStyle)
    }

    /** Grows or shrinks the line to exactly [cols] cells. New cells are blank. */
    fun resize(cols: Int) {
        if (text.size == cols) return
        val shrinking = cols < text.size
        text = text.copyOf(cols)
        style = style.copyOf(cols)
        if (shrinking) {
            // Never leave half of a double width character behind.
            if (cols > 0 && TextStyle.has(style[cols - 1], TextStyle.WIDE)) {
                text[cols - 1] = 0
                style[cols - 1] = TextStyle.withoutWidth(style[cols - 1])
            }
            compactExtras()
        }
    }

    /**
     * Number of leading cells worth keeping: everything up to the last cell that has text or a
     * non default style.
     */
    fun contentLength(): Int {
        var end = text.size
        while (end > 0 && text[end - 1] == 0 && style[end - 1] == TextStyle.DEFAULT) end--
        return end
    }

    /** Shrinks the backing arrays to [contentLength]. Used for lines that move to the scrollback. */
    fun trimToContent() {
        val end = contentLength()
        if (end < text.size) {
            text = text.copyOf(end)
            style = style.copyOf(end)
        }
    }

    /** Appends the text of cells `[from, toExclusive)` to [out]. Empty cells become spaces. */
    fun appendText(out: StringBuilder, from: Int, toExclusive: Int) {
        val end = minOf(toExclusive, text.size)
        var col = maxOf(from, 0)
        while (col < end) {
            val value = text[col]
            val cellStyle = style[col]
            when {
                TextStyle.has(cellStyle, TextStyle.WIDE_TRAILER) -> Unit
                value == 0 -> out.append(' ')
                value > 0 -> out.appendCodePoint(value)
                else -> out.append(extras!![-value - 1])
            }
            col++
        }
    }

    private companion object {
        /** Upper bound in UTF-16 units for one cell's text, so "zalgo" input cannot grow forever. */
        const val MAX_CLUSTER_LENGTH = 32

        /** How many stale cluster strings a line may accumulate before they are collected. */
        const val CLUSTER_SLACK = 16
    }
}
