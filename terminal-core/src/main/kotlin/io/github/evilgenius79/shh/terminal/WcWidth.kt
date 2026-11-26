package io.github.evilgenius79.shh.terminal

/** Number of terminal columns a Unicode code point occupies. */
public object WcWidth {
    /**
     * Returns 0 for combining/format characters, 2 for East Asian wide and fullwidth characters
     * (which includes emoji with default emoji presentation) and 1 for everything else.
     *
     * Control characters are not expected here; they report 1.
     */
    @JvmStatic
    public fun width(codePoint: Int): Int {
        // Nothing below U+0300 is zero width or wide (SOFT HYPHEN is deliberately width 1).
        if (codePoint < 0x300) return 1
        if (inRanges(WcWidthTables.ZERO_WIDTH, codePoint)) return 0
        if (codePoint < 0x1100) return 1
        return if (inRanges(WcWidthTables.WIDE, codePoint)) 2 else 1
    }

    /** Sum of [width] over all code points of [text]. */
    @JvmStatic
    public fun width(text: CharSequence): Int {
        var total = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            total += width(cp)
            i += Character.charCount(cp)
        }
        return total
    }

    private fun inRanges(table: IntArray, codePoint: Int): Boolean {
        var low = 0
        var high = table.size / 2 - 1
        if (codePoint < table[0] || codePoint > table[table.size - 1]) return false
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                codePoint < table[mid * 2] -> high = mid - 1
                codePoint > table[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}
